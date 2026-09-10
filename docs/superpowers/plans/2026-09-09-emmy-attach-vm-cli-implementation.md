# Emmy Attach VM 与 CLI 调试实施计划

> 按任务实施、审查和验证，创建独立的本地中文提交，不推送远端。本文所在目录沿用历史路径，当前实施不使用 Superpowers 工作流。

**目标：** 将 Emmy Attach 从隐式单 VM 调试改造成具备明确 Agent/VM 生命周期、VM-scoped 调试路由和可供后续 `emmy-debug` CLI 使用的稳定基础契约。

**架构：** 保留现有 IDEA XDebugger 会话和 Emmy v1 wire id，在其上追加显式的 v2 Envelope、Agent 握手响应、VM snapshot/lifecycle 事件和 opaque VM 身份。原生 Agent 以 `NativeVmRegistry` 管理每个 VM，每个 VM 独占 Debugger 和 HookState；IDEA 以 `VmRegistry` 接收并缓存状态。CLI Gateway、授权租约和 AI Probe 已建立在这些 DTO 之上，通过生产 Adapter 接入已有调试会话。

**进度口径（2026-09-10）：** P0、P1、P2 全部纳入下列十项任务。源码实施与本地自动化验证已覆盖主要链路，包括真实 IDEA 平台会话 → Native Lua 宿主 → CLI Gateway 的条件采集与 VM 生命周期闭环；已安装 IDEA GUI/EasyHook/UE/PIE、受本机环境阻塞的 Native TCP，以及尚未运行的远端跨平台 CI 仍是开放验收项。任务 10 的“全矩阵”保持未完成。逐项证据以 [验证矩阵](../specs/2026-09-09-emmy-validation-matrix.md) 为准，不以文件存在或复选框代替运行记录。

**技术栈：** Kotlin 2.1/JVM 17/21、IntelliJ Platform XDebugger、Gson、C++11、libuv、nlohmann/json、Windows EasyHook、现有 Gradle/CMake 构建链。

## 全局约束

- 旧 Emmy wire id `0..17` 永久冻结，新增消息使用 `EnvelopeV2=18`。
- Agent Ready 与 Lua VM Ready 是两个独立状态；Agent 在线但没有 VM 是合法状态。
- 所有新 VM 级请求携带 `vmId`；暂停引用携带 `pauseId`，不能使用裸 `lua_State*` 作为公共身份。
- 一个 Process Agent 首版只接受一个精确 Lua ABI/layout descriptor；混合 ABI 必须显式报错。
- Host 生命周期操作通常由 Lua owner thread 完成。`BeginLuaVmClose` 可由关闭协调线程调用，仅失效请求并唤醒暂停；宿主随后须等待 owner 安全返回，再在 owner 上调用 `lua_close` 与 `EndLuaVmClose`。`EndLuaVmClose` 不得访问已关闭的 `lua_State*`。
- Hook 停止必须先进入 disabled fast-path，再等待在途回调清空；不能在回调中访问已释放 Transporter。
- 原生协议线程不直接读取 Lua 栈；Lua API 只在 Lua owner thread 执行。
- Host API 在 Agent 尚未激活时必须进入进程内 Host Registry；Agent 激活后必须回放/对账已存在的 VM，不能用 `registrationId=0` 丢失 VM。
- Native Agent 通道必须在执行 `InitReq` 之前完成 nonce/token 认证；未认证连接不得加载 helper、控制或求值。
- 首版暂停语义固定为 `THREAD_PAUSE`：只保证命中 thread 的 Lua 栈一致性；任何 VM-wide 一致性必须由能力字段显式声明。
- 受限求值不得依赖跨线程强杀；`VALUE_PATH` 采用无副作用解析器，`RESTRICTED_EXPRESSION` 必须有 instruction budget 和不可 yield 约束，超时只返回失败。
- VM 重建、UnLua HotReload 和 PIE reset 使用 `contextGeneration/sourceEpoch` 失效旧 frame、cache、Probe 和脚本映射。
- Host-specific userdata 通过可选 `HostValueProvider` 扩展，必须定义 LuaThread/GameThread 调度、对象有效性和反射白名单。
- 重连策略必须定义退避、最大等待、旧请求取消、snapshot 线性化和终态；不能只改变一个枚举值。
- Native/IDEA/CLI 的 schema、认证、取消、幂等、事件丢失和端点清理都必须有自动化测试与 CI 检查。
- AI/CLI 默认只读、有界；本阶段不实现 CLI 启动/附加/终止进程。
- 每个任务独立提交，提交信息使用中文；只在本地提交，禁止 `git push`。
- 修改 Git 子模块时先在子模块本地提交，再在父仓库单独提交子模块指针。

---

### 任务 1：协议 ID 冻结与 v2 DTO

**文件：**

- 修改：`modules/debugger-emmy-protocol/src/main/kotlin/com/tang/intellij/lua/debugger/emmy/EmmyProtocol.kt`
- 创建：`modules/debugger-emmy-protocol/src/main/kotlin/com/tang/intellij/lua/debugger/emmy/EmmyProtocolV2.kt`
- 修改：`modules/debugger-emmy-protocol/src/test/kotlin/com/tang/intellij/lua/debugger/emmy/EmmyProtocolGoldenTest.kt`
- 创建：`modules/debugger-emmy-protocol/src/test/kotlin/com/tang/intellij/lua/debugger/emmy/EmmyProtocolV2Test.kt`

**接口：**

- `MessageCMD` 保持现有 `wireId`，新增 `EnvelopeV2(18)`，禁止依赖 enum ordinal。
- `data class EmmyV2Target(val vmId: String? = null, val threadId: String? = null, val pauseId: Long? = null, val frameId: String? = null)`。
- `data class EmmyV2Envelope(val cmd: Int = 18, val protocolVersion: Int = 2, val kind: String, val type: String, val requestId: String? = null, val agentSessionId: String? = null, val connectionEpoch: Long? = null, val eventSeq: Long? = null, val target: EmmyV2Target? = null, val ok: Boolean? = null, val error: EmmyV2Error? = null, val payload: JsonObject? = null)`。
- `data class EmmyV2Error(val code: String, val message: String, val retryable: Boolean = false, val details: JsonObject? = null)`。
- `data class VmDto(val vmId: String, val generation: Long, val displayName: String, val state: String, val luaVersion: String?, val discovery: String, val diagnosticStateAddress: String? = null)`。
- `data class VmSnapshotDto(val snapshotEventSeq: Long, val vms: List<VmDto>)`。
- `data class VmLifecycleDto(val vmId: String, val generation: Long, val previous: String?, val current: String, val reason: String? = null, val eventSeq: Long)`。
- `data class AgentDescribeDto(val agentSessionId: String, val protocolVersion: Int, val processId: Long, val capabilities: List<String>)`。

协议测试的最小断言形态：

```kotlin
val encoded = EmmyV2Envelope(
    kind = "event",
    type = "vm.lifecycle",
    eventSeq = 7,
    target = EmmyV2Target(vmId = "vm-1"),
    payload = JsonObject()
).toJson()
assertEquals(18, JsonParser.parseString(encoded).asJsonObject["cmd"].asInt)
assertEquals("vm.lifecycle", JsonParser.parseString(encoded).asJsonObject["type"].asString)
```

