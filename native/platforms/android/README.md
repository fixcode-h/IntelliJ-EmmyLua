# Android 真机附加

不改游戏包，在运行时把调试 runtime 注入到可调试的游戏进程里（设计文档 7.2 / 8.4）。iOS 首期不做。

## 组成

| 部分 | 位置 | 作用 |
| --- | --- | --- |
| IDE 侧 | `modules/debugger-android` | 读本地未裁剪 so 的 Lua 函数偏移和 Build ID；adb 设备/进程；部署和注入 agent；建转发；环境检查 |
| agent | `native/platforms/android/agent` | `libemmylua_agent.so`：在游戏进程里按偏移算出 `lua_*` 地址、校验 Build ID、临时 intercept `lua_pcallk` 抓 `lua_State`、交给调试核心、监听抽象 socket |
| 调试核心 | `native/emmy_debugger_core`（另一个线程在做） | 协议 v3、钩子、断点；agent 通过 `emmy_core_abi.h` 里的 C ABI 调它 |

## 流程

1. IDE 读本地未裁剪 `libUnreal.so`：`.symtab` 里的 `lua_*`（含 C++ 修饰名）偏移 + GNU Build ID，按 Build ID 缓存。
2. 设备侧预检：`run-as <包> cat /proc/<pid>/maps` 找到 `libUnreal.so`，只读开头 8 KB（`dd | od`）取 Build ID 比对，不一致直接报错。
3. 部署：`adb push` 到 `/data/local/tmp/emmylua/`，再 `run-as` 拷进应用私有目录 `code_cache/emmylua/`（`libemmylua_agent.so` + `agent.cfg`），共享目录里的配置随即删除（里面有握手 token）。
4. 注入：
   - Android 9+：`cmd activity attach-agent <pid> <so>=<cfg>`；启动并附加用 `am start -n <activity> --attach-agent <so>=<cfg>`。
   - Android 8 及以下：`adb forward tcp:0 jdwp:<pid>`，用 JDWP 在 `Handler.dispatchMessage` 下一次性断点，在主线程上调 `System.load(<so>)`。
5. agent 后台线程：等 `libUnreal.so` 加载 → 用 `dl_iterate_phdr` 取基址和内存里的 Build ID 校验 → 地址 = 基址 + 偏移 → `Emmy_SetLuaApiResolver` → `Emmy_ListenAbstract("emmylua-<pid>")` → ShadowHook intercept `lua_pcallk` / `lua_pcall` / `lua_resume`，第一次调用时在 Lua 线程上 `Emmy_RegisterLuaVm(L)`，3 秒后撤掉；`lua_newstate` / `luaL_newstate` / `lua_close` 常驻跟踪 VM 生命周期。
6. agent 把结果写进 `code_cache/emmylua/agent.status`，IDE 轮询拿到 `listening` 后 `adb forward tcp:0 localabstract:emmylua-<pid>`，走普通 TCP + 协议 v3。
7. 断开只撤转发；agent 留在进程里（JVMTI agent 不能卸载），下次附加发现 maps 里已有 agent 就复用。

不需要应用有 `INTERNET` 权限，设备上也不开 TCP 端口。

## 前提

- 应用可调试（`android:debuggable=true`，UE Development 包默认如此）。正式签名包需要 root，暂不支持。
- 本地有这次安装包对应的**未裁剪** `libUnreal.so`（UE 打包后在 `Binaries/Android` 或 `Intermediate/Android` 下），链接时带 `--build-id`（NDK 默认）。
- 设备 ABI：arm64-v8a、armeabi-v7a（ShadowHook 的支持范围）。

## 构建

```bash
ANDROID_NDK=/path/to/ndk native/platforms/android/build_agent.sh [输出目录]
# 产物：<输出目录>/<abi>/libemmylua_agent.so（已去符号；带符号版本在 .build-<abi>/）
```

- 必须 `c++_static`：agent 只依赖 `liblog/libm/libdl/libc`，只导出 `Agent_OnAttach`、`Agent_OnLoad`、`JNI_OnLoad`（CI 会检查）。
- ShadowHook 默认按 `EMMY_SHADOWHOOK_TAG`（v2.0.1）从 GitHub 下载并静态编进 agent，符号全部隐藏；离线时设 `EMMY_SHADOWHOOK_DIR`。
- 同一 CMake 构建里有 `emmy_debugger_core` 目标就链接它；没有时用 `core_stub.cpp` 占位，附加时状态文件会报"没有编入调试核心"。

主机单测（配置解析、基址 + 偏移 = 运行地址、Build ID、抓 L 的状态机、状态文件）：

```bash
cmake -S native/platforms/android/agent -B build/agent-host -DEMMY_AGENT_BUILD_TESTS=ON
cmake --build build/agent-host && ctest --test-dir build/agent-host --output-on-failure
```

## 真机验收（需要设备，云端 CI 跑不了）

1. 装一个 UE Development 包（UnLua），进入有 Lua 逻辑的界面。
2. IDE 里「检查环境」全部通过：adb、设备授权、系统版本、符号、ABI、可调试、Build ID。
3. 附加后 `adb logcat -s EmmyLuaAgent` 能看到"监听 localabstract:emmylua-<pid>"和"登记 VM"，3 秒后"已撤掉临时 intercept"。
4. 下断点能停、单步、看变量；断开后游戏继续正常运行；再次附加走复用路径。
5. 换一个不匹配的 so：附加前就报"不是同一次构建"。
6. Android 8.x 设备：走 JDWP 路径，需要游戏在前台。
7. 勾选「启动应用并附加」：应用重启，第一段 Lua 执行前停住等 IDE。

## 已知限制

- 抓 `lua_State` 依赖 `lua_pcallk` / `lua_pcall` / `lua_resume` 之一没被链接器裁掉（UE 宿主一定会调 pcall，实际上都在）。被 `--gc-sections` 裁掉的其他 `lua_*` 会在 IDE 里列出来，相关功能（例如修改变量）降级。
- 一个 so 里静态链接了两份 Lua 时同名函数有多个地址，IDE 拒绝附加并提示。
- 没有未裁剪 so 时的特征码兜底（设计文档 7.2 第 2 点）还没做。
