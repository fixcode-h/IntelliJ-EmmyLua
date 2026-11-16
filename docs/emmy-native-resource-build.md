# 原生资源打包

插件里的原生文件（Windows 附加、Android agent）不在仓库里维护，出包时由 CI 现编现放。本地要用自己编的版本时按这里做。

## 出包时（CI）

`.github/workflows/auto-release.yml`：

1. Windows x86 / x64：`cmake -S native` 编 `emmy_tool.exe`、`emmy_attach.dll`、`EasyHook.dll`；`native/emmy_debugger_core` 以 `dynamic` 模式按 Lua 5.1–5.4 各编一份 `emmy_debugger_core.dll`。
2. Android：`native/platforms/android/agent/build_agent.sh` 编 arm64-v8a / armeabi-v7a 的 `libemmylua_agent.so`。
3. 放进插件资源：

```text
debugger/emmy/windows/<x86|x64>/emmy_tool.exe
debugger/emmy/windows/<x86|x64>/emmy_attach.dll
debugger/emmy/windows/<x86|x64>/EasyHook.dll
debugger/emmy/windows/<x86|x64>/lua5X/emmy_debugger_core.dll   （51 / 52 / 53 / 54）
debugger/emmy/android/<abi>/libemmylua_agent.so
```

PDB 不进插件包，单独作为 Release 附件 `emmylua-windows-pdb-v<版本>.zip` 上传，目录结构和上面一致。

## 本地用自己编的

按 `auto-release.yml` 里「Build emmy_tool / emmy_attach」「Build cores」两步的命令编好，整理成：

```text
<native-root>/x86/{EasyHook.dll, emmy_tool.exe, emmy_attach.dll, lua51..lua54/emmy_debugger_core.dll}
<native-root>/x64/...
```

然后：

```bash
./gradlew -PemmyNativeDir=<native-root> runIde      # 或 buildPlugin
```

`gradle/emmy-native-resources.gradle.kts` 会先检查文件齐全（缺一个就失败），再用它们替换 `debugger/emmy/windows`。`tools/collect-emmy-native-resources.ps1` 可以从两个 CMake 构建目录收集 `emmy_tool` / `emmy_attach` / `EasyHook`，核心 DLL 要另外拷。
