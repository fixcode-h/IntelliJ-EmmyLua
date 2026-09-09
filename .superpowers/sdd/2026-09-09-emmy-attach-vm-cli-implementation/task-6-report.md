# 任务 6 报告：IDEA SourceIdentity 与重连/暂停状态

## 完成内容

- Emmy 断点、run-to-position 和求值 DTO 增加可选 `SourceIdentityWire`。`SourceIdentity` 对路径统一使用 `/`、小写盘符/路径、折叠 `.`/`..`，文件存在时最多读取 1 MiB 计算 SHA-256；不存在或不可读取时 `verified=false` 且不发送 hash。
- VM registry 记录 connection/context/source epoch；新 epoch 要求 snapshot-first，允许新 epoch 的 `snapshotEventSeq=0`，旧 epoch event 被拒绝。generation、context 或 source epoch 变化会清除当前 pause。
- pause snapshot 增加 epoch 元数据，并实现单一当前 UI pause、其余 pause FIFO 查询队列和 v1/v2 同 pause 去重。断开、resume、step、VM close 和相关 epoch 生命周期会失效旧引用；断开同时取消求值请求。
- 重连保留指数退避（200 ms 起、上限 3 s）、四次上限和终态；快照请求在 transport 不存在时记录警告，不再静默成功。失败 transport 不会留作当前 transport。
- `AttachedNotify` 继续登记 legacy VM，但发现第二个 VM 后只给出 `AMBIGUOUS` 诊断，不隐式解析默认 VM。

## 测试

- 通过：`JAVA_HOME=C:\Users\happyelements\.jdks\jbr-21.0.11 .\gradlew.bat :modules:debugger-emmy-protocol:test --no-daemon`
- 已补充纯 Kotlin 单测：Windows 路径规范化/缺失文件 unverified、pause 当前 UI/队列/去重、新 epoch 的零序号 snapshot、旧 epoch event 拒绝。
- 根工程 `:compileKotlin` 与 `test` 当前无法全量通过：并行中的 CLI 协议类型尚未齐全（`CliTargetSummary`、`CliSourceIdentity` 等），另有 `EmmyAttachTargetBootstrap.kt` 的 `break/continue` 编译错误；这些不属于本子任务改动。最新过滤输出未包含本任务 SourceIdentity、VmRegistry、PauseSnapshotStore、Emmy process 文件的编译错误。

## Loopback 限制

本任务没有启动真实 loopback/IDEA UI。验证覆盖 DTO 和纯 Kotlin 状态机边界；真实 attach transport 的断线、重连、snapshot-first 和多 VM UI 行为仍需在 CLI loopback 协议完成并可编译后进行集成验证。
