![logo](/snapshot/logo.png)

# EmmyLua for IntelliJ IDEA

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

EmmyLua 是一个功能强大的 Lua 语言支持插件，专为 IntelliJ IDEA 系列 IDE 设计。语言支持基于 EmmyLua 官方的 Rust 语言服务器 emmylua_ls（经 LSP4IJ 接入）；调试部分重写过：附加本机进程、Android 真机附加，以及给 AI Agent 用的 MCP 调试工具。

> 调试部分按 [调试插件重构设计](docs/design/2026-11-debugger-redesign.md) 重写（附加调试 + Android 真机附加 + MCP），旧的 Emmy 启动 / Emmy Attach / LuaPanda 调试方式已删除。原 EmmyLuaDebugger 原生调试器已并入本仓库 `native/` 目录，不再使用子模块。开发在 `dev` 分支进行。

## 📁 仓库结构

| 目录 | 内容 |
| --- | --- |
| `src/` | IntelliJ 插件：Lua 文件类型和词法高亮、LSP 客户端接入、UE 工作区探测、调试 IDE 侧 |
| `modules/` | 调试相关的独立 Kotlin 模块 |
| `native/` | 原生调试 runtime（原 EmmyLuaDebugger）：调试核心 `emmy_debugger_core`、Windows 附加注入、Android agent、测试 |
| `docs/design/` | 设计文档；`archive/` 下是历史设计 |
| `.github/scripts/` | CI 和本地开发用的脚本（如下载 emmylua_ls） |

## ✨ 主要特性

### 🔧 语言支持（emmylua_ls + LSP4IJ）

