# Emmy Attach、VM 与 CLI 验证矩阵

更新：2026-09-10。P0、P1、P2 全部纳入实施；“自动化通过”只表示对应测试层通过。真实 IDEA 平台测试已连接 Native Lua 宿主并通过 CLI Gateway 完成采集和生命周期闭环，但不等同于已安装 GUI、EasyHook 注入、UE/PIE 或远端 CI 验收。当前不能宣布全矩阵正常：Native TCP 两项仍失败，外部实机验收尚未执行。

## 本地验证结果

| 验证层 | 结果 | 证据与边界 |
| --- | --- | --- |
| JVM 全量测试 | 199 项，0 failure/error/skipped，55 suites | 根插件 138、5 个 debugger 模块 54、CLI 7；仅统计 settings.gradle 当前构建范围，包含快照预算、Probe 等待清理与真实 Native/IDE 集成 |
| IDEA 平台 → Native → CLI Gateway | 1/1，已计入上述 JVM 总数 | 真实 XDebugger session、生产 EmmyDebugProcessBase/pipe、Gateway/token/grant/lease；断点 ACK、标量/特殊 key/table 求值、Probe 条件采集与自动清理、stale pause、reset、CLOSING→CLOSED、VM 关闭后 Agent 在线 |
| 插件构建 / CLI 分发 | 通过 | buildPlugin、:tools:emmy-debug:installDist；CLI instance list 可运行，当前返回空实例 |
| Plugin Verifier | Compatible | IC-252.23892.409；1 处 deprecated API、21 处 experimental API，不能写成零警告 |
| Windows 动态 Native x64 Debug / Release | 各 14/14 | 两配置均完整构建；各显式排除 1 项 TCP 测试 |
| Windows 动态 Native x86 Debug / Release | 各 14/14 | 两配置均完整构建；各显式排除 1 项 TCP 测试 |
| 真实 Lua 5.4.6 source x64/x86 Debug | 各 17/17 | 包含真实 pipe 协议、VM 生命周期、coroutine/hook；各显式排除 2 项 TCP 测试 |
| 真实 Lua 5.1.5 / 5.2.4 / 5.3.5 source x64 Debug | 各 3/3 | 各版本真实 pipe 协议、hook dispatcher、VM lifecycle；覆盖函数环境及 nil `_ENV` 回归 |
| 真实 Lua 动态 DLL 加载 | 5.1 / 5.4 各通过 | 使用生产 SetupLuaAPI，创建真实 state 并 raw 读取 42；`lua_getfenv` 在 5.2+ 可缺省 |
| Linux x86_64 / glibc 2.17 交叉编译 | source / dynamic 全部目标编译链接通过 | 官方 Zig 0.14.1；未执行 Linux ELF，不能计入 Linux 运行通过 |
| Native TCP 单独复验 | 0/2，失败保留 | WFP filter 70739（Windows 防火墙 Query User Default）明确阻止 Native 回环入站；未修改安全策略 |
| 新 Native 资源打包 | 8/8 SHA-256 一致 | 最终 ZIP 内 JAR 的 Windows x86/x64 资源与此次 Release 构建一致；没有替换仓库旧二进制 |
| Workflow 静态验证 | 两份 YAML 经 yaml-lint 实际解析通过 | Native 8 配置矩阵与 Lua 51/52/53/54、父仓库 IDEA 251/252 和 Windows Native/IDE 集成 job；没有远端执行记录 |
| 已安装 IDEA GUI / EasyHook / UE / PIE 实机 | 未验证 | 上述 IDEA 平台测试运行真实插件生产类，但没有执行进程注入、已安装插件 UI 或 UE Host 接入 |

最终本地命令：

```powershell
$env:JAVA_HOME='C:\Users\happyelements\.jdks\jbr-21.0.11'
$env:EMMY_IDE_FIXTURE_EXE='F:\Project\IntelliJ-EmmyLua\EmmyLuaDebugger\build-runtime-20260910\emmy_debugger\emmy_native_ide_fixture.exe'
./gradlew.bat --no-daemon --no-build-cache test buildPlugin verifyPlugin `
  :tools:emmy-debug:installDist `
  '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' `
  -PemmyNativeDir=F:/Project/IntelliJ-EmmyLua/build/emmy-native-resources

