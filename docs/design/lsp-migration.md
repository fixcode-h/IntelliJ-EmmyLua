# 语言支持迁移到 LSP

日期：2026-11-16　状态：已实施

## 1. 为什么换

现在的语言支持是 2017 年上游 EmmyLua 的 PSI 实现（`com.tang.intellij.lua` 下的 Grammar-Kit 解析器、stub 索引、Java 写的类型推断）：

- 解析、索引、类型推断全在 IDE 进程里跑，大项目（UE + UnLua 生成的几万行声明）索引慢、占 IDE 堆、打字时推断卡。
- 类型系统停在旧版 EmmyLua 注解：泛型、`---@enum`、`---@overload`、条件类型这些新语法不认。
- 和 VS Code、Neovim 上的 EmmyLua 用的是两套实现，行为对不上，修一个 bug 要改两边。

EmmyLua 官方已经把分析器重写成 Rust 的语言服务器 **emmylua_ls**（`EmmyLuaLs/emmylua-analyzer-rust`，MIT）。VS Code 版 EmmyLua 已经只用它。我们改为 IDE 只做编辑器外壳，语义分析全部交给 emmylua_ls。

## 2. 总体结构

```
IDE 进程                                         emmylua_ls 进程（Rust）
┌──────────────────────────────────┐   stdio    ┌──────────────────────────┐
│ 语法层（本插件，无解析器）         │  LSP JSON  │ 解析 / 索引 / 类型推断     │
│  · 手写词法器：高亮、括号、注释     │ ─────────▶ │ 诊断 / 补全 / 跳转 / 引用  │
│  · 文件类型 Lua、扁平 PSI          │            │ 重命名 / 格式化 / 语义高亮 │
│ LSP 客户端：LSP4IJ                 │ ◀───────── │ 读 .emmyrc.json / .luarc  │
│ 工作区：UE 项目探测、库目录         │            └──────────────────────────┘
│ 调试链路（不依赖语义分析）          │
└──────────────────────────────────┘
```

### 2.1 LSP 客户端：选 LSP4IJ，不用平台自带 LSP API

| | LSP4IJ（Red Hat，EPL-2.0） | IntelliJ 平台 LSP API |
| --- | --- | --- |
| IDEA Community 2025.1 / 2025.2 | 支持 | **不支持**（2025.2 起只在 Ultimate 里，免费模式也只限 Ultimate 安装包） |
| 结构视图、面包屑、调用层次、类型层次 | 有 | 2025.2 没有 |
| 语义高亮、折叠、签名帮助、内联提示、Code Lens | 有 | 部分 |
| 服务器日志 / 报文查看（LSP 控制台） | 有 | 无 |
| 代价 | 用户要多装一个插件（安装本插件时 IDE 会提示一起装） | 无 |

我们基于 IC 2025.2 编译、出一个包（2026-11-16 起去掉了 2025.1，不设上限），平台 API 用不了；LSP4IJ 功能也更全。等 2025.3 合并版普及后再评估是否换平台 API（接口层收在 `lsp/` 包里，换起来只动这一层）。

版本：LSP4IJ 0.21.0（since 242），emmylua_ls 0.25.1。两个版本都写在 `gradle.properties`，升级只改这里。

### 2.2 语法层：留词法、丢解析器

IDE 里只保留不需要语义的东西，全部基于一个**手写的 Kotlin 词法器**（不再用 JFlex 生成代码）：

- 文件类型 `Lua`（`.lua`、`.lua.txt`），语法高亮（关键字、字符串、数字、注释、`---@` 注解标签），配色页。
- 括号匹配、`--` 注释切换、引号配对、回车缩进。
- 扁平 PSI：`ParserDefinition` 只把 token 平铺在文件节点下，不建语法树。平台的注释、括号、代码片段编辑器、断点都靠它工作，开销等于一次词法扫描。

语义上的东西（变量是局部还是全局、类名、函数调用）由 emmylua_ls 的**语义高亮**叠加上色。

### 2.3 功能对照