语言支持按 [语言支持迁移到 LSP](docs/design/lsp-migration.md) 改成了 IDE 只做编辑器外壳、语义分析交给 [emmylua_ls](https://github.com/EmmyLuaLs/emmylua-analyzer-rust)（EmmyLua 官方的 Rust 语言服务器，VS Code 版 EmmyLua 用的也是它）。IDE 里通过 [LSP4IJ](https://github.com/redhat-developer/lsp4ij) 和它通信，大项目不再在 IDE 里建 Lua 索引。

- **IDE 侧**：Lua 文件类型（`.lua`、`.lua.txt`）、语法和注解标签高亮、括号匹配、注释切换、新建 Lua 文件和实时模板
- **编辑小功能**（只靠词法器，不建语法树）：`then` / `do` / `function(...)` / `repeat` / `{` 后回车自动缩进并补上 `end` / `until` / `}`，打 `end` / `else` 自动回退缩进，`---` 注解行回车续行；Surround With（`if` / `do` / `for` / `function` / `--region`）；后缀模板（`.if` `.ifnot` `.nn` `.null` `.local` `.return` `.print` `.fori` `.forp` `.fora` `.par`）；意图动作「生成函数注解」；`.emmyrc.json` / `.luarc.json` 的字段补全和校验（JSON Schema）；项目树「外部库」里能浏览 UE 声明和额外库目录；拼写检查认得 Lua 关键字和标准库名
- **emmylua_ls 提供**：补全、跳转到定义 / 实现、查找引用、重命名、悬停文档、参数提示、内联提示、诊断和快速修复、格式化、语义高亮、代码折叠、转到符号、调用层次
- **新版注解语法**：泛型、`---@enum`、`---@overload` 等，和 VS Code / Neovim 上的 EmmyLua 行为一致
- **标准库**：emmylua_ls 内置，按配置的 Lua 版本（5.1–5.4、LuaJIT）选择
- **Unreal Engine / UnLua**：IDE 只打开 UE 项目里的 `Content/Script` 时，插件自动往上找 `.uproject`，把 UnLua「Generate IntelliSense」生成的目录（如 `Plugins/UnLua/Intermediate/IntelliSense`）加进库目录，蓝图类型和 `UE.xxx` 都能补全和跳转
- **上手检查**：打开 UE 项目时，如果还有没准备好的（语言服务没起来、没有 UE 声明、没开 UELink 后台生成、MCP 没装到项目），只弹一个「EmmyLua 上手检查」气泡；点开后一页列出每项状态和一键处理按钮，处理完自动刷新。随时可从「工具 → EmmyLua 上手检查…」打开，「不再提示」按项目记住

以前的 PSI 实现里这些功能**已去掉**：彩虹变量、内联变量等重构、Lua SDK 和 Lua 模块类型、LuaCheck 外部工具（诊断改由 emmylua_ls 做）。

### 🐛 调试功能
- **Lua 附加** - 附加到本机正在运行的进程（Windows），不用改游戏代码
- **Lua 真机附加** - 通过 adb 附加 Android 设备上的游戏进程，包内不需要加任何插件
- **断点** - 条件断点、命中次数、日志断点、Probe（不停下只记录值）、Lua 出错时暂停
- **变量和协程** - 变量分组展示、修改变量值、查看其他协程的调用栈
- **MCP 调试工具** - 让 Claude Code、Cursor 等 Agent 直接下断点、看栈和变量
- **MCP 代码查找** - Agent 用 `lua_code` 按名字找 Lua 函数 / 方法 / 类、按 require 路径找文件、看文件大纲、查引用（经 emmylua_ls，替代 UEToVS 的 Lua 工具）
- **UE 一键热重载** - 编辑器右键「重载到 UE」（Ctrl+Alt+R）或设置里打开「保存后自动重载」，经 UELink 让开着的 UE 编辑器 / 游戏重新加载 Lua 模块，不用附加
- **断点诊断** - 一直没对上代码的断点变灰并在悬停里说明原因；调试工具栏「诊断断点」列出加载过的 chunk 和建议的路径映射
- **调试控制台** - 交互式调试控制台，支持表达式求值
- **Lua 采样性能分析** - 调试窗口的「Lua 采样」页开始 / 停止采样，按函数看自身和总计占比、双击跳到源码，可导出折叠栈用 speedscope / flamegraph.pl 看火焰图；Android 真机附加也能用，Agent 用 `lua_debug_inspect action=profile`

## 🚀 安装方式

本插件依赖 **LSP4IJ** 插件。从磁盘安装本插件时，IDE 会提示同时安装 LSP4IJ，确认即可。emmylua_ls 已随插件打包（Windows x64 / arm64、macOS x64 / arm64、Linux x64 / arm64），不用另外安装。

### 从插件市场安装

插件市场里搜 **EmmyLua Enhanced**（正在准备上架，见 [发布到插件市场](docs/marketplace.md)）。注意市场里叫「EmmyLua」的是上游插件，和本插件用同一个语言 ID，不能同时启用（装本插件时 IDE 会提示停用上游 EmmyLua）。

### 从 GitHub Releases 安装

1. 从 [Releases](https://github.com/fixcode-h/IntelliJ-EmmyLua/releases) 页面下载最新版本
2. 进入 `File` → `Settings` → `Plugins`
3. 点击齿轮图标 → `Install Plugin from Disk...`
4. 选择下载的插件文件（.zip 格式），按提示下载缺少的 LSP4IJ
5. 重启 IDE

## 🛠️ 使用指南

### 语言支持配置

配置写在工作区根目录的 **`.emmyrc.json`**（emmylua_ls 也认 `.luarc.json`），改了自动生效，和 VS Code 版共用同一份。常用项：

```json
{
  "runtime": { "version": "Lua5.4" },
  "workspace": { "library": ["../Plugins/UnLua/Intermediate/IntelliSense"] },
  "diagnostics": { "disable": ["undefined-global"] }
}
```

完整字段见 emmylua_ls 的配置文档。插件设置页（`Settings` → `Languages & Frameworks` → `EmmyLua`）只有这几项，作为 `.emmyrc.json` 的补充：

- **emmylua_ls 路径**：留空用插件自带的；填了就用填的（自己编译的或包管理器装的）
- **默认 Lua 版本**：`.emmyrc.json` 没写 `runtime.version` 时使用
- **库目录**：额外加进索引的目录，和 `.emmyrc.json` 里的 `workspace.library` 叠加
- **UE 项目自动探测**：开关 UnLua IntelliSense 目录的自动添加，并显示探测结果
- **新建文件模板**：新建 Lua 文件时的默认内容

找不到 emmylua_ls 时编辑器顶部会提示，可以直接打开设置填路径。服务器日志和报文在 LSP4IJ 的「Language Servers」工具窗口里看。

### 类型注解

注解语法以 emmylua_ls 为准（兼容 LuaLS 的写法）：

```lua
---@class Player
---@field name string 玩家姓名
---@field level integer 玩家等级
local Player = {}

---@param name string
---@param level? integer 初始等级，默认 1
---@return Player
function Player.new(name, level)
    return setmetatable({ name = name, level = level or 1 }, { __index = Player })
end

---@generic T
---@param list T[]
---@return T?
local function first(list)
    return list[1]
end
```

### 调试配置

详细说明：[附加调试（Windows）](docs/user/attach.md)、[Android 真机附加](docs/user/android.md)、[UE 声明](docs/user/ue-declarations.md)、[Agent 调试和代码查找（MCP）](docs/mcp-debug-guide.md)。

1. **附加本机进程（Windows）**
   - `Run` → `Attach to Process…`，在列表里选「Lua 附加」下的游戏进程；或新建运行配置「Lua 附加」
   - 不需要在游戏里加任何代码，插件自动选择 32 / 64 位和 Lua 版本
   - UE 项目装了 UELink（UEKit）时，带名片的编辑器 / 游戏排在列表最前，并标出项目名和类型

2. **Android 真机附加**
   - 手机打开 USB 调试并用 adb 连上，游戏包需要可调试（debuggable）
   - 新建运行配置「Lua 真机附加」，选设备和进程后启动

3. **调试设置**在 `Settings` → `Tools` → `EmmyLua 调试`：变量展示规则、Agent 调试（MCP）状态、保存后自动重载到 UE 等；Agent 调试工具挂在 IDE 自带的 MCP 服务器上，在 `Settings` → `Tools` → `MCP 服务器` 里开启；出错时暂停在断点窗口的「Lua 错误」里打开

4. **断点不触发**：断点图标变灰时把鼠标放上去看原因；调试中点调试工具栏（或 `Run` 菜单）的「诊断断点」，能看到游戏执行过的 chunk 名、每个断点的匹配结果，有建议的映射时可以一键应用

## 🔧 支持的 IDE 版本

- **IntelliJ IDEA** (Community & Ultimate)
- **PyCharm** (Community & Professional)
- **WebStorm**
- **PhpStorm**
- **RubyMine**
- **CLion**
- **GoLand**
- **DataGrip**
- **Rider**
- **Android Studio**

**版本要求：**
- **最低版本：** 2025.2，之后的版本（2025.3、2026.x）都能装，CI 逐个大版本做兼容校验
- **依赖插件：** LSP4IJ（安装时 IDE 自动提示）

## ⚠️ 已知限制

- **调试只支持 Windows 本机附加和 Android 真机附加。** macOS / Linux 上语言支持正常，但不能调试；iOS 不支持（不改包没有可靠方案）。
- **不支持 LuaJIT。** 附加到 LuaJIT 进程会直接提示；插件面向 UE + UnLua（Lua 5.4），Windows 附加也支持 Lua 5.1–5.3。Android 真机只支持 Lua 5.4。
- **没有内嵌模式。** 游戏里 `require("emmy_core")` 再 `tcpListen` 的旧用法已经去掉，只能附加。
- **断点所在函数会变慢。** 没有断点、断点在别的文件时游戏基本不受影响；但正在执行的函数里有断点时，这个函数每一行都要回调一次调试器，紧循环可能慢一个数量级（Lua 钩子本身的开销）。调完记得删掉这类断点。数字见 [native/emmy_debugger_core/README.md](native/emmy_debugger_core/README.md#性能)。
- **Android 真机附加需要可调试的包和对应的符号。** 正式签名包只能在 root 设备上附加（暂不支持）。

## 📝 更新日志

- **语言支持迁到 LSP**：改用 emmylua_ls + LSP4IJ，见 [设计文档](docs/design/lsp-migration.md)
- **重写调试部分**：只保留「Lua 附加」和「Lua 真机附加」两种方式，删除旧的 Emmy / LuaPanda 调试；新协议 v3、新原生调试核心；内置 MCP 调试工具

每个版本的提交列表见 [Releases](https://github.com/fixcode-h/IntelliJ-EmmyLua/releases)。

## 🤝 贡献指南

我们欢迎社区贡献！请遵循以下步骤：

### 开发环境设置
1. **克隆仓库**
   ```bash
   git clone https://github.com/fixcode-h/IntelliJ-EmmyLua.git
   cd IntelliJ-EmmyLua
   git switch dev
   ```

2. **准备 emmylua_ls**（二选一）
   - 下载到 `build/emmylua-ls/`，`runIde` / 打包时会放进插件的 `server/` 目录（版本取 `gradle.properties` 的 `emmyLuaLsVersion`；需要 `curl`、`jq`、`unzip`，被 GitHub API 限流时设置 `GITHUB_TOKEN`）：
     ```bash
     .github/scripts/fetch-emmylua-ls.sh                  # 全部平台
     .github/scripts/fetch-emmylua-ls.sh "" build/emmylua-ls linux-x64   # 只要当前平台
     ```
   - 或者不下载，在沙箱 IDE 的 EmmyLua 设置里填本机 emmylua_ls 的路径

3. **构建项目**
   ```bash
   ./gradlew build
   ```

4. **运行测试**
   ```bash
   ./gradlew test
   ```

### 贡献流程
1. Fork 本仓库
2. 基于 `dev` 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 向 `dev` 创建 Pull Request

### 代码规范
- 遵循现有的代码风格
- 添加适当的注释和文档
- 确保所有测试通过
- 更新相关文档

## 🐛 问题反馈

遇到问题？请通过以下方式反馈：

1. **GitHub Issues**：[提交问题](https://github.com/fixcode-h/IntelliJ-EmmyLua/issues)
2. **功能请求**：使用 "enhancement" 标签
3. **Bug 报告**：使用 "bug" 标签，并提供详细的复现步骤

### 报告 Bug 时请包含：
- IDE 版本和插件版本
- 操作系统信息
- 详细的复现步骤
- 相关的错误日志
- 最小化的示例代码

## 📄 许可证

本项目基于 [Apache License 2.0](LICENSE.txt) 许可证开源。

## 🙏 致谢

- 本项目最初基于 [@tangzx](https://github.com/tangzx) 的 EmmyLua 插件和 EmmyLua 团队的 EmmyLuaDebugger 开发，现独立维护；原作者版权声明按 Apache License 2.0 保留在源码和 [LICENSE](LICENSE.txt) 中
- 感谢 JetBrains 提供的优秀 IDE 平台
- 感谢 Lua 社区的支持和反馈

## 📞 联系方式

- **仓库：** [github.com/fixcode-h/IntelliJ-EmmyLua](https://github.com/fixcode-h/IntelliJ-EmmyLua)
- **问题反馈：** [GitHub Issues](https://github.com/fixcode-h/IntelliJ-EmmyLua/issues)

## 🌟 支持项目

如果这个插件对您有帮助，请考虑：

- 给我们一个 ⭐ **Star**
- 分享给其他 Lua 开发者
- 参与项目贡献

---

**让 Lua 开发更加高效！** 🚀
