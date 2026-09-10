# Emmy Attach、VM 与 CLI 验证矩阵

本矩阵记录当前可复核证据。`实现` 只表示源码路径存在，`静态/JVM/Native` 表示对应自动化证据；没有真实 UE/PIE 或 GitHub CI 记录时保持未验证。

| 风险 | 当前状态 | 已有证据 | 尚未验证或缺口 |
| --- | --- | --- | --- |
| P0-F01 VM 生命周期与 Agent/VM 分离 | 部分通过 | Native 动态验证 10/10；source runtime harness 1/1 | 真实 UE PIE 创建、关闭、重建周期 |
| P0-F02 多 VM 显式路由 | 部分通过 | Native/JVM 单元基础 | 双 VM UE/PIE 控制隔离 |
| P0-F03 握手、Ready、snapshot 闭环 | 部分通过 | v2 JVM golden 与 native protocol 基础 | 真实 Agent socket 回环、重连 snapshot 线性化 |
| P0-F04 hook chaining 与 teardown barrier | 未完成 | 基础 hook 生命周期代码 | 宿主 hook 保留、在途回调、Detach 后完整 PIE 周期 |
| P0-S1 唯一 ABI/导出边界 | 部分通过 | 静态导出/子模块代码检查 | 真实 GetProcAddress 与 Bridge 不重复链接验证 |
| P0-S2 精确 Lua 私有布局 | 部分通过 | ABI descriptor 单元 fixture | UnLua C ABI 真实注册、私有读拒绝未知布局 |
| P0-S3 暂停粒度与一致性 | 未完成 | 部分 pause/frame 单元测试 | 双 coroutine 交错命中和 UE 实际线程暂停 |
| P0-S4 受限求值与不可中断 | 部分通过 | VALUE_PATH JVM 测试、legacy policy 拒绝 | 真实 Lua/UE 求值、超时和取消运行时证据 |
| P1-F05 稳定进程/Agent/VM 身份 | 部分通过 | Registry/epoch JVM 与 Native 基础 | 重复 attach、PID/地址复用真实流程 |
| P1-F06 混合 Lua ABI 检测 | 部分通过 | Native ABI fixture | 多 ABI Agent 真实第二 VM 注册 |
| P1-F07 wire/schema/framing/错误 | 部分通过 | JVM golden、native protocol 基础 | GitHub CI fuzz/frame 矩阵 |
| P1-F08 ProcessAttachmentManager 解耦 | 未验证 | 代码存在 | Agent 重启/PID 复用真实 IDEA 流程 |
| P1-S1 Native Agent 认证 | 未完成 | 可选认证代码静态存在 | nonce/token、重复 attach、未认证不得进 ProtoHandler |
| P1-S2 epoch/重连终态 | 未完成 | 有限重连基础 | 旧请求淘汰、退避上限、最终终止 |
| P1-S3 context reset/HotReload | 部分通过 | JVM reset/epoch 基础 | UnLua HotReload 与 PIE reset 实测 |
| P1-S4 HostValueProvider | 部分通过 | HostValueProvider/ABI fixture 基础 | GameThread 副本、对象失效、真实 UE 反射白名单 |
| P1-S5 source identity | 部分通过 | Kotlin canonical/hash/epoch 基础 | Native source hash 传播和真实 loader epoch |
| P1-S6 Probe/USER/UI 仲裁 | 部分通过 | Probe/JVM 用户抢占和 lease 测试 | 真实 IDEA UI、用户断点合并、多 VM 仲裁 |
| P1-S7 Attach bootstrap/rollback | 部分通过 | CLI/descriptor 静态与 JVM 基础 | already-attached、指数退避、rollback 和真实注入 |
| P2-F01 Target Registry/查询/事件流 | 部分通过 | JVM Gateway/Registry 测试 | 真实 IDEA 多会话事件流 |
| P2-F02 授权与控制租约 | 部分通过 | grant/revoke、lease、Gateway JVM 测试 | IDEA 用户入口和真实连接跨客户端验证 |
| P2-F03 Probe/断点所有权 | 部分通过 | owner-aware JVM/Probe 测试 | 真实 Agent composite snapshot |
| P2-F04 pause/frame 失效 | 部分通过 | stale pause/frame JVM 测试 | VM 关闭/重建真实运行时 |
| P2-F05 有界变量快照 | 部分通过 | VALUE_PATH/变量预算 JVM 测试 | 真实 Lua table、Native JSON 边界 |
| P2-F06 安全求值策略 | 部分通过 | VALUE_PATH AST 和 adapter policy 测试 | UE/UnLua 真实 `__index`/函数不调用证据 |
| P2-F07 wait/cancel 事件流 | 部分通过 | Gateway wait/cancel JVM 基础 | named pipe 跨连接和断线清理运行时 |
| P2-S1 schema/取消/幂等/framing | 部分通过 | request/cache/client 隔离并发测试 | fuzz、Native epoch 和 CI 门槛 |
| P2-S2 endpoint/输出隔离 | 部分通过 | descriptor/token/CLI JVM 测试 | Windows ACL、真实安装打包运行 |
| P2-S3 trust/redaction/rate/audit | 部分通过 | Gateway 授权/revoke/脱敏测试 | IDEA UI 审计和 UE trust 状态 |
| P2-S4 CI/构建/运行矩阵 | 未完成 | 本地动态 Native 10/10、source harness 1/1、定向 JVM 测试 | GitHub CI、x86/x64、Linux/macOS、UE/PIE 六级证据 |

## 当前验证记录

- 本地动态 Native：10/10，具体命令和日志由当前构建记录维护；不等同于 UE/PIE。
- source runtime harness：1/1；仅证明 source runtime harness 路径，不等同于真实 UnLua。
- JVM：定向协议、Gateway、Probe、CLI 测试已有通过记录；`buildPlugin` 尚在进行/等待根代理报告，暂不标为通过。
- 根插件全量测试曾在既有 Windows named pipe 测试失败；该失败不能被定向 JVM 通过覆盖。
- 真实 IDEA、UE Editor/PIE、重复 attach、Detach 后 PIE 周期和 GitHub CI：当前未验证。
