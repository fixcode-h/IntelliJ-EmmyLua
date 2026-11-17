# UE 声明（跳转、补全 UE 类型）

`UE.AActor`、蓝图类、`UnLua.Class()` 这些能跳转、能补全，靠的是一组 Lua 声明文件。插件会自动把它们交给语言服务器 emmylua_ls，大多数情况下不用配置。

## 声明从哪里来

| 来源 | 怎么得到 | 特点 |
| --- | --- | --- |
| UnLua 自带 | UE 编辑器里 UnLua 的「Generate IntelliSense」 | 要手动点，整遍生成 |
| UELink 后台生成（推荐） | 设置 → 语言和框架 → EmmyLua → 勾选「让 UE 编辑器后台生成反射声明」 | 需要 UEKit 的 UELink 2.5+；蓝图编译、资源保存后几秒内自动更新，只写变了的文件；**默认关闭** |

两者都有时只用 UELink 生成的，避免同一个类型出现两个跳转目标。

## 只打开了 Content/Script 也行

IDE 常常只打开 UE 项目里的 `Content/Script`。插件会往上找到 UE 项目根（含 `.uproject` 的目录），把项目里的声明目录加进去。加进去的目录在项目树的「外部库」节点下能看到。

## 手动刷新

工具菜单「UE 反射声明（EmmyLua）」或设置页的按钮：

- 刷新：只写变了的
- 补全全部蓝图：没打开过的蓝图也生成
- 全部重写

刷新时底部状态栏显示进度。

## 跳转不到时

1. 看项目树「外部库」里有没有 UE 声明目录。没有：确认设置里「自动探测 UE 项目，加入 UE 反射声明」勾着（下面会显示探测结果），或在「库目录」里手动加。
2. 有目录但跳转不到：在 LSP4IJ 的「Language Servers」工具窗口里看 emmylua_ls 的日志，可以在那里重启它。
3. 工作区根目录的 `.emmyrc.json` 里可以再加库目录（`workspace.library`），和设置里的叠加。

设计细节见 [lsp-migration.md](../design/lsp-migration.md) 第 3 节。