- [x] **步骤 1：写失败测试**

  测试显式断言 v1 wire id 列表、v2 cmd=18、Envelope JSON round-trip、缺省 target/error 字段和 VM snapshot/lifecycle 字段。

- [x] **步骤 2：运行测试确认失败**

  运行：`./gradlew.bat :modules:debugger-emmy-protocol:test --tests '*EmmyProtocolV2Test'`

  预期：由于 `EmmyProtocolV2` 和 `EnvelopeV2` 尚不存在而失败。

- [x] **步骤 3：实现最小协议模型**

  使用 Gson `JsonObject` 保持 payload 的未知字段；所有 ID 使用显式常量；不要修改 0-17 的含义。

- [x] **步骤 4：运行测试确认通过**

  运行：`./gradlew.bat :modules:debugger-emmy-protocol:test`

  预期：协议模块全部通过。

- [x] **步骤 5：本地提交**

  `git add modules/debugger-emmy-protocol && git commit -m "协议：冻结 Emmy wire id 并新增 v2 DTO"`

### 任务 2：原生 VM Registry 与 Host C ABI

**文件：**

- 创建：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/vm/vm_registry.h`
- 创建：`EmmyLuaDebugger/emmy_debugger/src/vm/vm_registry.cpp`
- 修改：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/emmy_facade.h`
- 修改：`EmmyLuaDebugger/emmy_debugger/src/emmy_facade.cpp`
- 修改：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/debugger/emmy_debugger_lib.h`
- 修改：`EmmyLuaDebugger/emmy_debugger/src/debugger/emmy_debugger_lib.cpp`
- 修改：`EmmyLuaDebugger/emmy_debugger/CMakeLists.txt`
- 创建：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/vm/vm_lifecycle.h`
- 创建：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/vm/host_vm_registry.h`
- 创建：`EmmyLuaDebugger/emmy_debugger/src/vm/host_vm_registry.cpp`
- 创建：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/vm/lua_abi_descriptor.h`

**接口：**

- `enum class VmLifecycleState { Created, Ready, Running, Paused, Closing, Closed, Lost, Error };`
- `struct VmMetadata { std::string displayName; std::string engineName; std::string engineContext; std::string luaVersionHint; std::string runtimeModule; };`
- `struct VmRecord { uint64_t id; uint64_t generation; lua_State* mainState; VmMetadata metadata; VmLifecycleState state; uint64_t eventSeq; };`
- `class NativeVmRegistry { Register(lua_State*, const VmMetadata&); NotifyReady(uint64_t); BeginClose(uint64_t, const std::string&); EndClose(uint64_t); Release(uint64_t); Find(uint64_t); FindByState(lua_State*); Snapshot(); SetEventSink(...); }`。
- `class HostVmRegistry { RegisterBeforeAgent(...); MarkReady(...); BeginClose(...); EndClose(...); DrainTo(NativeVmRegistry&); }`：Agent 未激活时先保存宿主注册信息，激活时一次性对账；不能因 `registrationId=0` 丢失事件。
- C ABI 导出：`Emmy_RegisterLuaVm`、`Emmy_NotifyLuaVmReady`、`Emmy_BeginLuaVmClose`、`Emmy_EndLuaVmClose`、`Emmy_ReleaseLuaVmRegistration`、`Emmy_SetLuaVmDisplayName`。
- `registrationId=0` 只表示参数无效或注册失败；Agent 尚未激活时也必须返回非零 pending registrationId，供后续 Ready/Close 调用关联 Host Registry 记录。所有字符串参数只在调用期间借用，Registry 必须复制。
- C ABI 必须定义 `EMMY_HOST_API_VERSION`、结构体 `size/version`、Windows `__declspec(dllexport)` 和 `__cdecl` 调用约定；宿主通过 `GetProcAddress` 绑定注入 Agent 的唯一导出，不得静态链接第二份 `EmmyFacade`。
- Host Registry 必须提供 `ReconcileExistingVms()`；Agent 在 InitReq 前后都可调用，返回已存在 VM 的注册状态和 layout fingerprint。

Registry 的核心状态约束：

```cpp
struct VmRecord {
    uint64_t id;
    uint64_t generation;
    lua_State* mainState;
    VmLifecycleState state;
    uint64_t eventSeq;
};

// Register 同一活动 mainState 必须返回同一 id；BeginClose/EndClose/Release 可重复调用。
uint64_t Register(lua_State* mainState, const VmMetadata& metadata);
bool BeginClose(uint64_t id, const std::string& reason);

// Agent 尚未激活时不丢失 Host 事件；激活后把记录转移到 NativeVmRegistry。
bool ReconcileExistingVms(NativeVmRegistry& destination);
```

- [x] **步骤 1：写失败测试/可执行 harness**

  在 `vm_registry.cpp` 同目录增加仅在 `EMMY_VM_REGISTRY_TEST` 下编译的纯 C++ harness，覆盖重复注册、状态转换、generation、地址复用和 Begin/EndClose 幂等；不调用 Lua API。

- [x] **步骤 2：运行 harness 确认失败**

  运行：`cmake -S EmmyLuaDebugger -B EmmyLuaDebugger/build-vm-registry -DEMMY_VM_REGISTRY_TEST=ON`；随后构建 `cmake --build EmmyLuaDebugger/build-vm-registry --config Debug --target emmy_debugger`。

  预期：新 Registry 符号尚未实现时配置或编译失败。

- [x] **步骤 3：实现 Host Registry、Native Registry 和激活对账**

  使用静态/原子 ID 生成器；以 main state 地址作为内部索引，以 generation 防止地址复用；不在 Registry 锁内调用 EventSink。Agent 未激活时把 Host API 调用写入进程内 Host Registry，并返回可用于后续生命周期调用的 pending registrationId；InitReq 到达后执行 `ReconcileExistingVms()`，再把事件放入有界 pending 队列；不得在 Agent 激活前返回并丢弃一个可观测 VM。

- [x] **步骤 4：接入唯一 C ABI 导出并构建**

  将两个 `src/vm/*.cpp` 加入 CMake；在 `emmy_debugger_lib.cpp` 用 `extern "C"`、固定调用约定和 Windows 导出宏转发到 `EmmyFacade::Get()`。UE Bridge 只能通过 `GetProcAddress(emmy_hook.dll, "Emmy_*")` 绑定；不要在 `DllMain` 中初始化 Registry，也不要让 Bridge 自己链接 `emmy_debugger`。

- [x] **步骤 5：运行测试确认通过**

  运行：`cmake --build EmmyLuaDebugger/build-vm-registry --config Debug --target emmy_debugger emmy_core emmy_hook`；执行 harness 并检查退出码为 0。

- [x] **步骤 6：子模块本地提交**

  在 `EmmyLuaDebugger` 内运行：`git add emmy_debugger && git commit -m "原生：新增 Lua VM 生命周期注册表与 Host API"`。

