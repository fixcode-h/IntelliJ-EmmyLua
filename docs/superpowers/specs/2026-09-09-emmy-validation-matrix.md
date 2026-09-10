# Emmy Attach、VM 与 CLI 验证矩阵

更新：2026-09-10。P0、P1、P2 全部纳入实施；“自动化通过”只表示对应测试层通过，不等同于真实 IDEA/UE/PIE 或远端 CI 验收。当前不能宣布全矩阵正常：Native TCP 两项仍失败，外部实机验收尚未执行。

## 本地验证结果

| 验证层 | 结果 | 证据与边界 |
| --- | --- | --- |
| JVM 全量测试 | 223 项，0 failure/error/skipped，63 suites | 根插件、5 个 debugger 模块、CLI 的 JUnit XML；包含生产 Adapter 的引用展开与 FIFO 淘汰回归 |
| 插件构建 / CLI 分发 | 通过 | buildPlugin、:tools:emmy-debug:installDist；CLI instance list 可运行，当前返回空实例 |
| Plugin Verifier | Compatible | IC-252.23892.409；1 处 deprecated API、21 处 experimental API，不能写成零警告 |
| Windows 动态 Native x64 Debug / Release | 各 14/14 | 两配置均完整构建；各显式排除 1 项 TCP 测试 |
| Windows 动态 Native x86 Debug / Release | 各 14/14 | 两配置均完整构建；各显式排除 1 项 TCP 测试 |
| 真实 Lua 5.4.6 source x64/x86 Debug | 各 17/17 | 包含真实 pipe 协议、VM 生命周期、coroutine/hook；各显式排除 2 项 TCP 测试 |
| Native TCP 单独复验 | 0/2，失败保留 | 并发连接与真实协议 harness 均连接超时；详见 Native 验收记录 |
| 新 Native 资源打包 | 8/8 SHA-256 一致 | 最终 ZIP 内 JAR 的 Windows x86/x64 资源与此次 Release 构建一致；没有替换仓库旧二进制 |
| Workflow 静态验证 | 两份 YAML 可解析 | Native 8 配置矩阵与父仓库 IDEA 251/252 已接入；没有远端执行记录 |
| IDEA / UE / PIE 实机 | 未验证 | 没有把单元测试、source harness 或安装包生成当作实机通过 |

最终本地命令：

```powershell
$env:JAVA_HOME='C:\Users\happyelements\.jdks\jbr-21.0.11'
./gradlew.bat --no-daemon --no-build-cache test buildPlugin verifyPlugin `
  :tools:emmy-debug:installDist `
  -PemmyNativeDir=F:/Project/IntelliJ-EmmyLua/build/emmy-native-resources

powershell -NoProfile -ExecutionPolicy Bypass -File tools/verify-emmy-native-resources.ps1 `
  -PluginZip build/distributions/EmmyLua-1.0.0-IDEA252.zip `
  -NativeRoot build/emmy-native-resources
```

产物：`build/distributions/EmmyLua-1.0.0-IDEA252.zip`。本次 ZIP SHA-256：

```text
D4D77D4EB44AA520084D10CE3F1DD88CA23961DC440FBA5D6CCA901275305B4C
```

JVM 报告位于根、`modules/*`、`tools/emmy-debug` 的 `build/test-results/test`；Verifier 位于 `build/reports/pluginVerifier/IC-252.23892.409`。Native 的构建目录、配置、命令与 TCP 失败报告见 [Native 验收记录](../../../EmmyLuaDebugger/docs/native_socket_acceptance_20260910.md)。

## 风险逐项对照

| 风险 | 已实现与已验证 | 开放验收 |
| --- | --- | --- |
| P0-F01 VM 生命周期与 Agent/VM 分离 | Host Registry/Native Registry；真实 Lua 双 VM、Ready/close/reset | UE PIE 创建、关闭、重建周期 |
| P0-F02 多 VM 显式路由 | VM/thread/pause/frame/context/source 校验；JVM + Native 隔离测试 | UE 双 VM 控制隔离 |
| P0-F03 握手、Ready、snapshot | 真实 pipe 的 Init/Ready/snapshot/reconnect | Native TCP；实际 IDEA 连接 |
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
| P1-S3 context reset | pause/cache/source/Probe 失效；真实 Lua reset | UnLua HotReload 与 PIE reset |
| P1-S4 HostValueProvider | C ABI、预算/对象失效/字段拒绝/调度 fixture | 宿主实际 GameThread 副本、反射白名单与 callback 生命周期 |
| P1-S5 source identity | Host source 登记、路径/hash/epoch 传播及严格匹配；真实 Lua fixture | 实际 loader 提供正确 hash/epoch |
| P1-S6 USER/Probe/UI 仲裁 | 多 owner 合成、用户抢占、多 VM 暂停路由；JVM 回归 | 真实 IDEA 断点 UI 与 Probe 同时命中 |
| P1-S7 bootstrap/rollback | PID 映射校验、有界远程等待、启动返回值与部分注入状态 | 实际注入失败/重配/rollback |
| P2-F01 Registry/查询/事件 | Gateway/Adapter 生产接入；DTO/JVM/CLI 测试 | IDEA 多会话事件流 |
| P2-F02 授权/控制租约 | grant/revoke/TTL/heartbeat/独占控制；UI 入口 | 真实多客户端操作 |
| P2-F03 Probe/断点所有权 | owner 合成、Probe cleanup、取消和 autoContinue 限制 | CLI→IDEA→UE 一体化采集 |
| P2-F04 pause/frame 失效 | 生产 opaque 引用、scope 隔离、stale 校验与有界 FIFO；VM close/reset | 实机断开/重建时引用失效 |
| P2-F05 变量快照 | Native raw 预算；生产 scope/child 展开、特殊 key 与同名变量测试 | 大规模 UE 数据快照 |
| P2-F06 求值策略 | 首版 VALUE_PATH；任意 Lua、函数调用和未知 policy 拒绝 | 以后扩展表达式须重新实现 budget/capability，当前不承诺 |
| P2-F07 wait/cancel | Gateway JSONL 事件与 done/cancel；named pipe JVM 测试 | 真正 AI CLI 长连接和 IDEA 退出 |
| P2-S1 schema/取消/幂等 | Session/cache/client 隔离；真实 pipe 重放/冲突/旧 epoch 测试 | 跨平台 CI 和长时间压力 |
| P2-S2 endpoint/输出隔离 | descriptor/token/ACL 路径、stdout JSON、安装分发 | 实际 IDEA 安装后的 Windows 用户隔离 |
| P2-S3 trust/redaction/rate/audit | 生产授权/撤销、信任检测、限流和审计；JVM 回归 | IDEA UI 审计/信任切换 |
| P2-S4 CI/构建/运行矩阵 | Windows 双架构、本地 JVM/Verifier、CI 定义、资源 SHA 验证 | TCP、Linux/macOS、GitHub CI、IDEA/UE/PIE |

## 仍须完成的外部验收

1. 在允许 Native 回环连接的环境运行未排除的 CTest，取得 TCP 两项通过记录。当前对照诊断指向本机网络访问限制，未确定具体拦截组件，也未更改安全策略。
2. 安装本次插件包，在真实 IDEA 调试会话与 UE/UnLua Host 接入中验证两种启动顺序、零/双 VM、HotReload、重复 attach、Detach 后 PIE，以及 CLI Probe 条件采集与用户抢占。
3. 获得远端提交授权后再运行 Linux/macOS 与 IDEA 251/252 CI。本次仅本地中文提交，没有 push，也没有合并提交历史。