| 功能 | 旧实现 | 新实现 |
| --- | --- | --- |
| 跳转到定义 / 声明 / 类型定义 / 实现 | PSI reference + stub 索引 | LSP `definition` / `declaration` / `typeDefinition` / `implementation` |
| 补全（含注解补全） | 3 个 CompletionContributor | LSP `completion` + `completionItem/resolve` |
| 查找引用、高亮使用处 | FindUsagesHandler | LSP `references`、`documentHighlight` |
| 重命名 | RenamePsiElementProcessor | LSP `prepareRename` / `rename` |
| 悬停文档 | DocumentationProvider | LSP `hover` |
| 诊断（未定义变量、类型不匹配……） | 17 个 Inspection | LSP 诊断（pull + publish），规则在 `.emmyrc.json` 的 `diagnostics` 里开关 |
| 快速修复 | 部分 Inspection 自带 | LSP `codeAction` |
| 格式化 | 自写 FormattingModel | LSP `formatting` / `rangeFormatting`（emmylua_ls 内置 EmmyLuaCodeStyle，读 `.editorconfig`） |
| 语义高亮、彩虹变量 | Annotator + RainbowVisitor | LSP `semanticTokens`（彩虹变量不再提供） |
| 代码折叠 | FoldingBuilder | LSP `foldingRange` |
| 结构视图、面包屑、粘性行 | StructureViewFactory | LSP `documentSymbol` |
| 转到符号 / 类（Ctrl+Alt+Shift+N） | goto contributors | LSP `workspace/symbol` |
| 参数提示（Ctrl+P） | ParameterInfoHandler | LSP `signatureHelp` |
| 参数名 / 类型内联提示 | ParameterHintsProvider | LSP `inlayHint` |
| 调用层次 | CallHierarchyProvider | LSP `callHierarchy` |
| 继承关系行标记（覆盖 / 被覆盖） | LineMarkerProvider | LSP `codeLens`（emmylua_ls 给出「n 个引用 / 实现」） |
| 文档链接（`require("a.b")` 可点击） | reference | LSP `documentLink` + `definition` |
| 标准库声明 | 插件带的 `std/` | emmylua_ls 内置（按 `runtime.version`，带中文翻译） |
| 库目录、源码根 | Lua SDK、模块类型、Lua 源码根 | `.emmyrc.json` 的 `workspace.library` / `workspaceRoots`，加上插件自动探测（§3） |
| Lua 版本 | 设置里的语言级别 | `.emmyrc.json` 的 `runtime.version`；插件设置里选的版本作为默认值下发 |
| 新建 Lua 文件、实时模板 | 有 | 保留（只靠文件类型判断上下文，不用 PSI） |
| 后缀模板、Surround With、内联变量、移动文件改 require | 有 | **去掉**。LSP 没有对应能力；移动文件时 emmylua_ls 通过 `willRenameFiles` 能改 require 的就会改 |
| Lua SDK、Lua 模块类型、LuaCheck 外部工具 | 有 | **去掉**（诊断由 emmylua_ls 做） |

### 2.4 配置

emmylua_ls 自己读工作区根目录的 `.emmyrc.json`（也认 `.luarc.json`、`.emmyrc.lua`），改了自动重载。插件**不另造配置格式**：

- 插件通过 `workspace/configuration`（section `emmylua`）下发一份「部分配置」，emmylua_ls 把它合并在 `.emmyrc.json` 之后：对象逐项合并、数组取并集。所以用户写的库目录和插件探测到的库目录会叠加，不会互相覆盖。
- 插件下发的内容：自动探测到的 UE 库目录（§3）、设置页里填的额外库目录、默认 Lua 版本（只在 `.emmyrc.json` 没写时生效）。
- 设置页（设置 → 语言和框架 → EmmyLua）只放：emmylua_ls 路径（留空用插件自带的）、额外库目录、是否自动探测 UE 项目、默认 Lua 版本，和「打开 / 新建 .emmyrc.json」按钮。

### 2.5 服务器进程

