# UnLua 适配检查表

- [ ] `FLuaEnv` 构造完成后保存非零 `registrationId`。
- [ ] Register 使用注入 Agent 的 `GetProcAddress` 函数指针，不静态链接 Emmy。
- [ ] Register 发生在 Lua 基础库/UnLua 初始化完成之后。
- [ ] Ready 只在宿主确认可调试后上报。
- [ ] RegisterLuaSource 使用 loader 实际加载字节计算 SHA-256，并传入当前 `sourceEpoch`；不使用 IDEA 磁盘 hash。
- [ ] Source registration 失败或 epoch 不一致时，先重新读取活动 VM/epoch；同 epoch 同 chunk 内容变化必须 ResetContext 后再注册。
- [ ] 析构入口先 BeginClose，再 `lua_close`，最后 EndClose/Release。
- [ ] HotReload/PIE reset 发布 `contextGeneration/sourceEpoch`，不伪装成普通 close。
- [ ] 临时 userdata-header state 被过滤，不进入 snapshot。
- [ ] 同一 main state 重复 Register 返回同一活动 ID；地址复用产生新 generation。
- [ ] 多 Lua ABI 被明确拒绝，不复用第一套 API table。
- [ ] HostValueProvider 的 UObject 访问有 GameThread 调度、deadline、白名单和副本化。
- [ ] Editor 无 PIE、PlayGame-first、PIE close/recreate、双 VM 场景分别验收。
