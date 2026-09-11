# 本机 Native TCP 回环拦截诊断

日期：2026-09-10。继续使用 TCP，不引入 WebSocket。此次只诊断当前机器及两个测试程序，不修改插件传输协议。

## 结论与证据边界

已定位本次连接失败的直接原因：Native 测试程序的 IPv4 回环入站连接命中 Windows Defender Firewall 的 `Query User Default` 兜底过滤器，未获得应用级放行。仅为两个精确测试程序临时添加 `127.0.0.1 → 127.0.0.1` 入站 TCP Allow 后，两项测试全部通过；规则随后删除，纯 Winsock 诊断程序仍能复现未放行时的超时。

这说明传输失败与本机防火墙的应用规则有关，但不能仅凭默认入站 Block，就宣称已经解释了“为何桌面回环流量没有通常的豁免”。目前未定位到启用该行为的独立设置，也没有证据将其归因于某个企业策略、安全软件或某次 Windows 更新；也未证明本机曾经存在某条回环豁免、后来被删除。

## 实测对照

| 程序/场景 | 应用规则 | 结果 |
| --- | --- | --- |
| PowerShell/.NET，显式 AF_INET、127.0.0.1 | 已有匹配当前 powershell.exe 路径的 Public 入站 TCP Allow | 同进程监听/连接成功 |
| 本次 Gradle 使用的 Java | 已有匹配 jbr-21.0.11/bin/java.exe 的 Public 入站 TCP Allow | JVM socket 测试通过 |
| 纯 Winsock 诊断程序，不链接 Emmy/libuv | 无专用 Allow | bind/listen 成功；select 等待约 5 秒后超时 |
| 两个 Native harness，临时放行前 | 无专用 Allow | TCP 测试 0/2 |
| 相同 Native harness，临时放行后 | 仅两个精确 exe、仅 IPv4 回环入站 TCP | 2/2，总耗时 0.63 秒 |

临时测试运行于 `EmmyLuaDebugger/build-runtime-20260910`（Windows x64、Lua 5.4 source）。报告为该目录的 `tcp-approved-results.xml`，时间 17:46。该结果不代表其他架构/配置的 TCP 测试也已经执行。

临时规则使用本次独立 GUID 命名，脚本在 `finally` 中删除规则。现场再次查询 `EmmyLua-Native-TCP-Test-*`，结果为 0；Windows 防火墙事件 2097/2052 也记录了本次两条规则的添加和删除。没有留下持久 Allow，没有关闭防火墙。

## 实际拦截路径

WFP 原始事件中，Native harness 的应用路径、端口 39547 与以下字段对应：

- `localAddrV4=127.0.0.1`、`remoteAddrV4=127.0.0.1`；
- `isLoopback=true`、`MS_FWP_DIRECTION_IN`；
- `filterId=70739`；
- provider：`FWPM_PROVIDER_MPSSVC_WF`；
- layer：`FWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4`；
- action：`FWP_ACTION_BLOCK`；
- 名称：`Query User`；origin：`Query User Default`。

该过滤器自己的描述说明：没有显式放行规则、也没有经用户提示允许的入站连接会被阻止。它是 Windows 防火墙生成的运行时过滤器，不是项目创建的一条名为 Emmy 的持久 Block 规则。微软的 [Filter Origin 排障说明](https://learn.microsoft.com/en-us/windows/security/operating-system-security/network-security/windows-firewall/filter-origin-documentation#query-user-default) 对 `Query user default` 给出了同样的解释，并明确区分默认过滤器和用户规则。名称带 `Query User` 不代表本次必然弹出过窗口，更不证明用户点击过拒绝。

当前 ActiveStore 中三个 profile 均启用，入站默认为 Block、出站默认为 Allow；活动 profile 是 Public，允许本地规则及入站例外，监听通知启用。PowerShell/Java 的已有 Allow 规则可以解释它们与新 Native exe 的差异，不能据此推断 .NET/JVM 天然绕过防火墙。

原始回环 drop 的 `originalProfile/currentProfile` 均为 0，70739 本身没有过滤条件；不能把“活动网卡是 Public”直接解释为“Public 网络分类导致这次回环拦截”。filterId 是本次快照中的运行时编号，服务重载后可能变化，复查时应同时核对应用、端口、方向、provider 与 filter origin。

Microsoft 的 [WFP 条件标志说明](https://learn.microsoft.com/en-us/windows/win32/fwp/filtering-condition-flags-) 明确定义了 loopback 与非 AppContainer loopback 标志，说明回环可以被 WFP 识别和过滤；这份文档本身并不证明某台 Windows 的默认桌面回环策略。此处以本机 `isLoopback=true` 的实际 drop 为依据。

## 已排除与尚未确定的原因

- **监听地址错误：已排除本次测试中的这一原因。** 纯 Winsock 直接绑定 `127.0.0.1`，WFP 也确认两端回环；不是把外部网卡地址误当成本机。
- **Emmy/libuv 专属故障：已排除连接超时由它们单独造成。** 独立 Winsock 程序同样失败，相同 Native 二进制仅改变受限 Allow 后通过。
- **Codex 离线出站 Block：不匹配本次进程。** 这些规则限定用户 SID 尾号 1003，诊断进程为当前用户尾号 1001，restricted SID 数为 0；实际 drop 是入站 Windows 防火墙过滤器。仅添加入站 Allow 就成功，也与“命中出站 Block”不符。不能根据规则名称归因。
- **AppContainerLoopback Permit：不能当作最终放行。** 该 Permit 属于应用隔离子层；Windows 防火墙子层仍可独立拒绝本次连接。被禁用的 `Quarantine Default Inbound Loopback Exception` 也不能直接认定为故障开关，未尝试启用。
- **第三方过滤回调：参与不等于拒绝。** 同一事件还记录了 `eaglehips TURecvAcceptMon Filter` 的终结型 callout；实际归因字段仍指向微软防火墙子层的 70739。此事件不能证明该回调导致系统回环策略变化，也不能排除它的间接影响。
- **历史问题：有线索，但缺少最初变更记录。** 本机已经有 `Codex-Diagnosis-Node-Loopback` 和 `Codex-Diagnosis-RiderBackend-Loopback` 两条持久本地 Allow，描述指向此前的 Rider/dsh 排障；它们不是本次创建的。现存防火墙事件最早为 2026-08-21，无法追溯更早的设置来源。
- **策略来源：尚未完全确定。** 本次查询未发现 `HKLM/SOFTWARE/Policies/Microsoft/WindowsFirewall` 下的传统 GPO 配置；`Get-NetFirewallSetting`、`netsh advfirewall show global` 与已检查的 SharedAccess/MpsSvc/BFE 配置也没有提供可归因的回环开关。这不等价于排除了所有 MDM、WFP 动态策略或历史系统修改。不能把当前默认 Block 值单独当成异常根因。

## 对项目的处理

保留现有 TCP 协议。已经证明所测试 Native TCP 握手和消息链路在精确 Allow 下正常，不应通过更换 WebSocket 掩盖环境差异。当前测试规则已撤销，未来在同一机器重新运行这些 Native 程序时仍可能需要相同的受限授权。

真实 EmmyAttach 监听发生在被附加进程内，防火墙按宿主可执行文件识别；测试 exe 的放行不会自动放行 UnrealEditor 等宿主。UE/PIE 尚未实机验收，不能据本次结果擅自给宿主添加规则。

原始 WFP 文件包含其他本机程序信息，只保存在忽略的 `build/verification-tools`，不提交；本文只记录与本次目标直接相关的字段。
