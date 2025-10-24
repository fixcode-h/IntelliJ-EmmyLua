![logo](/snapshot/logo.png)

# EmmyLua for IntelliJ IDEA

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![JetBrains Plugin](https://img.shields.io/jetbrains/plugin/v/9768-emmylua.svg)](https://plugins.jetbrains.com/plugin/9768-emmylua)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/9768-emmylua.svg)](https://plugins.jetbrains.com/plugin/9768-emmylua)

EmmyLua 是一个功能强大的 Lua 语言支持插件，专为 IntelliJ IDEA 系列 IDE 设计。在原有 EmmyLua 功能基础上，本版本特别增加了 **LuaPanda 调试模式**，为 Lua 开发者提供更完整的开发体验。

## ✨ 主要特性

### 🔧 核心功能
- **智能代码补全** - 基于类型推断的智能补全，支持函数、变量、字段等
- **语法高亮** - 完整的 Lua 语法高亮支持，支持 Lua 5.0-5.4 所有版本
- **代码导航** - 快速跳转到定义、查找引用、符号搜索
- **代码重构** - 重命名、提取方法、内联变量等重构操作
- **错误检测** - 实时语法错误检测和智能修复建议
- **代码格式化** - 自动代码格式化和代码风格检查
- **代码折叠** - 支持函数、表、注释等代码块的折叠

### 🐛 调试功能
- **LuaPanda 调试器集成** - 支持断点调试、单步执行、变量查看
  - 自动版本检测和更新提醒
  - 支持条件断点和日志断点
  - 实时变量监视和表达式求值
  - 调用栈跟踪和线程管理
- **Emmy 调试器支持** - 内置 Emmy 调试器，支持多平台调试
- **附加调试** - 支持附加到正在运行的 Lua 进程进行调试
- **调试控制台** - 交互式调试控制台，支持表达式求值

### 📚 文档支持
- **快速文档** - 鼠标悬停显示函数文档
- **参数提示** - 函数调用时的参数提示和类型检查
- **类型注解** - 支持 EmmyLua 注解语法，提供更好的类型推断
- **智能提示** - 基于上下文的智能代码提示

### 🎯 高级功能
- **多 Lua 版本支持** - 支持 Lua 5.0/5.1/5.2/5.3/5.4
- **标准库支持** - 内置完整的 Lua 标准库定义
- **代码检查** - 代码质量检查和最佳实践建议
- **结构视图** - 文件结构树状视图
- **方法分隔符** - 可视化方法分隔线
- **Unreal Engine 支持** - 特别优化的 UE Lua 开发支持

## 📸 功能预览

| 功能 | 预览 |
|------|------|
| 代码补全与导航 | ![overview](/snapshot/overview.gif) |
| 查找引用 | ![find_usages](/snapshot/find_usages.gif) |
| 快速文档 | ![quick_documentation](/snapshot/quick_documentation.gif) |
| 重命名重构 | ![rename](/snapshot/rename.gif) |
| 跳转到类 | ![go_to_class](/snapshot/go_to_class.gif) |
| 跳转到符号 | ![go_to_symbol](/snapshot/go_to_symbol.gif) |
| 方法重写标记 | ![method_override](/snapshot/method_override_line_marker.gif) |
| 参数提示 | ![param_hints](/snapshot/param_hints.png) |
| 方法分隔符 | ![method_separators](/snapshot/method_separators.png) |
| 结构视图 | ![structure_view](/snapshot/structure_view.jpg) |

## 🚀 安装方式

### 方式一：通过 JetBrains 插件市场（推荐）
1. 打开 IntelliJ IDEA 或其他支持的 JetBrains IDE
2. 进入 `File` → `Settings` → `Plugins`（macOS: `IntelliJ IDEA` → `Preferences` → `Plugins`）
3. 在 `Marketplace` 标签页搜索 "EmmyLua"
4. 点击 `Install` 安装插件
5. 重启 IDE

### 方式二：手动安装
1. 从 [Releases](https://github.com/tangzx/IntelliJ-EmmyLua/releases) 页面下载最新版本
2. 进入 `File` → `Settings` → `Plugins`
3. 点击齿轮图标 → `Install Plugin from Disk...`
4. 选择下载的插件文件（.zip 格式）
5. 重启 IDE

## 🛠️ 使用指南

### 基础配置
1. **创建 Lua 项目**
   - 新建项目时选择 "Empty Project"
   - 确保项目中的 Lua 文件扩展名为 `.lua`
   - 插件会自动识别并提供语言支持

2. **配置 Lua SDK**（可选）
   - 进入 `File` → `Project Structure` → `SDKs`
   - 添加 Lua SDK 路径以获得更好的标准库支持

### 调试配置

#### LuaPanda 调试器配置
1. **安装 LuaPanda 库**
   ```bash
   # 使用 luarocks 安装
   luarocks install luapanda
   ```

2. **在项目中集成 LuaPanda**
   - 插件会自动检测并提示添加 LuaPanda.lua 文件
   - 或手动将 LuaPanda.lua 复制到项目根目录

3. **在代码中启用调试**
   ```lua
   require("LuaPanda").start("127.0.0.1", 8818)
   ```

4. **创建调试配置**
   - 进入 `Run` → `Edit Configurations`
   - 点击 `+` → `LuaPanda Debug`
   - 配置调试参数（主机、端口等）

#### Emmy 调试器配置
1. **创建 Emmy 调试配置**
   - 进入 `Run` → `Edit Configurations`
   - 点击 `+` → `Emmy Debugger`
   - 设置目标 Lua 应用程序路径

2. **启动调试会话**
   - 设置断点
   - 点击调试按钮启动

### 类型注解使用

EmmyLua 支持丰富的类型注解语法，提供更好的代码智能：

```lua
---@class Player : Object
---@field name string 玩家姓名
---@field level number 玩家等级
---@field inventory table<string, Item> 物品清单
local Player = {}

---创建新玩家
---@param name string 玩家姓名
---@param level number 初始等级
---@return Player 玩家实例
function Player:new(name, level)
    local obj = {
        name = name,
        level = level or 1,
        inventory = {}
    }
    setmetatable(obj, self)
    self.__index = self
    return obj
end

---@param item Item 要添加的物品
---@param count number? 数量，默认为1
function Player:addItem(item, count)
    count = count or 1
    -- 实现代码...
end

---@generic T
---@param list T[] 列表
---@return T? 第一个元素
function getFirst(list)
    return list[1]
end
```

### 高级功能

#### 代码检查配置
1. 进入 `File` → `Settings` → `Editor` → `Inspections`
2. 展开 `Lua` 分类
3. 启用或禁用特定的代码检查规则

#### 代码格式化配置
1. 进入 `File` → `Settings` → `Editor` → `Code Style` → `Lua`
2. 配置缩进、空格、换行等格式化选项

#### Unreal Engine 项目配置
1. 在项目设置中选择项目类型为 "Unreal Engine"
2. 配置 .uproject 文件路径
3. 享受针对 UE Lua 的特殊优化

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
- **最新版本：** 2025.2+ （推荐）
- **最低版本：** 2025.1+
- **Java 版本：** JDK 17+ （2025.1），JDK 21+ （2025.2）

## 📝 更新日志

### 最新版本特性 (v1.3.0+)
- ✅ **增强的 LuaPanda 调试支持**
  - 自动版本检测和更新提醒
  - 改进的断点管理和调试体验
  - 更稳定的远程调试连接
- ✅ **改进的代码补全算法**
  - 更准确的类型推断
  - 更快的补全响应速度
  - 支持泛型类型推断
- ✅ **优化的性能表现**
  - 减少内存占用
  - 提升大型项目的响应速度
  - 优化索引构建过程
- ✅ **新增功能**
  - 支持 Lua 5.4 新语法特性
  - 改进的 Unreal Engine 集成
  - 更好的错误报告机制
- ✅ **修复的已知问题**
  - 修复了多个代码补全相关的 bug
  - 解决了调试器连接问题
  - 改进了插件稳定性

查看完整的 [更新日志](CHANGELOG.md)

## 🤝 贡献指南

我们欢迎社区贡献！请遵循以下步骤：

### 开发环境设置
1. **克隆仓库**
   ```bash
   git clone https://github.com/tangzx/IntelliJ-EmmyLua.git
   cd IntelliJ-EmmyLua
   ```

2. **构建项目**
   ```bash
   ./gradlew build
   ```

3. **运行测试**
   ```bash
   ./gradlew test
   ```

### 贡献流程
1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

### 代码规范
- 遵循现有的代码风格
- 添加适当的注释和文档
- 确保所有测试通过
- 更新相关文档

## 🐛 问题反馈

遇到问题？请通过以下方式反馈：

1. **GitHub Issues**：[提交问题](https://github.com/tangzx/IntelliJ-EmmyLua/issues)
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

- 感谢所有 [贡献者](https://github.com/tangzx/IntelliJ-EmmyLua/graphs/contributors) 的努力
- 感谢 JetBrains 提供的优秀 IDE 平台
- 感谢 Lua 社区的支持和反馈
- 特别感谢 LuaPanda 项目的集成支持

## 📞 联系方式

- **作者：** [**@tangzx** 阿唐](https://github.com/tangzx)
- **邮箱：** love.tangzx@qq.com
- **问题反馈：** [GitHub Issues](https://github.com/tangzx/IntelliJ-EmmyLua/issues)
- **讨论交流：** [GitHub Discussions](https://github.com/tangzx/IntelliJ-EmmyLua/discussions)

## 🌟 支持项目

如果这个插件对您有帮助，请考虑：

- 给我们一个 ⭐ **Star**
- 在 [JetBrains 插件市场](https://plugins.jetbrains.com/plugin/9768-emmylua) 留下评价
- 分享给其他 Lua 开发者
- 参与项目贡献

---

**让 Lua 开发更加高效！** 🚀