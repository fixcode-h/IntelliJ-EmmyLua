# 发布到 JetBrains 插件市场

插件在市场上叫 **EmmyLua Enhanced**（ID `com.fixcode.emmylua.enhanced`，vendor `fixcode`）。介绍页取 `plugin.xml` 的 `<description>`（英文在前，中文附后），「What's New」取出包时写进去的 `<change-notes>`（本次 Release 的提交列表，去掉 `chore(release)` 和 `内部：` 开头的提交）。

## 第一次上架（在网站上手动做一次）

1. 用 JetBrains 账号登录 <https://plugins.jetbrains.com>，右上角头像 →「Add new vendor」（或个人资料里的 Vendors）建 vendor：名字 `fixcode`，邮箱和 `plugin.xml` 的 `<vendor email>` 一致。
2. 右上角头像 →「Upload plugin」：vendor 选 `fixcode`，上传 [Releases](https://github.com/fixcode-h/IntelliJ-EmmyLua/releases) 里最新的 `IntelliJ-EmmyLua-*.zip`，许可证选 Apache 2.0，填源码地址 <https://github.com/fixcode-h/IntelliJ-EmmyLua>，渠道选 Stable。
3. 等 JetBrains 人工审核（一般 1–2 个工作日，有问题会发邮件）。审核期间新版本可以继续传，但要等第一版通过才对外可见。
4. 通过后，头像 → My Tokens →「Generate Token」生成一个永久令牌，放进 GitHub 仓库 Settings → Secrets and variables → Actions，名字 `JETBRAINS_MARKETPLACE_TOKEN`。

## 之后每次发版

合到 `main` 后 `auto-release.yml` 照常打包、发 GitHub Release，最后的 `marketplace` 任务把同一个 zip 用令牌传到市场（`POST /plugin/uploadPlugin`，按 `xmlId` 找插件）。

- 没配 `JETBRAINS_MARKETPLACE_TOKEN` 时这一步跳过，不算失败。
- 上传失败只标红 `marketplace` 这一项，GitHub Release 和版本跟踪提交不受影响。401/403 一般是令牌过期或令牌账号不是这个插件的 vendor 成员，换令牌后在 Actions 里重跑这个任务即可。
- 市场每个新版本都会再自动检查一遍（兼容性、API 使用），一般几分钟到几小时后上线。

本地手动发布：`JETBRAINS_MARKETPLACE_TOKEN=<令牌> ./gradlew publishPlugin`（要先按 CI 的步骤备好原生二进制和 emmylua_ls，一般不这么做）。

## 和上游 EmmyLua 的关系

本插件和上游 EmmyLua（`com.tang`）注册同一个语言 ID，`plugin.xml` 里声明了 `<incompatible-with>com.tang</incompatible-with>`：从市场安装时 IDE 会提示停用上游插件。插件名、ID、vendor 都和上游不同，审核时不会被当成同一个插件的重复上传。
