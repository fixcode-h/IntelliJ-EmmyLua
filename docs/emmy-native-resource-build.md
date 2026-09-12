# Native 资源打包

插件默认使用 `src/main/resources/debugger/emmy/windows` 中已验证的 x86/x64 资源。Native 源码或构建产物更新后，应先更新这八个默认资源，再构建插件；这样 `runIde`、`prepareSandbox` 和发布 ZIP 使用同一套工具。

## 覆盖资源

先将已验证的 Native 构建产物整理为以下目录：

```text
<native-root>/x86/EasyHook.dll
<native-root>/x86/emmy_core.dll
<native-root>/x86/emmy_hook.dll
<native-root>/x86/emmy_tool.exe
<native-root>/x64/EasyHook.dll
<native-root>/x64/emmy_core.dll
<native-root>/x64/emmy_hook.dll
<native-root>/x64/emmy_tool.exe
```

可使用收集脚本完成复制和 SHA-256 输出：

```powershell
powershell -File tools/collect-emmy-native-resources.ps1 `
  -BuildRoot F:/path/to/native-build-output `
  -OutputRoot F:/path/to/emmy-native-root
```

也可直接收集两个独立 CMake 构建目录，支持 Ninja 与 Visual Studio 的配置子目录：

```powershell
./tools/collect-emmy-native-resources.ps1 `
  -X86BuildDir ./EmmyLuaDebugger/build-x86-release-20260910 `
  -X64BuildDir ./EmmyLuaDebugger/build-release-20260910 `
  -Configuration Release -OutputRoot ./build/emmy-native-resources
```

传入目录后，Gradle 会在 `processResources` 阶段校验八个文件都存在且非空，并覆盖构建输出中的 `debugger/emmy/windows/x86` 和 `x64` 资源：

```powershell
./gradlew.bat buildPlugin -PemmyNativeDir=F:/path/to/emmy-native-root
```

本地直接启动带该资源的 IDEA sandbox，可使用 `runIde`，参数必须放在同一次 Gradle 调用中：

```powershell
$env:JAVA_HOME='C:/Users/<user>/.jdks/jbr-21.0.11'
.\gradlew.bat --no-daemon runIde -DIDEA_VER=252 `
  -PemmyNativeDir=F:/path/to/emmy-native-root
```

仅更新 sandbox、不启动 IDEA：

```powershell
.\gradlew.bat --no-daemon prepareSandbox -DIDEA_VER=252 `
  -PemmyNativeDir=F:/path/to/emmy-native-root
```

不传 `-PemmyNativeDir` 时直接使用仓库默认资源；传入参数时才用指定目录覆盖。两种路径都应保存 `Get-FileHash -Algorithm SHA256` 输出，用于确认 sandbox、打包输入与最终产物一致。不要只重启 IDEA 期待旧 sandbox 自动刷新；资源变更后必须重新执行 `prepareSandbox` 或重新安装插件 ZIP。

校验最终插件 ZIP 内八个资源，缺失、重复或 SHA-256 不一致均返回失败：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/verify-emmy-native-resources.ps1 `
  -PluginZip ./build/distributions/<实际插件包名>.zip `
  -NativeRoot ./build/emmy-native-resources
```

本机禁用脚本执行时，收集脚本也可采用上述 `powershell -NoProfile -ExecutionPolicy Bypass -File` 调用方式；该参数仅作用于这次子进程，不修改系统执行策略。