- 每个 IDE 项目一个 emmylua_ls 进程，stdio 通信；关项目或设置变化时由 LSP4IJ 停掉 / 重启。
- 可执行文件随插件打包在 `<插件目录>/server/<平台>/emmylua_ls[.exe]`，平台为 `win32-x64`、`win32-arm64`、`darwin-x64`、`darwin-arm64`、`linux-x64`、`linux-arm64`。解压后丢了可执行位时插件补上。
- 设置里填了路径就用填的（自己编的版本或包管理器装的）；都找不到时在编辑器顶部提示，并给「打开设置」按钮。

## 3. UE / UnLua 工作区

IDE 常常只打开 UE 项目里的 `Content/Script`。UnLua 编辑器里「Generate IntelliSense」生成的蓝图和 UE 类型声明在项目外面（一般是 `<UE项目>/Plugins/UnLua/Intermediate/IntelliSense`），所以以前蓝图类型、`UE.xxx` 都解析不到。

插件启动服务器前做一次探测（复用 `UnrealIntelliSenseRoots`，纯 `java.nio`，已有单测）：

1. 从项目目录往上找含 `*.uproject` 的目录，最多 8 层。找不到就什么也不加。
2. 在 UE 项目下找这些目录，存在的都加进 `workspace.library`：
   - `Intermediate/IntelliSense`
   - `Plugins/<插件>/Intermediate/IntelliSense`，插件目录下没有时再看 `Plugins/<分类>/<插件>/Intermediate/IntelliSense`（UnLua 生成的就在 `Plugins/UnLua/Intermediate/IntelliSense`）
3. 加进库的目录只做索引和跳转，不报诊断；`Content/Script` 本身仍是工作区根，`require("A.B")` 按它解析。
4. 设置里可以关掉自动探测。探测结果在「EmmyLua」设置页上显示，方便确认。

IntelliSense 目录重新生成时，emmylua_ls 的文件监听会增量更新索引，不用重启。

### 3.1 UELink 后台增量生成（2026-11-16）

UnLua 的 Generate IntelliSense 要手动点、整遍生成。UEKit 的 UELink 2.5 起可以在 UE 编辑器后台生成同格式的声明（**默认关闭**，协议见 UEKit 的 `docs/protocol.md`「Lua 声明」）：

- 输出 `<UE项目>/Intermediate/UELink/LuaDecl/`（`Native/`、`Blueprint/`、`UE.lua`、拷来的 `UnLua/` 静态声明），清单 `.uelink-luadecl.json` 记每个文件的内容哈希；蓝图编译、资源保存 / 改名 / 删除、Live Coding 后只写内容变了的文件。
- `UnrealIntelliSenseRoots.find`：这个目录有清单时**只用它**，不再加 UnLua 生成的目录（同名类型重复定义会让跳转出现两个目标）；没有时照旧。
- 文件增删改由 emmylua_ls 自己监听库目录（库目录在工作区外时它用 notify 监听），不用重启；只有库目录本身变了（第一次生成完、删掉目录退回 UnLua）才要重启，`UnrealDeclWatcher` 每 15 秒看一眼几个路径，变了就重启。
- 开关在 IDE 设置里（项目设置 `unrealLuaDecl`，默认关）：勾上 / 取消时按名片 `Saved/UELink/instances/*.json` 找开着的编辑器调 `/rpc` 的 `luadecl.enable`（`UELinkClient`）；UE 那边也记在本机个人配置里。勾着时 `UnrealDeclWatcher` 发现新开的编辑器（PID 变了）还没开就补开；没勾时不动 UE 那边（可能是在 UE 控制台开的）。
- 主动刷新：工具菜单「UE 反射声明（EmmyLua）」和设置页按钮，`luadecl.run`：刷新（只写变了的）、补全全部蓝图（`loadBlueprints`）、全部重写（`force`）。

## 4. 调试链路

新调试链路（附加、Android 真机、MCP）本来就不靠类型推断，只有四处碰了旧 PSI，改法：

