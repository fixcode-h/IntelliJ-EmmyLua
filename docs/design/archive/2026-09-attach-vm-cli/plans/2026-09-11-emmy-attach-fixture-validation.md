# Emmy Attach 真实 Lua 宿主与断点验收实施计划

> **面向执行者：** 按任务逐项执行；每个任务完成后独立验证并创建中文本地提交，禁止推送远端。

**目标：** 建立一个可复现真实 `emmy_tool attach` 注入路径的 Windows Lua 宿主，并修复/观测 IDEA 侧 VM snapshot、暂停事件和断点安装链路，最终验收断点、求值、Probe、上下文重置和 VM 关闭。

**架构：** 新增的 attach fixture 只链接共享 Lua 5.4 DLL，不链接 Emmy；真实 `emmy_hook.dll` 通过 `emmy_tool.exe attach` 注入并按 PID 派生 TCP 端口。IDEA 侧继续使用生产 `EmmyAttachDebugProcess`，仅增加可诊断的拒绝原因、稳定的 v2 VM 身份和断点 ACK 观测。

**技术栈：** CMake、MSVC、Lua 5.4.6、EmmyLuaDebugger native static library、Windows EasyHook、IntelliJ XDebugger、Kotlin/JUnit。

## 全局约束

- 只使用 TCP/Windows named pipe 现有生产协议，不新增 WebSocket 或 MCP 传输。
- 测试宿主必须通过共享 `lua54.dll` 导出 Lua 符号，覆盖真实注入扫描和 hook 安装。
- 生产代码不允许为了测试放宽 VM identity、source identity 或 pause fence 校验。
- 所有提交只写入本地 Git，提交信息使用中文，不执行 `git push`。
- 不覆盖已有用户修改：`.idea/vcs.xml`、`EmmyAttachDebugSettingsPanel.kt` 及无关构建目录。

### 任务一：IDEA v2 诊断与 VM 身份修复

**文件：**

- 修改：`src/main/java/com/tang/intellij/lua/debugger/emmy/EmmyDebugProcessBase.kt`
- 修改：`src/main/java/com/tang/intellij/lua/debugger/emmy/attach/EmmyAttachDebugProcess.kt`
- 修改：`src/test/kotlin/com/tang/intellij/test/debugger/VmRegistryTest.kt`

**交付：** `vm.snapshot` 输出拒绝原因、会话/epoch、快照序号和 VM 摘要；`debug.paused` 每个拒绝分支输出明确原因；断点替换发送/ACK 输出 revision 和数量；v2 已协商后 legacy `AttachedNotify` 只做诊断，不注册 `legacy-*` VM；IDEA 普通断点 owner 使用 `IDEA`。

**验证：** 运行 VM registry/protocol Kotlin 测试，并用现有 native fixture 原始 snapshot 验证 `APPLIED`；确认不改变 legacy v1 行为。

### 任务二：真实 attach Lua 宿主

**文件：**

- 创建：`EmmyLuaDebugger/tests/native_attach_lua_fixture.cpp`
- 修改：`EmmyLuaDebugger/CMakeLists.txt`
- 修改：`EmmyLuaDebugger/emmy_debugger/CMakeLists.txt`
- 创建：`EmmyLuaDebugger/tests/fixtures/attach_runtime.lua`

**交付：** 可执行文件启动共享 Lua 5.4 VM，循环执行带稳定行号、条件变量和嵌套表的脚本；stdin 支持 `reset`、`close-vm`、`stop`；stdout 提供 `ready`、`vmClosed`、`closed` 机器可读状态。宿主不预加载 Emmy，确保 attach 必须经过真实注入和 Lua DLL 导出扫描。

**验证：** 使用独立构建目录和 `LUA_BUILD_AS_DLL=ON` 编译，检查宿主依赖 `lua54.dll`；启动后确认进程持续运行、控制命令有界完成、EOF 可正常关闭。

### 任务三：IDEA 真实 attach 集成验收

**文件：**

- 创建：`src/test/kotlin/com/tang/intellij/test/debugger/EmmyNativeAttachIntegrationTest.kt`
- 修改：`build.gradle.kts`（仅在已有集成测试配置缺失时）
- 修改：`docs/cli-debug-guide.md`（补充 fixture 使用方式）

**交付：** 测试启动 fixture，调用生产 attach bootstrap 和真实 `emmy_tool.exe`，等待 TCP 连接与 v2 snapshot；通过 CLI Gateway 安装断点，验证断点命中、`value.answer` 求值、表展开、条件 Probe 自动继续和一次性清理；再验证 context reset 使旧 pause/frame 失效、`close-vm` 只关闭 VM 不结束 Agent，最后 `stop` 让进程正常退出。

**验证：** 在 Windows x64 Debug/Release native 资源下运行该测试；失败时保留 fixture 输出和 IDEA debug console 诊断，不吞掉根因。

### 任务四：全量构建与验收记录

**文件：**

- 修改：`EmmyLuaDebugger/docs/native_validation.md`
- 创建：`EmmyLuaDebugger/docs/native_attach_acceptance_20260911.md`

**交付：** 记录构建命令、资源目录、真实 attach 输出、snapshot/ACK/pause 证据和已知环境边界；列出未能执行的测试及原因，区分静态、native、IDEA 集成和真实运行时证据。

**验证：** native `ctest`、协议/VM registry 测试、真实 fixture attach 集成测试均有明确退出码和日志；最终检查工作树只包含本次范围修改，确认没有远端操作。