- [x] **步骤 7：父仓库提交子模块指针**

  在父仓库运行：`git add EmmyLuaDebugger && git commit -m "同步：更新 EmmyDebugger VM 生命周期子模块"`。

### 任务 3：原生协议握手、snapshot 和 lifecycle 事件

**文件：**

- 修改：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/transporter/transporter.h`
- 修改：`EmmyLuaDebugger/emmy_debugger/src/transporter/transporter.cpp`
- 修改：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/proto/proto.h`
- 修改：`EmmyLuaDebugger/emmy_debugger/src/proto/proto.cpp`
- 修改：`EmmyLuaDebugger/emmy_debugger/src/proto/proto_handler.cpp`
- 修改：`EmmyLuaDebugger/emmy_debugger/src/emmy_facade.cpp`
- 修改：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/emmy_facade.h`
- 创建：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/proto/protocol_session.h`
- 创建：`EmmyLuaDebugger/emmy_debugger/src/proto/protocol_session.cpp`
- 创建：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/transporter/transport_auth.h`
- 创建：`EmmyLuaDebugger/emmy_debugger/src/transporter/transport_auth.cpp`
- 修改：`EmmyLuaDebugger/emmy_tool/src/command_line.h`
- 修改：`EmmyLuaDebugger/emmy_tool/src/command_line.cpp`
- 修改：`EmmyLuaDebugger/emmy_tool/src/windows/utility.cpp`
- 修改：`src/main/java/com/tang/intellij/lua/debugger/emmy/attach/EmmyAttachTargetBootstrap.kt`
- 修改：`modules/debugger-emmy-protocol/src/main/kotlin/com/tang/intellij/lua/debugger/emmy/EmmyProtocolV2.kt`
- 修改：`modules/debugger-emmy-protocol/src/test/kotlin/com/tang/intellij/lua/debugger/emmy/EmmyProtocolV2Test.kt`

**接口：**

- `InitRsp` 返回 `protocolVersion=2`、`agentSessionId`、`capabilities`、`processId`。
- `ReadyRsp` 返回 Agent Ready 和当前 `snapshotEventSeq`。
- v2 `vm.snapshot` 请求/响应、`vm.lifecycle` 事件、`debug.paused`/`debug.resumed` 基本 Envelope。
- Transport 收发增加最大 frame 限制（默认 1 MiB）和 JSON parse failure 的结构化诊断；旧 v1 两行 framing 保持兼容。
- `EmmyFacade::FlushPendingVmEvents()` 只在 Transport 已连接且握手完成后执行。
- Native Agent 在 `InitReq` 前执行 challenge/response；nonce 由 `emmy_tool attach` 生成并通过受保护的启动参数或共享内存传入，token 不出现在普通日志。
- 请求状态包含 `requestId`、`connectionEpoch` 和 `operationHash`；重复 requestId 只能返回第一次结果，不能重复执行 Init/Action/Eval。
- 增加 `cancel` 消息和每请求 deadline；无法中止 Lua 执行时只撤销响应订阅并返回 `CANCEL_UNSUPPORTED`，不能从其他线程强杀 Lua owner thread。
- 明确定义重连：同一 Agent 保持 `agentSessionId`，每次连接递增 `connectionEpoch`；旧 epoch 的请求和事件全部丢弃，重连后先 snapshot 再增量。
- `emmy_tool attach` 返回结构化 bootstrap status（injected/listening/auth-ready/error），等待 endpoint handshake 而不是固定 sleep 100 ms；失败时报告是否已注入、是否已创建 listener，并避免把 already-attached 当作完整成功。

握手回环的固定字段示例：

```json
{"cmd":18,"protocolVersion":2,"kind":"response","type":"agent.describe","requestId":"r1","ok":true}
{"cmd":18,"protocolVersion":2,"kind":"response","type":"agent.ready","requestId":"r2","ok":true}
{"cmd":18,"protocolVersion":2,"kind":"response","type":"vm.snapshot","requestId":"r3","ok":true,"payload":{"snapshotEventSeq":4,"vms":[]}}
```

- [x] **步骤 1：写 Kotlin golden tests**

  增加 InitRsp/ReadyRsp/snapshot/lifecycle JSON 样例及未知字段兼容测试。

- [x] **步骤 2：写 native protocol test vector**

  在 C++ harness 中构造同样的 JSON，检查 `cmd=18`、`type`、`eventSeq`、`vmId` 和错误对象字段。

- [x] **步骤 3：实现 native responses/events**

  `OnInitReq` 先完成认证、检查 requestId 幂等表，再生成/复用 session ID 并回复 InitRsp；`ReadyReq` 设置握手完成、回复 ReadyRsp、发送完整 snapshot 和 pending lifecycle events。任何事件发送失败只记录诊断，不阻塞 Lua owner thread。

- [x] **步骤 4：实现 frame 上限和 parse error**

  在接收缓冲区追加常量上限；超过上限关闭当前连接并调用一次 OnDisconnect；解析异常不让 transport event loop 崩溃。为每条请求校验 requestId 长度、deadline、epoch 和幂等 hash；实现 cancel 请求的明确错误响应。

- [x] **步骤 5：实现 Native Agent 认证和重连 epoch**

  `TransportAuth` 比较一次性高熵随机 token（首版不引入新的加密库）；认证失败延迟关闭连接且不调用 `ProtoHandler`。重连保留 agentSessionId、递增 connectionEpoch，并在 Ready 后按 snapshotEventSeq 重放。

- [x] **步骤 6：修正 Attach bootstrap 状态**

  `EmmyAttachTargetBootstrap` 生成一次性 token，传给 `emmy_tool attach`；`emmy_tool` 通过受保护共享内存/参数传给注入 Agent，输出 machine-readable status；IDEA 轮询 endpoint/handshake，使用指数退避和明确总超时，失败时记录 rollback 状态。

- [ ] **步骤 7：运行验证**

  运行：`./gradlew.bat :modules:debugger-emmy-protocol:test`；构建 native Debug target；使用本地 socket harness 完成 InitReq -> InitRsp -> ReadyReq -> ReadyRsp -> vm.snapshot 的回环。

- [x] **步骤 8：本地提交**

  子模块提交：`git commit -m "协议：补齐 Emmy 握手响应与 VM 生命周期事件"`。父仓库提交：`git commit -m "同步：更新 Emmy 协议事件子模块"`。

当前进度说明：认证、重连 epoch、幂等/取消、bootstrap 状态和有界退避已实现。真实 Native 命名管道 harness 已覆盖未认证拒绝、Init/Ready/snapshot、断点/求值/继续、重复请求和重连旧 epoch 拒绝；TCP 同链路仍受本机连接阻塞影响，真实进程重复注入和 IDEA rollback 尚需实机验收，因此本任务的完整运行验证保留开放。

### 任务 4：per-VM Debugger、HookState 与控制路由

**文件：**

- 修改：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/debugger/emmy_debugger_manager.h`
- 修改：`EmmyLuaDebugger/emmy_debugger/src/debugger/emmy_debugger_manager.cpp`
- 修改：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/debugger/emmy_debugger.h`
- 修改：`EmmyLuaDebugger/emmy_debugger/src/debugger/emmy_debugger.cpp`
- 修改：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/debugger/hook_state.h`
- 修改：`EmmyLuaDebugger/emmy_debugger/src/debugger/hook_state.cpp`
- 修改：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/proto/proto.h`
- 修改：`EmmyLuaDebugger/emmy_debugger/src/proto/proto_handler.cpp`
- 修改：`EmmyLuaDebugger/emmy_debugger/src/emmy_facade.cpp`

**接口：**

- `Debugger` 保存所属 `vmId`，并在构造时创建自己的 `HookStateBreak/Continue/Step*`。
- `EmmyDebuggerManager::DoAction(uint64_t vmId, DebugAction)`、`Eval(uint64_t vmId, shared_ptr<EvalContext>)`、`GetDebuggerByVmId(uint64_t)`。
- `ActionParams`、`EvalContext`、`BreakNotify` 增加可选 `vmId`、`pauseId`、`threadId`；旧消息无 ID 时仅走 legacy 单 VM 路由。
- `Debugger::GetStacks` 返回真实成功状态，并绑定 `pauseId`。
- `enum class PauseScope { Thread, Vm }`，首版默认并明确上报 `Thread`；不得把单线程阻塞伪装成 VM-wide snapshot。
- `PauseRecord { vmId, threadId, pauseId, scope, consistency, reason }`；`consistency` 在 `Thread` 模式为 `THREAD_ONLY`，AI 采集只能读取该 thread 的 frame。

显式路由必须拒绝隐式 fallback：

```cpp
struct RouteResult {
    bool ok;
    const char* errorCode;
};

