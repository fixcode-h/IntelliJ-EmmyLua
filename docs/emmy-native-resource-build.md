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

传入目录后，Gradle 会在 `processResources` 阶段校验八个文件都存在且非空，并覆盖构建输出中的 `debugger/emmy/windows/x86` 和 `x64` 资源：

```powershell
./gradlew.bat buildPlugin -PemmyNativeDir=F:/path/to/emmy-native-root
```

不传 `-PemmyNativeDir` 时不执行覆盖校验，保持仓库现有资源路径和内容。构建前后都应保存 `Get-FileHash -Algorithm SHA256` 输出，用于确认打包输入与产物一致。
