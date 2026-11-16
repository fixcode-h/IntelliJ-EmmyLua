# Android 真机附加

不改游戏包，在运行时把调试 runtime 注入到可调试的游戏进程里（设计文档 7.2 / 8.4）。iOS 首期不做。

## 组成

| 部分 | 位置 | 作用 |
| --- | --- | --- |
| IDE 侧 | `modules/debugger-android` | 找 Lua 函数偏移（未裁剪 so / 符号目录 / 设备 so 自带符号 / 特征码，见下文）和 Build ID；adb 设备/进程；部署和注入 agent；建转发；环境检查 |
| agent | `native/platforms/android/agent` | `libemmylua_agent.so`：在游戏进程里按偏移算出 `lua_*` 地址、校验 Build ID、替换 `lua_pcallk` 抓 `lua_State` 并支持出错即停、交给调试核心、监听抽象 socket |
| 调试核心 | `native/emmy_debugger_core`（另一个线程在做） | 协议 v3、钩子、断点；agent 通过 `emmy_core_abi.h` 里的 C ABI 调它 |

## 流程

1. IDE 找 `lua_*` 函数偏移（含 C++ 修饰名）：优先读本地未裁剪 `libUnreal.so` 的 `.symtab`，没有时逐级兜底（见「没有未裁剪 so 时」），结果按 Build ID 缓存。
2. 设备侧预检：`run-as <包> cat /proc/<pid>/maps` 找到 `libUnreal.so`，只读开头 8 KB（`dd | od`）取 Build ID 比对，不一致直接报错。
3. 部署：`adb push` 到 `/data/local/tmp/emmylua/`，再 `run-as` 拷进应用私有目录 `code_cache/emmylua/`（`libemmylua_agent.so` + `agent.cfg`），共享目录里的配置随即删除（里面有握手 token）。
4. 注入：
   - Android 9+：`cmd activity attach-agent <pid> <so>=<cfg>`；启动并附加用 `am start -n <activity> --attach-agent <so>=<cfg>`。
   - Android 8 及以下：`adb forward tcp:0 jdwp:<pid>`，用 JDWP 在 `Handler.dispatchMessage` 下一次性断点，在主线程上调 `System.load(<so>)`。
5. agent 后台线程：等 `libUnreal.so` 加载 → 用 `dl_iterate_phdr` 取基址和内存里的 Build ID 校验 → 地址 = 基址 + 偏移 → `Emmy_ListenAbstract("emmylua-<pid>")` → ShadowHook 常驻替换 `lua_pcallk` / `lua_pcall`（替身里调 `Emmy_PrepareHostPcall` / `Emmy_FinishHostPcall`，宿主自己没给 errfunc 的错误「没被接住」，出错即停能停在出错行；带续延的 `lua_pcallk` 不插；替换失败时退回临时 intercept，只是没有出错即停），临时 intercept `lua_resume`；第一次调用时在 Lua 线程上 `Emmy_RegisterLuaVm(L)`，发现第一个 VM 3 秒后停止抓取并撤掉临时 intercept；`lua_newstate` / `luaL_newstate` / `lua_close` 常驻跟踪 VM 生命周期。hook 都装好后才 `Emmy_SetLuaApiResolver`，被替换的函数交给核心的是 trampoline（核心自己的 pcall、临时 state 不经过替身）。状态文件的 `errorStop=1` 表示出错即停可用。
6. agent 把结果写进 `code_cache/emmylua/agent.status`，IDE 轮询拿到 `listening` 后 `adb forward tcp:0 localabstract:emmylua-<pid>`，走普通 TCP + 协议 v3。
7. 断开只撤转发；agent 留在进程里（JVMTI agent 不能卸载），下次附加发现 maps 里已有 agent 就复用。

不需要应用有 `INTERNET` 权限，设备上也不开 TCP 端口。

## 前提

- 应用可调试（`android:debuggable=true`，UE Development 包默认如此）。正式签名包需要 root，暂不支持。
- 本地有这次安装包对应的**未裁剪** `libUnreal.so`（UE 打包后在 `Binaries/Android` 或 `Intermediate/Android` 下），链接时带 `--build-id`（NDK 默认）。没有时见下一节的兜底。