powershell -NoProfile -ExecutionPolicy Bypass -File tools/verify-emmy-native-resources.ps1 `
  -PluginZip build/distributions/EmmyLua-1.0.0-IDEA252.zip `
  -NativeRoot build/emmy-native-resources
```

产物：`build/distributions/EmmyLua-1.0.0-IDEA252.zip`。本次 ZIP SHA-256：

```text
98017A9CD5AC79ED3A7238642604E9A1626785F3E8F337596B8E3A617BC96C37
```

JVM 报告位于根、`modules/*`、`tools/emmy-debug` 的 `build/test-results/test`；Verifier 位于 `build/reports/pluginVerifier/IC-252.23892.409`。Native 的构建目录、配置、命令与 TCP 失败报告见 [Native 验收记录](../../../EmmyLuaDebugger/docs/native_socket_acceptance_20260910.md)。

统计范围固定为根插件、`debugger-core`、`debugger-transport`、`debugger-emmy-protocol`、`debugger-cli-protocol`、`debugger-luapanda-protocol` 和 `tools/emmy-debug`。不递归累计旧构建目录中已经退出 settings.gradle 的 AI/MCP 模块报告；此前记录的 223 项混入这些历史报告，已纠正。全量日志为 `build/verification-tools/final-verification.log`；真实联调 JUnit 为 `build/test-results/test/TEST-com.tang.intellij.test.debugger.EmmyNativeIdeFixtureIntegrationTest.xml`。

未提供 `EMMY_IDE_FIXTURE_EXE` 的普通 Gradle 测试会明确排除 Native/IDE 联调，不能提供这一层通过证据。Windows 专用 CI job 构建宿主并显式提供该路径。联调测试仅清理自身 session、RunContentDescriptor 和宿主进程，不操作用户已运行的 IDEA/UE。

## 风险逐项对照

| 风险 | 已实现与已验证 | 开放验收 |
| --- | --- | --- |
| P0-F01 VM 生命周期与 Agent/VM 分离 | Host Registry/Native Registry；真实 Lua 双 VM、Ready/close/reset | UE PIE 创建、关闭、重建周期 |
| P0-F02 多 VM 显式路由 | VM/thread/pause/frame/context/source 校验；JVM + Native 隔离测试 | UE 双 VM 控制隔离 |
| P0-F03 握手、Ready、snapshot | 真实 pipe 的 Init/Ready/snapshot/reconnect；生产 IDEA 会话实际连接 Native | Native TCP；EasyHook 注入后的连接 |
| P0-F04 hook chaining / teardown | quiescence、句柄重试、宿主 hook 与继承 coroutine 恢复测试 | EasyHook 注入后完整 Detach/PIE 周期 |
| P0-S1 唯一 ABI/导出边界 | 版本化 C ABI、大小检查、动态绑定示例、双架构构建 | UE Bridge GetProcAddress 与无重复 Facade 链接 |
| P0-S2 精确 Lua 私有布局 | UnLua fingerprint fixture、未知/错误布局拒绝、source/dynamic 分离测试 | 实际 UnLua 编译参数和布局 |
| P0-S3 暂停粒度 | THREAD pause claim、coroutine 单步隔离；owner 执行后 resumed | UE 调度下的线程一致性；不承诺 VM-wide pause |
| P0-S4 有界求值 | Native raw VALUE_PATH；policy/深度/节点/字节限制；真实 Lua 元方法不调用 | UE userdata 与宿主回调耗时 |
| P1-F05 进程/Agent/VM 身份 | opaque ID、generation/epoch、地址复用测试、PID 共享映射 | 真实 PID 复用与重复 attach |
| P1-F06 混合 ABI 检测 | descriptor 注册与实际访问前拒绝；第二 VM ABI fixture | 混合 Lua DLL 的真实进程 |
| P1-F07 wire/schema/framing | golden、framing、12 条实际执行的 fuzz fixture、结构化错误 | 远端跨平台 CI |
| P1-F08 AttachmentManager 解耦 | bootstrap/AttachmentManager 生产接入与 JVM 回归 | IDEA 进程重启/PID 复用 |
| P1-S1 Native 认证 | token bootstrap；未认证请求拒绝；新连接重新认证 | 真实注入 token 更新与进程权限 |
| P1-S2 重连终态 | 有界退避、取消旧请求；pipe session 保留和 epoch 递增 | IDEA 断线重连全过程 |
| P1-S3 context reset | pause/cache/source/Probe 失效；真实 Native reset 在 IDEA Gateway 中可观测 | UnLua HotReload 与 PIE reset |
| P1-S4 HostValueProvider | C ABI、预算/对象失效/字段拒绝/调度 fixture | 宿主实际 GameThread 副本、反射白名单与 callback 生命周期 |
| P1-S5 source identity | Host source 登记、路径/hash/epoch 传播及严格匹配；真实 Lua fixture | 实际 loader 提供正确 hash/epoch |
| P1-S6 USER/Probe/UI 仲裁 | 多 owner 合成、用户抢占、多 VM 暂停路由；JVM 回归 | 真实 IDEA 断点 UI 与 Probe 同时命中 |
| P1-S7 bootstrap/rollback | PID 映射校验、有界远程等待、启动返回值与部分注入状态 | 实际注入失败/重配/rollback |
| P2-F01 Registry/查询/事件 | Gateway/Adapter 生产接入；DTO/JVM/CLI 测试 | IDEA 多会话事件流 |
| P2-F02 授权/控制租约 | grant/revoke/TTL/heartbeat/独占控制；UI 入口 | 真实多客户端操作 |
| P2-F03 Probe/断点所有权 | owner 合成、Probe cleanup、取消和 autoContinue 限制；真实 Native/IDE/Gateway 条件采集后自动删断点 | CodexCli→已安装 IDEA→UE 一体化采集 |
| P2-F04 pause/frame 失效 | 生产 opaque 引用、scope 隔离、stale 校验与有界 FIFO；真实 Native/IDE/Gateway 的 continue/reset/close | UE 实机断开/重建时引用失效 |
| P2-F05 变量快照 | Native raw 预算；生产快照节点/字节/协作时间限额、Unicode 与截断标记、opaque 引用；Native 未展开表用 eval 读取 | 大规模 UE 数据快照；协作预算不承诺强杀 Lua |
| P2-F06 求值策略 | 首版 VALUE_PATH；任意 Lua、函数调用和未知 policy 拒绝 | 以后扩展表达式须重新实现 budget/capability，当前不承诺 |
| P2-F07 wait/cancel | Gateway JSONL 事件与 done/cancel；真实 JVM socket 断开清理绑定 Probe，普通短连接保留 Probe；旧代次隔离；CLI 失败 done 返回非零退出码 | CodexCli 与已安装 IDEA 的中断/退出 |
| P2-S1 schema/取消/幂等 | Session/cache/client 隔离；真实 pipe 重放/冲突/旧 epoch 测试 | 跨平台 CI 和长时间压力 |
| P2-S2 endpoint/输出隔离 | descriptor/token/ACL 路径、stdout JSON、安装分发 | 实际 IDEA 安装后的 Windows 用户隔离 |
| P2-S3 trust/redaction/rate/audit | 生产授权/撤销、信任检测、限流和审计；JVM 回归；断点清理失败最多三次尝试并发布 cleanupFailed/status 错误 | IDEA UI 审计/信任切换；真实断网后的清理恢复 |
| P2-S4 CI/构建/运行矩阵 | Windows 双架构、本地 JVM/Verifier、真实 Native/IDE/Gateway、CI 定义、资源 SHA 验证 | TCP、Linux/macOS 运行、GitHub CI、已安装 IDEA/UE/PIE |

## 仍须完成的外部验收

1. 在允许 Native 回环连接的环境运行未排除的 CTest，取得 TCP 两项通过记录。WFP 已确认 Windows 防火墙默认入站拦截。已提供 [受限验收脚本](../../../tools/test-native-tcp-with-firewall.ps1)，仅为两个明确测试程序临时放行 127.0.0.1 入站 TCP，并在 finally 删除本次规则；本轮未执行，操作系统规则变更须另获用户明确同意。
2. 安装本次插件包，在真实 IDEA 调试会话与 UE/UnLua Host 接入中验证两种启动顺序、零/双 VM、HotReload、重复 attach、Detach 后 PIE，以及 CLI Probe 条件采集与用户抢占。
3. 获得远端提交授权后再运行 Linux/macOS 与 IDEA 251/252 CI。本次仅本地中文提交，没有 push，也没有合并提交历史。
