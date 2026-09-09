# Emmy Host API 接入契约

本文定义 UE/UnLua 等宿主接入 Emmy Agent 的边界，不修改宿主工程源码。宿主只能通过注入 Agent 导出的 C ABI 注册 Lua VM；不得静态链接另一份 `EmmyFacade`。

## 生命周期顺序

正常创建：

```text
FLuaEnv 构造完成 Lua 基础库和 UnLua 注册
  -> GetProcAddress(emmy_hook.dll, "Emmy_RegisterLuaVm")
  -> Emmy_RegisterLuaVm(mainState, metadata)
  -> 完成宿主侧初始化后 Emmy_NotifyLuaVmReady(registrationId)
```

正常销毁：

```text
OnLuaStateDestroyed / FLuaEnv 析构入口
  -> Emmy_BeginLuaVmClose(registrationId, reason)
  -> lua_close(L)
  -> Emmy_EndLuaVmClose(registrationId)
  -> Emmy_ReleaseLuaVmRegistration(registrationId)
```

`BeginLuaVmClose` 必须在 `lua_close` 前调用；`lua_close` 返回后禁止再次读取 `lua_State*`。`registrationId=0` 只表示参数无效或注册失败，Agent 未激活时仍会返回非零 pending ID。

## ABI 约束

- 通过 `GetProcAddress` 绑定唯一导出：`Emmy_RegisterLuaVm`、`Emmy_NotifyLuaVmReady`、`Emmy_BeginLuaVmClose`、`Emmy_EndLuaVmClose`、`Emmy_ReleaseLuaVmRegistration`、`Emmy_SetLuaVmDisplayName`。
- `EmmyHostVmMetadata.size/version` 必须正确填写；字符串只在调用期间借用，Agent 会复制。
- 不在 `DllMain`、Lua allocator 回调或 loader lock 中调用 Host API。
- 宿主的 Lua ABI/layout descriptor 应在 Agent 初始化后对账；当前 UnLua 使用定制 `lua-5.4.3`、`LUA_IDSIZE=256` 和 SP hook 扩展，不能按通用 Lua 5.4 处理。

## HotReload 与 PIE reset

HotReload 或 PIE reset 不等同于 VM close：

1. 宿主暂停新的调试任务并发布 `contextGeneration/sourceEpoch` reset。
2. 使旧 frame、变量引用、脚本缓存和临时 Probe 失效。
3. 如果 `lua_State*` 被销毁，按正常 Close 顺序注销旧 VM。
4. 新 Lua state 重新 Register，不能复用旧 registrationId 或 generation。

## 临时 Lua state

UnLua 内部为 userdata header、反射探测或工具调用创建的临时 state 不得注册为公开 VM。只有生命周期 owner 明确持有并注册的 `FLuaEnv` 才能进入 VM snapshot。

## HostValueProvider

userdata 展开必须返回可序列化副本：

- Lua owner thread 只提取稳定引用和轻量字段。
- UObject/UStruct 反射读取切换到 GameThread，并使用 deadline。
- 对象失效、字段不在白名单或超时返回 `HOST_VALUE_UNAVAILABLE`。
- DTO 中禁止携带 `UObject*`、`FProperty*` 或跨线程悬空指针。
