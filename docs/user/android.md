# Android 真机附加

不改游戏包，在运行时把调试组件注入到手机上的游戏进程里。支持 arm64-v8a、armeabi-v7a，Lua 5.4（UnLua）。iOS 不支持。

## 前提

- 手机打开 USB 调试，`adb devices` 能看到并已授权。adb 路径可以在设置 → 工具 → EmmyLua 调试 → 设备里指定。
- 游戏包**可调试**（`android:debuggable=true`，UE 的 Development 包默认如此）。正式签名包暂不支持。
- 本地有这次安装包对应的**未裁剪** `libUnreal.so`（UE 打包后在 `Binaries/Android` 或 `Intermediate/Android` 下）。没有时见下面的「找不到符号」。

## 开始

1. 新建运行配置「Lua 真机附加」，选设备和游戏进程（或包名）。
2. 点「检查环境」：adb、设备授权、系统版本、符号、ABI、可调试、Build ID 全部通过再附加。
3. 启动配置。附加成功后像本机一样下断点、看变量。
4. 想从游戏启动就调试：勾选「启动应用并在第一行暂停」，应用会重启，在第一段 Lua 执行前停住等 IDE。

Android 8.x 走 JDWP 注入，附加时游戏要在前台。断开只撤掉端口转发，调试组件留在进程里，下次附加直接复用。

## 找不到符号

构建机清了 `Intermediate`、只剩安装包时，IDE 按顺序找 Lua 函数，任何一级找全就用它，附加日志里写明用的是哪一级：

1. 运行配置里指定的 so（Build ID 不一致时跳过）
2. 设置里的**符号目录**：把每次出包的未裁剪 so 存到共享盘，配在这里，按 Build ID 自动匹配
3. 设备上 so 自带的符号（打包时保留了 MiniDebugInfo 的工程这一级就够）
4. **参考 so**：同一工程以前任意一次构建的未裁剪 `libUnreal.so`，按特征码找函数。第一次要经 adb 读一遍代码段，USB 下几十秒，之后走缓存

都找不到时提示里会列出缺哪些函数、每一级的结果和办法。

## 常见问题

- 「不是同一次构建」：本地的 so 和手机上装的不是同一次打包，换成对应的 so。
- 附加后不停：先看 `adb logcat -s EmmyLuaAgent` 有没有「监听 localabstract:emmylua-<pid>」和「登记 VM」；其余排查同 [附加调试](attach.md#断点不停怎么办)。

技术细节见 [native/platforms/android/README.md](../../native/platforms/android/README.md)，验收步骤见 [验收清单](../acceptance-checklist.md) 的 C 节。
