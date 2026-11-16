# EmmyLua 调试插件重构设计：附加调试 + 真机调试 + MCP

> 日期：2026-11-16
> 状态：草案，待评审（本轮只出设计，不改代码）
> 范围：IntelliJ-EmmyLua 插件的调试部分、`EmmyLuaDebugger` 原生端（将并入本仓库 `native/`）、与 `UEKit` 的集成
> 已确认的前提：
> 1. 语言支持（补全、跳转、检查、格式化等）保留，只精简调试部分。
> 2. 调试只保留两种场景：**附加调试**（本机进程）和**真机调试**（Android / iOS / 其他设备上的游戏）。
> 3. 现有 `emmy-debug` CLI 删除，改为 **MCP Server** 给 Agent 用。
> 4. 不需要兼容旧架构；有更高性能的新架构就直接换。
> 5. MCP 支持一键写入当前项目的版本管理根目录；没装时弹窗提示是否安装。
> 6. EmmyLuaDebugger 并入本仓库，不再作为子模块，也不迁到 UEKit。

---

## 目录

1. [结论摘要](#1-结论摘要)
2. [现状评估](#2-现状评估)
3. [目标架构](#3-目标架构)
4. [新调试协议 v3](#4-新调试协议-v3)
5. [原生端（EmmyLuaDebugger）重构与性能](#5-原生端emmyluadebugger重构与性能)
6. [MCP Server 设计（替换 CLI）](#6-mcp-server-设计替换-cli)
7. [真机调试](#7-真机调试)
8. [EmmyLuaDebugger 是否保留、迁移到哪里](#8-emmyluadebugger-是否保留迁移到哪里)
9. [精简：删除清单](#9-精简删除清单)
10. [UI 重新设计](#10-ui-重新设计)
11. [其他优化与扩展点](#11-其他优化与扩展点)
12. [实施计划](#12-实施计划)
13. [风险与待定事项](#13-风险与待定事项)

---

## 1. 结论摘要

**现有项目能用，但结构不合理的地方集中在调试部分。** 9 月那轮重构把多 VM 模型、`sourceIdentity`、v2 消息、调试会话状态机做对了方向，`modules/debugger-core` 也有单测；问题在于这些好东西被三套调试器（Emmy 启动/远程、Emmy Attach、LuaPanda）、两代协议（v1 + v2 Envelope）和一套很重的 CLI（授权 / 租约 / Probe）缠在一起，最后都压在一个 2053 行的 `EmmyDebugProcessBase` 上。原生端的行钩子热路径上每执行一行 Lua 都要加锁并**复制一份断点行号 `std::set`**，这是运行时变慢的主要原因。

**建议的方向：**

| 方面 | 现在 | 改成 |
| --- | --- | --- |
| 调试方式 | Emmy 启动(TCP/管道 4 种)、Emmy Attach、LuaPanda | **附加**（本机注入）+ **真机**（设备内嵌 runtime，经 USB/局域网连） |
| IDE 侧结构 | `EmmyDebugProcessBase` 一个类管连接、握手、断点、控制、求值、CLI、重连 | 与 IntelliJ 无关的 `debugger-engine`（纯 Kotlin，可单测）+ 薄的 XDebugger 适配层 + MCP 适配层 |
| 协议 | v1（两行文本）+ v2 Envelope 并存 | **v3**：长度前缀帧、请求/响应带 id、能力协商、按需拉取变量，不再兼容 v1/v2 |
| 原生热路径 | 每行加锁 + 复制 set + 常开 LINE/CALL/RET 钩子 | 无锁不可变断点索引 + 按函数开关行钩子；无断点时近乎零开销 |
| Agent 接入 | 独立 `emmy-debug` CLI + 授权/租约 | IDE 内置 **MCP Server**（127.0.0.1 HTTP），一键写入项目 `.mcp.json` 等配置 |
| 真机 | 只有文档说"可以自己编进去" | 正式支持 Android（adb forward/reverse）、iOS（usbmux）、局域网发现；UE 项目走 UEKit 新插件 |
| 原生库 | 子模块 + 二进制提交在 `src/main/resources/debugger`（约 11 MB） | 源码并入 `native/`，同一次 CI 编译后打进插件包 |

**功能不缩水、性能有量化目标。** 逐项对照见 [2.5](#25-功能完整性改造后不缩水)：除命名管道和纯 Lua 调试器两项（给出理由和兜底），现有能力全部保留，并新增修改变量、出错即停、协程视图、Agent 附加/连设备等。性能目标与验收方法见 [5.3](#53-性能目标与验收)：不下断点时游戏侧开销目标 ≤ 1.05 倍，USB 下命中断点到显示变量 ≤ 100 ms。

**EmmyLuaDebugger 结论：必须保留，必须重构（性能和移动端），并入本仓库 `native/` 目录（已确认）。** 它是引擎无关的调试 runtime（附加注入、5 个 Lua 版本、非 UE 宿主都靠它）；并入后协议两端可以在一个 PR 里同时改。UE 项目的真机调试**不新增插件、不改打包**：复用已随开发包进到设备上的 UELink，在里面加一个 Lua 调试通道，引用 `native/` 拆出的无依赖调试核心。详见[第 8 节](#8-emmyluadebugger-是否保留迁移到哪里)。

---

## 2. 现状评估

### 2.1 现有组成

| 部分 | 位置 | 规模 | 说明 |
| --- | --- | --- | --- |
| 语言支持 | `src/main/java/.../lua/{psi,editor,codeInsight,...}`、`gen/` | 占代码大头 | 保留，本次不动 |
| 调试 IDE 层 | `src/main/java/.../lua/debugger/**` | ~13,000 行 | Emmy、Attach、LuaPanda、CLI 网关全在主模块里 |
| 调试模块 | `modules/debugger-{core,transport,emmy-protocol,cli-protocol,luapanda-protocol}` | ~4,000 行 | 状态机、事件循环、请求注册、协议编解码 |
| CLI 工具 | `tools/emmy-debug` | ~740 行 | 独立分发的命令行 |
| 原生资源 | `src/main/resources/debugger/emmy/**` | ~11 MB | dll/exe/so/dylib 直接提交 |
| 原生端 | 子模块 `EmmyLuaDebugger` | ~20,000 行 C++ | `emmy_debugger` 核心、`emmy_core` Lua 模块、`emmy_hook` + `emmy_tool` Windows 注入 |

### 2.2 做得好的，要保留

- **多 VM 一等模型**：`VmRegistry`、`vmId` / `pauseId` / `sourceEpoch` 贯穿所有暂停引用，PIE 重启、热重载后旧引用能可靠失效。
- **`sourceIdentity`**：按宿主实际加载的字节做 hash，热更新后的代码不会被当成本地文件。真机调试尤其需要。
- **Host API**（`Emmy_RegisterLuaVm` 等）：宿主显式上报 VM 生命周期，不全靠 hook 猜。
- **`debugger-core` 的状态机 / 事件循环 / 请求注册**，有单测；**协议 golden test**。
- **受限求值（VALUE_PATH）**：Agent 默认不能执行任意 Lua，这个安全默认值要保留到 MCP。
- **有界快照**（depth / nodes / bytes / deadline），避免一次展开巨型 table 卡死游戏。
- **附加断开后目标不再卡死在暂停**（原生端最近的修复）。

### 2.3 不合理的地方

**架构层面**

1. **上帝类**。`EmmyDebugProcessBase.kt` 2053 行，同时负责：传输候选与重连、v1/v2 双握手、VM 快照请求与重试、XDebugger 断点、CLI 断点与 Probe 合并（`sendCompositeBreakpointSnapshotLegacy`）、单步/运行到光标、求值、CLI 后端接口 `EmmyDebugBackend` 的 10 个方法、内联值快照、授权。CLI 的概念（`cliBreakpoints`、`cliProbes`、`registerCliTarget`、`autoAuthorizeCliClients`）直接写在调试核心里，删 CLI 要动核心。
2. **IDE 层和引擎没分开**。调试逻辑在主插件模块里，依赖 `XDebugSession`，脱离 IDE 无法测试；`modules/debugger-core` 只抽出了状态机这类通用件，真正的会话逻辑没进去。
3. **两代协议并存**。v1 的 0–17 号消息和 v2 Envelope 同时存在，每条路径都有"legacy 分支"，代码量和测试量翻倍。
4. **三套调试器重复**。LuaPanda（`LuaPandaDebugProcess` 989 行 + `LuaPandaTransporter` 792 行 + 协议模块 + 版本检查 + 通知组）与 Emmy 功能重叠，且协议能力更弱。
5. **CLI 过重**。授权菜单、客户端标识、控制租约（含心跳和 TTL）、Probe 代际、实例发现文件、token 文件、8 种退出码；Agent 还得学会一套命令行语法、自己拼 `--target --vm --pause --frame --lease --client`。MCP 天然解决"工具发现 + 参数 schema + 会话"这些问题，这些机制大部分可以去掉。
6. **附加调试的 UE 特化写死在通用代码里**（`UEProcessType`、`UEProcessListRenderer`、`filterUEProcesses`）。
7. **运行配置手写持久化**（`JDOMExternalizerUtil` 逐字段读写 + 手写 schema 版本），设置面板一部分是 Java Swing 表单（`EmmyDebugSettingsPanel.java` 362 行）。

**性能层面（原生端，详见第 5 节）**

8. 行钩子每次触发：`luaThreadMtx` 加锁换 vector、`GetLineSet()` 在锁内**按值返回整个 `std::set<int>`**（每执行一行 Lua 就一次堆分配）、`hookStateMtx` 加锁、`evalMtx` 加锁；`EmmyFacade::Hook` 每次还要 `debuggerMtx` 加锁查 map。
9. 钩子掩码在运行态常开 `LINE | CALL | RET`，没有断点、不在单步时也是满额开销。
10. 断点命中时 `GetBreakpoints()` 复制整个断点数组，再逐个做模糊路径匹配。
11. 传输格式是"命令号一行 + JSON 一行"，大 payload 需要整体扫描换行；变量快照在暂停时偏向一次性推送。

**仓库卫生**

12. `bin/main/**`（旧源码副本）被提交了，尽管 `.gitignore` 里有 `bin/`。
13. `bunch/`（旧的多版本分支工具 + 15 个 jar）和根目录 `.bunch`，当前构建已不用。
14. `.superpowers/`（过程记录）、根目录 `protocol_analysis_report.md`（LuaPanda 分析）、`design/emmy.ai`（设计源文件）、部分 `.idea/` 文件在仓库里。
15. 原生二进制直接提交在插件仓库，和子模块源码版本没有可校验的对应关系。
16. 子模块地址是 SSH（`git@github.com:...`），匿名 / CI 克隆会失败，这也是并仓的理由之一（见 8.3）。
17. README 已过时（指向 tangzx 的 Releases、LuaPanda 安装方式）。
18. `org.eclipse.egit.github.core`（2014 年的库）只为错误上报使用。

### 2.4 评估结论

| 问题 | 是否必须改 | 理由 |
| --- | --- | --- |
| 拆上帝类 / 引擎与 IDE 分层 | 必须 | MCP 和 UI 要共用同一个会话，不拆就只能再往 `EmmyDebugProcessBase` 里加第三套接口 |
| 删 LuaPanda、管道模式、CLI | 必须 | 用户已明确只保留两种场景；这三块占调试代码约 40% |
| 协议 v3 | 强烈建议 | 真机链路（Wi-Fi、USB 转发）对延迟和包大小敏感；且可以借机删掉 v1/v2 双路径 |
| 原生热路径优化 | 必须 | 真机 CPU 更弱，现在的每行开销在手机上会直接表现为掉帧 |
| 仓库卫生 | 建议 | 成本低，顺手做 |

### 2.5 功能完整性：改造后不缩水

精简的是"实现方式"（三套调试器、两代协议、CLI），不是"能力"。下表逐项列出现在各入口提供的能力，以及改造后由谁承接。✅ 表示已有并保留，🆕 表示新增，⚠️ 表示有取舍。

**调试基础能力**

| 能力 | 现在 | 改造后 | 说明 |
| --- | --- | --- | --- |
| 行断点 | Emmy / LuaPanda | ✅ | |
| 条件断点 | Emmy / LuaPanda | ✅ 改为 runtime 侧预编译执行 | 弱网下不再往返 IDE |
| 日志断点（不停） | Emmy | ✅ | |
| 命中次数条件 | Emmy（`hitCondition`） | ✅ | |
| 临时断点 / 运行到光标 | Emmy | ✅ | 最近刚修过 Run to Position 的顺序和暂停身份问题，迁移时保留这两个用例 |
| 单步进入 / 跳过 / 跳出 | Emmy / LuaPanda | ✅ | 新热路径下按需开钩子，见 5.2 |
| 暂停（Break All） | Emmy / LuaPanda | ✅ | 无断点时靠 COUNT 钩子响应，最长延迟见 5.4 |
| 启动即停（stopOnEntry） | LuaPanda | ✅ 用 runtime 的 `waitIDE` + `breakHere` 承接 | 运行配置里加"连接后在第一行暂停"选项 |
| 调用栈 | 全部 | ✅ 分页加载 | |
| 局部变量 / upvalue / 全局 | 全部 | ✅ 按需展开 | |
| 表达式求值 | Emmy（任意 Lua） | ✅ | IDE 内仍可任意求值；Agent 默认只读路径 |
| 求值时代码补全 | Emmy（`LuaDebuggerEditorsProvider`） | ✅ | 依赖语言支持，保留 |
| 修改变量值 | 无 | 🆕 `setVariable` | 现在都不支持，新协议补上 |
| 监视表达式 | IDE 通用能力 | ✅ + 🆕 按项目持久化 | |
| 内联变量值 | ✅ `LuaEditorLinePainter` | ✅ | |
| 控制台堆栈跳转 | ✅ `LuaTracebackFilter` | ✅ | |
| 自定义类型显示（UE 的 FVector、TArray 等） | Emmy `emmyHelper_ue.lua` | ✅ ⚠️ 改为通过协议下发 helper 源码 | 现在 `InitReq` 传的是**本机磁盘路径**，真机上不存在；v3 在握手后把 helper 脚本内容发给 runtime 加载 |
| 多 VM / PIE 重启 / 热重载后失效 | Emmy v2 | ✅ | |
| `sourceIdentity` 内容校验 | Emmy v2 | ✅ | 真机热更新场景更重要 |
| 协程 | Emmy 自动跟随当前协程 | ✅ + 🆕 协程列表视图 | |
| 出错即停 | 无 | 🆕 | 见第 11 节 |
| 断线重连 + 断点重发 | Emmy | ✅ | 真机 App 切后台必需 |

**连接方式**

| 方式 | 现在 | 改造后 |
| --- | --- | --- |
| Windows 进程附加（注入） | Emmy Attach | ✅，并进入 IDE 原生 Attach to Process 列表 |
| IDE 连 runtime（TCP） | Emmy NEW `tcp-client` | ✅ 并入"设备/远程调试" |
| runtime 连 IDE（TCP） | Emmy NEW `tcp-server` | ✅ 并入"设备/远程调试"（连接方向选项） |
| 命名管道 | Emmy NEW `pipe-*` | ⚠️ 删除：只能本机用，TCP 回环完全覆盖，且多一个原生依赖 |
| 纯 Lua 调试器（无需原生编译） | LuaPanda | ⚠️ 删除。代价：宿主**无法编译原生库**时就没法调试。缓解：这种情况很少（Unity/UE/自研引擎都能链接 C 库）；若确有需要，P7 之后可以用 Lua 写一个实现 v3 子集的 runtime 兜底，复用同一套 IDE 和 MCP |
| Android / iOS 真机 | 无正式支持 | 🆕 |
| UE 进程免注入直连 | 无 | 🆕 经 UELink 调试通道 + 名片 |

**Agent 能力（CLI → MCP）**

| 能力 | CLI | MCP |
| --- | --- | --- |
| 列目标 / VM | ✅ | ✅ |
| 栈 / 作用域 / 变量 | ✅ | ✅ 且返回带源码片段的可读文本 |
| 受限求值（路径） | ✅ | ✅ 默认模式 |
| 任意 Lua 求值 | ✗ | 🆕 设置开启后可用 |
| Probe（采集不停） | ✅ | ✅ 变成断点属性 |
| 断点增删 | ✅ | ✅ 且在编辑器里可见 |
| 继续 / 单步 / 暂停 | ✅（需租约） | ✅ 单步后自动等待并返回新位置 |
| 等待事件 | `wait --cursor` | `wait_for_pause` |
| 附加进程 / 连设备 | ✗（只能操作已有会话） | 🆕 需用户确认 |
| 读调试输出 / 错误 | ✗ | 🆕 |

结论：除"命名管道"和"纯 Lua 调试器"两项外，所有能力保留或增强；这两项的取舍理由和兜底方案如上。

---

## 3. 目标架构

### 3.1 分层

```mermaid
flowchart TB
    subgraph IDE["IntelliJ 插件"]
        UI["调试 UI<br/>XDebugger 适配 · 运行配置 · 工具窗口"]
        MCP["MCP Server<br/>127.0.0.1 Streamable HTTP"]
        SM["DebugSessionManager<br/>(project service)"]
        ENG["debugger-engine<br/>会话 · VM · 断点 · 执行控制 · 变量 · 求值<br/>纯 Kotlin，无 IntelliJ 依赖"]
        TGT["目标适配<br/>AttachTarget · DeviceTarget"]
        PROTO["debugger-protocol v3"]
        TR["debugger-transport<br/>TCP · adb 转发 · usbmux · 局域网发现"]
        UI --> SM
        MCP --> SM
        SM --> ENG
        ENG --> PROTO --> TR
        TGT --> TR
        SM --> TGT
    end

    subgraph DEV["目标进程 / 设备"]
        RT["emmy runtime（EmmyLuaDebugger）<br/>协议 · VM 注册 · 断点索引 · 钩子"]
        HOST["宿主：UE(UnLua) / Unity / 自研引擎 / lua.exe"]
        RT --- HOST
    end

    TR <-->|"v3 帧"| RT
```

要点：

- **`debugger-engine` 是唯一的"真相"**。断点、VM 列表、暂停状态、变量引用都存在这里；XDebugger UI 和 MCP 都只是它的客户端，看到的是同一份状态。UI 里点"继续"，MCP 的 `wait_for_pause` 会收到；Agent 下的断点也会出现在编辑器里。
- **不再有"CLI 断点 / IDE 断点合并"**。Agent 的断点就是普通断点，带一个 `origin = agent` 标记（UI 里用不同图标显示，可一键清除）。Probe 变成断点的一个属性（`capture` + `autoContinue`），不是单独一套机制。
- **目标适配只负责"怎么连上"**。附加 = 注入 + 连 PID 派生端口；真机 = 选设备 + 建转发 + 连端口。连上之后的一切走同一个引擎。

### 3.2 模块划分（Gradle）

| 模块 | 依赖 | 内容 |
| --- | --- | --- |
| `modules/debugger-protocol` | 无 | v3 帧编解码、消息 DTO、能力位；合并现在的 `debugger-emmy-protocol`，删 v1/v2 |
| `modules/debugger-transport` | protocol | `Connection` 接口；TCP 实现；`AdbForwarder`、`UsbmuxConnector`、`LanDiscovery` |
| `modules/debugger-engine` | protocol, transport | 由现 `debugger-core` 扩展而来：`DebugSession`、`VmRegistry`、`BreakpointStore`、`ExecutionController`、`VariableService`、`EventBus` |
| `modules/debugger-mcp` | engine | MCP 工具定义、参数 schema、结果格式化、权限策略；不依赖 IntelliJ，可用假 engine 单测 |
| 主插件 `debugger/` 包 | 以上全部 + IntelliJ | XDebugger 适配、运行配置、UI、MCP HTTP 宿主、MCP 安装器 |

### 3.3 `debugger-engine` 内部

`EmmyDebugProcessBase` 拆成以下几个对象，每个都可单测：

| 组件 | 职责 | 来源 |
| --- | --- | --- |
| `ConnectionManager` | 建连、握手、能力协商、心跳、断线重连 | 从 Base 拆出 |
| `VmRegistry` | VM 生命周期、`sourceEpoch`、显示名 | 现有，移入 engine |
| `BreakpointStore` | 所有断点（IDE/Agent），按 revision 增量下发 | 合并现 `breakpoints` + `cliBreakpoints` + `BreakpointComposer` + `BreakpointRepository` |
| `ExecutionController` | 继续 / 暂停 / 单步 / 运行到行，按 vmId/threadId 路由 | 从 Base 拆出 |
| `PauseStore` | 暂停快照、栈帧、失效规则 | 现 `PauseSnapshotStore` |
| `VariableService` | 按需拉取变量（分页）、引用句柄、求值 | 现 `EmmyEvaluator` + `RestrictedValuePathEvaluator` |
| `SourceMapper` | chunkName ↔ 本地文件映射、`sourceIdentity` 校验 | 现 `SourceMappingService` + `SourceIdentity` |
| `EventBus` | `paused / resumed / vmAdded / vmClosed / output / breakpointVerified` 等事件，供 UI 和 MCP 订阅 | 现 `DebugSessionEventLoop` |

线程模型：每个会话一个单线程事件循环（沿用 `DebugSessionEventLoop`），所有状态只在这个线程修改；对外 API 返回 `CompletableFuture`/挂起函数。UI 和 MCP 调用都是投递消息，不存在两个入口同时改状态的问题，现在的 `cliStateGeneration`、`cliBreakpointMutationPending` 这类并发补丁可以去掉。

---

## 4. 新调试协议 v3

不兼容 v1/v2。旧版本 runtime 连上来时握手失败，提示升级 runtime。

### 4.1 帧格式

```
+---------+---------+-----------+------------------+
| magic 2 | type 1  | length 4  | payload (length) |
+---------+---------+-----------+------------------+
magic = 0xE3 0x01   type: 1=request 2=response 3=event
```

- **长度前缀**，不再按换行切分；接收方按长度一次读完，不扫描。
- **payload 编码**：默认 JSON（便于调试和抓包）；握手时协商 `cbor` 能力后切换 CBOR（体积约为 JSON 的 50–70%，解析更快）。两端都用同一套 DTO，切换编码不改上层。
- **request/response 带 `id`**，响应与请求一一对应，取代现在靠 `cmd` 号 + 回调猜对应关系；超时、取消（`cancel {id}`）有统一语义。

### 4.2 握手与能力

```text
IDE → runtime: hello { protocol: 3, client: "intellij", token?, wantCaps: [...] }
runtime → IDE: welcome { protocol: 3, runtimeVersion, luaVersion, platform, arch,
                          process: {pid, name}, caps: ["cbor","valuePath","luaEval",
                          "hostApi","sourceHash","logpoint","hitCount","runToLine"], sessionId }
```

握手完成后 IDE 发送 `loadHelpers {scripts:[{name, source}]}`，把 `emmyHelper.lua` / `emmyHelper_ue.lua` 等类型显示脚本的**内容**发给 runtime 加载（现在传的是本机磁盘路径，真机上不存在）。

能力位决定 UI 和 MCP 暴露哪些功能（例如没有 `luaEval` 时 MCP 不提供任意表达式求值）。

### 4.3 按需取数据

现在暂停时偏向一次推完整栈和变量。v3 改成：

1. `paused` 事件只带 `vmId / pauseId / threadId / reason / 顶层帧位置`。
2. 栈：`stackTrace {pauseId, start, count}` 分页。
3. 作用域：`scopes {frameId}` → locals / upvalues / globals 的 `ref`。
4. 变量：`variables {ref, start, count}` 分页；大 table 只返回前 N 项 + `total`。
5. 求值：`evaluate {frameId, expr, mode: valuePath|lua}`。

IDE 只取看得见的部分，Wi-Fi 下暂停响应从"等整包"变成"先显示顶层帧"。

### 4.4 断点增量

`setBreakpoints {revision, upserts:[...], removes:[...]}`，runtime 回 `breakpointsApplied {revision, verified:[...]}`。断点字段：`id, source{path, chunkName?, sha256?}, line, condition?, hitCondition?, logMessage?, capture?[], autoContinue?, vmId?`。

---

## 5. 原生端（EmmyLuaDebugger）重构与性能

### 5.1 热路径现状（`emmy_debugger.cpp`）

每执行一行 Lua，`Debugger::Hook` 会：

```text
EmmyFacade::Hook         → GetDebugger(L)：debuggerMtx 加锁 + map 查找
Debugger::Hook           → evalMtx 加锁取 pendingAction
                         → luaThreadMtx 加锁 swap executors
FindBreakPoint(ar)       → manager->GetLineSet()：breakpointsMtx 加锁 + 复制 std::set<int>
                         → hookStateMtx 加锁取 hookState
```

运行态钩子掩码常开 `LUA_MASKCALL | LUA_MASKLINE | LUA_MASKRET`（`emmy_facade.cpp:77/80/1316`）。

### 5.2 新热路径

1. **按函数开关行钩子**（核心优化）
   - 运行态（无单步）只开 `CALL | RET`。
   - CALL 时用 `ar->source` 指针（Lua 5.2+ 字符串驻留，同一 chunk 指针稳定）查**每 VM 的 source 缓存**：这个 chunk 有没有断点。有，才给当前协程打开 `LINE`；返回到无断点函数时关掉。
   - 没有任何断点时：掩码为 0，只保留一个 `COUNT` 钩子（例如每 100 万条指令）用于响应"暂停"请求。**零断点 = 近乎零开销**。
   - 单步时按需开 `LINE`（step in）或 `LINE | CALL | RET`（over/out 的深度跟踪）。
2. **无锁不可变断点索引**：断点更新时在网络线程构建新的 `BreakpointIndex`（`unordered_map<sourceKey, flat_set<line>>` + 条件预编译），用 `std::atomic<std::shared_ptr>` 或原子指针 + 代际回收发布；钩子线程只做一次原子读。
3. **chunk 解析缓存**：`chunkName → sourceKey` 的路径匹配结果按 `(source 指针, 断点 revision)` 缓存，命中后不再做模糊匹配字符串运算。
4. **待执行动作用原子标志**：`pendingAction`、`executors` 非空用 `atomic<uint32_t>` 位标志判断，只有置位时才加锁。
5. **`lua_State → Debugger` 查找**：把 `Debugger*` 存进 `lua_getextraspace`（5.3+）或每 VM 一个小的开放寻址表，去掉全局 map 锁。
6. **条件断点**：条件编译为受限表达式 AST（沿用 `restricted_eval`），命中时求值；不再每次 `luaL_loadstring`。

预期：无断点时的 Lua 执行开销从"每行若干次加锁 + 一次分配"降到接近 0；有断点文件内的函数才有行钩子开销。需要用 `tests/` 下的基准（新增 `bench_hook`：100 万次循环、fib(25)、table 遍历）在 Windows x64 和 Android arm64 各测一次，作为验收数据。

### 5.3 性能目标与验收

下面的数字是**目标**，不是实测值。P2 第一步先用 `bench_hook` 量出现状作为基线（Windows x64 + 一台中端 Android arm64），再按此表验收。比较对象是"同一程序不挂调试器"的耗时。

**运行时开销（游戏侧，最关键）**

| 场景 | 现在的行为 | 目标 |
| --- | --- | --- |
| 已连接，没有任何断点 | 每行触发钩子 + 多次加锁 + 复制 set | ≤ 1.05 倍（只剩稀疏 COUNT 钩子） |
| 有断点，但当前执行的函数所在文件没有断点 | 同上 | ≤ 1.3 倍（只有 CALL/RET 钩子 + 一次指针查表） |
| 正在有断点的函数里执行 | 同上 + 命中时模糊匹配 | ≤ 2 倍（行钩子 + 无锁 `flat_set` 查行号） |
| 条件断点高频命中（每帧上千次） | 每次回到通用求值路径 | 单次条件判断 ≤ 2 µs（预编译 AST） |
| 未连接 IDE（开发包，runtime 已编入但没人连） | — | 1.00 倍：不挂任何钩子 |
| 内存 | — | runtime 常驻 ≤ 2 MB；每 VM 额外 ≤ 64 KB |

**交互延迟（IDE 侧）**

| 操作 | 目标（USB / 本机） | 目标（局域网 Wi-Fi） |
| --- | --- | --- |
| 命中断点 → 编辑器高亮当前行 | ≤ 50 ms | ≤ 150 ms |
| 暂停后显示顶层帧局部变量 | ≤ 100 ms | ≤ 250 ms |
| 展开一个 1 万项的 table（首页 100 项） | ≤ 100 ms | ≤ 300 ms |
| 单步 | ≤ 50 ms | ≤ 150 ms |
| Break All 生效（无断点、游戏在跑） | ≤ 20 ms（COUNT 间隔按 CPU 自适应） | ≤ 100 ms |
| 修改断点 → runtime 生效 | ≤ 30 ms | ≤ 100 ms |
| MCP `lua_debug_step` 从调用到返回新位置 | ≤ 150 ms | ≤ 300 ms |

**IDE 侧实现要求**

- 所有网络、解码在后台线程；EDT 上只做 UI 更新，任何调试操作都不允许阻塞 EDT（现在部分求值路径有同步等待，迁移时逐个改成异步）。
- 变量树、栈全部分页；内联值只请求编辑器可见行涉及的变量名。
- 帧解码零拷贝读取（`ByteBuffer` 切片），CBOR 用流式解码。

**手段与依据**

| 手段 | 为什么能变快 |
| --- | --- |
| 按函数开关行钩子 | Lua 的行钩子每条语句都要从 VM 切到 C 回调，是最大的固定开销；只在需要的函数里开，绝大多数代码完全不付这笔钱 |
| 无锁不可变断点索引 | 钩子线程只做一次原子读和一次哈希查找，去掉每行多次加锁和一次堆分配 |
| `source` 指针缓存 | 同一函数的 `source` 字符串指针不变，路径匹配结果算一次复用，不在热路径做字符串比较 |
| 长度前缀帧 + CBOR + 按需拉取 | 少传、少扫描、少解析；Wi-Fi 下最明显 |
| runtime 侧执行条件 / 日志断点 | 不再每次命中都往返 IDE |

**验收方式**：`bench_hook` 和 IDE 侧的假 runtime 延迟测试进 CI，任一指标比基线退步超过 10% 直接失败；真机数字在 P5 结束时用 Android 中端机和 iPhone 各测一轮写进验证文档。

### 5.4 其他原生端改动

| 项 | 改动 |
| --- | --- |
| 核心拆分 | 拆出 `emmy_debugger_core`：钩子状态机、断点索引、变量读取、受限求值、协议编解码，不含网络 / 线程 / 注入，宿主通过小接口提供 IO；`emmy_core`、附加注入、UELink 都基于它 |
| C++ 标准 | C++11 → C++17（`std::optional`、`string_view`、`shared_mutex`、结构化绑定），清理手写锁宏 |
| 协议 | 实现 v3；删除 v1 消息、v2 Envelope、管道传输（`pipeline_*_transporter.cpp`） |
| `EmmyFacade` | 1327 行单例拆成 `Session`（连接 + 协议）、`VmHub`（VM 注册）、`HookDispatcher`；单例只留 C ABI 入口 |
| 网络 | 保留 libuv（各平台都能编，已经在用）；网络线程只收发帧，解析后投递到每 VM 的命令队列 |
| 钩子共存 | 新增 `HookMux`：一个 `lua_State` 只能挂一个钩子，提供 C ABI `Emmy_HookMux_Register(callback, mask)` 让宿主其他工具（如 UELink 的快照点 / 采样）共享，代替各自"记住上一个钩子再转发"的做法 |
| 平台 | 新增 Android（NDK, arm64-v8a / armeabi-v7a / x86_64）、iOS（静态库 / xcframework）、Linux arm64 构建 |
| 产物 | 本仓库 CI 编译各平台 runtime，PC 端二进制直接打进插件包；Android/iOS/Unity 包作为 Release 附件 `emmy-runtime-<ver>-<platform>.zip` + `SHA256SUMS` 发布；仓库不再提交二进制 |
| 安全 | 局域网监听强制 token（配对码）；`EMMY_DEBUG_ENABLED` 编译宏，发布包不编入 |

---

## 6. MCP Server 设计（替换 CLI）

### 6.1 承载方式

| 方案 | 说明 | 结论 |
| --- | --- | --- |
| A. 插件内嵌 MCP Server（Streamable HTTP，`127.0.0.1`） | 插件自己起 HTTP 端点，任何 MCP 客户端都能连 | **采用** |
| B. 贡献工具到 JetBrains 内置 MCP Server（2025.2+） | 复用 IDE 的 MCP 端点 | 作为可选附加：检测到内置 MCP Server 插件时同时注册一份，同一套工具实现 |
| C. stdio 桥接进程 | 客户端启动一个小程序再连 IDE | 不采用：要在项目配置里写本机 Java 路径，团队共享的配置文件没法用 |

选 A 的原因：端点地址稳定，可以写进项目根目录的配置文件给全组用；不依赖 IDE 版本；我们能完全控制会话和权限。

端点：`http://127.0.0.1:<port>/mcp`，默认端口 `9330`，可在设置里改；被占用时顺延并提示更新配置。只绑定回环地址，校验 `Origin` / `Host` 头（防 DNS rebinding，MCP 规范要求）。

### 6.2 权限模型（代替授权菜单 + 租约）

| 级别 | 工具 | 默认 |
| --- | --- | --- |
| 只读 | 列会话、栈、变量、断点、输出、`wait_for_pause` | 允许 |
| 控制 | 下/删断点、继续、单步、暂停、运行到行、Probe | 每个调试会话**第一次**控制时 IDE 弹通知「Agent 请求控制调试会话 X：允许 / 本次会话总是允许 / 拒绝」 |
| 任意求值 | `evaluate` 的 `mode=lua`（执行任意 Lua） | 关闭；设置里单独打开 |
| 启动/附加 | `attach_process`、`connect_device` | 每次在 IDE 里弹确认 |

- 原 CLI 的 VALUE_PATH 受限求值作为 `evaluate` 的默认模式保留。
- 原"租约"去掉：引擎单线程处理所有命令，UI 和 Agent 不会冲突；UI 操作优先，Agent 正在 `wait_for_pause` 时用户手动继续，Agent 会收到 `resumed (by user)`。
- 调试工具窗口里有"Agent"页，实时显示 Agent 的每一次调用，可一键"撤销 Agent 控制"。

### 6.3 工具清单

命名统一用 `lua_debug_` 前缀，避免和 Agent 面板已有的 `ue_*`、UELink 的 `lua.*` 操作混淆。

**会话与目标**

| 工具 | 参数 | 返回 |
| --- | --- | --- |
| `lua_debug_list_sessions` | — | 会话列表：id、类型（attach/device）、目标、状态、VM 数、是否暂停 |
| `lua_debug_list_targets` | `kind?: process\|device` | 可附加的本机进程（自动识别加载了 Lua 的进程）和已发现的设备 |
| `lua_debug_attach_process` | `pid` 或 `name`, `config?` | 新会话 id（需用户确认） |
| `lua_debug_connect_device` | `deviceId` 或 `host+port`, `config?` | 新会话 id（需用户确认） |
| `lua_debug_stop_session` | `sessionId` | — |
| `lua_debug_list_vms` | `sessionId` | VM 列表：vmId、显示名、状态、sourceEpoch |

**断点**

| 工具 | 参数 |
| --- | --- |
| `lua_debug_set_breakpoint` | `file, line, condition?, hitCount?, logMessage?, capture?[], autoContinue?, vmId?` |
| `lua_debug_remove_breakpoint` | `breakpointId` 或 `file+line`，`all?`（只删 Agent 下的） |
| `lua_debug_list_breakpoints` | `origin?: ide\|agent\|all` → 列表 + 是否已验证 |

`capture + autoContinue` 就是原 CLI 的 Probe：命中时采集变量，不停下游戏；采集结果进事件流，用 `wait_for_pause` 或 `get_probe_hits` 读。

**执行控制**

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `lua_debug_continue` | `sessionId, vmId?` | |
| `lua_debug_pause` | `sessionId, vmId?` | |
| `lua_debug_step` | `sessionId, kind: over\|into\|out` | 执行后自动等待下一次暂停（默认 10 秒），直接返回新位置和当前帧局部变量摘要，省掉 Agent 再调一次 |
| `lua_debug_run_to_line` | `sessionId, file, line` | 同上 |
| `lua_debug_wait_for_pause` | `sessionId?, timeoutMs (≤120000)` | 阻塞到下一次暂停 / Probe 命中 / 会话结束；**Agent 调试循环的核心工具** |

**查看**

| 工具 | 参数 | 返回 |
| --- | --- | --- |
| `lua_debug_get_stack` | `sessionId, vmId?, start?, count?` | 帧列表：frameId、函数、本地文件:行、chunkName、源码片段（当前行 ±3） |
| `lua_debug_get_variables` | `frameId, scope?: locals\|upvalues\|globals, path?, ref?, depth?(默认 1), limit?` | 变量树（有界），每项带 `ref` 可继续展开 |
| `lua_debug_evaluate` | `frameId, expression, mode?: valuePath\|lua` | 值 + 类型 + 可展开 ref |
| `lua_debug_get_output` | `sessionId, sinceSeq?, limit?` | 调试器日志、`print` 输出、Lua 错误 |
| `lua_debug_get_probe_hits` | `breakpointId?, sinceSeq?` | Probe 采集记录 |

### 6.4 返回值设计

- 同时返回 `structuredContent`（JSON，给程序用）和简洁文本（给模型读），文本示例：

  ```text
  已暂停 · VM "GameLua"(vm-2) · 原因: 断点 #3
  #0 BagPanel:OnItemClick  Content/Script/UI/BagPanel.lua:120
     118 |   local item = self.items[index]
     119 |   if not item then return end
  >  120 |   self:ShowDetail(item)
  locals: self=<BagPanel 0x1a2b>, index=7, item={id=1003, count=2, …}
  ```

- 所有引用（`frameId`、`ref`）都绑定 `pauseId`，过期时返回明确错误和恢复提示：「这个栈帧已失效（游戏已继续运行）。先调用 lua_debug_wait_for_pause 或 lua_debug_get_stack 获取新的 frameId。」
- 大小预算：单次返回默认 ≤ 16 KB 文本，超出截断并标明 `truncated` 和如何继续取。

### 6.5 MCP 安装到项目（用户新增需求）

**写到哪里**：当前项目的**版本管理根目录**（`ProjectLevelVcsManager.getAllVersionedRoots()`；多个根时让用户选，默认含 `.idea` 的那个；没有 VCS 时用项目根）。

**写哪些文件**（按检测到的客户端勾选，默认勾选已存在对应目录的）：

| 客户端 | 文件 | 写入内容 |
| --- | --- | --- |
| Claude Code | `<root>/.mcp.json` | `{"mcpServers": {"emmylua-debug": {"type": "http", "url": "http://127.0.0.1:9330/mcp"}}}` |
| Cursor | `<root>/.cursor/mcp.json` | `{"mcpServers": {"emmylua-debug": {"url": "http://127.0.0.1:9330/mcp"}}}` |
| VS Code / Copilot | `<root>/.vscode/mcp.json` | `{"servers": {"emmylua-debug": {"type": "http", "url": "..."}}}` |
| Gemini CLI | `<root>/.gemini/settings.json` | `{"mcpServers": {"emmylua-debug": {"httpUrl": "..."}}}` |
| Codex | 用户级 `~/.codex/config.toml` | 只在用户勾选时写，单独提示"这是全局配置" |

各客户端的配置格式以实现时的官方文档为准，安装器为每个客户端写一个小的 `McpClientConfigWriter`，便于跟进格式变化。

**写入规则**

- **合并不覆盖**：读取已有 JSON，只增改 `emmylua-debug` 这一项，保留其他 server 和格式（缩进跟随原文件）。解析失败时不写，提示用户手动处理并给出要粘贴的片段。
- **不写密钥**：配置里只有本机回环地址，没有 token，可以安全提交到仓库；安全靠"只听 127.0.0.1 + Origin 校验 + IDE 内确认控制"。
- 写完后用 VCS 的"新文件"提示让用户决定是否加入版本管理（不自动 `git add`）。
- 端口改了以后，安装器检测到已写入的地址和当前端口不一致，提示一键更新。

**没安装时的提示**

- 触发时机：项目打开后（且项目里有 `.lua` 文件），以及第一次启动 Lua 调试会话时。
- 判定"已安装"：任一已勾选客户端的配置文件里存在 `emmylua-debug` 且 URL 指向当前端口。
- 通知（气泡，不是模态弹窗，避免打断）：

  ```text
  EmmyLua 调试 MCP 未安装到本项目
  安装后 Claude Code / Cursor 等 Agent 可以直接调试 Lua（断点、单步、看变量）。
  [安装到 D:/Game]   [选择客户端…]   [不再提示]
  ```

- "不再提示"按项目记住；设置页和 Tools 菜单里随时可以再装、卸载、查看状态。

### 6.6 与原 CLI 概念的对照

| CLI | MCP |
| --- | --- |
| `instance list` + `--instance` + token 文件 | 不需要：MCP 端点就是这个 IDE |
| 授权菜单 + `--client` | 首次控制时 IDE 内确认 |
| `lease acquire/heartbeat/release` | 去掉 |
| `target list` / `vm list` | `list_sessions` / `list_vms` |
| `stack` / `scopes` / `variables` | `get_stack` / `get_variables` |
| `eval --policy VALUE_PATH` | `evaluate`（默认 valuePath） |
| `probe run` + `wait --cursor` | `set_breakpoint{capture, autoContinue}` + `wait_for_pause` / `get_probe_hits` |
| 8 种退出码 | MCP 错误 + 中文恢复提示 |

---

## 7. 真机调试

### 7.1 原理

真机上没法注入，所以 runtime **编进游戏**（开发包），游戏启动后 runtime 监听端口或主动连 IDE；IDE 通过 USB 转发或局域网连上去。连上后和附加调试走同一套 v3 协议和引擎。

```mermaid
sequenceDiagram
    participant IDE
    participant Fwd as adb / usbmux
    participant App as 设备上的游戏 + emmy runtime
    App->>App: 启动，Emmy_StartServer(0.0.0.0:9966 或 127.0.0.1:9966)
    App-->>IDE: (局域网) UDP 广播名片
    IDE->>Fwd: adb forward tcp:L tcp:9966 / usbmux connect
    IDE->>App: hello{protocol:3, token}
    App-->>IDE: welcome{caps, platform, luaVersion}
    App-->>IDE: vmAdded(...)
    IDE->>App: setBreakpoints(...)
```

### 7.2 连接方式

| 方式 | 适用 | 实现 |
| --- | --- | --- |
| Android USB（推荐） | Android | 插件调用 `adb`（路径自动找 `ANDROID_HOME` / PATH，可在设置里指定）：`adb devices -l` 列设备，`adb forward tcp:<本地> tcp:9966` 后连 `127.0.0.1:<本地>`。游戏只需监听 `127.0.0.1`，不暴露到网络 |
| Android 反向 | 设备主动连 IDE | `adb reverse tcp:9966 tcp:9966`，游戏 `Emmy_Connect("127.0.0.1", 9966)`，适合"游戏启动就要停在第一行" |
| iOS USB | iOS | 第一期调用 `iproxy`（libimobiledevice）做转发；第二期内置 usbmux 客户端（macOS 连 `/var/run/usbmuxd`，Windows 连 Apple Mobile Device Service 的 27015 端口），免装工具 |
| 局域网 | 所有平台 | 游戏监听 `0.0.0.0:9966` 并每 2 秒 UDP 广播名片到 `9967`；IDE 列出发现的设备。**必须配对码**：游戏日志/屏幕显示 6 位码，首次连接输入，之后按设备记住 |
| 手动 | 兜底 | 填 `host:port` |

设备名片（UDP，JSON）：`{app, version, platform, arch, deviceName, port, protocol: 3, pid, needsPairing}`。

### 7.3 runtime 集成方式

| 宿主 | 集成 |
| --- | --- |
| UE + UnLua（真机） | 复用 UELink（开发包里已有），加 Lua 调试通道，见 8.4 |
| Unity（xLua / toLua / slua） | 提供 native plugin 包：Android `.so`、iOS 静态库；C# 一行 `EmmyDebug.Start(luaEnv.L, 9966)` |
| 其他 C/C++ 引擎 | 链接 `emmy_debugger` 静态库，调用 C ABI：`Emmy_StartServer`、`Emmy_RegisterLuaVm`、`Emmy_RegisterLuaSource` |
| 纯 Lua 环境（PC） | 现有 `emmy_core`：`require("emmy_core").tcpListen(...)` |

**Lua 符号**：手游的 Lua 通常静态链接进主 so / 可执行文件，runtime 必须**和游戏用同一份 Lua**编译（UnLua 是定制的 lua-5.4.3，`LUA_IDSIZE=256`）。Android 上也可用 `dlsym(RTLD_DEFAULT)` 动态找符号（现有 `lua_api_loader`），iOS 不行，只能源码编入。

**iOS 限制**：不能 `dlopen` 第三方库，只能静态链接；App 进后台时 socket 会被挂起，runtime 需要在回前台时重建监听，IDE 侧自动重连（沿用现有重连 + 断点重发）。

### 7.4 源码映射

真机上的 chunkName 和本机路径不一样（`@Content/Script/UI/BagPanel.lua`、`assets/lua/ui/bag.lua`、从 pak 加载的虚拟路径）。

1. **映射规则表**（运行配置里）：`设备前缀 → 本地目录`，如 `Content/Script/ → D:/Game/Content/Script/`。
2. **自动学习**：首次遇到未映射的 chunk，按"路径段从后往前"匹配项目内文件；唯一匹配直接用，多个候选时在 UI 里让用户选一次并记入规则表。
3. **内容校验**：宿主注册了 `sha256` 时，与本地文件对比；不一致（热更新、未同步）时在编辑器顶部提示"设备上的代码和本地不一致，断点行号可能不准"。
4. **字节码**：去掉调试信息的 luac 无法断点，握手后检测到时提示。

### 7.5 弱网与性能

- v3 按需取变量 + CBOR，暂停后首屏只传顶层帧。
- 心跳 5 秒，3 次未回判定断开；Wi-Fi 切换、App 后台都走自动重连。
- 超时按连接类型调整（USB 10 秒、局域网 20 秒）。
- 运行时开销靠第 5 节的按函数开关行钩子，真机上不下断点时几乎无影响。

---

## 8. EmmyLuaDebugger 是否保留、迁移到哪里

### 8.1 是否必须保留

**必须。** 两个场景都离不开它：

- **附加调试**：要往一个没准备过的进程里注入代码并挂钩子，只能靠 `emmy_hook` + `emmy_tool` 这种原生注入；纯 Lua 方案（LuaPanda 那种 `require` 一个 Lua 文件）做不到"附加"。
- **真机调试**：设备上的 runtime 必须是原生库，且要支持 5.1–5.5 和 LuaJIT 多个版本、多种宿主。

能替代它的只有自己重写一个，没有必要：它的多 VM、Host API、受限求值、sourceIdentity 都已经做完并有测试。

### 8.2 是否需要重构

**需要，且主要是性能和移动端**，见第 5 节。按收益排序：

1. 热路径（按函数开关行钩子、无锁断点索引）——真机必需。
2. 协议 v3 ——与插件同步切换。
3. Android / iOS 构建与 CI 产物发布。
4. `HookMux` 钩子共存——和 UELink 共存必需。
5. `EmmyFacade` 拆分、C++17 ——可放到后面。

### 8.3 迁移方式：并入 IntelliJ-EmmyLua（已确认）

用户已确认：**EmmyLuaDebugger 并入 IntelliJ-EmmyLua 仓库**，不放进 UEKit，也不再作为子模块。

**为什么并入插件仓库**

| 好处 | 说明 |
| --- | --- |
| 协议两端一次改完 | v3 协议、断点字段、能力位的改动在一个 PR 里同时改 Kotlin 和 C++，一起评审、一起测，不再有"子模块指针落后"的问题 |
| 版本对齐 | 插件版本和 runtime 版本由同一个 tag 决定；插件打包的二进制一定来自同一提交的源码 |
| CI 简化 | 现在的 `build.yml` / `verify.yml` 已经在编子模块，只是还要额外拉子模块；并入后少一步，也不再受子模块 SSH 地址影响 |
| 体积可接受 | EmmyLuaDebugger 工作区约 15 MB（大头是 `third-party` 里的 5 个 Lua 版本、LuaJIT、libuv、EasyHook），历史约 6 MB、390 个提交 |

**目录**

```text
IntelliJ-EmmyLua/
  native/                     ← 原 EmmyLuaDebugger 全部内容
    CMakeLists.txt
    emmy_debugger/            调试核心（协议、VM 注册、断点索引、钩子）
    emmy_core/                Lua C 模块（require "emmy_core"）
    emmy_hook/ emmy_tool/     Windows 附加注入
    shared/ tests/ cmake/ third-party/
    platforms/android/        🆕 NDK 构建脚本
    platforms/ios/            🆕 xcframework 构建脚本
    emmy_debugger_core/       🆕 从 emmy_debugger 拆出的无 IO、无第三方依赖的调试核心，供 UELink 等宿主以源码引用（见 8.4）
  modules/ src/ ...           插件
```

**迁移步骤**

1. 用 `git subtree add --prefix=native <EmmyLuaDebugger> main`（不压缩历史）把代码连同提交历史并入；`git log -- native/` 仍能追溯原提交。
2. 删除 `.gitmodules` 和 `EmmyLuaDebugger` 子模块条目。
3. CI 中 `cmake -S EmmyLuaDebugger` 改为 `cmake -S native`，去掉 `submodules: recursive`；顺手加 `-DCMAKE_POLICY_VERSION_MINIMUM=3.5` 或升级 `third-party` 的 `cmake_minimum_required`（当前 runner 的 CMake 4.x 因 libuv 等第三方库最低版本过低而配置失败，所有 Native 检查都是红的）。
4. 原生 CI 只在 `native/**` 变化时跑（`paths` 过滤），不拖慢纯 Kotlin 的 PR。
5. 原 EmmyLuaDebugger 仓库：README 写明"已迁移到 IntelliJ-EmmyLua/native"，然后归档（只读）。外部若有人直接引用旧仓库，旧 tag 仍可用。
6. 插件资源目录 `src/main/resources/debugger/emmy/**` 的二进制改为由同一次 CI 构建产出、打进插件包，不再提交到仓库。

**和 UEKit 的关系**：UE 项目真机调试**不新增插件、不改打包流程**，复用已经随开发包进到设备上的 UELink，见 8.4。为此 `native/` 里把调试核心拆出一个无 IO、无第三方依赖的 `emmy_debugger_core`，UELink 以源码方式引用它。

### 8.4 UE 真机调试：复用 UELink，不新增插件

**为什么不单独做 `LuaDebug` 插件**：再加一个插件意味着每个项目都要改 `.uproject`、改打包配置、处理 libuv 等第三方库在 Android/iOS 上的 UBT 编译，落地成本高（已与用户确认不现实）。

**UELink 已经具备的条件**（`fixcode-h/UEKit` 现状）：

| 条件 | 现状 |
| --- | --- |
| 随开发包进设备 | `UELinkCore` 是 Runtime 模块，只排除 Shipping，没有平台限制，Development / Test 包里本来就有 |
| 能拿到 Lua VM | 已链接 UnLua 的 Lua（`SetupLua`），有 `StateFor()` 取 `lua_State` |
| 已经在用调试钩子 | `lua.snap`（快照点）、`lua.profile`（采样）用 `lua_sethook` |
| 只监听本机 | HTTP 绑定 `127.0.0.1`，真机上配合 `adb forward` / usbmux 正好安全 |

**方案：在 UELink 里加一个"Lua 调试通道"**

```text
plugins/UELink/Source/UELinkCore/Private/
  LuaDebug/
    LuaDebugChannel.cpp     独立线程 + FSocket 监听 127.0.0.1:<端口>，收发协议 v3 帧
    LuaDebugHost.cpp        把 UnLua 的 lua_State 注册给调试核心；PIE / 重建 Lua 环境时注销
    ThirdParty/emmy_core/   从 IntelliJ-EmmyLua@tag 的 native/emmy_debugger_core 同步的源码（约 10 个文件）
```

- **不用 UE 的 HTTPServer 做调试通道**：断点会让游戏线程停住，而 UELink 的 HTTP 请求在游戏线程上处理，停住后就没人回包了（这也是 UELink 注释里说"不做断点"的原因）。调试通道用自己的线程收包；游戏线程停在断点里时，钩子循环从队列里取"查栈 / 取变量 / 继续"等命令，在 Lua 所属线程上执行。这正是 Emmy 现在暂停时的工作方式。
- **`emmy_debugger_core`**：从 `native/emmy_debugger` 拆出纯逻辑部分（钩子状态机、断点索引、变量读取、受限求值、协议 v3 编解码），网络、线程、日志通过一个很小的接口由宿主提供。没有 libuv，没有 `emmy_hook` / EasyHook，UBT 直接编，iOS 也能用。PC 端的 `emmy_core` / 附加注入同样基于这个核心，两边行为一致。
- **端口与发现**：调试端口写进 UELink 名片（新字段 `luaDebug`）。PC 上 IDE 扫 `<项目>/Saved/UELink/instances/*.json` 直接列出可调试的编辑器 / PIE / `-game` 进程，**不需要注入**；真机上 IDE 通过 `adb forward` / usbmux 转发到设备的 `127.0.0.1:<端口>`。
- **开关**：默认不监听，需要 `-LuaDebug` 命令行或 `DefaultUELink.ini` 里 `bLuaDebug=True`；未连接时不挂任何钩子，零开销。
- **钩子共存**：调试通道、`lua.snap`、`lua.profile` 在 UELink 内部共用一个钩子分发（`HookMux`），不再"记住上一个钩子再转发"。
- **source hash**：在 UnLua 的加载回调里对实际交给 Lua 的字节算 SHA-256 并注册，真机热更新也能对上。
- **与 Agent 面板的关系**：面板的 `ue_*` MCP 工具不变；Lua 断点调试的 MCP 由 IDE 插件提供（第 6 节）。

**按项目情况的降级方案**

| 情况 | 可用能力 |
| --- | --- |
| 包里有 UELink（推荐） | 完整断点调试：断点、单步、看变量、求值、出错即停 |
| 包里有 UELink 但不能加调试通道代码 | 只能"不停游戏的快照点"：IDE / MCP 通过 UELink 已有的 `lua.snap` 在指定行采集局部变量和调用栈，相当于日志断点 + Probe，不能单步 |
| 包里什么都不加 | Android 可调试包（`debuggable=true`）理论上可以用 `cmd activity attach-agent` 加载一个 JVMTI agent，再由它加载调试核心，配合本地未裁剪的 `libUnreal.so` 符号找到 Lua 函数地址，实现"真机附加"。iOS 无法做到。实现复杂、依赖符号文件，列为后续调研项，不进首期 |

---

## 9. 精简：删除清单

### 9.1 调试功能

| 删除 | 路径 |
| --- | --- |
| LuaPanda 调试器 | `src/main/java/.../debugger/luapanda/**`（14 个文件）、`modules/debugger-luapanda-protocol/`、`src/main/resources/debugger/luapanda/`、`LuaPandaVersionChecker`、通知组 `LuaPanda Version Check`、plugin.xml 中 `LuaPandaConfigurationType` / `LuaPandaDebuggerRunner`、`protocol_analysis_report.md` |
| Emmy "启动/远程" 旧配置 | `EmmyDebugConfigurationType.kt`、`EmmyDebugSettingsPanel.java`、`EmmyDebugProcess.kt`、`EmmyDebuggerRunner.kt`（功能并入新的"真机/远程"配置） |
| 管道传输 | `Transporter.kt` 中 `Pipeline*Transporter`、`EmmyDebugTransportType.PIPE_*`、依赖 `org.scala-sbt.ipcsocket`；原生端 `pipeline_*_transporter.cpp`、`emmy_core` 的 `pipeListen/pipeConnect` |
| v1 / v2 协议 | `modules/debugger-emmy-protocol` 中 `EmmyProtocol.kt`（v1）、`EmmyProtocolV2.kt`，由 `debugger-protocol` v3 取代；原生端 `proto.cpp` 旧消息、`protocol_v2.cpp` |
| CLI | `tools/emmy-debug/`、`modules/debugger-cli-protocol/`、`src/main/java/.../debugger/cli/**`（10 个文件）、`CliGatewayApplicationService` 注册、Tools 菜单的授予/撤销权限动作、`docs/cli-debug-guide.md` |
| 通用运行配置基类中只为上述服务的部分 | `LuaRunConfiguration` / `LuaCommandLineState` / `LuaRunner` 视新配置需要精简 |

### 9.2 仓库

| 删除 / 处理 | 说明 |
| --- | --- |
| `bin/` | 已在 `.gitignore`，从版本库移除 |
| `bunch/`、`.bunch` | 旧多版本工具，构建不用 |
| `.superpowers/`、`docs/superpowers/` | 过程记录；有价值的结论并入本设计或 `docs/design/` |
| `design/emmy.ai` | 移到发布素材仓库或 Git LFS |
| `src/main/resources/debugger/emmy/**` 二进制 | 改为由 `native/` 在同一次 CI 中编译后打包 |
| `.idea/` 下的已提交文件 | 只留 `copyright` 配置 |
| `org.eclipse.egit.github.core` | 错误上报改为打开预填 issue 的浏览器链接 |
| `.gitmodules`、`EmmyLuaDebugger` 子模块 | 源码以 subtree 并入 `native/` 后删除 |

### 9.3 保留

附加调试（Windows 注入，后续可扩展 macOS/Linux）、真机/远程调试、行断点（条件 / 命中次数 / 日志断点）、单步、运行到光标、多 VM、内联变量值（`LuaEditorLinePainter`）、求值（含补全）、`LuaTracebackFilter`（控制台堆栈跳转）、全部语言支持。

---

## 10. UI 重新设计

### 10.1 原则

- **两个入口，一个心智**：只有"附加到进程"和"连接设备"，名字直接说做什么。
- **优先用 IDE 原生入口**：附加走 IntelliJ 的 `Run > Attach to Process…`（`XAttachDebuggerProvider`），用户不需要先建运行配置。
- **用 Kotlin UI DSL v2** 重写所有设置面板，删除 Java Swing 表单；运行配置持久化改用 `RunConfigurationOptions`（声明式字段，自动序列化，不再手写 JDOM）。
- 所有字符串进 `LuaBundle`，中英双语。

### 10.2 附加到进程

`Run > Attach to Process…`（Ctrl+Alt+F5）列表里出现 Lua 分组：

```text
┌ Attach to Process ─────────────────────────────────────────┐
│ 🔍 搜索进程                                                  │
│ Lua（检测到 Lua 运行时）                                     │
│   ● UnrealEditor.exe   12345  x64  Lua 5.4  UE 编辑器(PIE)   │
│   ● MyGame.exe         23456  x64  Lua 5.4                  │
│ 最近附加                                                     │
│   ○ MyGame.exe                                               │
│ 其他进程（展开）                                              │
└─────────────────────────────────────────────────────────────┘
```

- 架构从 PE 头自动判断，删除"选择 x86/x64"选项。
- 按模块是否加载 `lua*.dll` / 导出 `lua_*` 符号识别 Lua 进程；UE 进程类型识别作为一种"进程标签提供者"扩展，而不是写死在通用列表里。
- 开启了 UELink 调试通道的 UE 进程直接显示"可直连（无需注入）"，选它走 TCP 直连。
- 仍提供"Lua 附加"运行配置，给需要固定进程名、自动附加的场景用。

### 10.3 连接设备

新运行配置"Lua 设备调试"：

```text
┌ Lua 设备调试 ──────────────────────────────────────────────┐
│ 设备    [▼ Pixel 8 (USB · adb)        ] [刷新]             │
│          · Pixel 8 (USB · adb)                              │
│          · iPhone 15 (USB · usbmux)                         │
│          · MyGame @ 192.168.1.23 (局域网)                    │
│          · 手动输入地址…                                     │
│ 端口    [9966]   连接方向 (●) IDE 连设备  ( ) 设备连 IDE      │
│ 配对码  [······]（仅局域网）                                  │
│ ▸ 源码映射                                                   │
│   设备路径前缀            本地目录                            │
│   Content/Script/         $PROJECT_DIR$/Content/Script/  [+][-] │
│   [ ] 自动学习未映射的文件                                     │
│ [测试连接]  ✓ 已连接 · Lua 5.4 · runtime 3.0.0 · 2 个 VM       │
└─────────────────────────────────────────────────────────────┘
```

"测试连接"会真正建转发、握手、断开，把失败原因说清楚（adb 未找到 / 设备未授权 / 端口未监听 / 协议版本不符）。

### 10.4 调试窗口

在标准 Debug 工具窗口里增加：

| 页签 | 内容 |
| --- | --- |
| VM | 当前会话所有 Lua VM：名字、状态（运行/暂停/已关闭）、sourceEpoch；切换当前 VM |
| Agent | MCP 调用实时记录（时间、工具、参数摘要、结果）；"撤销 Agent 控制"、"清除 Agent 断点" |
| 映射 | 本会话遇到的 chunkName → 本地文件、未映射项、hash 不一致项，可直接改规则 |

Agent 下的断点在编辑器装订线上用不同图标（带小机器人角标），悬停显示是哪个 Agent 在什么时候下的。

### 10.5 设置页

`Settings > Tools > EmmyLua 调试`：

```text
MCP Server
  [✓] 启用       端口 [9330]   状态: ● 运行中 · 1 个客户端已连接
  [ ] 允许 Agent 执行任意 Lua 表达式（默认只允许读取变量路径）
  本项目安装状态: Claude Code ✓  Cursor ✗  VS Code ✗     [安装/更新…]
设备
  adb 路径  [自动检测: C:/Android/sdk/platform-tools/adb.exe]
  iproxy 路径 [未找到]  （iOS USB 调试需要，第二期内置后不再需要）
日志
  调试器日志级别 [运行时 ▼]
```

### 10.6 状态栏

右下角小部件：`Lua 调试 MCP ●`，点击显示端口、连接的客户端、快捷"安装到本项目"。未安装时图标为灰色并带提示点。

---

## 11. 其他优化与扩展点

按建议优先级排列。

| 优先级 | 项 | 说明 |
| --- | --- | --- |
| 高 | 出错即停 | runtime 钩住 `lua_error` / `pcall` 错误路径，未捕获错误自动暂停在出错行；MCP 的 `wait_for_pause` 返回 `reason=error` 和错误信息。Agent 排查崩溃最有用 |
| 高 | 日志断点 / 命中次数 / 条件断点在 runtime 侧执行 | 不再回 IDE 判断，弱网下也不卡 |
| 高 | 原生端基准测试与 CI | `bench_hook` 进 CI，性能回退直接失败 |
| 高 | 插件端集成测试用假 runtime | 用 Kotlin 写一个 v3 协议的假 runtime，覆盖引擎 + XDebugger 适配 + MCP 工具，不再依赖 Windows 原生 fixture 才能测主链路 |
| 中 | 热重载 | 编辑器保存 Lua 文件后，经调试连接把文件内容推给 runtime 重新加载（宿主提供 reload 回调；UE 侧可复用 UELink 的 `lua.reload`） |
| 中 | 协程视图 | 在栈视图里列出当前 VM 所有活跃协程，可切换查看 |
| 中 | DAP 适配 | 引擎已与 IntelliJ 解耦，再加一层 DAP 适配就能给 VS Code 用，复用全部真机能力 |
| 中 | 采样性能分析 | runtime 用 COUNT 钩子采样调用栈，IDE 侧火焰图；与 UELink `lua.profile` 共用 `HookMux` |
| 中 | macOS / Linux 附加 | macOS 用 `task_for_pid` + 动态库注入受 SIP 限制，Linux 用 `ptrace`；先做 Linux |
| 低 | 内存快照与对比 | 遍历 registry 统计 table 数量/大小，两次快照对比找泄漏（UELink `lua.leaks` 已做了 UObject 方向） |
| 低 | 监视表达式持久化 | 按项目保存 watch 列表 |
| 低 | 构建只保留 2025.2+ | 内置 MCP Server、新 UI DSL 都在 252 之后更稳定；评估去掉 251 构建以减少兼容代码 |

---

## 12. 实施计划

每个阶段一个或几个 PR，每阶段结束都能发版。

| 阶段 | 内容 | 涉及仓库 | 产出 |
| --- | --- | --- | --- |
| P0 清理 + 并仓 | 删除仓库垃圾（9.2）；EmmyLuaDebugger 以 subtree 并入 `native/`，删子模块；修复 CI 的 CMake 4.x 配置失败；README 更新 | 本仓库 | 无功能变化，CI 恢复绿色 |
| P1 删减 | 删 LuaPanda、管道、CLI（9.1）；`EmmyDebugProcessBase` 去掉 CLI 相关代码 | 插件 | 只剩附加 + Emmy 远程，代码减约 40% |
| P2 协议 v3 + 原生性能 | `native/` 实现 v3、热路径优化、`HookMux`、基准；Android/iOS 构建；CI 发布产物 | 本仓库 `native/` | runtime 3.0 |
| P3 引擎拆分 | 新建 `debugger-protocol` / `debugger-engine`，拆上帝类，XDebugger 适配层 | 本仓库 | 对接 runtime 3.0（可与 P2 在同一系列 PR 中推进） |
| P4 MCP | `debugger-mcp` 模块、HTTP 宿主、权限确认、Agent 页、安装器与未安装提示 | 插件 | Agent 可调试 |
| P5 真机 | 设备运行配置、adb / iproxy / 局域网发现、源码映射 UI | 插件 | Android / iOS 可用 |
| P6 UE 集成 | `native/emmy_debugger_core` 拆分；UELink 加 Lua 调试通道（同步核心源码）、名片 `luaDebug` 字段、`HookMux` 接入 | 本仓库 + UEKit(UELink) | UE 真机 + 免注入直连，不改项目打包 |
| P7 UI 打磨 | Attach to Process 集成、设置页、状态栏、i18n | 插件 | — |

P2 与 P1 可并行；P3 依赖 P2 的协议定稿（可以先定协议文档，两边同时做）。

---

## 13. 风险与待定事项

| 项 | 说明 | 建议 |
| --- | --- | --- |
| 协议不兼容 | 旧 runtime（已编进游戏的 emmy_core）无法连新插件 | 新插件握手失败时明确提示升级；保留一个旧插件版本下载链接 |
| 按函数开关行钩子的边界 | 尾调用、协程切换、C 函数回调 Lua 时深度计数易错 | 用现有 `vm_lifecycle_harness` 和新增用例覆盖；保留"全量行钩子"调试开关做兜底 |
| iOS usbmux 内置实现 | 协议非公开，Windows 依赖 iTunes/Apple 设备服务 | 第一期只用 iproxy |
| MCP 客户端配置格式变化 | 各家格式还在变 | 每个客户端一个独立写入器，配格式测试 |
| 默认端口冲突 | 9330 / 9966 被占用 | 顺延并在 UI / 安装器中提示 |
| UnLua 定制分支 | Lua ABI 与官方不同 | UELink 调试通道与游戏共用同一份 Lua 头文件编译（UELink 已经这样做）；Host API 中对 ABI 描述做对账（已有 `lua_abi_descriptor`） |
| 待定 | MCP 默认端口号、配对码展示方式（日志 / 屏幕浮层）、是否去掉 251 构建 | 评审时确认 |