| 位置 | 旧依赖 | 改为 |
| --- | --- | --- |
| 行内变量值（`LuaInlineValuePainter`） | 遍历 PSI 找本行的局部变量、参数、赋值目标 | 用词法器扫当前行的名字 token，排除关键字和 `.`/`:` 后面的成员名，再按调试器给的变量表取值。效果相同，不用等索引 |
| 求值输入框（`LuaDebuggerEditorsProvider`） | `LuaExprCodeFragmentImpl` | 用 Lua 文件类型的普通代码片段（扁平 PSI），保留高亮和括号匹配 |
| 调用栈里的文件链接（`LuaTracebackFilter`） | `LuaFileUtil` 按源码根找文件 | 调试链路已有的 `IdeaSourceFileIndex`（按文件名索引 + 路径后缀匹配） |
| 断点类型、图标 | `LuaFileType`、`LuaIcons` | 搬到新包，不变 |

## 5. 删除和改名

- 删除：`psi/`、`stubs/`、`ty/`、`reference/`、`search/`、`codeInsight/`、`editor/`（除保留的编辑器小功能）、`comment/`、`refactoring/`、`hierarchy/`、`usages/`、`folding/`、`documentation/`、`annotator/`、`ext/`、`project/` 里的 SDK / 模块类型 / 源码根、`gen/` 整个目录、`*.flex`、`*.bnf`、`src/main/resources/std`、`src/main/compat`、对应的 inspection / intention 说明资源。
- 包名：剩下的代码（调试、MCP、Android、各 `modules/`）全部从 `com.tang.intellij.lua` 改为 `com.fixcode.intellij.lua`，目录一起改。
- **不改的持久化字符串**（改了用户设置会丢）：运行配置类型 ID、`@State` 的名字和存储文件（`EmmyLuaDebugSettings`、`emmylua-debug.xml` 等）、断点类型 ID、插件 ID `com.fixcode.emmylua.enhanced`、语言 ID `Lua`、文件类型名 `lua`。
- 版权头：上游文件删掉后自然没有了；新写的文件不加文件头。仍保留上游代码的文件保留原 Apache 头（许可证要求），仓库根 `LICENSE.txt` 不动。

## 6. 出包

- `gradle.properties` 写 `emmyLuaLsVersion`。`auto-release.yml` 和 `verify.yml` 在打包前按版本从 emmylua-analyzer-rust 的 Release 下载各平台压缩包，解出可执行文件放到 `build/emmylua-ls/<平台>/`，Gradle 的 `prepareSandbox` 把它拷进插件的 `server/` 目录。下载失败直接让出包失败，不出没有服务器的包。
- 本地开发：运行 `.github/scripts/fetch-emmylua-ls.sh "" build/emmylua-ls <平台>` 下当前平台的（不写平台就下全部）；不下也能编，运行时用设置里的路径或 PATH 里的 `emmylua_ls`。
- 自动版本号、按提交生成更新日志、回写 `version.properties` 的流程不变。

## 7. 性能目标

| 指标 | 目标 | 说明 |
| --- | --- | --- |
| 打字时高亮延迟 | 无可感知延迟 | 只有词法器在 IDE 里跑，单 token 增量重扫 |
| IDE 堆占用 | 不再随 Lua 代码量增长 | 索引都在 emmylua_ls 进程里，IDE 不建 stub 索引 |
| 冷启动到第一次跳转可用 | 5 万行声明（UnLua IntelliSense 量级）< 10 秒 | emmylua_ls 并行索引；之后的跳转、补全 < 100 毫秒 |
| 项目打开时 IDE 索引 | 不再出现 Lua 的「正在建索引」 | 去掉了 stub / 文件索引 |

## 8. 风险

- 用户要多装 LSP4IJ：本插件声明为必需依赖，IDE 安装本插件时会提示一起装；从磁盘装 zip 时也会提示下载缺的依赖。README 写清楚。
- emmylua_ls 和旧实现的诊断规则不完全一样，第一次用可能多出或少了一些提示。规则可在 `.emmyrc.json` 里关。
- 彩虹变量、后缀模板这些去掉的功能，有人用再按需补（可以基于词法器做）。
