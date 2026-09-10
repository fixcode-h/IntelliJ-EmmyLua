# EmmyLua CLI 调试指南

`emmy-debug` 连接已经运行的 IDEA 调试会话，不负责启动或注入调试进程。

## 启动与选择实例

首次初始化 Emmy 调试会话或访问 CLI 授权菜单时，插件会启动本机 CLI Gateway，并在实例目录写入描述和 token 文件。因此 `instance list` 为空不代表 IDEA 没有运行。

```text
emmy-debug instance list
emmy-debug target list --instance <idea-instance-id> --json
```

存在多个 IDEA 实例时必须使用 `--instance`；也可以同时显式指定 `--endpoint` 和 `--token-file`。

## 授权

默认没有任何 CLI 客户端的目标授权。在 IDEA 的 `Tools` 菜单执行：

- `授予 EmmyLua CLI 调试权限...`
- `撤销 EmmyLua CLI 调试权限...`

授权时输入客户端标识，命令行通过 `--client` 使用同一标识。撤销会同时失效该客户端的控制租约和 AI Probe。

```text
emmy-debug target status --target <target-id> --client emmy-debug
emmy-debug lease acquire --target <target-id> --client emmy-debug
```

## 只读查询

```text
emmy-debug vm list --target <target-id> --client emmy-debug
emmy-debug stack --target <target-id> --vm <vm-id> --pause <pause-id> --client emmy-debug
emmy-debug scopes --target <target-id> --vm <vm-id> --pause <pause-id> --frame <frame-id> --client emmy-debug
emmy-debug variables --target <target-id> --vm <vm-id> --pause <pause-id> --frame <frame-id> --path locals --client emmy-debug
```

变量展开受 `--max-depth`、`--max-nodes` 和 `--max-bytes` 限制，省略时使用服务端默认预算。`--json` 适合脚本和 AI 工具消费。

`--path` 是 `VALUE_PATH`：它使用实际变量名，例如 `player.name`、`value["a.b"]`，从当前暂停快照的 local、upvalue、global 中查找；`locals.` 不是表达式前缀。`scopes` 返回的 `variablesReference` 是绑定当前 VM/pause/frame 的 opaque token，通过 `--variables-reference <token>` 展开，不能当作 `--path` 重新解析；恢复、reset 或使用另一 frame 时必须重新查询。

`--path locals`、`--path upvalues` 和 `--path globals` 是 scope 选择器；scope 返回的 opaque token 不受同名变量或特殊 key 影响。

VM 和 source 相关响应中的 `sourceIdentity` 表示宿主运行时注册的元数据：`chunkName`、规范化后的 `canonicalPath`、`sourceEpoch` 以及可用时的运行时字节 SHA-256。CLI/IDEA 不会用本地磁盘文件重新计算 hash 来替代宿主注册值。

宿主未提供 hash 时，只能按当前 VM 的 raw chunk path 与 `sourceEpoch` 精确匹配；此类 source identity 不应视为内容已验证。PIE/HotReload 发生 reset 后，旧 epoch 的 source identity、暂停帧和断点引用均应重新查询。

## 受限求值

CLI 求值仅允许 `VALUE_PATH`，由 Native 在 Lua 所属线程读取当前暂停帧的 raw 值；不执行任意 Lua 表达式、函数或元方法。legacy Agent 不开放 AI 求值和 Probe。

```text
emmy-debug eval --target <target-id> --vm <vm-id> --pause <pause-id> \
  --frame <frame-id> --expression player.name --policy VALUE_PATH \
  --lease <lease-id> --client emmy-debug
```

`--expression` 使用实际变量名和 raw 字段路径。`--source-identity` 可选但推荐填写：直接复制 `stack` 响应中对应 frame 的 `sourceIdentity`，不要自行根据本地文件计算或杜撰 hash。identity 使用 `canonicalPath`、可选的 `sourceHash`、`sourceEpoch` 和 `verified` 字段；不匹配当前宿主快照时会返回 `SOURCE_IDENTITY_MISMATCH`。

求值、控制、断点和 Probe 需要当前客户端持有目标 lease。客户端退出或 lease TTL 到期后，服务端会拒绝后续控制请求。

## Probe

```text
emmy-debug probe run --target <target-id> --vm <vm-id> --source-identity '<运行时 sourceIdentity JSON>' \
  --line 120 --condition 'player.health < 20' --capture player.health --capture player.name \
  --timeout 30s --hit-limit 1 --auto-continue \
  --lease <lease-id> --client emmy-debug
emmy-debug wait --target <target-id> --client emmy-debug
emmy-debug probe remove --target <target-id> --probe-id <probe-id> \
  --lease <lease-id> --client emmy-debug
```

`--capture` 是 `VALUE_PATH`，可以重复传入，也支持用逗号分隔路径；引号 key 中的逗号会保留，例如 `value["a,b"]`。`--condition` 只支持受限条件语法，不能调用 Lua 函数。Probe 必须提供 `sourceIdentity`，应从目标运行时或 `stack` 响应复制；也可用 `--file` 仅按路径定位，此时 identity 的 `verified=false`，不代表已验证运行时字节内容。`--file` 与 `--source-identity` 不能同时使用。

`probe run` 返回安装结果和 `probeId`；采集在之后命中该行时发生。通过 `wait --cursor <游标>` 接收包含采集值的 `probe.hit` 事件，或用 `probe status --probe-id <id>` 查询状态。普通命令输出单行 JSON，`wait` 输出 NDJSON 事件流并以 `done` 结束。长时间采集应在租约到期前调用 `lease heartbeat`。

Probe 只在暂停原因完全属于该 Probe 时自动继续；用户控制、撤销授权、超时和调试目标关闭都会使 Probe 失效并清理后端断点。

## 常见错误

`NOT_AUTHORIZED` 表示需要在 IDEA 中授予目标权限；`LEASE_REQUIRED` 或 `LEASE_EXPIRED` 表示需要重新获取租约；`STALE_PAUSE_REFERENCE` 表示暂停帧已变化，应重新查询 VM 和 pause；`EVALUATION_DENIED` 表示请求不是允许的 `VALUE_PATH`。

CLI 返回 0 表示请求成功，2 表示参数或授权类错误，3 表示 Gateway 不可用，其他非 0 值表示服务端错误或请求失败。
