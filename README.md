![logo](/snapshot/logo.png)

# EmmyLua for IntelliJ IDEA

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![JetBrains Plugin](https://img.shields.io/jetbrains/plugin/v/9768-emmylua.svg)](https://plugins.jetbrains.com/plugin/9768-emmylua)

EmmyLua 是一个功能强大的 Lua 语言支持插件，专为 IntelliJ IDEA 系列 IDE 设计。在原有 EmmyLua 功能基础上，本版本特别增加了 **LuaPanda 调试模式**，为 Lua 开发者提供更完整的开发体验。

## ✨ 主要特性

### 🔧 核心功能
- **智能代码补全** - 基于类型推断的智能补全，支持函数、变量、字段等
- **语法高亮** - 完整的 Lua 语法高亮支持
- **代码导航** - 快速跳转到定义、查找引用、符号搜索
- **代码重构** - 重命名、提取方法、内联变量等重构操作
- **错误检测** - 实时语法错误检测和智能修复建议
- **代码格式化** - 自动代码格式化和代码风格检查

### 🐛 调试功能
- **LuaPanda 调试器集成** - 支持断点调试、单步执行、变量查看
- **Emmy 调试器支持** - 内置 Emmy 调试器，支持多平台调试
- **远程调试** - 支持远程 Lua 应用程序调试
- **调试控制台** - 交互式调试控制台，支持表达式求值

### 📚 文档支持
- **快速文档** - 鼠标悬停显示函数文档
- **参数提示** - 函数调用时的参数提示
- **类型注解** - 支持 EmmyLua 注解语法，提供更好的类型推断

### 🎯 高级功能
- **多 Lua 版本支持** - 支持 Lua 5.0/5.1/5.2/5.3/5.4
- **标准库支持** - 内置完整的 Lua 标准库定义
- **代码检查** - 代码质量检查和最佳实践建议
- **结构视图** - 文件结构树状视图
- **方法分隔符** - 可视化方法分隔线

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
1. 打开 IntelliJ IDEA
2. 进入 `File` → `Settings` → `Plugins`
3. 搜索 "EmmyLua"
4. 点击 `Install` 安装插件
5. 重启 IDE

### 方式二：手动安装
1. 从 [Releases](https://github.com/tangzx/IntelliJ-EmmyLua/releases) 页面下载最新版本
2. 进入 `File` → `Settings` → `Plugins`
3. 点击齿轮图标 → `Install Plugin from Disk...`
4. 选择下载的插件文件
5. 重启 IDE

## 🛠️ 使用指南

### 基础配置
1. 创建或打开 Lua 项目
2. 确保文件扩展名为 `.lua`
3. 插件会自动识别并提供语言支持

### 调试配置
1. 配置 LuaPanda 调试器：
   - 在项目中添加 LuaPanda 调试库
   - 配置调试启动参数
   - 设置断点开始调试

2. 使用 Emmy 调试器：
   - 创建 Emmy 调试配置
   - 设置目标 Lua 应用程序
   - 启动调试会话

### 类型注解
使用 EmmyLua 注解语法提供更好的代码智能：

```lua
---@class Player
---@field name string
---@field level number
local Player = {}

---@param name string
---@param level number
---@return Player
function Player:new(name, level)
    local obj = {
        name = name,
        level = level
    }
    setmetatable(obj, self)
    self.__index = self
    return obj
end
```

## 🔧 支持的 IDE 版本

- IntelliJ IDEA (Community & Ultimate)
- PyCharm
- WebStorm
- PhpStorm
- RubyMine
- CLion
- GoLand
- DataGrip
- Rider
- Android Studio

**最低版本要求：** 2025.1+

## 📝 更新日志

### 最新版本特性
- ✅ 增强的 LuaPanda 调试支持
- ✅ 改进的代码补全算法
- ✅ 优化的性能表现
- ✅ 修复的已知问题

查看完整的 [更新日志](CHANGELOG.md)

## 🤝 贡献指南

欢迎贡献代码！请遵循以下步骤：

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

## 📄 许可证

本项目基于 [Apache License 2.0](LICENSE.txt) 许可证开源。

## 🙏 致谢

- 感谢所有贡献者的努力
- 感谢 JetBrains 提供的优秀 IDE 平台
- 感谢 Lua 社区的支持

## 📞 联系方式

- **作者：** [**@tangzx** 阿唐](https://github.com/tangzx)
- **邮箱：** love.tangzx@qq.com
- **问题反馈：** [GitHub Issues](https://github.com/tangzx/IntelliJ-EmmyLua/issues)

---

如果这个插件对您有帮助，请给我们一个 ⭐ Star！