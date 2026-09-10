# Native 资源打包

插件默认继续使用 `src/main/resources/debugger/emmy/windows` 中已有的跨平台资源。不会自动复制、替换或提交新的 Native 二进制。

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

不传 `-PemmyNativeDir` 时不执行覆盖校验，保持仓库现有资源路径和内容。构建前后都应保存 `Get-FileHash -Algorithm SHA256` 输出，用于确认打包输入与产物一致。

校验最终插件 ZIP 内八个资源，缺失、重复或 SHA-256 不一致均返回失败：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/verify-emmy-native-resources.ps1 `
  -PluginZip ./build/distributions/<实际插件包名>.zip `
  -NativeRoot ./build/emmy-native-resources
```

本机禁用脚本执行时，收集脚本也可采用上述 `powershell -NoProfile -ExecutionPolicy Bypass -File` 调用方式；该参数仅作用于这次子进程，不修改系统执行策略。