## 没有未裁剪 so 时

构建机清了 `Intermediate`、只拿到安装包时，IDE 按下面的顺序逐级找，**任何一级拿全调试核心必需的函数就用它**，附加日志和「检查环境」里写明用的是哪一级、前面几级为什么不行（`elf/LuaSymbolLocator.kt`）：

| 顺序 | 来源 | 说明 |
| --- | --- | --- |
| 0 | 运行配置里指定的 so | Build ID 和设备不一致时跳过，继续往下找（不再直接报错） |
| 1 | 符号目录（设置 → 设备，可多个） | 递归找 ELF（`.so` / `.debug` / `.dbg` / `.sym`），Build ID 和设备上的 so 一致且带 `.symtab` 就用；也认 `<目录>/.build-id/xx/yyyy.debug`。扫描结果按「路径 + 大小 + 修改时间」缓存，归档目录第二次只 stat。适合把每次出包的未裁剪 so 存到共享盘 |
| 2 | 设备 so 自带的符号 | 经 adb 按需读设备上的 so（`run-as dd` + `exec-out`，只读头部、节头和符号表几次往返，APK 内直接映射的也行）：`.dynsym` 和 `.gnu_debugdata`（MiniDebugInfo，xz 压缩的 `.symtab`）。UE 默认 `-fvisibility=hidden`，`.dynsym` 一般没有 `lua_*`；打包时保留 MiniDebugInfo 的工程这一级就够了 |
| 3 | 特征码 | 设置里（或运行配置里）指定「参考 so」：同一工程**以前任意一次构建**的未裁剪 `libUnreal.so`，同 ABI、同 Lua 版本。见下 |

特征码怎么做（`ArmCodeMasker.kt`、`SignatureScanner.kt`）：

- 从参考 so 的 `.symtab` 取每个需要的函数（必需 + 可选 + hook 点）从入口到结尾的机器码（最多 4 KB），把和地址有关的立即数通配掉：arm64 的 ADRP / ADR、B / BL、B.cond、CBZ / CBNZ、TBZ / TBNZ、LDR literal，以及 ADRP 之后以它为基址的 `:lo12:` ADD / LDR / STR；armv7 Thumb-2 的 B / B.cond / CBZ、BL / BLX / B.W、LDR literal（连同落在函数里的文字池）、ADR、MOVW / MOVT；ARM 模式的 B / BL / BLX、LDR literal、MOVW / MOVT。Thumb 函数看符号最低位，结果也带上最低位。
- 在设备 so 的可执行段里一次扫完所有函数：每个特征选一个全固定位的 4 字节锚点，按对齐步长取字、先查位图再查锚点表，命中后校验前 64 字节。**只接受唯一匹配**：多处匹配就把特征加长一倍再比，直到函数结尾；仍不唯一或一处都没有就算这个函数找不到。实测 128 MB 代码段约 0.5 秒（不含经 adb 读取的时间）。
- 两三条指令的小函数（`lua_status` 之类）单独做特征不可靠：再搜参考 so 里紧挨着它的函数（含 static），按「前后两个已匹配函数间距不变」或「256 字节内的紧邻函数」推算位置，并且整个函数体（通配后）都对得上才接受。
- 两个函数匹配到同一个地址（参考 so 里不是同一个）时两个都不要。
- 第 2 级找到的函数直接沿用，只搜剩下的；可选函数缺了照常降级。结果按「设备 Build ID + 参考 Build ID」缓存在 IDE 的 system 目录（`emmylua/android-symbols/`），第二次附加不再读设备、不再搜索。搜索在后台线程，调试会话停止时会中止。
- 必需函数缺一个就整体失败，提示里列出缺哪些、每一级的结果和三条办法：保留未裁剪 so、配符号目录、指定参考 so。