RouteResult DoAction(uint64_t vmId, bool hasPauseId, uint64_t pauseId, DebugAction action);
RouteResult Evaluate(uint64_t vmId, uint64_t pauseId, uint64_t frameId, const EvalPolicy& policy);
```

- [x] **步骤 1：增加隔离测试**

  用两个 Debugger fake 验证 StepOver/StepIn 状态对象地址不同、按 vmId 路由不会读取另一个 VM 的 current state；增加未知 vmId 和 legacy 多 VM 拒绝测试。

- [x] **步骤 2：实现 per-VM HookState**

  删除 Manager 中共享的可变 HookState 成员，移动为 Debugger 私有成员；保持状态类接口不变，先保证单 VM 行为不变。

- [x] **步骤 3：实现显式路由**

  Manager 通过 NativeVmRegistry 查找 vmId；找不到返回结构化 `VM_NOT_FOUND`；legacy 请求仅在唯一活动 VM 时转发，否则 `AMBIGUOUS_VM`。

- [x] **步骤 4：绑定暂停代次**

  Debugger 每次 HandleBreak 递增 pauseId，并记录命中 thread 和 `PauseScope::Thread`；continue/step/close 先清空活动 pause；迟到 Eval 依据 vmId+pauseId+threadId 丢弃。只有未来实现所有 Lua owner thread 的安全屏障后才能声明 `PauseScope::Vm`。

- [x] **步骤 5：增加并发/一致性回归**

  用两个 VM、同一 VM 两个 coroutine 和交错命中顺序验证：一个 thread 暂停时另一个 thread 仍可运行；CLI/IDE 返回 `consistency=THREAD_ONLY`，不会返回全 VM 一致性承诺。

- [x] **步骤 6：构建与回归**

  运行 native Debug build 和原有 IDEA `:test`；使用 Lua 5.4 harness 验证两个 VM 的断点/求值路由。

- [x] **步骤 7：本地提交**

  子模块：`git commit -m "调试器：隔离每个 Lua VM 的状态与控制路由"`；父仓库：`git commit -m "同步：更新 per-VM 调试器子模块"`。

当前进度说明：per-VM HookState、完整 v2 控制/求值响应、VM/thread/pause/frame/context/source 绑定和 owner thread 控制已实现。resumed 在 Lua owner 实际执行动作后发布；真实 Lua harness 覆盖双 VM、coroutine 单步隔离、reset 和暂停中关闭；暂停语义仍为 THREAD，不承诺 VM 全局一致性。

### 任务 5：安全 teardown、hook chaining 与 ABI 描述

**文件：**

- 修改：`EmmyLuaDebugger/emmy_hook/src/emmy_hook.windows.cpp`
- 修改：`EmmyLuaDebugger/emmy_hook/src/dllmain.cpp`
- 修改：`EmmyLuaDebugger/emmy_debugger/src/emmy_facade.cpp`
- 修改：`EmmyLuaDebugger/emmy_debugger/src/api/lua_api_loader.cpp`
- 修改：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/api/lua_api_loader.h`
- 修改：`EmmyLuaDebugger/emmy_debugger/src/api/lua_state/lua_state_54.cpp`
- 修改：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/vm/vm_registry.h`
- 创建：`EmmyLuaDebugger/emmy_hook/src/hook_manager.h`
- 创建：`EmmyLuaDebugger/emmy_hook/src/hook_manager.cpp`
- 创建：`EmmyLuaDebugger/emmy_debugger/include/emmy_debugger/vm/host_value_provider.h`

**接口：**

- `HookManager::DisableAndQuiesce(timeout)`、`HookManager::UnhookIfSafe()`、`HookManager::InFlightCount()`。
- `LuaAbiDescriptor { major, minor, release, layoutHash, luaIdSize, privateLayoutSupported }`。
- Hook callback 入口检查 `AgentLifecycle::enabled`，禁用后只调用原函数。
- 对标准 `lua_Hook`、项目 `splua_sethook`/`luaSP_setobjhook` 定义 chaining/ownership；无法安全共存时能力字段明确为 false。
- `HookChainRecord { previousHook, previousMask, previousCount, emmyHook, owner }`；恢复宿主 hook 时只恢复仍属于当前 chain 的版本，不能覆盖宿主后续替换。
- `HookQuiescence { EnterCallback(), LeaveCallback(), DisableAndWait(deadline), InFlightCount() }`；模块卸载必须在计数归零后进行。
- `LuaAbiDescriptor` 必须包含 `LUA_VERSION_NUM`、release/build hash、`sizeof(lua_State)`、关键字段 offset、`LUA_IDSIZE`、SP hook ABI 标记；不匹配时禁用私有布局读取。

- [x] **步骤 1：写 teardown/ABI 测试**

  测试 disable 后 callback 不进入 Facade、在途计数归零、重复 disable/unhook 幂等、不同 `LUA_IDSIZE`/layoutHash 被拒绝；增加先由宿主安装 hook、再由 Emmy 安装、再由宿主替换 hook 的 chain 恢复测试。

- [x] **步骤 2：实现 hook handle 所有权**

  保存每次 `LhInstallHook` 返回的 handle；禁止只依赖泄漏的临时指针；在停止路径统一 disable，再按能力卸载。

- [x] **步骤 3：修复 Facade 空 Transporter 路径**

  `Attach()`、`OnBreak()`、`SendLog()` 先读取连接快照；Transport 不存在或非 Ready 时只入队/返回错误，不解引用空指针。Transporter 停止前先发布 disabled 标志，再等待 callback quiescence；关闭期间拒绝新的 Lua owner task。

- [x] **步骤 4：加入 ABI fingerprint**

  Host 在注册时可传 layout descriptor；自动探测结果与 descriptor 不一致时 VM 进入 ERROR，禁止读取私有结构。为当前 UnLua `lua-5.4.3 + LUA_IDSIZE=256 + sphook` 建立明确 fingerprint fixture；不能把 Emmy 内置 5.4.6 头文件当作通用 ABI。

- [x] **步骤 5：实现暂停粒度和 Host 值提供器边界**

  默认只实现 `THREAD_PAUSE/THREAD_ONLY`；增加 `HostValueProvider` 回调注册和 GameThread 调度接口，任何 UObject/UStruct 读取都经过有效性检查与白名单，失败时返回 `HOST_VALUE_UNAVAILABLE`，不从 native 直接调用 Unreal UObject。

- [x] **步骤 6：运行验证并提交**

  构建 x86/x64 native targets；运行 detach 后完整 Lua 调用、PIE close/recreate 和错误 ABI harness；提交 `安全：增加 Emmy hook teardown 屏障与 ABI 校验`。

当前进度说明：disabled/quiescence/unhook 屏障、失败保留句柄以供重试、宿主 hook chaining、ABI 描述/拒绝与 HostValueProvider 边界均已实现并有 Native 自动化测试。HostValueProvider 的 GameThread 调度、对象有效性和白名单仍由实际 UE 适配器负责；EasyHook 注入后完整 Detach/PIE 周期尚未实机验证。

### 任务 6：IDEA VmRegistry、握手状态和 legacy 边界

**文件：**

- 创建：`src/main/java/com/tang/intellij/lua/debugger/emmy/VmRegistry.kt`
- 创建：`src/main/java/com/tang/intellij/lua/debugger/emmy/VmRegistryModels.kt`
- 创建：`src/main/java/com/tang/intellij/lua/debugger/emmy/PauseSnapshotStore.kt`
- 创建：`src/main/java/com/tang/intellij/lua/debugger/emmy/SourceIdentity.kt`
- 修改：`src/main/java/com/tang/intellij/lua/debugger/emmy/EmmyDebugProcessBase.kt`
- 修改：`src/main/java/com/tang/intellij/lua/debugger/emmy/EmmyDebugProcess.kt`
- 修改：`src/main/java/com/tang/intellij/lua/debugger/emmy/attach/EmmyAttachDebugProcess.kt`
- 修改：`src/main/java/com/tang/intellij/lua/debugger/emmy/Transporter.kt`
- 创建：`src/test/kotlin/com/tang/intellij/test/debugger/VmRegistryTest.kt`
- 修改：`src/test/kotlin/com/tang/intellij/test/debugger/EmmyTransporterTest.kt`

**接口：**

- `class VmRegistry { applySnapshot(VmSnapshotDto); applyLifecycle(VmLifecycleDto); legacyAttached(stateAddress); resolve(vmId?); invalidatePause(vmId, pauseId); list(): List<VmRecord> }`。
- `VmRecord` 保存 state、generation、discovery、lastEventSeq、activePauseId。
- `EmmyDebugProcessBase` 在收到 `EnvelopeV2` 时按 eventSeq 幂等处理；序号间隙触发 snapshot 请求。
- `ReadyRsp` 触发 Agent Ready；`AttachedNotify` 只作为 legacy diagnostic，legacy 多 VM 时拒绝外部控制。
- `SourceIdentity { uri, canonicalPath, sourceHash, loaderEpoch, verified }`；Probe/断点必须携带 source identity，不能只依赖 file+line。
- `PauseSnapshotStore` 以 `targetId+vmId+pauseId` 为 key，并保存 `PauseScope/consistency`；同一 Target 的多个 VM 暂停采用“单一当前 UI pause + 其余暂停排队”策略，不能自动覆盖当前 UI。
- `DebugSessionStateMachine` 增加 Agent Ready、Agent Disconnected、Reconnecting、SnapshotApplied 事件；定义最大重连次数、指数退避、旧请求取消和最终终止条件。
- 增加 `contextGeneration`/`sourceEpoch` reset 事件，覆盖 UnLua HotReload、PIE reset、VM 复用；reset 后旧脚本、cache、frame、Probe 全部失效。

- [x] **步骤 1：写 VmRegistry 测试**

  覆盖空 snapshot、多 VM、重复事件、序号间隙、CLOSING/CLOSED、地址复用和 legacy 第二 VM；增加两个 VM 同时暂停时当前 UI pause/排队策略、断线重连退避和 context reset 失效测试。

- [x] **步骤 2：实现纯 Kotlin Registry**

  不依赖 IntelliJ SDK；用 immutable DTO 和同步/单线程约束保证 snapshot+event 一致性。

- [x] **步骤 3：接入 Emmy 消息分发**

  增加 `EnvelopeV2` 分支和 ReadyRsp 分支；保留旧 BreakNotify/EvalRsp 解析；把 Attach 的 initialized 从 AttachedNotify 改为 ReadyRsp。

- [x] **步骤 4：加入暂停引用失效**

  VM lifecycle、resume、disconnect、connectionEpoch 和 `contextGeneration/sourceEpoch` 变化时清空 pause/frame/evaluation references；非当前 VM 的暂停进入队列，用户明确选择后才切换 UI。

- [x] **步骤 5：加入 source identity 和重连状态机**

  解析 canonical path、source hash、loader epoch；断线后按固定退避重连，达到上限才终止 Target；重连成功先应用 snapshot，再恢复事件消费，旧 epoch 响应一律丢弃。

- [x] **步骤 6：运行 IDEA 测试与构建**

  运行：`./gradlew.bat :test --tests 'com.tang.intellij.test.debugger.VmRegistryTest' :modules:debugger-emmy-protocol:test`；再运行 `./gradlew.bat test`。

- [x] **步骤 7：本地提交**

  `git add src modules && git commit -m "IDEA：增加 VM 注册表并区分 Agent 与 VM 就绪状态"`

当前进度说明：VmRegistry、event gap/epoch 处理、PauseSnapshotStore、source identity、重连状态机与多 VM 暂停 UI 仲裁已接入生产路径，并有 JVM/构建验证。真实 IDEA 多会话与用户交互属于独立验收层，不由 JVM 测试代替。

### 任务 7：UE/UnLua 宿主适配示例与契约验证

**文件：**

- 创建：`docs/superpowers/specs/2026-09-09-emmy-host-api-integration.md`
- 创建：`EmmyLuaDebugger/docs/host_api_example.cpp`
- 创建：`EmmyLuaDebugger/docs/host_api_contract.json`
- 修改：`EmmyLuaDebugger/README.md`
- 创建：`docs/superpowers/specs/2026-09-09-emmy-unlua-adapter-checklist.md`

**接口：**

- 文档明确 `FLuaEnv` 构造完成基础库/UnLua 注册后调用 Register，析构中 `lua_close` 前调用 BeginClose，close 后 EndClose/Release。
- 说明动态 `GetProcAddress` 绑定到注入 Agent，避免 UE Bridge 产生第二个 `EmmyFacade` 单例。
- 记录实际项目 `lua-5.4.3`、`LUA_IDSIZE=256`、SP hook 扩展的 layout fingerprint 生成方式。
- 明确 `FUnLuaDelegates::OnLuaStateCreated/OnLuaStateDestroyed` 只是宿主通知点，适配器必须在 `lua_close` 前执行 BeginClose；不能等 destructor 返回后再读取指针。
- 明确 `FLuaEnv::HotReload`/`OnPreLuaContextCleanup` 触发 `contextGeneration/sourceEpoch` reset，不把 reset 当作 VM close。
- 提供 `HostValueProvider` 示例：LuaThread 采集 userdata 描述，必要的 UObject/反射字段切换到 GameThread，并返回可序列化副本；禁止把 `UObject*` 或 `FProperty*` 指针放入 DTO。
- 过滤 UnLua 内部为 userdata header 计算而创建的临时 state，只有注册过的 `FLuaEnv` 才能成为公开 VM。

- [x] **步骤 1：写契约校验脚本/样例**

  用 JSON fixture 校验函数名、调用顺序、非零 pending registrationId 行为、registrationId=0 失败行为和 ABI 字段。

- [x] **步骤 2：写宿主适配说明**

  说明 Host API 不在 DllMain 调用，不在 lua_close 返回后读取 state，不把临时 userdata-header state 注册为 VM；给出 `OnLuaStateCreated -> Register -> Ready`、`OnLuaStateDestroyed -> BeginClose -> lua_close -> EndClose/Release` 的固定顺序，并单独列出 HotReload/reset 顺序。

- [x] **步骤 3：写 HostValueProvider 与 ABI fixture**

  在样例中定义 `DescribeUserdata(vmId, threadId, valueRef, limits)` 和 `DispatchToGameThread(requestId, deadline)`；fixture 覆盖 UObject 已销毁、反射字段拒绝、超时和返回副本，确认不跨线程持有 Unreal 指针。

- [x] **步骤 4：运行文档/JSON 静态检查并提交**

  运行 JSON parse、代码围栏和链接检查；提交 `文档：补充 UE UnLua Host API 接入契约`。

当前进度说明：生命周期、动态导出、ABI、source identity、HostValueProvider 的接入契约和可执行 fixture 已提供。该任务提供宿主适配示例，不修改本仓库之外的 UE/UnLua 工程；真实 UE/PIE 接入与验证保持未验证。

### 任务 8：CLI Gateway 基础（只读）

**文件：**

- 创建：`modules/debugger-cli-protocol/build.gradle.kts`
- 创建：`modules/debugger-cli-protocol/src/main/kotlin/com/tang/intellij/lua/debugger/cli/CliProtocol.kt`
- 创建：`src/main/java/com/tang/intellij/lua/debugger/cli/CliGatewayService.kt`
- 创建：`src/main/java/com/tang/intellij/lua/debugger/cli/CliGatewayDescriptor.kt`
- 创建：`src/main/java/com/tang/intellij/lua/debugger/cli/CliEndpointJanitor.kt`
- 创建：`src/main/java/com/tang/intellij/lua/debugger/cli/CliRedactionPolicy.kt`
- 创建：`src/test/kotlin/com/tang/intellij/test/debugger/CliGatewayProtocolTest.kt`
- 修改：`settings.gradle`
- 修改：`build.gradle.kts`

**接口：**

- JSONL request：`requestId`, `operation`, `targetId`, `arguments`。
- 只读操作：`instance list`、`target list/status`、`vm list`、`wait`、`stack`、`scopes`、`variables`。
- Windows 命名管道优先，TCP fallback 必须 token；stdout 单行 JSON，stderr 诊断。
- 本任务不实现 lease、breakpoint 写入、eval 或 Probe。
- Gateway 启动/停止与 IDEA Application 生命周期绑定；实例 descriptor 原子写入，启动时清理过期 PID/instanceId，退出时删除自己的 descriptor。
- 项目未受信任、调试会话未授权或字段被标记敏感时，target/variable 详情脱敏；`instance list` 不泄露项目路径和 token。
- `wait` 使用 NDJSON 流：以 `event` 记录持续输出，以最终 `done` 记录结束原因；Ctrl+C 发送 cancel，服务端释放 waiter。
- 只读请求也有最大并发数、单请求 deadline 和响应大小上限；超过上限返回明确错误。

CLI 请求与响应的最小形态：

```json
{"requestId":"r1","operation":"vm.list","targetId":"target-1","arguments":{}}
{"requestId":"r1","ok":true,"data":{"targetId":"target-1","vms":[]}}
```

- [x] **步骤 1：写协议测试**

  覆盖 JSONL 编解码、未知字段、错误码、超长请求拒绝和单行响应。

- [x] **步骤 2：实现 DTO 与 Gateway**

  Gateway 只调用 `DebugTargetRegistry`/`VmRegistry`，不直接持有 XDebugger UI 对象。

- [x] **步骤 3：运行测试并提交**

  运行 CLI protocol 和 IDEA tests；增加 stale descriptor、未信任项目脱敏、NDJSON done/cancel、并发限流和 endpoint 清理测试；提交 `CLI：增加 IDEA 调试会话只读网关基础`。

当前进度说明：JSONL、Windows named pipe、wait/cancel、descriptor/端点清理、限流/脱敏和 Application 生命周期服务均已实现。JNA 已统一为 IntelliJ 平台版本；named-pipe 集成测试正常执行，不以 skip 代替通过。CLI 生产 Adapter 提供真实会话的栈、scope 和变量引用访问。

### 任务 9：CLI 控制、授权租约与 AI Probe

**文件：**

- 创建：`modules/debugger-cli-protocol/src/main/kotlin/com/tang/intellij/lua/debugger/cli/CliCommands.kt`
- 创建：`src/main/java/com/tang/intellij/lua/debugger/cli/AuthorizationService.kt`
- 创建：`src/main/java/com/tang/intellij/lua/debugger/cli/ControlLeaseManager.kt`
- 创建：`src/main/java/com/tang/intellij/lua/debugger/cli/AiProbeService.kt`
- 创建：`src/main/java/com/tang/intellij/lua/debugger/cli/RestrictedValuePathEvaluator.kt`
- 创建：`src/main/java/com/tang/intellij/lua/debugger/cli/BreakpointComposer.kt`
- 创建：`src/test/kotlin/com/tang/intellij/test/debugger/AiProbeServiceTest.kt`

**接口：**

- lease：`acquire/release/heartbeat`，每个 target 一个独占 lease。
- AI breakpoint/probe 带 owner 和 `SESSION` scope；只能删除自己的对象。
- `VALUE_PATH` 只允许 raw locals/upvalues/globals/property path；默认深度 3、节点 100、结果 64 KiB。
- `probe run` 支持条件、captures、hitLimit、timeout、autoContinue；用户断点暂停优先。
- `wait --probe-id` 显式绑定一代 Probe；取消/超时/异常断开只清理绑定对象，正常事件分页与普通 CLI 短连接不清理。旧定时器和异步 cleanup 不得误删同 ID 重装；清理失败有界重试并上报。
- `BreakpointComposer` 按 `(sourceIdentity, vmId, line)` 合并 USER/CLI/SYSTEM 条目，保留多个 owner 的独立 condition/log/hit state；发送给 Agent 的是原子 composite snapshot，不能用同位置替换丢失用户断点。
- `RestrictedValuePathEvaluator` 只解析标识符、点字段和 literal index，使用 raw API；不调用 `__index`/`__tostring`/函数，不接受赋值、require、yield 或任意 Lua source。
- 求值预算包含最大节点、字节、表达式长度和 instruction/checkpoint 数；native 不支持硬取消时，CLI 只能撤销响应并返回 `CANCEL_UNSUPPORTED`，不能承诺 500 ms 强制中断。
- AI Probe 的 autoContinue 只有在暂停原因集合完全由该 Probe 产生、没有 USER/SYSTEM reason 且 lease 仍有效时才执行；IDE 用户在 Probe 等待期间操作时，Probe 进入 `ORPHANED`，不得自动继续。
- 用户显式操作优先于 AI lease；AI lease 失效、目标取消授权或项目失去信任时，所有 CLI Probe 立即停止并清理。

- [x] **步骤 1：写授权和 Probe 测试**

  覆盖 TARGET_BUSY、过期 lease、owner 隔离、VM close、stale pause、截断和 autoContinue 冲突；增加 USER+CLI 同位置条件断点合并、IDE 用户抢占、取消不可中断求值、sourceIdentity 不匹配和 rate limit 测试。

当前进度说明：授权/撤销、lease、IDEA 服务入口、生产求值、Probe 生命周期、owner 断点合成和用户抢占已实现并有自动化覆盖。Native VALUE_PATH 使用 raw Lua API；首版拒绝任意 Lua 执行和未声明的求值策略。真实 IDEA 平台测试已通过生产 Gateway 对 Native Lua 完成条件采集、自动继续和断点清理；已安装 IDEA + UE + CodexCli 的一体化运行仍需实机验收。

- [x] **步骤 2：实现服务**

  所有控制请求先校验 token、target grant、lease、vm state、pause reference 和 source identity，再进入 DebugTargetAdapter；实现 lease TTL/heartbeat、每 client/target 限流、Probe cleanup 和审计摘要。

- [x] **步骤 3：实现受限求值与断点合成**

  先在 Kotlin 侧解析 `VALUE_PATH` AST 并拒绝危险 token；`BreakpointComposer` 生成可回滚的 composite snapshot，记录每个 owner 的贡献和命中原因；只在完整 snapshot ACK 后替换 Agent 端断点。

- [x] **步骤 4：运行全套验证并提交**

  运行协议、核心、IDEA、CLI tests；提交 `AI调试：增加授权租约与条件采集 Probe`。

### 任务 10：CI、构建矩阵与端到端验收

**文件：**

- 修改：`.github/workflows/verify.yml`
- 修改：`EmmyLuaDebugger/.github/workflows/build.yml`
- 修改：`EmmyLuaDebugger/emmy_debugger/CMakeLists.txt`
- 创建：`EmmyLuaDebugger/tests/vm_lifecycle_harness.cpp`
- 创建：`EmmyLuaDebugger/tests/protocol_fuzz_cases.jsonl`
- 创建：`docs/superpowers/specs/2026-09-09-emmy-validation-matrix.md`

**接口：**

- 父仓库 CI 必须构建协议/core/transport、运行 VM Registry/Protocol/IDEA/CLI tests，并校验子模块工作树干净。
- 原生 CI 必须覆盖 Windows x64/x86、Linux、macOS；修正 `libuv-1.29.0` include 路径为实际 `libuv-1.46.0`，并增加 `-Werror` 之外的 ABI/layout smoke 检查。
- Harness 必须覆盖 Host-before-Agent、Agent-before-Host、双 VM、同 VM 双 coroutine、context reset、暂停中关闭、认证失败、重连 epoch、frame limit 和 detach quiescence。
- fuzz fixture 必须覆盖畸形 JSON、未知 v2 type、重复 requestId、旧 epoch、超长 payload、非法 VM/pause/frame ID。

- [x] **步骤 1：写 CI 失败门槛**

  先在 workflow 中加入协议和 native harness job；缺少子模块 checkout、构建失败、任一 fixture 失败或未生成测试报告均使 job 失败。

- [x] **步骤 2：修正 native 构建配置**

  在 `emmy_debugger/CMakeLists.txt` 使用 `${emmy_SOURCE_DIR}/third-party/libuv-1.46.0/include`；确认 Windows x86/x64 目标都链接相同协议/Registry 源文件，并记录编译器与 Lua layout fingerprint。

- [x] **步骤 3：实现端到端 harness**

  Harness 通过 Host API 注册 VM，在 Agent 激活后对账；依次驱动 lifecycle、pause、close、reconnect 和 stale reference，输出 machine-readable JSON 结果。

- [ ] **步骤 4：运行全矩阵**

  运行：`./gradlew.bat --no-daemon test buildPlugin verifyPlugin`；运行 native CMake x64/x86 Debug/Release；在可用 UE 环境运行 Editor/PIE smoke。分别记录静态、单元、原生、IDEA、UE、CLI 六级证据。

- [x] **步骤 5：本地提交**

  `git add .github EmmyLuaDebugger docs && git commit -m "验证：增加 Emmy VM 生命周期与 CLI 调试验收矩阵"`

## 计划自检

- 设计稿中的 Agent/VM/暂停/CLI 四层均有对应任务。
- 本文是分阶段总路线图：最初先实施任务 1-7（设计稿阶段 0-2），随后按用户继续开发的要求实施任务 8-10。当前范围包含完整 P0/P1/P2，尚未关闭的运行验收仍保持未勾选。
- 任务 1-7 先建立身份、生命周期、协议、teardown、Host adapter 和 source/context 失效，任务 8-9 才开放外部 CLI 控制，任务 10 固化 CI/运行时验收。
- 每个任务保留未完成勾选，并给出明确文件、接口、测试命令和中文提交信息；勾选状态只表示实施进度，不把计划文本误当成已完成证据。
- 子模块提交与父仓库指针提交分离，符合本地提交、不 push 和不合并成单一提交的要求。
- 任务 2 的纯 C++ harness 不依赖 Lua API；任务 3 之后再做真实 Lua/Socket/UE 验证，避免把纯数据结构测试误当运行时验收。

审查项闭合矩阵（设计稿原始编号与本计划新增的交叉风险均列出）：

| 审查项 | 计划任务 | 具体验收 |
| --- | --- | --- |
| P0-F01 完整 VM 生命周期与 Agent/VM 就绪分离 | 任务 2、3、6、7、10 | Host-before-Agent、VM-first、CREATED/READY/CLOSING/CLOSED 顺序、空 snapshot、IDEA ReadyRsp 初始化 |
| P0-F02 多 VM 显式路由 | 任务 4、6、9、10 | 双 VM 控制隔离、缺少 vmId 返回 AMBIGUOUS_VM、未知 vmId 返回 VM_NOT_FOUND |
| P0-F03 握手响应闭环 | 任务 3、6、10 | InitReq/InitRsp -> ReadyReq/ReadyRsp -> vm.snapshot 回环；无 ReadyRsp 不得 initialized |
| P0-F04 Hook chaining 与 teardown barrier | 任务 3、5、10 | 宿主 hook 替换、in-flight callback、disable/unhook 幂等、detach 后继续运行 |
| P0-S1 唯一 ABI/导出边界 | 任务 2、5、7、10 | GetProcAddress 唯一导出、Bridge 不静态链接第二份 Facade、layout fingerprint |
| P0-S2 精确 Lua 私有布局 | 任务 5、7、10 | 5.4.3/256/SP hook fixture；不匹配时拒绝私有布局读取 |
| P0-S3 暂停粒度与一致性 | 任务 4、6、10 | THREAD_ONLY、双 coroutine 交错命中、禁止伪装 VM-wide |
| P0-S4 受限求值与不可中断语义 | 任务 9、10 | AST 拒绝、预算、CANCEL_UNSUPPORTED；不跨线程强杀 Lua |
| P1-F05 稳定进程/Agent/VM 身份 | 任务 1、2、3、6、10 | PID 复用、地址复用、agentSessionId/connectionEpoch/vmGeneration/eventSeq 单调性 |
| P1-F06 混合 Lua ABI 检测 | 任务 2、5、7、10 | 第二 ABI VM 进入 ERROR 并返回 MIXED_LUA_ABI_UNSUPPORTED |
| P1-F07 显式 wire id、schema、framing 和统一错误 | 任务 1、3、10 | v1 id golden test、v2 cmd=18、畸形 JSON、frame limit、结构化错误 |
| P1-F08 ProcessAttachmentManager 与真实 VM 状态解耦 | 任务 3、6、10 | 仅 Agent/VM snapshot 驱动真实状态；PID 复用和 Agent 重启不复活旧 Target |
| P1-S1 Native Agent 认证 | 任务 3、8、10 | nonce/token 失败不能到达 ProtoHandler；未授权连接不能读取详情 |
| P1-S2 epoch/snapshot 线性化与重连终态 | 任务 3、6、10 | 序号间隙、旧 epoch、snapshot 重建、退避上限和最终终止 |
| P1-S3 context reset/HotReload | 任务 6、7、10 | sourceEpoch/cache/frame/Probe 失效，PIE reset 不误报为普通 close |
| P1-S4 HostValueProvider 与临时 Lua state 过滤 | 任务 5、7、10 | GameThread 副本、对象失效、反射白名单、userdata-header 临时 state 不入 snapshot |
| P1-S5 source identity | 任务 6、9、10 | canonical path/hash/loaderEpoch mismatch 拒绝错误脚本映射 |
| P1-S6 Probe/USER 断点合并与多 VM UI 仲裁 | 任务 6、9、10 | composite snapshot、autoContinue 冲突、当前 pause/排队/显式切换 |
| P1-S7 Attach bootstrap/rollback | 任务 3、10 | injected/listening/auth-ready/error 状态、认证、重试、already-attached 诊断 |
| P2-F01 Debug Target Registry、VM 查询与事件流 | 任务 6、8、10 | target/vm/status、snapshot/event cursor、序号间隙恢复 |
| P2-F02 外部授权与控制租约 | 任务 8、9、10 | target grant/revoke、独占 lease、TTL/heartbeat、TARGET_BUSY |
| P2-F03 AI 断点/Probe 所有权 | 任务 9、10 | owner 隔离、SESSION 清理、只能删除自己的对象 |
| P2-F04 暂停代次与失效引用 | 任务 4、6、9、10 | pauseId/frameId stale、VM close/disconnect/reset 后全部失效 |
| P2-F05 有界变量快照 | 任务 8、9、10 | 深度/节点/字节/capture/时间上限，截断字段准确返回 |
| P2-F06 安全求值策略 | 任务 9、10 | VALUE_PATH raw 访问、危险 token 拒绝、UNSAFE 明确关闭 |
| P2-F07 可等待的 CLI 调试事件流 | 任务 6、8、10 | NDJSON event/done/cancel、waiter 释放、事件日志有界淘汰 |
| P2-S1 schema/取消/幂等/framing | 任务 1、3、10 | fuzz、重复 requestId、operationHash、frame limit、cancel |
| P2-S2 CLI 端点清理与输出隔离 | 任务 8、10 | stale descriptor 清理、stdout 单行 JSON、诊断只写 stderr |
| P2-S3 trust/redaction/rate/audit/arbitration | 任务 8、9、10 | 未信任项目脱敏、限流、审计摘要、用户抢占和 Probe 清理 |
| P2-S4 CI/构建/运行矩阵 | 任务 10 | Gradle、CMake x86/x64、Lua/UE/PIE/CLI 六级证据 |

说明：`P0-S*`、`P1-S*`、`P2-S*` 是本轮复核新增的交叉风险编号；原设计稿的 P0/P1/P2 与 F-01 至 F-08 均已逐项映射。受限求值虽然在原稿的 AI 能力段落中出现，本计划将其提升为 P0 安全门槛，未通过时不开放 Probe。

## 2026-09-10 收尾记录

- 生产 Adapter 的 scope/value 引用改为 opaque token，修复同名变量、特殊 key 和满容量淘汰；保留独立中文提交。
- Native 已提交多 VM/owner 控制、raw VALUE_PATH、source/Host 接口、hook/注入安全与真实 Lua/pipe harness；父仓库已单独提交子模块指针。
- JVM 当前构建范围内 199 项测试通过（55 suites），不累计退出构建的历史 AI/MCP 报告；包含真实 Native/IDE/Gateway 联调。Windows 动态 API 四配置各 14 项通过，真实 Lua 5.4 source 两配置各 17 项通过，Lua 5.1/5.2/5.3 source 各 3 项通过。这些 Native 统计均显式排除了 TCP。
- 联调验证 source reset、表与特殊 key 的有界读取、Probe 自动清理，以及 VM 独立关闭时 IDEA 收到 CLOSING/CLOSED 且 Agent 保持在线；由实际 Gson 报文发现并修复 Native 可选字段读取断言。
- 最后审查补齐 `wait --probe-id` 取消/超时/真实 socket 断开清理、同 ID 重装代次隔离、清理失败有界重试和诊断；普通 CLI 短连接不删除 Probe，终态事件仍可读取，CLI 等待失败返回非零退出码。已通过定向测试及包含真实联调的全量回归。
- 新 Release Native 资源已打包，最终插件 ZIP 的八个资源 SHA-256 与输入完全一致；Plugin Verifier 判定 Compatible。
- 仍未关闭：任务 3 的 Native TCP 验证、任务 10 的实机/跨平台全矩阵。TCP 独立复验失败 2/2；没有修改本机安全策略，没有 push，没有用 skip 或局部通过替代整体验收。
