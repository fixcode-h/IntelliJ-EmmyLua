#!/usr/bin/env python3
"""用真的 emmylua_ls 跑冒烟测试：UE 项目布局下的声明能不能跳转，冷启动多久。

插件给 emmylua_ls 的东西只有两样：工作区目录、`workspace/configuration` 的 `emmylua` 段（EmmyLuaWorkspaceConfig）。
这里照同样的形状喂给服务器，断言：
  1. 工作区外的库目录（设置里的额外库、UELink 生成的声明在项目外时）里的全局能跳转；
  2. 工作区里 UnLua 自带声明（Plugins/UnLua/Content/IntelliSense）里的 UE 类型能跳转；
  3. 5 万行声明时，从 initialized 到第一次跳转可用的时间（lsp-migration.md 目标 < 10 秒）写进报告，超过 --max-cold 秒失败。

用法：lsp-smoke.py <emmylua_ls 可执行文件> [--max-cold 30] [--report 文件]
只用标准库，CI 不装依赖。
"""
import argparse
import json
import os
import pathlib
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time


class Client:
    def __init__(self, exe, config):
        self.proc = subprocess.Popen([exe], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        self.config = config
        self.next_id = 0
        self.responses = {}
        self.cond = threading.Condition()
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _send(self, msg):
        body = json.dumps(msg).encode("utf-8")
        self.proc.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
        self.proc.stdin.flush()

    def _read(self):
        out = self.proc.stdout
        while True:
            length = None
            while True:
                line = out.readline()
                if not line:
                    return
                line = line.strip()
                if not line:
                    break
                if line.lower().startswith(b"content-length:"):
                    length = int(line.split(b":")[1])
            msg = json.loads(out.read(length))
            if "method" in msg and "id" in msg:
                self._answer(msg)
            elif "id" in msg:
                with self.cond:
                    self.responses[msg["id"]] = msg
                    self.cond.notify_all()

    def _answer(self, req):
        method = req["method"]
        if method == "workspace/configuration":
            result = [self.config if item.get("section") in (None, "emmylua") else None for item in req["params"]["items"]]
        else:  # client/registerCapability、window/workDoneProgress/create 等
            result = None
        self._send({"jsonrpc": "2.0", "id": req["id"], "result": result})

    def request(self, method, params, timeout=30):
        self.next_id += 1
        rid = self.next_id
        self._send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        deadline = time.time() + timeout
        with self.cond:
            while rid not in self.responses:
                left = deadline - time.time()
                if left <= 0:
                    raise TimeoutError(method)
                self.cond.wait(left)
            msg = self.responses.pop(rid)
        if "error" in msg:
            raise RuntimeError(f"{method}: {msg['error']}")
        return msg.get("result")

    def notify(self, method, params):
        self._send({"jsonrpc": "2.0", "method": method, "params": params})

    def close(self):
        try:
            self.request("shutdown", None, timeout=5)
            self.notify("exit", None)
        except Exception:
            pass
        try:
            self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.proc.kill()


def uri(path):
    return pathlib.Path(path).resolve().as_uri()


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


MAIN = """local Actor = UE.AActor
local loc = Actor:K2_GetActorLocation()
FooLib.Bar()
---@type AActor
local typed
"""


def make_project(root, decl_lines):
    proj = root / "Game"
    write(proj / "Game.uproject", "{}\n")
    write(proj / "Content" / "Script" / "Main.lua", MAIN)
    # UnLua 自带声明的写法：UE 表 + 每个类型一个文件
    intellisense = proj / "Plugins" / "UnLua" / "Content" / "IntelliSense"
    write(intellisense / "UE.lua", "---@class UE\n---@field AActor AActor\nUE = {}\n")
    write(intellisense / "AActor.lua", "---@class AActor\nlocal M = {}\n\n---@return FVector\nfunction M:K2_GetActorLocation() end\n\nreturn M\n")
    # 工作区外的库目录
    lib = root / "ExtLib"
    write(lib / "FooLib.lua", "---@class FooLib\nFooLib = {}\n\nfunction FooLib.Bar() end\n")
    # 冷启动用的大量声明：每个类 50 行
    classes = max(1, decl_lines // 50)
    gen = lib / "Generated"
    per_file = 20
    for f in range((classes + per_file - 1) // per_file):
        parts = []
        for c in range(f * per_file, min(classes, (f + 1) * per_file)):
            parts.append(f"---@class UGen{c} : UObject\nlocal UGen{c} = {{}}\n")
            for m in range(16):
                parts.append(f"---@param a integer\n---@return integer\nfunction UGen{c}:Method{m}(a) end\n")
        write(gen / f"Gen{f}.lua", "".join(parts))
    return proj, lib


def find_col(text, line, needle):
    return text.splitlines()[line].index(needle)


def definition_target(client, doc, line, col):
    result = client.request("textDocument/definition", {"textDocument": {"uri": doc}, "position": {"line": line, "character": col}})
    if not result:
        return None
    first = result[0] if isinstance(result, list) else result
    return first.get("uri") or first.get("targetUri")


def wait_definition(client, doc, line, col, expect_suffix, timeout):
    start = time.time()
    last = None
    while time.time() - start < timeout:
        last = definition_target(client, doc, line, col)
        if last and any(last.endswith(s) for s in expect_suffix):
            return time.time() - start, last
        time.sleep(0.2)
    return None, last


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("exe")
    ap.add_argument("--decl-lines", type=int, default=50000)
    ap.add_argument("--max-cold", type=float, default=30.0, help="冷启动上限（秒），CI 机器慢，比设计目标 10 秒宽")
    ap.add_argument("--report", help="把结果（Markdown）追加到这个文件，如 $GITHUB_STEP_SUMMARY")
    args = ap.parse_args()

    root = pathlib.Path(tempfile.mkdtemp(prefix="emmylua-smoke-"))
    failures = []
    rows = []
    try:
        proj, lib = make_project(root, args.decl_lines)
        # 和 EmmyLuaWorkspaceConfig.build 一样的形状：没有用户配置文件时下发 runtime.version
        config = {"workspace": {"library": [str(lib)]}, "runtime": {"version": "Lua5.4"}}
        client = Client(args.exe, config)
        t0 = time.time()
        client.request("initialize", {
            "processId": os.getpid(),
            "rootUri": uri(proj),
            "workspaceFolders": [{"uri": uri(proj), "name": "Game"}],
            "capabilities": {"workspace": {"configuration": True, "workspaceFolders": True},
                             "textDocument": {"definition": {"linkSupport": False}}},
        }, timeout=60)
        client.notify("initialized", {})
        main_path = proj / "Content" / "Script" / "Main.lua"
        doc = uri(main_path)
        client.notify("textDocument/didOpen", {"textDocument": {"uri": doc, "languageId": "lua", "version": 1, "text": MAIN}})

        cases = [
            ("工作区外的库目录：FooLib.Bar", 2, find_col(MAIN, 2, "Bar"), ("/ExtLib/FooLib.lua",)),
            # 跳到 UE 表上的字段声明或类型本身都算对
            ("工作区里 UnLua 自带声明：UE.AActor", 0, find_col(MAIN, 0, "AActor"), ("/IntelliSense/UE.lua", "/IntelliSense/AActor.lua")),
            ("注解里的 UE 类型：---@type AActor", 3, find_col(MAIN, 3, "AActor"), ("/IntelliSense/AActor.lua",)),
            ("UnLua 声明里的方法：K2_GetActorLocation", 1, find_col(MAIN, 1, "K2_GetActorLocation"), ("/IntelliSense/AActor.lua",)),
        ]
        cold = None
        for name, line, col, suffix in cases:
            elapsed, got = wait_definition(client, doc, line, col, suffix, args.max_cold if cold is None else 5)
            if elapsed is None:
                failures.append(f"{name}：跳转没有到 {' 或 '.join(suffix)}（实际 {got}）")
                rows.append(f"| {name} | ✗ | {got} |")
            else:
                if cold is None:
                    cold = time.time() - t0
                rows.append(f"| {name} | ✓ | |")
        client.close()
    finally:
        shutil.rmtree(root, ignore_errors=True)

    lines = [
        f"### emmylua_ls 冒烟测试（{args.decl_lines} 行声明）",
        "",
        f"冷启动到第一次跳转可用：{cold:.1f} 秒（设计目标 < 10 秒，CI 上限 {args.max_cold:.0f} 秒）" if cold is not None else "冷启动：没有一次跳转成功",
        "",
        "| 用例 | 结果 | 实际跳到 |",
        "| --- | --- | --- |",
        *rows,
        "",
    ]
    text = "\n".join(lines)
    print(text)
    if args.report:
        with open(args.report, "a", encoding="utf-8") as f:
            f.write(text + "\n")
    if failures:
        for f in failures:
            print("::error::" + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
