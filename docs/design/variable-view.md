# 断点时变量展示设计

> 状态：**已实施**（2026-11-16 起草，同日在原生核心 `src/rich_view.cpp` 和 IDE 变量树落地）。
> 关联：[2026-11-debugger-redesign.md](2026-11-debugger-redesign.md)（总设计）、[protocol-v3.md](protocol-v3.md)（协议，本文第 13 节是对它的**纯增量**补充）。
> 范围：暂停时「变量 / 监视 / 悬停 / 内联值 / MCP 取变量」里一个 Lua 值怎么显示、展开后子项怎么排列，以及为此需要的协议字段、runtime 和 IDE 的实现要求。

## 目录

1. [现状问题](#1-现状问题)
2. [目标与原则](#2-目标与原则)
3. [一行值的组成](#3-一行值的组成)
4. [各类型的显示](#4-各类型的显示)
5. [table 展开：排序、分段与分组](#5-table-展开排序分段与分组)
6. [元表与继承成员](#6-元表与继承成员)
7. [作用域：局部变量、upvalue、可变参数、全局](#7-作用域局部变量upvalue可变参数全局)
8. [UE 对象](#8-ue-对象)
9. [对象身份与循环引用](#9-对象身份与循环引用)
10. [大数据：分页、区间、长字符串、展开状态](#10-大数据分页区间长字符串展开状态)
11. [自定义显示：显示规则与 helper v2](#11-自定义显示显示规则与-helper-v2)
12. [安全：什么时候执行目标进程里的代码](#12-安全什么时候执行目标进程里的代码)
13. [协议增量](#13-协议增量)
14. [runtime 实现要点](#14-runtime-实现要点)
15. [IDE 实现要点](#15-ide-实现要点)
16. [交互](#16-交互)
17. [性能目标与验收](#17-性能目标与验收)
18. [测试](#18-测试)
19. [分工与阶段](#19-分工与阶段)
20. [待定](#20-待定)

---

## 1. 现状问题

从旧实现（`emmy_debugger.cpp` 的 `GetVariable`、`emmyHelper*.lua`、`LuaXValue.kt`）里整理出来，新设计逐条解决：

| # | 问题 | 原因 | 本文对应 |
| --- | --- | --- | --- |
| 1 | 子项按名字**字符串**排序，`[10]` 排在 `[2]` 前面；数组和哈希混在一起 | IDE `VariableComparator` 对 `name` 做字典序 | 5.1 |
| 2 | `self` 首次展开只有 `(metatable)`、`(metatable.__index)` 两个伪项，真实字段要再 eval 一次；伪项和普通字段混排 | runtime 按深度一次性构造子树；元表用伪项表示 | 5、6 |
| 3 | 继承来的方法 / 默认值看不到，或者要在 `(metatable.__index)` 里一层层翻 | 没有「继承成员」概念 | 6.2 |
| 4 | table 的值显示成 `table(0x1a2b, size = 3)`，看不出内容；IDE 侧用已缓存子项拼摘要，首包没有子项时又退回地址 | runtime 不给摘要 | 4.1 |
| 5 | userdata（UnLua 的 UObject）展开会走 `__index` → `Class_Index` → 读属性，对象失效时直接崩游戏；为此 IDE 干脆不再展开 userdata | 读成员走元方法，没有有效性检查 | 8、12 |
| 6 | TArray/TMap 用 `ToTable` 整个转成 table，几万项时卡顿 | helper 一次构造全部子项 | 8.4、11.3 |
| 7 | 没有循环引用标记，`a.owner.child.owner…` 能无限点下去，看不出是同一个对象 | 没有对象身份 | 9 |
| 8 | 子项展开路径用名字拼字符串（`a["b"][3]`），键是 table / 浮点 / 含引号字符串时拼错 | IDE 侧拼 `evalExpr` | 13.2 `evaluateName` |
| 9 | 函数只在 Lua 5.4 显示参数名，其他版本只有地址；不能跳到定义 | `DisplayFunction54` 只处理 5.4 | 4.5 |
| 10 | 协程只显示地址 | — | 4.6 |
| 11 | 局部变量按名字排序，看不出声明顺序；`(*temporary)` 混在里面 | — | 7.1 |
| 12 | 全局变量里一堆 `string`、`table`、`print`… 淹没业务全局 | — | 7.4 |
| 13 | 自定义显示只能写 Lua processor，且要自己处理深度、自己构造全部子项 | helper 契约是「深度 + 一次构造」 | 11 |

---

## 2. 目标与原则

1. **安全第一**：看变量不能改变游戏状态，更不能把游戏弄崩。默认只用 `rawget` / `lua_next` / `lua_getinfo` / `lua_getlocal` 这类只读 API；会执行代码的地方（`__tostring`、helper、宿主反射）都有开关、`pcall`、指令预算和有效性检查（第 12 节）。
2. **一眼看懂**：每个值一行就能看出「是什么类型、大概内容是什么、有多少项」。摘要由 runtime 生成（它手里有值），IDE 不再靠已缓存子项拼。
3. **稳定的结构**：同一类值永远按同样的规则排列——数组段按下标、字段段按智能排序、方法 / 继承 / 元表放在底部分组里。不同 Lua 版本、不同框架（UnLua / tolua / xlua / 纯 Lua）看起来一致。
4. **按需、有界**：只取看得见的部分。任何一次请求的耗时和字节数都有上限；十万项的 table 和一层的 table 展开速度差不多。
5. **一个模型，多处使用**：变量面板、监视、悬停、内联值、MCP 取变量用同一份数据模型（第 15.1 节），MCP 把它渲染成文本。
6. **可扩展但不必须扩展**：不写任何配置时默认显示就够用；项目可以用声明式规则（不执行代码）或 Lua helper（执行代码）定制。

---

## 3. 一行值的组成

IntelliJ 变量树的一行是 `图标 名字 = {类型标签} 值摘要`。约定每部分放什么：

```
[图标] name = {类型标签} 摘要                                   [行尾提示]
  │       │        │          │                                     │
  │       │        │          └ runtime 给的 value（第 4 节）        └ 例：「↻ 循环引用」「已失效」「继承自 BagPanel」
  │       │        └ typeName + 数量，例：{ItemData}、{table[3]}、{BP_Hero_C}
  │       └ 键的显示名：标识符原样，其他键用 [..]（第 5.2 节）
  └ 按 kind 选图标（第 15.3 节）
```

例子：

```
▸ self = {BagPanel} BagPanel{selected=3, items=#12, …}
▸ items = {table[12]} {ItemData{id=1003}, ItemData{id=1004}, …}
  index = 7
  name = "长剑"
▸ hero = {BP_Hero_C} Hero_0 (/Game/Maps/Main.Main:PersistentLevel.Hero_0)
▸ onClick = {function} function BagPanel:OnItemClick(self, index)  BagPanel.lua:112
▸ co = {coroutine} suspended  Loader.lua:48
▸ pos = {FVector} (X=10.0, Y=0.0, Z=95.5)
  dead = {UObject} <已失效>                                         已失效
```

数量放在类型标签里，不占摘要：数组 `{table[12]}`、纯哈希 `{table{5}}`、混合 `{table[3]{2}}`、空表 `{table}`；有类型名时显示类型名，数量放进悬停提示。

---

## 4. 各类型的显示

### 4.1 table

runtime 先判断形态（`kind`），再生成摘要。判断只用 `rawlen`、`lua_next`、`lua_getmetatable`、`rawget`，不触发元方法。

| kind | 判断 | 摘要 | 例 |
| --- | --- | --- | --- |
| `array` | 只有 1..n 的整数键（允许少量空洞，见 5.3） | 前 5 项值，`, ` 分隔 | `{"a", "b", "c"}` |
| `record` | 没有元表（或元表里没有 table 型 `__index`），有非数组键 | 前 5 个**字段**（按 5.1 顺序，跳过函数） | `{id=1003, count=2, owner=…}` |
| `object` | 有元表且 `__index` 链能走到 table（典型 OO 实例） | `类名{前 5 个字段}`；类名见 6.3 | `BagPanel{selected=3, items=#12}` |
| `class` | 自己是别的对象的 `__index`，或者是 `package.loaded` 里的模块且大部分成员是函数 | `class 类名（n 个方法）` | `class BagPanel (23 个方法)` |
| `module` | `package.loaded[name] == t` 且不是 class | `module 名字` | `module UI.BagPanel` |
| `record`（空） | 没有任何键 | `{}` | `{}` |

摘要里嵌套值的写法：字符串带引号并截到 32 字节；数字原样；嵌套 table 写 `{…}`，已知类型写 `类名{…}`，数组写 `#n`；函数写 `ƒ`；userdata 写类型名。整个摘要不超过 `maxValueLength`（默认 256 字节，悬停 1024），超出以 `…` 结尾。

有 `__tostring` 的 table：按第 12 节策略调用，结果作为摘要，原来的字段摘要放进悬停提示。

### 4.2 string

- 摘要：带双引号、转义（`\n` `\t` `\"` `\\`，不可打印字节写 `\xNN`），超出 `maxValueLength` 截断，末尾显示总长：`"第一行\n第二行…"（共 5321 字节）`。
- `length` 字段给出完整字节长度，IDE 据此决定是否显示「查看完整内容」链接（IntelliJ 的 `XFullValueEvaluator`），点了才用 `evaluate { maxLength: 1 MiB }` 取全文。
- 看起来像 JSON（首字符 `{`/`[` 且能解析）时，查看窗口默认用 JSON 格式化显示。

### 4.3 number

- Lua 5.3+ 区分整数和浮点：`kind = integer | float`。浮点总是带小数点（`3.0`、`1e+20`），整数不带，这样一眼看出 `3` 和 `3.0` 不是一回事。
- 浮点值是整数时不用指数写法：`1.4068584936114e+14` 写成 `140685849361140.0`（|n| < 2^53）；其余用最短能回读的写法（`%.14g`，不够再 `%.17g`）。
- 特殊值：`nan`、`inf`、`-inf`、`-0.0` 原样写出。
- 64 位整数以字符串传输，不经过 JSON number，避免超过 2^53 丢精度。
- IDE 设置「整数同时显示十六进制」：`255 (0xFF)`。

### 4.4 boolean / nil

原样：`true`、`false`、`nil`。

### 4.5 function

| 情况 | 摘要 | 子项 |
| --- | --- | --- |
| Lua 函数 | `function 名字(参数…)` + 行尾 `文件:行` | `source`（可跳转）、`upvalues`（分组）、`params`、`isvararg` |
| C 函数 | `C function 名字` 或 `C function 0x…` | 无（`upvalues` 有就列出） |

- 名字：runtime 不能可靠拿到函数名，只在两种情况下给：它是被展开对象自己的字段（用键名，例 `BagPanel:OnItemClick`）、或者在栈帧里能从 `lua_getinfo "n"` 拿到。否则不写名字。
- 参数名：5.2+ 用 `lua_getlocal(L, nullptr, i)` 取，5.1 / LuaJIT 用 `lua_getinfo` 的 `nparams`（LuaJIT 有）写成 `(a1, a2)`，都拿不到时写 `(…)`。所有版本统一处理，不再只支持 5.4。
- `location = { chunkName, line }`，IDE 用和断点相同的路径映射找到本地文件，行尾显示成可点击的 `BagPanel.lua:112`，右键「跳转到定义」。

### 4.6 thread（协程）

- 摘要：`状态  文件:行`，状态用 `coroutine.status` 的四种（`running` / `suspended` / `normal` / `dead`），位置是它当前栈顶（`lua_getstack(co, 0)`），`dead` 时没有位置。
- 子项：`status`，加一个分组「调用栈（n 帧）」，每帧是一个可展开节点，展开得到该帧的局部变量（和第 7 节同样的规则）。这样不切线程也能看挂起协程里的局部变量。
- 右键「在调用栈视图中查看此协程」：切换到 `threads` / `stackTrace { threadId }`。

### 4.7 userdata / lightuserdata

- userdata 先交给 helper（第 11 节）和宿主（第 8 节）；都不认识时：摘要是元表 `__name`（有的话）加地址：`FVector 0x7ffd…`，子项只有「元表」分组。
- **不**对没人认领的 userdata 做 `obj[key]` 读取（就是旧实现崩溃的路径）。
- lightuserdata：`0x7ffd1234`，没有子项。

---

## 5. table 展开：排序、分段与分组

展开一个 table（`view = auto`，默认）时，子项分成三块，自上而下：

```
▾ self = {BagPanel} BagPanel{selected=3, items=#12, …}
    ── 数组段（按下标）──────────────
    [1] = "a"
    [2] = "b"
    ── 字段段（智能排序）────────────
    items = {table[12]} {…}
    selected = 3
    title = "背包"
    ["display name"] = "x"
    [0] = "zero"
    [true] = 1
  ▸ 方法 (4)                       ← 底部分组：自己身上的函数
  ▸ 继承自 BagPanel (23)           ← 底部分组：__index 链第 1 层
  ▸ 继承自 UIBase (41)             ← 底部分组：__index 链第 2 层
  ▸ 元表 BagPanel                  ← 底部分组：原始元表
```

### 5.1 字段段的「智能排序」

依次比较：

1. **键类别**：标识符字符串 → 其他字符串 → 数字（不在数组段里的）→ boolean → 其他（table / function / userdata 键）。
2. 标识符字符串内：**自然排序、不分大小写**（`item2` < `item10`，`Name` 和 `name` 相邻）；完全相同时大写在前。
3. 其他字符串：按字节序。
4. 数字：按数值（`-1` < `0` < `0.5` < `1e9`）。
5. 其他键：按地址（只为稳定，不表达含义）。

Lua 的 table 没有插入顺序，`next` 的顺序随哈希变化、对人没有意义，所以默认排序。设置里可以改成「按名称（纯字典序）」或「原始顺序（`next`）」。

**函数成员**：一个 table 里既有数据字段又有函数时，函数收进底部分组「方法 (n)」；全是函数（典型的模块 / 类表）时直接列出，不再套一层。设置「函数成员单独分组」可关。

### 5.2 键的显示名

| 键 | 显示 | `evaluateName` 片段 |
| --- | --- | --- |
| 标识符字符串（且不是 Lua 关键字） | `name` | `.name` |
| 其他字符串 | `["display name"]`（转义同 4.2） | `["display name"]` |
| 整数 | `[3]` | `[3]` |
| 浮点 | `[0.5]` | `[0.5]` |
| boolean | `[true]` | 无（valuePath 不支持，见 13.2） |
| table / function / userdata | `[table 0x1a2b]`、`[ItemData{id=3}]`（有摘要时用摘要） | 无 |

### 5.3 数组段

- 范围是 `1..n`，`n = rawlen(t)`（Lua 返回的「边界」）。只在 `1..n` 内找整数键，从 `n+1` 起的整数键算字段段。
- 空洞：`1..n` 里值为 `nil` 的下标不显示（Lua 的 `#` 本来就允许空洞）。分页按**下标范围**而不是按条数（见 10.1），所以空洞不影响区间分组。
- 稀疏表（例如只有 `[1]` 和 `[100000]`）：`rawlen` 可能返回 1，`[100000]` 就落在字段段的数字类里，这是期望的显示。

### 5.4 底部分组

底部分组是 `kind = group` 的虚拟节点，自带 `ref`，展开时再取。它们只在第一页（`start = 0`）的 response 里通过 `groups` 字段返回，不计入 `total`，所以分页不会把它们挤到第十页之后。IDE 用 `XValueChildrenList.addBottomGroup` 放在最底部。

| 分组 | 出现条件 | 内容 |
| --- | --- | --- |
| 方法 (n) | 5.1 函数成员规则 | 自己身上的函数值字段，按智能排序 |
| 继承自 X (n) | 有 table 型 `__index` 链（第 6 节） | 每层一个分组 |
| `__index` 是函数 | `__index` 是函数 | 不展开，只给函数位置和说明（第 6.2 节） |
| 元表 X | 有元表，且设置「显示元表」开着 | 原始元表，按 6.1 显示 |
| 内置 (n) | 只在全局作用域（第 7.4 节） | 标准库和框架全局 |

「继承自 X」之外的分组（方法、`__index` 是函数、元表、UnLua 类表的内部字段）平时很少看，统一收进最后一个分组「内部」（默认折叠，后面的灰字列出里面有什么，例「方法、元表 BagPanel」），展开后才是上表的分组。这样展开一个对象，首屏只有字段、宿主分组和继承成员。

---

## 6. 元表与继承成员

### 6.1 元表节点

元表本身也是 table，但展开时元方法和普通字段分开：

```
▾ 元表 BagPanel
    __index = {class BagPanel} …
    __name = "BagPanel"
    __tostring = {function} …   (元方法)
    __gc = {function} …         (元方法)
    Ctor = {function} …
```

`__` 开头且是 Lua 已知元方法名的键带 `attrs: ["metamethod"]`，排在最前，图标不同。

### 6.2 继承成员（`__index` 链）

对象的「成员」不止自己的键。runtime 沿 `getmetatable(x).__index` 往上走：

1. `__index` 是 table：这一层成为一个分组「继承自 类名 (n)」，类名按 6.3 取。继续看这个 table 的元表 `__index`。
2. `__index` 是函数：停止，加一个「`__index` 是函数」分组，内容是这个函数的位置，说明「通过函数取值的成员不会自动读取（避免执行代码）」。用户可以在监视里用 `lua` 模式自己求值。
3. 最多 16 层；遇到已经走过的 table（环）停止。
4. 每层只列**该层自己有、且更近一层没有**的键；被更近一层（含对象自身）覆盖的键仍然列出，但带 `attrs: ["shadowed"]`，IDE 灰色加删除线，提示「被 xx 覆盖」。
5. 每层内部同样分「数据字段」和「方法」：数据字段（类上的默认值）在前，方法在后，都按智能排序。
6. 分组节点的 `value` 写该层的来源，例如 `module UI.BagPanel`，可点击跳转到模块文件（`location`）。

设置「显示继承成员」关闭时不返回这些分组。

### 6.3 类名推断

按顺序取第一个能取到的：

1. 显示规则 / helper 指定的类型名（第 11 节）。
2. 元表的 `__name`（字符串，`luaL_newmetatable` 和多数绑定库会设）。
3. 元表或 `__index` 链第一层上的常见 OO 字段：`__cname`、`__className`、`className`、`__type`（列表可在显示规则里改）。用 `rawget`，只认字符串值。
4. 反查模块名：元表或 `__index` 第一层是 `package.loaded` 里某个模块表时，用模块名（例 `UI.BagPanel`，显示时取最后一段 `BagPanel`，悬停给全名）。反查表每次暂停建一次（遍历 `package.loaded`，O(模块数)），之后 O(1)。
5. 以上都没有：`table`。

IDE 还可以加第 6 步：runtime 只给了 `table` 时，用 EmmyLua 的静态类型推断拿到变量声明类型（`---@type ItemData`），以灰色斜体显示，表示「静态类型，非运行时」。

### 6.4 UnLua 实例

UnLua 的 Lua 实例是一张表（字段 `Object` 指向 UObject userdata），元表链指向模块表。显示为：

```
▾ self = {BagPanel · WBP_Bag_C} BagPanel{selected=3, …}
    selected = 3
    …
  ▸ UE 对象 WBP_Bag_C_0                ← 分组：展开见第 8 节
  ▸ 继承自 BagPanel (23)
  ▸ 内部  方法、元表 BagPanel          ← 默认折叠（5.4）
```

- 宿主认领了实例表时，`Object` 字段本身不在字段段重复出现（它就是「UE 对象」分组）。
- 根节点摘要是宿主摘要后面跟几个字段，不用展开就能看到关键数值：`BP_Enemy_C_0 {Damage=128.0, Health=100, …}`。显示规则写了 `summary` 时用规则的。
- UnLua 的类表（元表里有 lightuserdata 型 `ClassDesc` 和字符串 `__name`，例如 `self.DamageType`）显示成 `class UDamageType`，`kind = class`；`ClassDesc`、`TypeHash`、`__` 开头的字段收进「内部 → UnLua 字段」。
- 类型名是资产路径（`/Game/UI/WBP_Bag.WBP_Bag_C`）时只显示最后一段 `WBP_Bag_C`，完整路径放在行尾灰字（`hint`）。

---

## 7. 作用域：局部变量、upvalue、可变参数、全局

### 7.1 局部变量

- 按**声明顺序**（`lua_getlocal` 的下标顺序），不按名字排序。方法的 `self` 自然在第一个。
- 同名遮蔽：内层的正常显示，外层的显示为 `name`（灰色、`attrs: ["shadowed"]`），悬停说明「被第 N 行的同名局部变量遮蔽」。
- 内部临时值（`(*temporary)`、`(for state)`、`(C temporary)` 等以 `(` 开头的名字）默认不显示；设置可开，开了放到底部分组「临时值」。
- 还没执行到声明行的局部变量 Lua 本来就取不到，不需要特殊处理。

### 7.2 upvalue

按 `lua_getupvalue` 下标顺序。`_ENV`（5.2+）不在这里列出（它就是全局作用域）。

### 7.3 可变参数

`...` 显示为 `...[1]`、`...[2]`，作用域标题写个数。

### 7.4 全局

- 只列出**业务全局**，标准库和框架全局（`string`、`table`、`math`、`coroutine`、`io`、`os`、`debug`、`package`、`utf8`、`bit32`、`jit`、`_G`、`_VERSION`、`print`/`pairs` 等基础函数，以及 `UE`、`UnLua` 这类框架入口）收进底部分组「内置 (n)」。名单在 runtime 里内置、可被显示规则扩充。
- 判定业务全局用名单而不是「首次暂停时的快照差集」，因为附加时游戏早就跑起来了，没有干净的初始状态。
- 全局可能很多，不给 `count`（协议 9.2 已约定），分页取。

---

## 8. UE 对象

UE 对象是现在问题最多的地方：属性读取要经过 UE 反射，对象可能已经被 GC，崩溃代价是游戏直接挂。分三种来源，按可用性逐级降级。

### 8.1 显示

```
▾ hero = {BP_Hero_C} Hero_0                                   /Game/Maps/Main.Main:PersistentLevel.Hero_0
  ▸ Lua 字段 (3)                  ← 有 Lua 实例表时
  ▾ BP_Hero_C (5)                 ← 按声明类分组，最派生在前
      Health = 100.0
      Level = 7
  ▸ Character (32)
  ▸ Pawn (18)
  ▸ Actor (61)
  ▸ 组件 (6)                      ← UActorComponent 子对象，按名字
  ▸ 方法 (UFunction, 128)         ← 默认折叠
```

- 摘要：对象名，行尾给完整路径；类型标签是类名。
- 属性在各自分组里按**声明顺序**（和 UE 细节面板一致），设置可切成按名字。有 `Category` 元数据时（编辑器下）可作为二级分组，默认不开，避免层级太深。
- **已失效的对象**（`!IsValid`、PendingKill、已 GC）：摘要 `<已失效>`，红色，`attrs: ["invalid"]`，**不提供任何子项**。这条是防崩溃的关键，所有 UE 来源都必须先做有效性检查再读属性。

### 8.2 结构体与基础类型

| 类型 | 摘要 | 子项 |
| --- | --- | --- |
| `FVector` / `FVector2D` / `FVector4` | `(X=10.0, Y=0.0, Z=95.5)` | 各分量 |
| `FRotator` | `(P=0.0, Y=90.0, R=0.0)` | 各分量 |
| `FQuat` / `FTransform` | `T=(…) R=(…) S=(…)` | 平移 / 旋转 / 缩放 |
| `FLinearColor` / `FColor` | `(R=1, G=0.5, B=0, A=1)`，IDE 行首画色块 | 各分量 |
| `FName` / `FString` / `FText` | 字符串（带引号），`FText` 加 `text` 前缀 | 无 |
| `FGameplayTag` | `Tag.Name` | 无 |
| 枚举 | `EState::Running (2)` | 无 |
| 委托 / 多播委托 | `绑定 n 个` | 每个绑定：对象 + 函数名 |
| 软引用 / 类引用 | 路径，未加载时加 `（未加载）` | 无（不触发加载） |

### 8.3 容器

`TArray` / `TSet` 按数组显示（`{TArray<FItem>[1203]}`），`TMap` 按「键 → 值」显示，子项名为键的摘要。都**分页**读取（第 10 节），不再 `ToTable` 整个转换。

### 8.4 数据从哪来

| 来源 | 什么时候可用 | 能做什么 |
| --- | --- | --- |
| **A. 宿主值提供者**（`HostValueProvider`） | PC 上进程里有宿主插件注册了提供者（UEKit 的 UELink 可以在检测到 emmy runtime 时动态注册，不链接、不改项目代码） | 完整：有效性、按类分组的全部属性、声明顺序、组件、方法、容器分页。在游戏线程上走 UE 反射，不经过 Lua 的 `__index` |
| **B. Lua helper**（`emmyHelper_ue.lua` v2） | 总是可用（包括 Android 真机附加，包内没有任何插件） | 有效性（`UE.UKismetSystemLibrary.IsValid` 等，可配置）；已知结构体 / 容器的格式化；**显示规则里列出的属性名**逐个安全读取；已经被 Lua 访问过、缓存在元表上的成员 |
| **C. 原生反射**（Android agent 内） | 后续阶段；需要本地未裁剪符号 | 在 agent 里用符号偏移直接读 `UClass` 属性链，能力接近 A |

runtime 对 userdata 依次问 A → B，第一个认领的负责；没人认领按 4.7 显示。A 不可用时，B 在对象行尾提示「属性列表有限：未检测到宿主插件」，悬停说明怎么补（装 UELink，或在显示规则里列出要看的属性）。

### 8.5 和 UEKit 的边界

- UELink 注册提供者属于 UEKit 仓库的改动（`plugins/UELink`），按 UEKit 规则另开提交，只在检测到 `Emmy_RegisterHostValueProvider` 导出时注册，不链接 emmy。
- 这里定的是接口（13.5）和显示规则；UELink 里的实现另立任务。

---

## 9. 对象身份与循环引用

- table / userdata / function / thread 都带 `objectId`（`lua_topointer` 的 hex），只在**本次暂停内**有意义（GC 后地址可能复用）。
- **循环引用**：IDE 展开节点时检查祖先链，有相同 `objectId` 就在行尾标「↻ 循环引用 → self.owner」（指向那个祖先的 `evaluateName`），仍然允许展开（反正是按需加载，不会无限递归），点行尾提示跳到祖先节点。
- **同一对象**：IDE 可选高亮「和当前选中节点是同一个对象」的其他节点（同一 `objectId`），帮助理解别名。
- **标记对象**：IntelliJ 的 Mark Object（F11）按 `objectId` 打标签，本次暂停内所有出现处都显示标签。跨暂停不保证（地址可复用），文档和提示里说明。

---

## 10. 大数据：分页、区间、长字符串、展开状态

### 10.1 分页

- 字段段：每页 100（设置可改，最大 1000），IDE 用 `tooManyChildren(剩余数)` 显示「显示更多（还有 n 项）」。
- 数组段：`indexedCount > 100` 时 IDE 不直接列，而是切成区间分组 `[1..100]`、`[101..200]`…，每组展开时用 `filter = indexed, start, count` 取。超过 10000 项时再套一层（`[1..10000]` → `[1..100]`…），任何一层最多 100 个子节点。
- 区间按下标算（`start` 是下标偏移，`start = 0` 对应 `[1]`），空洞只是让某个区间少几项。

### 10.2 一致的快照

第一次对某个 `ref` 取子项时，runtime 把**排好序的键列表**存进本次暂停的引用表；之后的每一页都用同一份键列表 + `rawget` 取值。这样：

- 分页之间顺序不变，不会漏项或重复（直接用 `next` 分页在 table 被改后会出错）。
- 每页 O(页大小)，排序只做一次。
- `setVariable` 或 `lua` 模式求值改了 table 后，IDE 刷新时用新的 `ref` 重新取，旧键列表随暂停一起丢。

键数超过 `maxSortKeys`（默认 10000）时不排序（直接用 `next` 顺序收集键，仍然存快照），response 里 `sorted = false`，IDE 在该节点下加一行灰色提示「项太多，按内部顺序显示」。

### 10.3 长字符串和深嵌套

- 摘要一律截断（4.2），完整内容按需取。
- 嵌套没有深度限制（按需展开），但单次 response 有字节上限：一页 + 内联展开（10.4）合计不超过 256 KiB，超出时 runtime 少给几项并设 `truncated`，IDE 显示「更多」。

### 10.4 恢复展开状态（一次往返）

每次单步后整棵树都要重建，旧实现是逐层请求，USB / Wi-Fi 上每层一个往返（5–30 ms），展开三层就明显卡一下。

v3 增量：`variables` 可以带 `expand`，列出要顺带展开的**名字路径**（相对于这个 `ref`，例如 `[["self"], ["self", "items"]]`）。runtime 在同一个 response 里把这些节点的首页子项内联到对应 `Variable.children`。IDE 在暂停后取局部变量时把上次的展开状态（IntelliJ 按名字路径记录）塞进去，一次往返就把可见树全部拿回来。限制：最多 32 条路径、深度 ≤ 8、每个内联节点只给首页。

---

## 11. 自定义显示：显示规则与 helper v2

两层，按需选：

| | 显示规则（声明式） | helper v2（Lua 代码） |
| --- | --- | --- |
| 写法 | JSON 文件 | Lua 脚本 |
| 是否执行目标进程里的代码 | **否**，runtime 原生计算，只用 `rawget` | 是，在调试器私有环境里执行 |
| `allowLuaEval = false`（MCP 未授权）时 | 可用 | 仍可用（helper 是 IDE 下发的、用户自己的脚本，不是 Agent 输入） |
| 能做什么 | 摘要模板、固定字段、隐藏字段、类名字段、要读的 UE 属性名 | 任意：识别类型、生成摘要、分页列子项 |
| 典型用途 | 「ItemData 显示成 `#1003 ×2`」「Hero 只看 Health、Level」 | 框架适配（tolua / xlua / UnLua）、UE 容器 |

### 11.1 显示规则文件

默认位置 `<项目根>/.emmylua/debug-view.json`，建议提交到版本库，团队共用（类似 natvis）。IDE 读文件、校验后用 `setViewRules` 下发（13.4），文件变化时重新下发。

```json
{
  "version": 1,
  "classNameFields": ["__cname", "__className", "className"],
  "builtinGlobals": ["Logger", "Timer"],
  "types": {
    "ItemData": {
      "summary": "#{id} ×{count}",
      "pin": ["id", "count"],
      "hide": ["_dirty", "_cache"]
    },
    "UI.BagPanel": {
      "summary": "{title} 选中={selected}"
    },
    "BP_Hero_C": {
      "ueProperties": ["Health", "Level", "Team"],
      "pin": ["Health"]
    }
  }
}
```

- `types` 的键匹配 6.3 推断出的类名，或模块全名。
- `summary`：`{path}` 是相对对象的 valuePath（`{owner.name}`、`{items[1]}`），runtime 用 `rawget` 沿路径取值并按 4.1 的嵌套写法格式化；取不到写 `?`。不支持任何表达式。
- `pin`：固定在字段段最前面的字段（按列出顺序），同时是没有 `summary` 时摘要优先展示的字段。
- `hide`：不显示的字段（「原始视图」里仍可见）。
- `ueProperties`：来源 B（8.4）下要安全读取的 UE 属性。
- 右键「固定到顶部 / 取消固定」「隐藏此字段」直接改这个文件（第 16 节）。

### 11.2 helper v2

新核心的 helper 放在插件资源 `debugger/emmy/v3/`：`emmy.lua`（接口和调度）、`emmy_ue.lua`（UE 结构体和容器）、`emmy_main.lua`（入口，`loadHelpers` 的 main）。runtime 加载完后从入口返回的 `emmy.__dispatch` 取调度函数放进 registry（键 `emmy.formatter.dispatch`），不放全局、不依赖 debug 库。写法 `local emmy = require("emmy")` 后调用 `emmy.registerFormatter`，**惰性、分页**：

```lua
emmy.registerFormatter {
    name = "TArray",
    -- 匹配：类型名（6.3 推断结果或 __name）或函数
    match = { typeName = "TArray" },
    priority = 100,
    -- 返回类型标签（可选）
    typeName = function(obj) return "TArray<" .. obj:GetElementTypeName() .. ">" end,
    -- 一行摘要，只在生成父节点那一页时调用
    summary = function(obj) return string.format("Num=%d", obj:Length()) end,
    -- 子项：先问总数，再按页取；展开时才调用
    count = function(obj) return obj:Length() end,
    children = function(obj, start, count)
        local out = {}
        for i = start + 1, math.min(start + count, obj:Length()) do
            out[#out + 1] = { name = "[" .. i .. "]", value = obj:Get(i) }
        end
        return out
    end,
    -- 有效性：返回 false 时显示 <已失效> 且不再调用其他函数
    isValid = function(obj) return UE.UKismetSystemLibrary.IsValid(obj) end,
}
```

- `children` 返回的每项 `value` 是真实的 Lua 值，runtime 再按本文规则生成它的 `Variable`（嵌套的 TArray 也会再匹配 formatter）；也可以给 `{ name, summary, typeName, kind }` 直接指定显示。
- 每个回调都在 `pcall` + 指令预算（`helperBudgetMs`，默认 20 ms）下执行；出错或超时时这一项退回默认显示，行尾标「helper 出错」，悬停给错误信息，同一类型连续出错 3 次后本次暂停内不再调用。
- 旧的 `registerProcessor` / `getProcessorBase` / `createNode` 在 `emmy.lua` 里做了兼容：processor 被包成一个低优先级的 formatter，展开时跑一遍 `process` 再按页切出来。`registerProxyRecognizer` 不再支持。UE 处理（`emmy_ue.lua`）全部用 `registerFormatter`；UObject 本身归 UELink 的宿主值提供者（13.6），formatter 只在宿主不认领时才被问到。
- `children` 的每一项都要包成表（`{ name, value }` 或 `{ name, summary }`）；不是表的项按 `[下标] = 原值` 显示。不给 `count` 时，返回满一页就认为还有下一页。

---

## 12. 安全：什么时候执行目标进程里的代码

| 操作 | 默认 | 说明 |
| --- | --- | --- |
| `rawget` / `lua_next` / `rawlen` / `lua_getinfo` / `lua_getlocal` / `lua_getupvalue` | 总是 | 只读，不触发元方法 |
| `__index` 函数 | **从不** | 只显示「`__index` 是函数」分组（6.2） |
| `__pairs` / `__len` | **从不** | 展开用原始键；`#` 用 `rawlen` |
| `__tostring` | 只对 table | userdata 的 `__tostring` 交给 helper / 宿主决定（UnLua 失效对象上调它可能崩）。设置：仅 table / 全部 / 从不 |
| helper / formatter | 开（有 `helpers` 能力时） | `pcall` + 指令预算；`isValid` 先行 |
| 宿主值提供者 | 开（注册了就用） | 宿主负责有效性检查和线程 |
| 显示规则 | 开 | 只 `rawget` |

所有会执行代码的调用都：

1. 用 `lua_pcall`，并在调用前挂 COUNT 钩子做指令预算（`__tostring` 5 ms、helper 20 ms），超时中止；
2. 在调用前后检查栈平衡；
3. 结果只取字符串副本，不保留引用；
4. 期间屏蔽断点（调试器自己的调用不能再命中断点或单步）。

「原始视图」（`view = raw`）完全不调用 helper / 宿主 / `__tostring`，用来排查 helper 本身的问题或在怀疑会崩时查看。

---

## 13. 协议增量

全部是对 [protocol-v3.md](protocol-v3.md) 的**向后兼容增加**，挂在两个新能力位下。没协商这些能力时，runtime 和 IDE 都按 v3.0 原文行为（9.3 的顺序、`(metatable)` 伪项等）。

### 13.1 能力位

| 能力 | 有它才能用 |
| --- | --- |
| `richVariables` | 13.2–13.3 的所有新参数、新字段和新排序 |
| `viewRules` | `setViewRules`（13.4） |

`richVariables` 改变 `variables` 的返回形状，所以 runtime 只在 `hello.caps` **明确列出**它时开启；不带 `caps` 的客户端（「全要」）拿到的仍是 v3.0 的形状。

### 13.2 `Variable` 新增字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `kind` | string | 显示形态：`array` `record` `object` `class` `module` `integer` `float` `function` `cfunction` `coroutine` `userdata` `ueObject` `ueStruct` `ueContainer` `ueEnum` `group` `frame`。未知值 IDE 按 `type` 显示 |
| `attrs` | string[] | 可选：`inherited` `shadowed` `metamethod` `invalid` `readOnly` `lazy` `pinned` `truncated` `helperError` |
| `objectId` | string | table / userdata / function / thread 的地址 hex，本次暂停内有效（第 9 节） |
| `length` | integer | 字符串的完整字节长度；容器的元素总数（`namedCount + indexedCount` 之外的来源，如 TArray） |
| `location` | object | `{ chunkName, line }`：Lua 函数定义处、协程栈顶、模块文件 |
| `hint` | string | 行尾提示文字（例 `/Game/Maps/Main…`、`被第 12 行遮蔽`），IDE 灰色显示 |
| `children` | object | 只在请求了 `expand` 时出现：`{ variables, total, groups, sorted }`，结构同 `variables` 的 body |

已有字段的补充约定：

- `value`：按第 4 节生成的摘要，受 `maxValueLength` 约束。
- `typeName`：按 6.3 推断；数组 / 哈希的数量**不**写进 `typeName`，IDE 用 `indexedCount` / `namedCount` 拼。
- `evaluateName`：runtime 生成，用 valuePath 语法拼接父路径和 5.2 的片段；键无法用 valuePath 表示（boolean / table 键）、继承成员（valuePath 默认不走 `__index`）、helper 生成的虚拟子项时**不给**，IDE 对这些节点禁用「添加到监视 / 复制路径」。
- `ref`：分组节点（`kind = group`）总是 > 0。

### 13.3 `variables` 新增参数和返回

```json
{ "command": "variables",
  "args": { "ref": 201, "start": 0, "count": 100, "filter": null,
            "view": "auto", "maxValueLength": 256,
            "expand": [["self"], ["self", "items"]] } }
→ { "ok": true, "body": {
      "variables": [ Variable, ... ], "total": 4,
      "groups": [ { "name": "继承自 BagPanel", "kind": "group", "ref": 377, "value": "module UI.BagPanel", "namedCount": 23,
                    "location": { "chunkName": "@Content/Script/UI/BagPanel.lua", "line": 1 } } ],
      "sorted": true, "truncated": false } }
```

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `view` | `auto` | `auto`：本文规则；`raw`：`lua_next` 原始顺序、不分组、不调 helper / 宿主 / `__tostring`，元表作为 `(metatable)` 伪项（同 v3.0） |
| `maxValueLength` | 256 | 本次返回的每个 `value` 的最大字节数，上限 1024 |
| `expand` | 无 | 10.4。名字是 `variables` 返回的显示名 |

| 返回 | 说明 |
| --- | --- |
| `groups` | 底部分组（5.4），只在 `start = 0` 且 `filter` 不是 `indexed` 时返回，不计入 `total` |
| `sorted` | 字段段是否按 5.1 排序（10.2） |
| `truncated` | 因字节上限少给了项（10.3） |

### 13.4 `setViewRules`（能力 `viewRules`）

```json
{ "command": "setViewRules", "args": { "rules": { ...第 11.1 节文件内容... } } }
→ { "ok": true, "body": { "accepted": 12, "warnings": [ { "type": "ItemData", "message": "summary 里的 {cnt} 不是合法 valuePath" } ] } }
```

- 整体替换。runtime 预解析模板，非法的规则跳过并在 `warnings` 里说明，不影响其他规则。
- 推荐在握手后的初始化序列里，`loadHelpers` 之后、`setOptions` 之前发送。
- 对之后新建的引用生效；已经取过的节点不变。IDE 收到 response 后刷新变量树（重新取作用域 = 新引用），所以右键固定 / 隐藏在当前这次暂停里就能看到。
- 实现：摘要模板只用于实例（`object` / `record`），类表和模块表照常；`{路径}` 里的字符串放原文、其他按 4.1 的嵌套写法，取不到写 `?`；只有 `pin` 没有 `summary` 时摘要是 `{固定字段=值, …}`。`hide` 也作用于继承分组里的成员。规则优先于 `__tostring`。

### 13.5 `setOptions` 新增

```json
{ "command": "setOptions", "args": { "variableView": {
    "sort": "smart", "groupMethods": true, "showInherited": true, "showMetatable": true,
    "toString": "tables", "hideStdGlobals": true, "showTemporaries": false,
    "maxSortKeys": 10000, "helperBudgetMs": 20 } } }
```

| 字段 | 默认 | 取值 |
| --- | --- | --- |
| `sort` | `smart` | `smart` / `name` / `none` |
| `groupMethods` | `true` | 5.1 |
| `showInherited` | `true` | 6.2 |
| `showMetatable` | `true` | 6.1 |
| `toString` | `tables` | `tables` / `all` / `never`（第 12 节） |
| `hideStdGlobals` | `true` | 7.4 |
| `showTemporaries` | `false` | 7.1 |
| `maxSortKeys` | `10000` | 10.2 |
| `helperBudgetMs` | `20` | 11.2 |

### 13.6 宿主值提供者 C ABI v2

v1（`Emmy_RegisterHostValueProvider`，一次 `describeUserdata`）只能给一行摘要，列不了子项，也拿不到 Lua 值对应的引擎对象。v2 是新的注册入口，和 v1 并存；runtime 优先用 v2。UEKit 的 UELink 已按这一版实现（`plugins/UELink/Source/UELinkCore/Private/UELinkEmmyHostValueAbi.h` 是副本，两边同步改）。

```c
#define EMMY_HOST_VALUE_ABI_VERSION 2u

typedef void (EMMY_HOST_API_CALL *EmmyHostWriteFn)(void* sink, const char* utf8, size_t length);

typedef struct EmmyHostValueQuery {
    uint32_t size, version;
    uint64_t vmId;
    uint64_t pauseId;          /* 同一暂停内宿主发出的 childRef 有效 */
    struct lua_State* L;       /* 暂停所在的 lua_State，只在本次调用内有效；hostRef 非空时可为 NULL */
    int32_t index;             /* 被查询的 userdata / table 在 L 栈上的绝对索引 */
    const char* hostRef;       /* 宿主之前给出的 childRef；NULL 表示查询栈上的值 */
    uint32_t start, count;     /* listChildren 分页 */
    uint32_t maxValueLength;
    uint64_t deadlineUnixMillis;
    void* sink; EmmyHostWriteFn write;   /* 宿主把 UTF-8 JSON 结果写进来，可分多次 */
} EmmyHostValueQuery;

/* 返回 EmmyHostValueStatus：OK 认领；UNAVAILABLE 不认领（runtime 交给 helper / 默认显示）；
 * OBJECT_INVALID 认领但对象已失效（结果 valid=false）；NEEDS_GAME_THREAD 不在能读引擎对象的线程上 */
typedef int  (EMMY_HOST_API_CALL *EmmyHostQueryFn)(void* userData, const EmmyHostValueQuery* query);
typedef void (EMMY_HOST_API_CALL *EmmyHostReleaseFn)(void* userData, uint64_t vmId, uint64_t pauseId);

typedef struct EmmyHostValueProviderV2 {
    uint32_t size, version;
    void* userData;
    const char* name;
    EmmyHostQueryFn describe;       /* 写 {valid, typeName, summary, hint?, kind, expandable} */
    EmmyHostQueryFn listChildren;   /* 写 {valid, total, children:[{name, type, value, typeName?, kind?, attrs?, hint?, length?, expandable?, childRef?}]} */
    EmmyHostReleaseFn releasePause; /* 暂停结束时调用，宿主释放这次暂停发出的句柄 */
} EmmyHostValueProviderV2;

EMMY_HOST_API_EXPORT uint64_t EMMY_HOST_API_CALL Emmy_RegisterHostValueProviderV2(const EmmyHostValueProviderV2* provider);
EMMY_HOST_API_EXPORT int      EMMY_HOST_API_CALL Emmy_UnregisterHostValueProviderV2(uint64_t providerId);
// 可选（宿主先查导出再用）：id 是否还注册着。同一组回调 + userData 重复注册返回原 id。
EMMY_HOST_API_EXPORT int      EMMY_HOST_API_CALL Emmy_HasHostValueProviderV2(uint64_t providerId);
```

runtime 侧约定：

- 生成 userdata 或 table 的 `Variable` 时先调 `describe`（L + index）。认领了就用它的 `typeName` / `summary` / `hint` / `kind`；`valid = false` 时摘要是 `<已失效>`、带 `attrs: ["invalid"]`、没有子项，**之后不再对这个值调用 helper 或 `__tostring`**。
- userdata 被认领时，它的子项全部来自 `listChildren`（L + index）；table 被认领时（UnLua 实例表），自己的 Lua 字段照常列出，再加一个分组「UE 对象 名字」，分组的子项来自 `listChildren`（第 6.4 节）。
- 子项里 `expandable` 为真的，runtime 分配协议 `ref` 并记住 `childRef`；展开时用 `hostRef = childRef` 再调 `listChildren`。`kind = "group"` 的子项当分组节点显示。
- 回调只在暂停循环里、VM 所在线程上调用；调用期间屏蔽断点和单步（第 12 节）。暂停结束时调 `releasePause`。
- 宿主的写入可能分多次，runtime 拼接后解析；解析失败当作未认领，并输出一条 `runtime` 警告。

### 13.7 MCP

`lua_debug_get_variables` 返回同一模型渲染的文本：每行 `名字 = {类型} 摘要`，缩进表示层级，分组以 `▸ 分组名 (n) ref=377` 一行表示（不自动展开），`depth` 参数超出时只给 `ref`。字段说明里写清楚「继承成员和方法在分组里，用 ref 继续展开」。

---

## 14. runtime 实现要点

给原生核心（`native/emmy_debugger_core`）：

1. **引用表**：每次暂停一个。`ref → { 值（在 registry 的暂停缓存表里持有）, 视图（auto/raw/分组类型）, 键快照（懒建） }`。暂停结束整表丢弃、registry 缓存表清空，`ref` 回 `STALE_REFERENCE`。
2. **键快照**：第一次取子项时 `lua_next` 一遍，分出数组段（`1..rawlen`）和字段段，字段段按 5.1 排序（键数 ≤ `maxSortKeys`），函数成员单独记一份。键存进缓存表里的 Lua 数组（保证 table 键 / userdata 键不被 GC），C++ 侧只存下标。
3. **摘要**：生成子项 `Variable` 时顺带生成摘要；嵌套 table 摘要只看前 5 项、不递归超过一层，单个摘要的工作量有上限（最多 5 次 `lua_next` + 5 次取值）。
4. **类名推断缓存**：同一暂停内按元表地址缓存 6.3 的结果；`package.loaded` 反查表懒建一次。
5. **继承分组**：走 `__index` 链只用 `lua_rawget`；每层的「已覆盖」判断用一个本请求内的键集合。
6. **执行代码的调用**：统一走一个 `SafeCall(fn, budgetMs)`：COUNT 钩子预算、栈平衡检查、调用期间屏蔽断点和单步、错误转成字符串。
7. **字节预算**：编码 response 时累计字节，到 256 KiB 停止添加并设 `truncated`。
8. **协议外不变**：所有这些都在 VM 所在线程、暂停循环里执行（协议第 18 节）。

---

## 15. IDE 实现要点

给 IDE 引擎（`debugger-engine`）和 XDebugger 适配层：

### 15.1 共享模型（engine，无 IntelliJ 依赖）

引擎线程已定：值模型是 `VariableHandle`（包着协议的 `VariableDto` + 所属暂停），UI 和 MCP 共用，XValue 只做渲染。本文只要求在它上面加**可选**字段，没协商 `richVariables` 时都是 `null`、行为不变：

```kotlin
// VariableDto 追加（全部可选）
val kind: String?, val attrs: List<String>?, val objectId: String?, val length: Long?,
val location: LocationDto?, val hint: String?, val children: VariablesBody?   // children 只在 expand 时有

// VariablesArgs 追加
val view: String? /* "auto" | "raw" */, val maxValueLength: Int?, val expand: List<List<String>>?

// VariablesBody 追加
val groups: List<VariableDto>?, val sorted: Boolean?, val truncated: Boolean?
```

`VariableService` 需要的能力：按 `(ref, filter, start, count, view, expand)` 取一页；取字符串完整值（`evaluate` + `maxLength`）。

- 页缓存按 `(pauseId, ref, filter, start, view)`，暂停结束整体清掉。
- 没协商 `richVariables` 时（旧 runtime 不会出现，但假 runtime / 测试可能）由引擎把 v3.0 的返回转换成同一模型：IDE 侧自己排序、把 `(metatable)` 伪项挪进 `groups`。

### 15.2 XDebugger 适配层

- 一个 `LuaValueNode : XNamedValue`，按 `kind` 选图标和 `XValuePresentation`：键名、类型标签、摘要分色（字符串绿色、数字蓝色、失效红色、遮蔽 / 继承灰色）。
- `computeChildren`：数组段 > 100 切区间分组（`XValueGroup`，`addTopGroup`）；字段段一页一批 `addChildren(list, last = false)`，剩余用 `tooManyChildren(remaining, loadMore)`；`groups` 用 `addBottomGroup`。
- 字符串 `length > value 长度` 时 `setFullValueEvaluator`。
- 函数 / 协程 / 模块的 `location`：`computeSourcePosition` + `computeInlineDebuggerData`，支持 Jump to Source。
- 循环引用：`LuaValueNode` 持有父链，构造时比对 `objectId`。
- 变化高亮：用平台自带的。IntelliJ 换帧时按 `XStackFrame.getEqualityObject()` 保存 / 恢复变量树状态，恢复时把名字路径相同、显示值不同的节点标成 changed（`XDebuggerTreeRestorer` → `markChanged`）。所以栈帧的身份必须跨暂停稳定：用「VM + 协程 + chunk + 定义行 + 函数名」，不用每次暂停都换新的 `frameId`。同时也让单步后展开状态自动恢复。只比较展开过、可见的节点。
- 全部请求在后台协程，EDT 只做 `setPresentation` / `addChildren`。
- 删掉旧 `LuaXValue.kt` 的 `VariableComparator`、`buildInlineSummary`、`evalExpr` 拼接、对 userdata 的特殊绕行（由 runtime 安全策略取代）。

### 15.3 设置

放在 UI 线程已有的 `debug/ui/settings/LuaDebugSettings.kt`（`State` 里加一个 `VariableViewSettings` 子对象）和 `LuaDebugConfigurable.kt`（加「变量视图」group），文案进 `messages/LuaDebugBundle*.properties`。字段与 13.5 一一对应，另加三项只在 IDE 用的：

| 设置 | 默认 | 对应 |
| --- | --- | --- |
| 排序 | 智能 | `sort` |
| 函数成员单独分组 | 开 | `groupMethods` |
| 显示继承成员 | 开 | `showInherited` |
| 显示元表 | 开 | `showMetatable` |
| 调用 `__tostring` | 仅 table | `toString` |
| 全局变量隐藏标准库 | 开 | `hideStdGlobals` |
| 显示临时值 | 关 | `showTemporaries` |
| 每页条数 | 100 | IDE：`variables.count` |
| 字符串预览长度 | 256 | IDE：`maxValueLength` |
| 整数同时显示十六进制 | 关 | IDE 渲染 |
| 显示规则文件 | `.emmylua/debug-view.json` | IDE：第 11.1 节 |

设置变化时 IDE 发 `setOptions { variableView }` 并刷新可见部分。

### 15.4 图标

| kind | 图标 |
| --- | --- |
| `array` / `ueContainer` | 数组 |
| `record` | 表 |
| `object` / `ueObject` | 对象（类图标） |
| `class` / `module` | 类 / 模块 |
| `function` / `cfunction` | 方法（C 函数带小标） |
| `coroutine` | 线程 |
| `group` | 分组；继承分组用「父类」图标 |
| `integer` / `float` / string / boolean / nil | 基本值 |
| `invalid` 属性 | 叠加错误标记 |

---

## 16. 交互

变量树、监视、悬停窗口里节点的右键菜单：

| 动作 | 适用 | 实现 |
| --- | --- | --- |
| 复制值 | 所有 | 字符串取完整值（`evaluate maxLength`），table 复制摘要 |
| 复制为 JSON | table / 结构体 | `evaluate`（valuePath 模式）+ runtime 序列化，深度 ≤ 5、≤ 1 MiB，循环处写 `"<cycle>"` |
| 复制路径 | 有 `evaluateName` 的 | `evaluateName` |
| 添加到监视 | 同上 | 平台自带 |
| 修改值 | locals / upvalues / table 字段 | `setVariable` |
| 以文本 / JSON 查看 | 字符串、table | 弹出查看器，JSON 自动格式化 |
| 跳转到定义 | 函数、模块、协程 | `location` |
| 跳转到类型定义 | 有类名的 | 用 EmmyLua 索引找 `---@class 类名` 或同名模块文件 |
| 原始视图 | table / userdata | 以 `view = raw` 重新展开该节点 |
| 固定到顶部 / 取消固定 | 有类名的对象的字段 | 改显示规则文件的 `pin` |
| 隐藏此字段 | 同上 | 改显示规则文件的 `hide` |
| 标记对象 | 有 `objectId` 的 | 平台 Mark Object |
| 在调用栈中查看 | 协程 | 切换线程 |

悬停（编辑器里鼠标停在变量上）：直接显示同一棵树的根节点，首层自动展开。内联值（行尾灰字）：只用 `value`，不展开任何东西；只请求编辑器可见行涉及的名字（协议第 18 节已约定）。

---

## 17. 性能目标与验收

在设计文档 5.3 的环境（PC 本机附加 / Android USB）下，以 runtime 内耗时和 IDE 端到端各测一次：

| 场景 | runtime 内 | 端到端（PC） | 端到端（Android USB） |
| --- | --- | --- | --- |
| 暂停后显示局部变量（20 个，含 3 个 table） | ≤ 2 ms | ≤ 50 ms | ≤ 100 ms |
| 恢复 3 层展开状态（`expand`） | ≤ 5 ms | ≤ 60 ms | ≤ 120 ms |
| 展开 100 字段的 table | ≤ 2 ms | ≤ 30 ms | ≤ 60 ms |
| 展开 10 万项数组的第一个区间 | ≤ 2 ms（不排序） | ≤ 30 ms | ≤ 60 ms |
| 展开 5 万键哈希表第一页（含排序） | ≤ 40 ms | ≤ 80 ms | ≤ 120 ms |
| 同一表的后续页 | ≤ 1 ms | ≤ 30 ms | ≤ 60 ms |
| 展开带 400 个属性的 UObject（来源 A） | ≤ 10 ms | ≤ 50 ms | — |
| 单个 response 大小（100 项一页） | ≤ 32 KiB 典型、≤ 256 KiB 上限 | | |

验收：`native/tests` 加 `bench_variables`（用固定种子生成上述数据结构，跑 1000 次取 p50/p95），CI 记录并在回退超过 20% 时失败；IDE 端用假 runtime 加注入延迟（5 / 20 ms RTT）测端到端。

---

## 18. 测试

一个专门的 Lua fixture（`native/tests/fixtures/variables.lua`）覆盖所有形态，runtime 用 golden JSON 断言 `variables` 的输出，IDE 用同一份 golden 做渲染快照测试：

- 数组：空表、`{1,2,3}`、10 万项、有空洞、`[0]` 和负数下标、超过 `rawlen` 的整数键
- 哈希：标识符 / 非标识符字符串键、关键字键（`["end"]`）、浮点键、boolean 键、table 键、自然排序（`item2`/`item10`）、大小写
- 混合：数组 + 哈希 + 函数
- OO：三层继承（含覆盖）、`__index` 是函数、`__index` 成环、`__name` / `__cname` / 模块反查三种类名来源
- 元方法：`__tostring` 正常 / 报错 / 死循环（预算中止）、`__pairs` / `__len` 不被调用（用计数器断言）
- 循环引用：自引用、两表互引、经过元表的环
- 字符串：长字符串、含 `\0` 和非 UTF-8 字节、JSON 字符串
- 数字：整数 / 浮点 / `nan` / `inf` / `-0.0` / `math.maxinteger`
- 函数：Lua 函数（各 Lua 版本参数名）、C 函数、有 upvalue 的闭包
- 协程：suspended（看它的局部变量）、dead、normal
- userdata：无元表、有 `__name`、helper 认领、helper 报错、模拟 UE 对象（有效 / 失效，失效时断言没有任何属性读取发生）
- 作用域：同名局部变量遮蔽、临时值、可变参数、全局的内置分组
- 分页一致性：分页过程中 `setVariable` 改表，旧页不乱、刷新后新 ref 正确
- `expand`：多路径、路径不存在、超过限制

每个 Lua 版本（5.1 / 5.2 / 5.3 / 5.4 / 5.5 / LuaJIT）都跑同一套，差异（例如 5.1 没有整数类型）在 golden 里按版本分支。

---

## 19. 分工与阶段

| 阶段 | 内容 | 负责 |
| --- | --- | --- |
| V0 | 本文评审；协议增量合进 `protocol-v3.md`（加变更记录） | 本线程 + 协议 / 原生核心线程 |
| V1 | runtime：`richVariables` 全部（键快照、智能排序、分组、继承、摘要、`expand`、`objectId`、函数 / 协程显示、作用域规则、安全调用）；fixture + golden + `bench_variables` | 原生核心线程（本线程可承担 fixture 和 golden） |
| V2 | IDE：`VariableDto` 可选字段、XDebugger 渲染（区间、分页、分组、图标、循环引用、完整值、跳转）和右键动作（挂在 `com.fixcode.intellij.lua.debug.xdebugger` 的 XValue 上）、MCP 文本渲染 | IDE 引擎线程；「变量视图」设置页（15.3）由本线程在 UI 线程的设置类上补 |
| V3 | `viewRules`：显示规则文件、`setViewRules`、固定 / 隐藏动作；helper v2 `registerFormatter`，UE 结构体 / 容器改写到 `v3/emmy_ue.lua`（已完成） | 本线程 |
| V4 | UE 来源 A：runtime 实现 13.6 的 v2 注册入口和调用；UEKit UELink 侧已完成（UELink 2.4.0-beta.4） | 原生核心线程 |
| V5（可选） | Mark Object、复制为 JSON、Android 来源 C（原生反射）。变化高亮已用平台机制做掉（15.2） | 视 V1–V4 结果再排 |

V1、V2 可以并行：IDE 先对着假 runtime 和 golden JSON 开发。

---

## 20. 待定

1. **函数成员分组的阈值**：现在是「有数据也有函数就分组」。如果用户觉得模块表里少量常量也触发分组不方便，可以改成「函数占比 < 50% 才分组」。先按简单规则实现，看反馈。
2. **继承分组默认展开与否**：默认折叠。OO 代码多的项目可能希望第一层继承默认展开，可加设置。
3. **跨暂停的对象标记**：地址会复用，目前不做；如果需要，可以让 runtime 在 registry 里用弱表给对象分配稳定 id（有少量开销）。
4. **UE 属性 `Category` 二级分组**：只有编辑器构建有元数据，真机没有，可能造成两边显示不一致，默认关闭。
5. **来源 C（Android 原生反射）**：依赖符号偏移，UE 版本差异大，等真机附加稳定后再评估。