启动并附加（应用还没跑）时，从 `pm path` 的安装目录 `lib/<abi>/` 或 APK（含 split APK）里不压缩存放的 `lib/<abi>/libUnreal.so` 找设备上的 so，同样能读 Build ID、走第 2、3 级。
- 设备 ABI：arm64-v8a、armeabi-v7a（ShadowHook 的支持范围）。

## 构建

```bash
ANDROID_NDK=/path/to/ndk native/platforms/android/build_agent.sh [输出目录]
# 产物：<输出目录>/<abi>/libemmylua_agent.so（已去符号；带符号版本在 .build-<abi>/）
```

- 必须 `c++_static`：agent 只依赖 `liblog/libm/libdl/libc`，只导出 `Agent_OnAttach`、`Agent_OnLoad`、`JNI_OnLoad`（CI 会检查）。
- ShadowHook 默认按 `EMMY_SHADOWHOOK_TAG`（v2.0.1）从 GitHub 下载并静态编进 agent，符号全部隐藏；离线时设 `EMMY_SHADOWHOOK_DIR`。
- 同一 CMake 构建里有 `emmy_debugger_core` 目标就链接它；没有时用 `core_stub.cpp` 占位，附加时状态文件会报"没有编入调试核心"。

主机单测（配置解析、基址 + 偏移 = 运行地址、Build ID、抓 L 的状态机、状态文件），以及端到端测试 `agent_core_e2e_test`：
把 Lua 5.4.6 编成符号全隐藏的 `libfakeunreal.so`（dlsym 找不到 lua_*，和设备上一样），按 `.symtab` 生成 agent.cfg，
跑 agent 主流程（`RunAgent`）+ dynamic 模式的真调试核心，测试客户端经抽象 socket 握手（错令牌被拒）、下条件断点、停下、求值、继续。
只有内联 hook 换成了假后端：

```bash
cmake -S native/platforms/android/agent -B build/agent-host -DEMMY_AGENT_BUILD_TESTS=ON
cmake --build build/agent-host && ctest --test-dir build/agent-host --output-on-failure
```

## 真机验收（需要设备，云端 CI 跑不了）

1. 装一个 UE Development 包（UnLua），进入有 Lua 逻辑的界面。
2. IDE 里「检查环境」全部通过：adb、设备授权、系统版本、符号、ABI、可调试、Build ID。
3. 附加后 `adb logcat -s EmmyLuaAgent` 能看到"监听 localabstract:emmylua-<pid>"和"登记 VM"，3 秒后"抓取窗口结束"。
4. 下断点能停、单步、看变量；断开后游戏继续正常运行；再次附加走复用路径。
5. 换一个不匹配的 so：附加前就报"不是同一次构建"。
6. Android 8.x 设备：走 JDWP 路径，需要游戏在前台。
7. 勾选「启动应用并附加」：应用重启，第一段 Lua 执行前停住等 IDE。

## 已知限制

- 抓 `lua_State` 依赖 `lua_pcallk` / `lua_pcall` / `lua_resume` 之一没被链接器裁掉（UE 宿主一定会调 pcall，实际上都在）。被 `--gc-sections` 裁掉的其他 `lua_*` 会在 IDE 里列出来，相关功能（例如修改变量）降级。
- 一个 so 里静态链接了两份 Lua 时同名函数有多个地址，IDE 拒绝附加并提示。
- 特征码兜底依赖参考 so 和这次构建的 Lua 代码、编译选项基本一致：UnLua 改了 Lua 源码、换了编译器版本或优化级别，函数体变了就匹配不上，只能回到未裁剪 so 或符号目录。只接受唯一匹配，宁可找不到也不认错地址。
- 特征码只支持 arm64 和 armv7（ARM / Thumb）；x86 / x86_64 模拟器只能用未裁剪 so 或设备符号。
- 第 2、3 级要经 adb 读设备上的 so：特征码要把代码段（libUnreal.so 常有一两百 MB）读一遍，第一次会慢（USB 下几十秒），之后走缓存。
- 兜底流程只在主机单测里用真 Lua 5.4.6 的两次构建验证过（`modules/debugger-android` 的 `SymbolFallbackTest`），还没在真机和真实 libUnreal.so 上验收。
