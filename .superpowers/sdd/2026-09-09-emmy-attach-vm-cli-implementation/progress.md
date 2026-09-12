# SDD ledger - plan: docs/superpowers/plans/2026-09-09-emmy-attach-vm-cli-implementation.md

本轮接续记录。历史提交已覆盖任务 1、2、7 的主要步骤，以及任务 3、4、5、6、8、9 的部分步骤；以下只记录本轮重新验证后的状态。

Task 1: complete (历史提交 3112b32..)
Task 2: complete (历史提交 758189a..，工作树仍有后续 native 改动待归档)
Task 3: in progress (认证、重连、transport 生命周期和 attach bootstrap 闭环待补)
Task 4: in progress (暂停代次严格校验和并发回归待补)
Task 5: in progress (teardown barrier、ABI/HostValueProvider fixture 待补)
Task 6: in progress (SourceIdentity、重连状态机、UI 仲裁待补)
Task 7: in progress (文档已有，fixture/静态验证待补)
Task 8: in progress (Gateway、wait/cancel、应用 Service 和 CLI IPC 已有；真实 IDEA/named-pipe 运行时证据待补)
Task 9: in progress (授权、lease、VALUE_PATH、Probe 和 CLI 工具已有；真实 Lua/IDEA/UE 运行时证据待补)
Task 10: not started (CI、harness、验证矩阵待补)

## 2026-09-10 复核

以下状态按当前源码和实际命令证据更新；文件存在或能静态编译不等于运行时验收通过。

- Task 1：complete。JVM 协议 golden/round-trip 测试已有通过证据。
- Task 2：in progress。Native 动态验证当前记录为 10/10，source runtime harness 为 1/1；Host ABI/HostValueProvider 仍有后续改动，不能把整个任务标为 complete。
- Task 3：in progress。wire/frame/parse 基础已有；Native 认证的重复附加重协商、完整旧 epoch 请求淘汰、退避终态和 rollback 仍未形成完整验收证据。
- Task 4：in progress。per-VM 路由基础已有，暂停代次严格校验、并发一致性和 hook chaining 仍未闭环。
- Task 5：in progress。teardown/ABI/HostValueProvider 仍缺完整 native fixture、在途回调屏障和 hook chaining 证据。
- Task 6：in progress。VmRegistry 和 v2 分发已有；source hash 的 native 侧闭环、UI 仲裁和完整重连状态机未验证。
- Task 7：in progress。Host API/UnLua 文档和 fixture 基础已有；真实 UE/PIE、source hash native 侧、UnLua 生命周期接入未验证。
- Task 8：in progress。只读 Gateway、wait/cancel、应用 Service、named pipe 和 endpoint 清理已有 JVM 证据；真实 IDEA 服务启动和跨连接运行未验证。
- Task 9：in progress。授权/lease、VALUE_PATH、Gateway、AI Probe 和 CLI 工具已有定向 JVM 测试；真实 Lua 值读取、真实 IDEA/UE Probe、用户 UI 授权流程未验证。
- Task 10：not started。没有 GitHub CI 六级矩阵证据，也没有真实 UE/PIE、x86/x64 全矩阵和完整端到端回环证据。

### 当前明确缺口

1. Native Agent 认证与重复 attach 协商、重连 epoch 淘汰和 rollback 终态。
2. Lua hook chaining：保留宿主 hook 的 mask/count/回调，并覆盖宿主替换后的重新串联；当前实现仍不能作为完成证据。
3. Native 侧 source hash 与 loader epoch 的完整传播和匹配验证。
4. teardown barrier 与暂停中关闭的真实线程竞态验证。
5. HostValueProvider、ABI 私有布局和多 ABI 对账的真实 C ABI/UnLua fixture。
6. 真实 IDEA/UE Editor/PIE 周期、重复 attach、Detach 后完整 PIE 周期和崩溃安全验证。
7. GitHub CI、Windows x86/x64、Linux/macOS 构建及端到端 harness 尚无当前证据。

已完成但不应重复列为“未实现”的能力：CLI Gateway DTO/IPC、授权 grant/revoke、独占 lease、VALUE_PATH 拒绝 legacy 任意 Lua 求值、Probe owner/超时/用户抢占清理、CLI 参数解析和中文使用说明。上述能力仍需要真实 IDEA/UE 运行时验收。
