# native

IntelliJ-EmmyLua 调试器的原生部分（协议 v3）。旧的 emmy_core / emmy_debugger / emmy_hook 已经删除，不再兼容旧协议。

| 目录 | 内容 |
| --- | --- |
| `emmy_debugger_core/` | 调试核心：协议 v3、断点、单步、变量视图、出错即停。支持 Lua 5.1–5.4，源码接入或运行时解析 Lua API（dynamic）。说明见它自己的 README |
| `emmy_attach/` | Windows 本机附加：注入目标进程后找到 Lua 模块、判断版本、加载 `lua5X/emmy_debugger_core.dll` 并开端口 |
| `emmy_tool/` | Windows 命令行工具：`attach`（注入 emmy_attach.dll，stdout 最后一行输出结果 JSON）、`arch_pid`、`arch_file`、`list_processes` |
| `shared/` | emmy_tool 和 emmy_attach 共用的共享内存、附加结果码 |
| `platforms/android/` | Android 真机附加用的 agent |
| `third-party/` | Lua 5.1–5.4 源码（测试和源码接入用）、nlohmann/json、EasyHook |

## 构建

```bash
# 调试核心（任意平台）
cmake -S native/emmy_debugger_core -B build/core -DEMMY_CORE_LUA_VERSION=54
cmake --build build/core && build/core/emmy_core_session_test

# Windows 附加工具
cmake -S native -B build/native -A x64
cmake --build build/native --config Release --target emmy_tool emmy_attach
```

出包时 `.github/workflows/auto-release.yml` 会编好 Windows x86/x64 的 `emmy_tool.exe`、`emmy_attach.dll`、`EasyHook.dll` 和 `lua51`–`lua54/emmy_debugger_core.dll`，以及 Android 的 agent，放进插件资源。约定见 [docs/design/protocol-v3.md](../docs/design/protocol-v3.md) 第 16 节。
