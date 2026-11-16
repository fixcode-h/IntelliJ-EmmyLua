# EmmyLua 调试协议 v3

> 状态：**定稿 v3.0**（2026-11-16）。IDE 引擎（`modules/debugger-protocol` / `debugger-engine`）、原生核心（`native/emmy_debugger_core`）、Android 真机附加 agent 都以本文为准。
> 改动规则：对已定稿的字段只做**向后兼容的增加**（新字段、新命令、新事件、新能力位）；语义变化或删除必须升 `revision` 并在文末「变更记录」写清楚，同一个 PR 里两端一起改。
> 背景与取舍见 [2026-11-debugger-redesign.md](2026-11-debugger-redesign.md) 第 4 节。

## 目录

1. [术语](#1-术语)
2. [传输与帧](#2-传输与帧)
3. [消息结构](#3-消息结构)
4. [连接生命周期](#4-连接生命周期)
5. [握手](#5-握手)
6. [VM 与线程](#6-vm-与线程)
7. [断点](#7-断点)
8. [执行控制](#8-执行控制)
9. [暂停后取数据](#9-暂停后取数据)
10. [求值与修改变量](#10-求值与修改变量)
11. [输出与日志](#11-输出与日志)
12. [类型显示脚本（helpers）](#12-类型显示脚本helpers)
13. [其他命令](#13-其他命令)
14. [错误码](#14-错误码)
15. [能力位](#15-能力位)
16. [连接方式约定](#16-连接方式约定)
17. [时序示例](#17-时序示例)
18. [实现要求](#18-实现要求)
19. [变更记录](#19-变更记录)
20. [采样性能分析（profile）](#20-采样性能分析profile)

---

## 1. 术语

| 词 | 含义 |
| --- | --- |
| IDE | 调试客户端：IntelliJ 插件里的 `debugger-engine`，或测试用的假客户端 |
| runtime | 目标进程里的调试端：`emmy_debugger_core` + 传输层（PC 附加、Android 附加、内嵌模式都一样） |
| VM | 一个 Lua 主状态（`lua_State` 主线程）及其所有协程。一个进程可以有多个 VM |
| thread | VM 内的一个 Lua 线程（主线程或协程） |
| pause | 一次暂停。每次暂停分配新的 `pauseId`，继续运行后它和它派生出的 `frameId` / `ref` 全部失效 |
| chunkName | Lua 里函数的 `source` 字段（`@Content/Script/UI/BagPanel.lua`、`=[C]`、`[string "..."]` 等），原样传输 |

整数约定（`pauseId` 按 VM 分别计数，其余 id 在各自范围内唯一，见对应章节）：协议里所有 id（`id`、`seq`、`vmId`、`pauseId`、`frameId`、`ref`、`revision`）都是 **1 ≤ x < 2^53** 的整数（JSON 能精确表示，Kotlin 用 `Long`），0 表示「无/全部」。`threadId` 是不透明字符串。

---

## 2. 传输与帧

传输层只要求**可靠、有序的字节流**：TCP、`adb forward` 到 `localabstract`、本机回环都一样。不再支持命名管道。

### 2.1 帧头

```
 0       1       2       3       4       5       6       7
+-------+-------+-------+-------+-------+-------+-------+---------------- ...
| 0xE3  | 0x01  | type  |        length (uint32, 大端)   | payload (length 字节)
+-------+-------+-------+-------+-------+-------+-------+---------------- ...
```

| 字段 | 说明 |
| --- | --- |
| magic | 固定 `0xE3 0x01`。读到别的值立即断开（见 2.3） |
| type | 低 4 位是消息种类：`1` request、`2` response、`3` event；`0x10` 位表示 payload 是 CBOR（未置位为 UTF-8 JSON）；其余位必须为 0，收到非 0 视为协议错误 |
| length | payload 字节数，**不含** 7 字节帧头，大端 |
| payload | 一个 JSON 对象（或等价的 CBOR map），结构见第 3 节 |

- **最大帧**：16 MiB（`16 * 1024 * 1024`）。超出的帧不读 payload，直接断开连接。握手里双方可以声明更小的 `maxFrameBytes`，发送方不得超过对方声明的值。
- **空 payload**：`length = 0` 视为协议错误。
- 接收方按 `length` 一次读完整帧再解析，不扫描分隔符。

### 2.2 编码

- 默认 JSON（UTF-8，无 BOM）。
- 双方在握手中都声明了 `cbor` 能力后，**发送方可以**对之后的任意帧使用 CBOR（RFC 8949，map 键为文本串），并置 `0x10` 位；接收方必须两种都能解。每帧自描述，没有「切换时刻」，抓包和中途断线都不会错位。
- `hello` / `welcome` 必须用 JSON。
- 二进制数据（暂未使用）在 JSON 中用 base64 字符串，在 CBOR 中用字节串。

### 2.3 与旧版本的识别

- runtime 收到的首个字节不是 `0xE3`（旧 IDE 发的是 v1 文本行 `"<数字>\n{...}\n"`）：回一行纯文本 `EMMY_PROTOCOL_V3_REQUIRED\n` 然后断开，旧 IDE 会在日志里看到原因。
- IDE 收到的首个字节不是 `0xE3`：提示「目标进程里的调试 runtime 版本过旧，请升级到 3.x」。

---

## 3. 消息结构

### 3.1 request（type = 1，只有 IDE → runtime）

```json
{ "id": 7, "command": "stackTrace", "args": { "vmId": 1, "pauseId": 3, "start": 0, "count": 20 } }
```

- `id`：IDE 分配，连接内单调递增，不复用。
- `args`：可省略，等价于 `{}`。未知字段**必须忽略**（便于加字段）。
- runtime 不向 IDE 发 request；需要 IDE 知道的事一律用 event。

### 3.2 response（type = 2，只有 runtime → IDE）

```json
{ "id": 7, "ok": true, "body": { "frames": [ ... ], "total": 12 } }
{ "id": 8, "ok": false, "error": { "code": "STALE_PAUSE", "message": "这次暂停已经结束（游戏已继续运行），请等下一次 paused 事件后再取栈。" } }
```

- 每个 request **恰好一个** response，`id` 对应。runtime 可以乱序回复（例如求值比取栈慢）。
- `ok = true` 时有 `body`（可为 `{}`）；`ok = false` 时有 `error`。
- `error.code` 是稳定的大写错误码（第 14 节），程序按它判断；`error.message` 是给人看的中文说明，写清楚怎么办，IDE 和 MCP 可原样展示。可选 `error.data` 放结构化细节。

### 3.3 event（type = 3，只有 runtime → IDE）

```json
{ "seq": 41, "event": "paused", "body": { ... } }
```

- `seq`：runtime 分配，连接内从 1 开始单调递增，IDE 用它检测丢失和排序。
- **顺序保证**：同一连接上，某命令的 response 发出前产生的事件，一定排在该 response 之前。例：`continue` 的 response 一定在对应的 `resumed` 事件**之后**到达；`setBreakpoints` 的 response 之前已发出的 `breakpointStatus` 不会晚于它。
- 未知事件**必须忽略**。

---

## 4. 连接生命周期

```text
建连 → IDE 发 hello → runtime 回 welcome（握手完成）
     → IDE 发 loadHelpers / setOptions / setBreakpoints（任意顺序，可省）
     → IDE 发 configurationDone（释放 waitIDE、处理 stopOnEntry）
     → 正常调试 …
     → IDE 发 disconnect 或任一方关连接
```

- **推荐顺序**：`hello` → welcome → `loadHelpers` → `setOptions` → `setBreakpoints { replaceAll: true }`（全量）→ `configurationDone`。runtime 在 `configurationDone` 之前**不放行** `waitIDE`，保证启动即停和第一个断点不丢。
- 握手完成前，除 `hello` 外的任何 request 回 `NOT_READY`；握手 5 秒内未完成，runtime 断开。
- **TCP 方向和协议角色无关**：「IDE 连 runtime」和「runtime 连 IDE」两种方式建连后都由 IDE 先发 `hello`。
- 同一时刻一个 runtime 只接受**一个**已握手的 IDE 连接；第二个连接握手时回 `BUSY` 并断开（附加场景下不会出现两个 IDE 抢同一进程）。
- **心跳**：IDE 每 5 秒发一次 `ping`；runtime 15 秒没收到任何帧视为断线。IDE 连续 3 次 `ping` 无回应（各 5 秒超时）视为断线。
- **断线时 runtime 的行为**（不管是主动 `disconnect` 还是异常断开）：清空全部断点和 runToLine、关闭单步、恢复所有暂停的 VM、卸掉行钩子（只保留 HookMux 里宿主自己注册的钩子），回到「未连接」状态等下一次连接。游戏不能因为 IDE 掉线而卡住。
- **重连**：新连接是全新会话（新的 `sessionId`、`seq` 从 1 开始），IDE 重新下发断点和选项。VM 的 `vmId` 在 runtime 进程内稳定，重连后不变，IDE 可以据此保留 UI 状态。

---

## 5. 握手

### 5.1 `hello`

```json
{
  "id": 1, "command": "hello",
  "args": {
    "protocol": 3,
    "revision": 0,
    "client": { "name": "intellij-emmylua", "version": "3.0.0" },
    "token": "8F3K-29QX",
    "caps": ["cbor", "luaEval", "valuePath", "setVariable", "exceptions", "output"],
    "maxFrameBytes": 16777216
  }
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `protocol` | 是 | 固定 `3`。不是 3 时 runtime 回 `PROTOCOL_MISMATCH` 并断开 |
| `revision` | 否 | 客户端实现的协议修订号（本文为 0）。只用于日志和兼容判断，不导致握手失败 |
| `client` | 是 | 客户端名称和版本，写进 runtime 日志 |
| `token` | 视情况 | runtime 配置了 token（局域网监听必须有、附加时由 IDE 生成并注入）时必填；比较用常量时间；不匹配回 `AUTH_FAILED` 并断开。同一来源连续 3 次失败后 runtime 拒绝该来源 60 秒 |
| `caps` | 否 | IDE 希望使用的能力。最终生效的是双方交集 |
| `maxFrameBytes` | 否 | IDE 能接收的最大帧，默认 16 MiB |

### 5.2 `hello` 的 response（即 welcome）

```json
{
  "id": 1, "ok": true,
  "body": {
    "protocol": 3,
    "revision": 0,
    "sessionId": "c1f0a9e2",
    "runtime": { "name": "emmy_debugger_core", "version": "3.0.0", "build": "a1b2c3d" },
    "lua": { "version": "5.4", "jit": false, "abi": "lua54" },
    "platform": "windows", "arch": "x64",
    "process": { "pid": 12345, "name": "MyGame-Win64-DebugGame.exe", "package": null, "buildId": null },
    "caps": ["cbor", "valuePath", "luaEval", "setVariable", "logpoint", "hitCondition", "condition",
             "capture", "runToLine", "exceptions", "coroutines", "output", "helpers", "sourceHash", "hookMux"],
    "maxFrameBytes": 16777216,
    "vms": [ { "vmId": 1, "generation": 1, "name": "GameLua", "state": "running", "luaVersion": "5.4", "sourceEpoch": 1 } ]
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `sessionId` | 本次连接的不透明 id，日志关联用 |
| `runtime` | runtime 实现和版本 |
| `lua` | 进程里检测到的 Lua。`version` 取值 `5.1` / `5.2` / `5.3` / `5.4` / `5.5`；`jit = true` 表示 LuaJIT（此时 `version` 为 `5.1`）；`abi` 是 runtime 识别出的 ABI 标识，仅供诊断 |
| `platform` | `windows` / `linux` / `macos` / `android` / `ios` |
| `arch` | `x86` / `x64` / `arm64` / `arm` |
| `caps` | **双方交集**，即本连接实际可用的能力 |
| `process` | `pid`、进程名；Android 上 `package` 为包名、`buildId` 为宿主 `libUnreal.so` 等主模块的 GNU Build ID（hex），IDE 用来核对本地符号文件；其他平台为 `null` |
| `vms` | 握手时已存在的 VM 快照（结构同 6.1 的 `VmInfo`）。**重连后 IDE 以它为准重建 VM 表**，之后的变化走事件 |

握手完成后 IDE 只依据 `caps` 决定开放哪些 UI 和 MCP 工具（例如没有 `luaEval` 就不开放任意表达式求值）。

---

## 6. VM 与线程

### 6.1 `VmInfo`

```json
{ "vmId": 1, "generation": 1, "name": "GameLua", "state": "running", "luaVersion": "5.4", "sourceEpoch": 1,
  "engine": "UnLua", "context": "PIE-0", "mainThreadId": "t1" }
```

| 字段 | 说明 |
| --- | --- |
| `vmId` | runtime 进程内唯一、稳定，VM 关闭后不复用 |
| `generation` | 同一 `lua_State` 地址被复用时的注册代次（诊断用；`vmId` 已经保证不复用） |
| `name` | 显示名（宿主通过 C ABI 设置；没有则 runtime 生成 `Lua VM #<n>`） |
| `state` | `running` / `paused` / `closing` |
| `sourceEpoch` | 宿主重置上下文（PIE 重启、热重载）时 +1。IDE 用它判断按源码缓存的东西（断点验证、源码映射）是否需要重新确认 |
| `engine` / `context` | 可选，宿主提供的引擎名和上下文（如 PIE 实例） |
| `mainThreadId` | 主线程的 `threadId` |

### 6.2 事件

| 事件 | body | 说明 |
| --- | --- | --- |
| `vmAdded` | `VmInfo` | 新 VM 注册（附加后发现或宿主调用 `Emmy_RegisterLuaVm`） |
| `vmStateChanged` | `{ vmId, state, sourceEpoch, reason }` | 状态或 `sourceEpoch` 变化。`reason` 例：`contextReset`、`hotReload` |
| `vmClosed` | `{ vmId, reason }` | VM 关闭。该 VM 的暂停、frame、ref 全部失效；IDE 移除其所有 UI |

`paused` / `resumed` 也会让 `VmInfo.state` 变化，但**不另发** `vmStateChanged`。

### 6.3 `threadId`

- 不透明字符串（实现可用协程地址的 hex，例如 `"t7ffde0a1b2c0"`）。只保证在**协程存活期间**唯一。
- 主线程的 `threadId` 在 VM 存活期间固定。

### 6.4 `vms`

```json
{ "id": 3, "command": "vms" }
→ { "id": 3, "ok": true, "body": { "vms": [ VmInfo, ... ] } }
```

### 6.5 `threads`（能力 `coroutines`）

```json
{ "id": 4, "command": "threads", "args": { "vmId": 1 } }
→ { "ok": true, "body": { "threads": [ { "threadId": "t1", "name": "main", "status": "running" },
                                       { "threadId": "t7ffde0a1b2c0", "status": "suspended" } ] } }
```

- `status`：`running`（正在执行/当前暂停所在）/ `suspended` / `normal`（resume 了别人）/ `dead`。
- 只在 VM 暂停时可用，否则回 `NOT_PAUSED`。runtime 通过遍历 registry 和已知协程得到列表，可能不完整（未被引用的协程会被 GC）。

---

## 7. 断点

### 7.1 断点对象 `Breakpoint`

```json
{
  "id": "bp-12",
  "source": { "path": "D:/Game/Content/Script/UI/BagPanel.lua", "chunkName": "@Content/Script/UI/BagPanel.lua", "sha256": "9f2c…" },
  "line": 120,
  "condition": "index > 5",
  "hitCondition": ">= 3",
  "logMessage": "click {index} {item.id}",
  "capture": ["self.items", "index"],
  "autoContinue": false,
  "vmId": 0
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | IDE 分配的不透明字符串，连接内唯一。runtime 在事件里原样带回 |
| `source.path` | 是 | IDE 本机文件路径，统一用 `/` 分隔 |
| `source.chunkName` | 否 | IDE 已知（学习过或用户配置了映射）的 chunkName。给了就**只**按它精确匹配 |
| `source.sha256` | 否 | 本地文件内容的 SHA-256（小写 hex）。runtime 有宿主登记的哈希且不一致时，断点状态为 `mismatch`（仍然生效，只是提示） |
| `line` | 是 | 1 起的行号 |
| `condition` | 否 | Lua 表达式，在**断点所在帧**里求值，结果为真才算命中。runtime 侧执行，不往返 IDE。语法错误时断点状态为 `invalid` |
| `hitCondition` | 否 | 命中次数条件，见 7.3。先判断 `condition`，满足后才计数 |
| `logMessage` | 否 | 日志断点：命中时输出一条 `output`（`category = "logpoint"`），`{expr}` 会被替换成求值结果（`{{` / `}}` 转义），**不暂停** |
| `capture` | 否 | 能力 `capture`。命中时按 valuePath 语法（10.2）采集这些值，放进 `breakpointHit` 事件 |
| `autoContinue` | 否 | 为 `true` 时命中不暂停，只发 `breakpointHit`（这就是原 CLI 的 Probe）。有 `logMessage` 时视为 `true` |
| `vmId` | 否 | 只在这个 VM 生效；0 或省略表示所有 VM |

### 7.2 匹配规则（chunkName ↔ 断点）

runtime 对每个 chunk（按 `source` 字符串指针缓存）计算一次匹配结果，断点修订号变化时重算：

1. 规范化：去掉开头 `@`；`\` 换成 `/`；去掉 `./`，折叠 `a/../`；连续 `/` 合并。Windows 和 Android 目标上比较时**忽略大小写**（welcome 后 IDE 可用 `setOptions.caseSensitive` 覆盖）。
2. 断点给了 `source.chunkName`：规范化后与 chunk 精确相等才匹配。
3. 否则按**路径段后缀**匹配：chunk 的路径段是断点路径段的后缀（常见：chunk 是相对路径 `Content/Script/UI/BagPanel.lua`，断点是绝对路径），或断点路径段是 chunk 路径段的后缀（设备上是绝对路径）。比较前两边都去掉扩展名 `.lua` / `.luac` / `.txt` / `.bytes`。
4. 一个 chunk 同时匹配多个不同 `source.path` 时，取匹配段数最多的那个；仍并列时都不匹配，并对这些断点发 `breakpointStatus { state: "ambiguous" }`，提示用户配置 chunkName 映射。
5. `=[C]`、`=?` 等非文件源永不匹配；`[string "..."]`（`load` 的代码串）只能用 `source.chunkName` 精确匹配。
6. 不带 `@` 的 chunk 也可能是文件（UnLua 及其改过的分支常直接用路径或模块名当 chunkname），满足其一就按文件匹配：单行、不超过 1024 字，并且
   - 以 `.lua` / `.luac` / `.txt` / `.bytes` 结尾，不含 `(){}=;"'`，没有路径分隔符时也不含空白；或
   - 含 `/` 或 `\`，不含代码里才会出现的符号（`(){}=;"'`、制表符、`,+*<>[]#~`）；或
   - 是模块名：只有字母、数字、`_` 和点（`Tutorials.BP_CharacterBase`），不是关键字。规范化时点换成 `/`，按 `require` 的规则当路径比后缀。

断点下发 5 秒后，如果某个 VM 还没有任何 chunk 匹配上这批断点，runtime 发一次 `output { category: "runtime", level: "warn", vmId }`，正文列出这段时间执行过的 chunk 原文（去重，最多 20 个）；一个都没执行过时说明钩子收到的事件数，或者断点还没在这个 VM 上生效。用于区分「路径对不上」和「代码没跑 / 钩子没挂上」。

### 7.3 `hitCondition` 语法

| 写法 | 含义 |
| --- | --- |
| `5` 或 `== 5` | 第 5 次命中时停 |
| `>= 5` / `> 5` | 第 5 次（第 6 次）及以后每次都停 |
| `% 3` | 每 3 次停一次（第 3、6、9…次） |

计数按「断点 × VM」独立；`setBreakpoints` 里该断点被 upsert（即使内容不变）时清零。语法错误时断点状态为 `invalid`。

### 7.4 `setBreakpoints`

增量更新。runtime 内部把整张断点表重建为不可变索引后原子发布（热路径无锁），所以批量提交比逐条提交更好。

```json
{ "id": 10, "command": "setBreakpoints",
  "args": { "revision": 5, "replaceAll": false, "upserts": [ Breakpoint, ... ], "removes": ["bp-3", "bp-4"] } }
→ { "id": 10, "ok": true, "body": { "revision": 5,
      "breakpoints": [ { "id": "bp-12", "state": "pending" }, { "id": "bp-13", "state": "invalid", "message": "条件表达式语法错误：…" } ] } }
```

| 字段 | 说明 |
| --- | --- |
| `revision` | IDE 分配，**每个连接内**单调递增（新连接重新从 1 开始）。runtime 收到不大于当前值的 revision 回 `INVALID_ARGS` |
| `replaceAll` | `true` 时先清空全部断点再应用 `upserts`（重连后首次下发用这个），`removes` 被忽略 |
| `upserts` | 新增或整体替换（按 `id`） |
| `removes` | 要删的 `id`；不存在的 id 忽略 |

response 的 `breakpoints` 列出本次 upsert 的每个断点的**初始**状态：`pending`（已接受，还没在任何 VM 里匹配到代码）或 `invalid`（条件/命中条件语法错误等，`message` 说明原因）。之后的验证结果走事件。

### 7.5 事件 `breakpointStatus`

```json
{ "seq": 50, "event": "breakpointStatus",
  "body": { "id": "bp-12", "vmId": 1, "state": "verified", "chunkName": "@Content/Script/UI/BagPanel.lua", "line": 120 } }
```

| `state` | 含义 |
| --- | --- |
| `verified` | 已在该 VM 中匹配到 chunk。`line` 是实际生效行（runtime 可把落在空行/注释上的断点挪到下一个有效行，不能挪时保持原行） |
| `mismatch` | 匹配到 chunk，但宿主登记的内容哈希与 `source.sha256` 不一致。仍然生效 |
| `ambiguous` | 7.2 第 4 条 |
| `invalid` | 运行中发现的错误（例如条件求值出错）；`message` 说明 |

- runtime 在某个 VM **第一次**执行到某 chunk 中的函数时做匹配（钩子只在有断点时开启，所以「没执行过的文件」上的断点会一直是 `pending`，这是正常的）。
- 同一 `(id, vmId, state)` 不重复发送。`sourceEpoch` 变化后 runtime 重新匹配并重新发送。

### 7.6 事件 `breakpointHit`

`autoContinue` 断点、日志断点每次命中时发送（暂停的断点不发，暂停信息在 `paused` 里）。

```json
{ "seq": 61, "event": "breakpointHit",
  "body": { "id": "bp-12", "vmId": 1, "threadId": "t1", "hitCount": 3, "line": 120, "timeMs": 1763295300123,
            "captures": [ { "expression": "index", "value": "7", "type": "number" },
                          { "expression": "self.items", "value": "table: 0x1a2b", "type": "table", "error": null } ] } }
```

- `captures` 的每项是**一层**快照（不带 `ref`，不能再展开），大 table 只给 `value` 摘要。
- 高频命中时 runtime 可以合并或丢弃：每个断点每秒最多发 100 条，超出部分计数后在下一条里给 `dropped: <n>`。

---

## 8. 执行控制

### 8.1 通用约定

- 控制命令按 VM 路由。**VM 之间互不影响**：一个 VM 暂停时其他 VM 照常运行（如果它们在同一个 OS 线程上，自然也会被阻塞，这是宿主决定的）。
- 对暂停中的 VM 发命令必须带当前 `pauseId`；不匹配回 `STALE_PAUSE`（例如用户已在 UI 上继续，Agent 的旧请求才到）。
- 所有命令立即回 response，不等待下一次暂停。

### 8.2 `continue`

```json
{ "command": "continue", "args": { "vmId": 1, "pauseId": 3 } }
{ "command": "continue", "args": { "vmId": 0 } }
```

- 指定 `vmId` + `pauseId`：继续该 VM。VM 没暂停回 `NOT_PAUSED`。
- `vmId = 0`（或省略）：继续所有暂停中的 VM，忽略 `pauseId`。

### 8.3 `pause`

```json
{ "command": "pause", "args": { "vmId": 1 } }
```

- `vmId = 0` 表示所有 VM。已经暂停的 VM 忽略。
- 请求在 VM 下一次执行 Lua 代码时生效（无断点时靠稀疏的 COUNT 钩子，目标延迟见设计文档 5.3）。生效时发 `paused { reason: "pause" }`。VM 一直不执行 Lua（例如游戏在加载）时不会暂停，IDE 可以再次发送，无副作用。

### 8.4 `stepIn` / `stepOver` / `stepOut`

```json
{ "command": "stepOver", "args": { "vmId": 1, "pauseId": 3, "threadId": "t1" } }
```

- `threadId` 省略时为本次暂停所在线程。
- 语义（以本次暂停的栈深为基准，**只跟踪发起单步的那个线程**）：
  - `stepIn`：在任意更深或同层或更浅的帧里执行到**新的一行**就停。进入 C 函数不停，C 函数回调 Lua 时停在 Lua 第一行。
  - `stepOver`：在**同层或更浅**的帧里执行到新的一行就停。
  - `stepOut`：回到**更浅**的帧并执行到新的一行就停。
  - 协程：被跟踪的线程 `yield` 后，单步在它被 `resume` 回来时继续；在被跟踪线程里 `resume` 另一个协程时，`stepIn` 停在那个协程的下一行，`stepOver` 不进去。
  - 尾调用：`stepOver` 把尾调用视为同层（不会因为尾调用而「跳出」）。
- 单步过程中遇到断点：按断点停（`reason: "breakpoint"`），单步取消。
- 停下时发 `paused { reason: "step" }`。

### 8.5 `runToLine`（能力 `runToLine`）

```json
{ "command": "runToLine", "args": { "vmId": 1, "pauseId": 3, "source": { "path": "D:/Game/.../BagPanel.lua", "chunkName": null }, "line": 140 } }
```

- runtime 设置一个只属于该 VM 的临时断点（匹配规则同 7.2）然后继续运行。到达时发 `paused { reason: "runToLine" }`。
- **任何一次该 VM 的暂停**（包括先命中了别的断点）都会清除这个临时断点。
- VM 没暂停时也可以发（`pauseId` 省略），相当于「设临时断点」。

### 8.6 事件 `paused`

```json
{ "seq": 70, "event": "paused",
  "body": {
    "vmId": 1, "pauseId": 4, "threadId": "t1",
    "reason": "breakpoint",
    "breakpointIds": ["bp-12"],
    "description": "命中断点 bp-12",
    "top": { "frameId": 101, "name": "BagPanel:OnItemClick", "chunkName": "@Content/Script/UI/BagPanel.lua", "line": 120 },
    "exception": null
  } }
```

| `reason` | 触发 |
| --- | --- |
| `breakpoint` | 行断点（`breakpointIds` 列出本行命中的、会暂停的断点） |
| `step` | 单步完成 |
| `pause` | `pause` 命令 |
| `runToLine` | 8.5 |
| `entry` | `setOptions.stopOnEntry` 且 `configurationDone` 后第一次执行 Lua |
| `exception` | 出错即停（`exception` 字段为 `{ "message": "...", "traceback": "...", "uncaught": true }`） |
| `hostBreak` | 宿主或 Lua 代码调用了 `emmy.breakHere()` / `Emmy_BreakHere` |

- `top` 就是栈顶帧，结构同 9.1 的 `Frame`，让 IDE 不等 `stackTrace` 就能高亮当前行。
- 一个 VM 同一时刻最多一个暂停。`pauseId` 在**每个 VM 内**单调递增（从 1 开始，重连不清零）；`frameId` / `ref` 在连接内全局唯一。

### 8.7 事件 `resumed`

```json
{ "seq": 71, "event": "resumed", "body": { "vmId": 1, "pauseId": 4, "by": "continue" } }
```

`by`：`continue` / `step` / `runToLine` / `disconnect` / `host`（宿主强制恢复，例如 VM 正在关闭）。

---

## 9. 暂停后取数据

所有命令都要求目标 VM 处于暂停，否则回 `NOT_PAUSED`。`frameId` 和 `ref` 只在发出它们的那次暂停内有效，暂停结束后使用回 `STALE_REFERENCE`。

### 9.1 `stackTrace`

```json
{ "command": "stackTrace", "args": { "vmId": 1, "pauseId": 4, "threadId": "t1", "start": 0, "count": 20 } }
→ { "ok": true, "body": { "frames": [ Frame, ... ], "total": 12 } }
```

`Frame`：

```json
{ "frameId": 101, "level": 0, "threadId": "t1", "name": "BagPanel:OnItemClick", "what": "Lua",
  "chunkName": "@Content/Script/UI/BagPanel.lua", "line": 120, "lineDefined": 112, "lastLineDefined": 140 }
```

- `threadId` 省略时为暂停所在线程。runtime **可以**把**父协程链**（resume 了当前协程的线程）的帧接在后面，这些帧的 `threadId` 不同；IDE 不能假设一定有（`emmy_debugger_core` 3.0 不追加，父协程的栈用 `threads` + `stackTrace { threadId }` 查看）。
- `what`：`Lua` / `C` / `main` / `tail`。C 帧 `chunkName` 为 `=[C]`、`line` 为 -1。
- `total` 是总帧数（含父协程链）。`count` 默认 20、最大 200。

### 9.2 `scopes`

```json
{ "command": "scopes", "args": { "frameId": 101 } }
→ { "ok": true, "body": { "scopes": [
      { "name": "locals",   "ref": 201, "count": 4 },
      { "name": "upvalues", "ref": 202, "count": 2 },
      { "name": "varargs",  "ref": 203, "count": 1 },
      { "name": "globals",  "ref": 204 } ] } }
```

- `globals` 是该帧的 `_ENV`（5.2+）或函数环境（5.1/LuaJIT），**不给 `count`**（可能很大）。
- 没有内容的作用域（例如无 varargs）不列出。

### 9.3 `variables`

```json
{ "command": "variables", "args": { "ref": 201, "start": 0, "count": 100, "filter": null } }
→ { "ok": true, "body": { "variables": [ Variable, ... ], "total": 4 } }
```

`Variable`：

```json
{ "name": "item", "nameType": "string", "value": "{id=1003, count=2, …}", "type": "table",
  "typeName": "ItemData", "ref": 305, "namedCount": 2, "indexedCount": 0, "evaluateName": "item" }
```

| 字段 | 说明 |
| --- | --- |
| `name` | 显示用的键。字符串键原样；数字键 `"[3]"`；其他类型键 `"[table: 0x…]"` |
| `nameType` | 键的 Lua 类型名 |
| `value` | 显示用的值摘要，最长 1024 字节（超出截断并以 `…` 结尾）。table 摘要最多列前 5 项 |
| `type` | Lua 类型名：`nil` `boolean` `number` `string` `table` `function` `userdata` `lightuserdata` `thread` |
| `typeName` | 可选，更具体的类型名（helper 给的，如 `FVector`、`ItemData`；或 metatable 的 `__name`） |
| `ref` | 可展开时 > 0，传给 `variables` 取子项；叶子为 0 |
| `namedCount` / `indexedCount` | 可选，子项数量（table 的哈希部分 / 数组部分），让 IDE 决定分页 |
| `evaluateName` | 可选，能在 valuePath 语法下重新取到这个值的表达式（用于「添加到监视」和 `setVariable`） |

- `filter`：`named` / `indexed` / 省略（全部）。table 的子项顺序：先数组部分（按下标），再哈希部分（按 `next` 顺序），最后是 `(metatable)` 伪项（`name` 为 `"(metatable)"`）。
- `count` 默认 100、最大 1000。`total` 是满足 `filter` 的子项总数。
- 字符串值：`value` 是带引号、转义后的摘要；要看完整长字符串用 `evaluate` 并给 `maxLength`。
- 有 helper（第 12 节）时，userdata / table 的 `value`、`typeName` 和子项由 helper 决定。

---

## 10. 求值与修改变量

### 10.1 `evaluate`

```json
{ "command": "evaluate", "args": { "frameId": 101, "expression": "self.items[index].id", "mode": "valuePath", "context": "watch", "maxLength": 1024 } }
→ { "ok": true, "body": { "value": "1003", "type": "number", "ref": 0, "typeName": null } }
```

| 参数 | 说明 |
| --- | --- |
| `frameId` | 求值所在帧；省略时：VM 暂停则为栈顶，VM 未暂停回 `NOT_PAUSED`（v3.0 不支持运行中求值） |
| `expression` | 表达式 |
| `mode` | `valuePath`（默认）或 `lua` |
| `context` | `watch` / `repl` / `hover` / `clipboard`，只影响默认 `maxLength` 和日志 |
| `maxLength` | 返回 `value` 的最大字节数，默认 1024，最大 1 MiB |

- `valuePath` 模式（能力 `valuePath`）：只做**取值**，不执行任意代码，不触发 `__index` 以外的元方法（`__index` 是否触发由 `setOptions.valuePathInvokeIndex` 控制，默认不触发）。语法见 10.2。Agent 默认用它。
- `lua` 模式（能力 `luaEval`）：任意 Lua 表达式或语句（以 `return` 开头的语句块也可），在该帧的局部变量、upvalue、`_ENV` 下执行。runtime 在 `setOptions.allowLuaEval = false` 时回 `EVAL_FORBIDDEN`。
- 求值出错回 `ok: false`，`code = EVAL_ERROR`，`message` 是 Lua 错误信息。
- 求值超过 `setOptions.evalTimeoutMs`（默认 1000 ms，用 COUNT 钩子打断）回 `TIMEOUT`。

### 10.2 valuePath 语法

```
path    := root ( '.' ident | '[' key ']' )*
root    := ident                      -- 依次查局部变量、upvalue、_ENV
key     := integer | '"' string '"' | "'" string "'"
ident   := [A-Za-z_][A-Za-z0-9_]*
```

限制（与现有 `restricted_eval` 相同）：表达式 ≤ 4096 字节、≤ 32 段。不合法时回 `INVALID_ARGS`，`error.data.reason` 为解析器的稳定错误码。

### 10.3 `setVariable`（能力 `setVariable`）

```json
{ "command": "setVariable", "args": { "ref": 201, "name": "index", "value": "8", "mode": "lua" } }
→ { "ok": true, "body": { "value": "8", "type": "number", "ref": 0 } }
```

- `ref` + `name`：修改该容器（locals/upvalues/table）里名为 `name` 的项。`name` 用 `variables` 返回的显示名（`"[3]"` 表示数组下标 3）。
- `value` 是在**该容器所在帧**求值的表达式；`mode` 为 `lua` 时需要 `luaEval` 能力并受 `allowLuaEval` 约束，`valuePath` 时只能是字面量（数字、带引号字符串、`true`/`false`/`nil`）或 valuePath。
- 修改后，本次暂停里之前拿到的 `ref` 仍然有效，但它们的缓存值可能过时，IDE 应刷新可见部分。

---

## 11. 输出与日志

### 11.1 事件 `output`（能力 `output`）

```json
{ "seq": 80, "event": "output", "body": { "category": "stdout", "text": "hello\n", "vmId": 1, "chunkName": "@main.lua", "line": 3 } }
```

| `category` | 来源 |
| --- | --- |
| `stdout` | Lua 的 `print`（runtime 在 `setOptions.captureOutput = true` 时替换 VM 的 `print`，仍会调用原 `print`） |
| `stderr` | 未被捕获的 Lua 错误（与 `exceptions` 无关，总是转发） |
| `logpoint` | 日志断点 |
| `runtime` | runtime 自己的诊断日志（`level` 字段：`debug`/`info`/`warn`/`error`） |
| `host` | 宿主通过 C ABI `Emmy_Log` 送来的日志 |

- `text` 单条最长 64 KiB，超出截断。runtime 侧限流：每秒最多 1000 条，超出部分丢弃并在下一条带 `dropped: <n>`。

---

## 12. 类型显示脚本（helpers）

### 12.1 `loadHelpers`（能力 `helpers`）

```json
{ "command": "loadHelpers", "args": {
    "scripts": [
      { "name": "emmyHelper",        "source": "-- 文件内容 …" },
      { "name": "tool/emmyLog",      "source": "…" },
      { "name": "emmyHelper_ue",     "source": "…" }
    ],
    "main": "emmyHelper" } }
→ { "ok": true, "body": {} }
```

- IDE 发送脚本**内容**，不再发送本机路径（真机上不存在）。`name` 是模块名，`require(name)` 能取到。
- runtime 对每个现有 VM 和之后新加的 VM：把每个脚本以 chunkName `@emmy/<name>.lua` 编译，放进一个**调试器私有**的模块表（不污染宿主的 `package.loaded` / `package.preload`），然后执行 `main`。helper 的环境是 `setmetatable({ require = 私有 require }, { __index = _G, __newindex = _G })`：读写全局仍落到宿主的 `_G`（helper 约定要定义全局 `emmyHelper` / `emmyHelperInit`），只有 `require` 先查私有表，查不到再走宿主的 `require`。
- 重复调用 `loadHelpers` 会替换之前的脚本并在每个 VM 重新执行。
- response 只表示脚本已收下，**立即返回 `{}`**：VM 可能正在跑、要到下一次钩子才执行脚本，不能为此阻塞。
- 加载失败不影响调试，只是失去自定义显示；失败原因通过 `output { category: "runtime", level: "warn" }` 报告（每个 VM 一条）。

### 12.2 helper 契约

沿用现有 `emmyHelper.lua` 的约定，runtime 提供：

- 全局函数 `emmyHelperInit()`：给 `emmyHelper` 表设置 `createNode`。
- `emmyHelper.queryVariable(variable, obj, typeName, depth)` 和可选的 `emmyHelper.queryVariableCustom(...)`：runtime 在生成 table / userdata 的 `Variable` 时调用，helper 填写 `variable.value`、`variable.valueTypeName`，用 `variable:addChild(createNode())` 添加子项。

v3 的懒加载：helper 生成的子项由 runtime 缓存在本次暂停里，`variables` 分页读取；helper 不需要感知分页。`depth` 固定传 1。

---

## 13. 其他命令

### 13.1 `setOptions`

任何时候都可以发，只改给出的字段。

```json
{ "command": "setOptions", "args": {
    "stopOnEntry": false,
    "exceptions": "none",
    "captureOutput": true,
    "allowLuaEval": true,
    "valuePathInvokeIndex": false,
    "evalTimeoutMs": 1000,
    "caseSensitive": null,
    "pauseLatencyMs": 20 } }
```

| 选项 | 默认 | 说明 |
| --- | --- | --- |
| `stopOnEntry` | `false` | `configurationDone` 后第一次执行 Lua 时暂停（`reason: "entry"`） |
| `exceptions` | `none` | 能力 `exceptions`（runtime 没声明这个能力时，传 `none` 以外的值返回 `UNSUPPORTED`；`emmy_debugger_core` 已声明；「没被接住」要宿主在自己的错误处理函数里通知核心，见原生核心 README「出错即停」）。`none` / `uncaught`（错误没有被 `pcall`/`xpcall` 接住时暂停）/ `all`（任何错误都暂停，包括被 `pcall` 接住的） |
| `captureOutput` | `false` | 转发 `print`（11.1） |
| `allowLuaEval` | `true` | `false` 时 `mode: "lua"` 的求值和 `setVariable` 回 `EVAL_FORBIDDEN`（MCP 未授权任意求值时由 IDE 设为 `false`） |
| `valuePathInvokeIndex` | `false` | valuePath 是否触发 `__index` |
| `evalTimeoutMs` | `1000` | 求值超时 |
| `caseSensitive` | `null` | 路径匹配是否区分大小写；`null` 表示按平台默认（Windows/Android 不区分） |
| `pauseLatencyMs` | `20` | Break All 的目标响应延迟；runtime 据此调整无断点时的 COUNT 钩子间隔 |

### 13.2 `configurationDone`

IDE 完成初始配置（helpers、选项、断点）后发送。runtime 在此之后：释放宿主的 `waitIDE` 等待；若 `stopOnEntry` 则在下一次执行 Lua 时暂停。重复发送无副作用。

### 13.3 `cancel`

```json
{ "command": "cancel", "args": { "requestId": 42 } }
```

尽力取消一个还没回复的请求（主要是耗时的求值）。被取消的请求回 `CANCELLED`；已完成或不存在的请求忽略。`cancel` 自己总是回 `ok: true`。

### 13.4 `ping`

```json
{ "command": "ping" } → { "ok": true, "body": { "timeMs": 1763295300123 } }
```

### 13.5 `disconnect`

```json
{ "command": "disconnect", "args": { "terminate": false } }
```

runtime 回 response 后执行第 4 节的断线清理并关闭连接。`terminate: true` 表示同时请求宿主退出进程（能力 `terminate`，宿主没提供退出回调时忽略）。

### 13.6 事件 `terminated`

runtime 即将停止服务（进程退出、宿主调用 `Emmy_Stop`）时尽力发送：`{ "reason": "processExit" }`。

---

## 14. 错误码

| 码 | 含义 | 典型 message |
| --- | --- | --- |
| `NOT_READY` | 握手未完成 | 还没有完成握手，请先发送 hello。 |
| `PROTOCOL_MISMATCH` | `protocol` 不是 3 | 调试 runtime 只支持协议 v3，请升级 IDE 插件或 runtime 到同一版本。 |
| `AUTH_FAILED` | token 不对 | 配对码不正确，请在设备日志/屏幕上核对配对码后重试。 |
| `BUSY` | 已有其他 IDE 连接 | 这个进程已经被另一个调试器连接，先断开那边再试。 |
| `UNKNOWN_COMMAND` | 命令不认识 | runtime 不支持命令 xxx，请升级 runtime。 |
| `INVALID_ARGS` | 参数缺失或格式错 | 参数 line 必须是正整数。 |
| `UNSUPPORTED` | 能力未协商 | 这个 runtime 不支持修改变量（缺少能力 setVariable）。 |
| `VM_NOT_FOUND` | vmId 不存在或已关闭 | Lua VM 1 已经关闭，请刷新 VM 列表。 |
| `NOT_PAUSED` | 需要暂停但没暂停 | Lua VM 1 正在运行，先暂停或等断点命中。 |
| `STALE_PAUSE` | `pauseId` 不是当前暂停 | 这次暂停已经结束，请等下一次 paused 事件。 |
| `STALE_REFERENCE` | `frameId` / `ref` 已失效 | 这个栈帧已失效（游戏已继续运行），请重新获取调用栈。 |
| `EVAL_ERROR` | 求值出错 | Lua 错误原文 |
| `EVAL_FORBIDDEN` | 任意求值被关闭 | 当前不允许执行任意 Lua，请在 IDE 设置中开启，或改用 valuePath。 |
| `TIMEOUT` | 求值超时 | 求值超过 1000 ms 被中止，表达式可能有死循环。 |
| `CANCELLED` | 被 `cancel` | 请求已取消。 |
| `INTERNAL` | runtime 内部错误 | 附带诊断信息，请把 runtime 日志附到问题报告里。 |

IDE 收到不认识的错误码按 `INTERNAL` 处理并展示 `message`。

---

## 15. 能力位

| 能力 | 有它才能用 |
| --- | --- |
| `cbor` | CBOR 帧（2.2） |
| `valuePath` | `evaluate` 的 `valuePath` 模式、`capture` |
| `luaEval` | `evaluate` 的 `lua` 模式、`condition`、`logMessage` 里的 `{expr}`（只有 valuePath 时插值按 valuePath 求值，条件断点不可用） |
| `setVariable` | `setVariable` |
| `condition` | 断点 `condition` |
| `hitCondition` | 断点 `hitCondition` |
| `logpoint` | 断点 `logMessage` |
| `capture` | 断点 `capture` 和 `breakpointHit` 的 `captures` |
| `runToLine` | `runToLine` |
| `exceptions` | `setOptions.exceptions` 与 `paused { reason: "exception" }` |
| `coroutines` | `threads` |
| `output` | `output` 事件里的 `stdout` / `logpoint` |
| `helpers` | `loadHelpers` |
| `sourceHash` | 宿主登记了源码哈希，`breakpointStatus` 可能给 `mismatch` |
| `hookMux` | runtime 用 HookMux 与宿主其他钩子共存（诊断信息，IDE 不依赖） |
| `terminate` | `disconnect { terminate: true }` |
| `profiler` | `profile`（第 20 节）。没协商到时 IDE 不发 `profile`、不显示采样按钮 |

能力位只增不改义；新功能加新能力位，旧 IDE 看不到就不会用。

---

## 16. 连接方式约定

| 场景 | 建连方式 | 端口 / 地址 |
| --- | --- | --- |
| PC 附加（注入，Windows） | runtime 监听回环，IDE 连 | `127.0.0.1:<port>`，端口按 PID 派生、以 `emmy_tool` 输出为准；token 由 IDE 每个会话生成，注入时传入。见 16.1 |
| Android 真机附加 | runtime 监听 abstract unix socket，IDE `adb forward tcp:<本机端口> localabstract:emmylua-<pid>` 后连本机端口 | socket 名 `emmylua-<pid>`；token 由 IDE 生成，通过 `attach-agent` 参数传入 |
| 内嵌 runtime（IDE 连 runtime） | runtime 监听，IDE 连 | 宿主指定，默认 `9966`；监听非回环地址时**必须**配置 token |
| 内嵌 runtime（runtime 连 IDE） | IDE 监听，runtime 连 | 宿主指定 `host:port`；建连后仍由 IDE 先发 `hello` |

### 16.1 Windows 本机附加（`emmy_tool` + `emmy_attach.dll`）

1. 选位数：`emmy_tool.exe arch_pid <pid>`，用插件目录下对应的 `x86/` 或 `x64/`。
2. 附加：`emmy_tool.exe attach -p <pid> -dir <位数目录> -dll emmy_hook.dll -auth-token <token> -protocol 3`。`-protocol 3` 时 `-dll` 被忽略，注入的是 `emmy_attach.dll`；token 建议 32 位十六进制，空串表示不校验。
3. 结果：stdout **最后一行**是 JSON。成功 `{"ok":true,"protocol":3,"host":"127.0.0.1","port":N,"pid":P,"reinjected":false}`，退出码 0；失败 `{"ok":false,"protocol":3,"pid":P,"error":"中文原因"}`，退出码非 0。
   - 目标进程还没加载 Lua（例如 UE 编辑器里 Game 还没启动）时不算失败：`emmy_attach.dll` 留在进程里，后台每秒找一次导出 Lua API 的模块，找到后自动挂钩、开始监听 `port`。这时返回 `{"ok":true,...,"waiting":true,"message":"..."}`，退出码 0；IDE 显示「等待 Lua 加载」，持续尝试连 `port`，连上后照常握手。等待期间再次附加只更新 token，仍返回 `waiting:true`。
   - Lua 当 C++ 编译（UnLua 等）时导出名是修饰过的（`?lua_gettop@@...`），按 `@@` 前的名字认。
4. 重复附加（含 IDE 重连）：进程里已有 `emmy_attach.dll` 时不再注入，只换 token，返回 `reinjected:true`。进程里有旧版 `emmy_hook.dll` 时直接失败，提示重启目标进程。
5. 目标进程里：`emmy_attach.dll` 在已加载模块里找导出 `lua_*` 的模块，按导出判断 Lua 版本（`lua_newuserdatauv` → 5.4，`lua_rotate` → 5.3，`lua_callk` → 5.2，否则 5.1；有 `luaJIT_setmode` 时报不支持），加载同目录的 `lua5X/emmy_debugger_core.dll`（dynamic 模式，用目标进程自己的 Lua），挂 `lua_pcall` / `lua_pcallk` / `lua_resume` / `lua_close` 在 Lua 线程上登记 VM，然后监听端口。附加时 Lua 必须已加载。
6. 文件布局（每个位数目录）：`emmy_tool.exe`、`emmy_attach.dll`、`EasyHook.dll`、`lua51/` … `lua54/emmy_debugger_core.dll`。

局域网发现（可选，内嵌模式）：runtime 每 2 秒向 UDP `255.255.255.255:9967` 广播一个 UTF-8 JSON 名片 `{"emmy":3,"name":"MyGame","pid":123,"port":9966,"platform":"android","arch":"arm64","needToken":true}`，不含 token。

---

## 17. 时序示例

### 17.1 附加并命中断点

```text
IDE                                           runtime
 |-- hello {protocol:3, token} -------------->|
 |<------------- response welcome {caps, vms} |
 |-- loadHelpers {scripts, main} ------------>|
 |-- setOptions {captureOutput:true} -------->|
 |-- setBreakpoints {revision:1, replaceAll}->|
 |<--- response {revision:1, [bp-1 pending]} -|
 |-- configurationDone ---------------------->|
 |            (游戏执行到 BagPanel.lua 的函数)
 |<-- event breakpointStatus {bp-1 verified} -|
 |<-- event paused {vmId:1, pauseId:1, top} --|
 |-- stackTrace {vmId:1, pauseId:1} --------->|
 |-- scopes {frameId:101} ------------------->|
 |<------------------------ response frames --|
 |<------------------------ response scopes --|
 |-- variables {ref:201} -------------------->|
 |<--------------------- response variables --|
 |-- stepOver {vmId:1, pauseId:1} ----------->|
 |<-- event resumed {vmId:1, by:step} --------|
 |<------------------------- response ok -----|
 |<-- event paused {pauseId:2, reason:step} --|
```

### 17.2 Probe（采集不停）

```text
 |-- setBreakpoints {revision:2, upserts:[{id:"probe-1", line:88, capture:["self.hp"], autoContinue:true}]} ->|
 |<-- response {revision:2, [probe-1 pending]} --|
 |<-- event breakpointStatus {probe-1 verified} -|
 |<-- event breakpointHit {probe-1, hitCount:1, captures:[...]} -|
 |<-- event breakpointHit {probe-1, hitCount:2, captures:[...]} -|
```

---

## 18. 实现要求

这些不是线上格式，但两端都要遵守，否则性能目标（设计文档 5.3）达不到：

**runtime**

- 网络线程只做收发和解帧；需要访问 Lua 的命令投递给目标 VM 的命令队列，由 VM 所在线程在钩子里或暂停循环里执行。
- 无断点、无单步、无暂停请求时不挂行钩子；只在含断点 chunk 的函数里开行钩子（设计文档 5.2）。
- 断点表是不可变快照，`setBreakpoints` 构建新快照后原子发布；钩子线程不加锁。
- 条件断点在 `setBreakpoints` 时预编译（每个 VM 首次匹配时 `load` 一次并缓存函数），命中时只调用。
- 暂停时不主动推送栈和变量，只发 `paused`。

**IDE**

- 所有网络读写和解码在后台线程，EDT 上不等待任何 response。
- 只拉取可见部分：栈分页、变量分页、内联值只请求可见行涉及的名字。
- 对 `STALE_PAUSE` / `STALE_REFERENCE` 静默丢弃结果（用户已经继续了），不弹错误。

---

## 19. 变更记录

| 修订 | 日期 | 内容 |
| --- | --- | --- |
| v3.0（revision 0） | 2026-11-16 | 首个定稿。采用 IDE 引擎线程提出的消息字段命名（`command`/`args`、`ok`/`body`/`error`、`seq`/`event`/`body`）与命令名 |
| v3.0（revision 0） | 2026-11-16 | 按 `emmy_debugger_core` 实现补充说明（不改字段语义）：`loadHelpers` 立即返回 `{}`、失败走 `output`；helper 读写宿主 `_G`；父协程帧为可选；`exceptions` 能力未声明时 `setOptions.exceptions` 只接受 `none`。变量视图的字段扩展见 [variable-view.md](variable-view.md)，后续合进第 9 节 |
| v3.0（revision 0） | 2026-11-16 | 新增能力 `profiler` 和命令 `profile`（第 20 节），向后兼容：老 runtime 不声明这个能力，IDE 就不发 |

---

## 20. 采样性能分析（profile）

能力 `profiler`。runtime 在 VM 线程上按「每 N 条 Lua 指令」采样当前协程的调用栈，聚合成函数表和折叠栈；IDE 和 MCP 用它回答「游戏里哪段 Lua 最耗时」。和 UELink 的 `lua.profile`（按函数累计调用次数和用时）不同，这里是采样，放在调试核心里，Android 真机附加（包里没有 UELink）也能用。

### 20.1 `profile`

```json
{ "command": "profile", "args": { "action": "start", "vmId": 0, "intervalInstructions": 1000, "durationMs": 3000 } }
→ { "ok": true, "body": { "vms": [1], "intervalInstructions": 1000, "durationMs": 3000 } }

{ "command": "profile", "args": { "action": "stop", "sort": "self", "limit": 50, "stackLimit": 500 } }
→ { "ok": true, "body": {
      "running": false, "vms": [1], "samples": 2950, "durationMs": 3001, "intervalInstructions": 1000, "overheadMs": 1.2,
      "functions": [ { "fn": "add", "source": "@game/main.lua", "line": 3, "selfSamples": 1800, "totalSamples": 1800,
                       "selfPercent": 61.02, "totalPercent": 61.02 } ],
      "totalFunctions": 12,
      "stacks": [ { "stack": "<匿名> (game/main.lua:7);add (game/main.lua:3)", "samples": 1800 } ],
      "totalStacks": 37, "stacksTruncated": false } }
```

| 参数 | 用于 | 说明 |
| --- | --- | --- |
| `action` | 全部 | `start`（开始；已经在采样时清空重来）/ `stop`（停止并返回结果，同 `result`）/ `result`（返回当前结果，采样中也能取，不停止） |
| `vmId` | 全部 | 省略或 0 表示所有 VM：`start` / `stop` 作用于每个 VM，结果按函数和折叠栈合并 |
| `intervalInstructions` | `start` | 每多少条 VM 指令采一次，100–10000000，默认 1000 |
| `durationMs` | `start` | 0–3600000，默认 0（直到 `stop`）。到点后 runtime 在下一次采样时自动停止 |
| `sort` | `stop` / `result` | `self`（默认，按自身样本）/ `total`（按总样本，含调用的函数） |
| `limit` | `stop` / `result` | 函数表最多返回几行，1–1000，默认 50 |
| `stackLimit` | `stop` / `result` | 折叠栈最多返回几条（按样本数从多到少），0–10000，默认 500；另有约 8 MiB 的总长度上限 |

结果字段：

| 字段 | 说明 |
| --- | --- |
| `running` | 还有 VM 在采样 |
| `samples` | 有效样本数（暂停在断点 / 单步时到点的不计入，个数在 `pausedSkipped`） |
| `durationMs` | 从 `start` 到 `stop`（或到现在）的时长 |
| `overheadMs` | 采样回调本身花的时间（不含 Lua 5.4 挂钩子后逐条指令检查的固有开销，见原生核心 README「采样性能分析」） |
| `functions` | 函数表：`fn` 显示名（函数名来自调用处，从宿主 C 代码调进来的取不到时是 `<匿名>`；主 chunk 是 `<主 chunk>`）、`source` chunkName 原样（C 函数为 `=[C]`）、`line` 函数定义行（C 函数 -1、主 chunk 0）、`selfSamples` / `totalSamples`、`selfPercent` / `totalPercent`（占 `samples` 的百分比，保留两位小数）。同一个样本里递归出现多次的函数 `total` 只算一次 |
| `totalFunctions` | 不截断时的函数个数 |
| `stacks` | 折叠栈：`stack` 是根在前、`;` 分隔的帧名（`函数名 (chunk:定义行)`，C 函数 `名字 [C]`），和 flamegraph.pl / speedscope 的 `.folded` 一行「`stack samples`」一致 |
| `totalStacks` / `stacksTruncated` | 不同栈的总数；超过 `stackLimit` 或长度上限时 `stacksTruncated: true` |
| `truncatedSamples` | 可选：栈深超过 64 层、根部被截掉的样本数（这些栈以 `<截断>` 开头） |
| `otherSamples` | 可选：每个 VM 不同栈超过 1 万条后，之后新出现的栈合并成一条 `<其他>` |

约定：

- 不采样时 runtime 不挂任何为采样准备的钩子；`start` 经 HookMux 注册一个 COUNT 客户端，`stop` 摘掉。
- 只用 `lua_getstack` / `lua_getinfo`（`S`，C 函数和第一次见到的函数加 `n`），dynamic 模式下不读宿主 `lua_Debug` 里 Lua 私有的字段。
- 采样不改变游戏状态（不暂停、不改变量）。IDE 断开时 runtime 停掉所有 VM 的采样。
- 样本按执行的 Lua 指令数分布，不按墙钟时间：COUNT 钩子只在执行 Lua 指令时计数，花在 C 函数（引擎接口、`string` 库等）里的时间不计入任何样本。看「哪段 Lua 逻辑执行得最多」很准，看「Lua 调了哪个慢的引擎接口」要配合引擎自己的分析器。
- 错误：`action` / 范围不对回 `INVALID_ARGS`，`vmId` 不存在或进程里还没有 VM 回 `VM_NOT_FOUND`。
