# LuaPanda 协议分析报告

## 概述
通过深入分析VSCode LuaPanda插件和IDEA插件的代码，我发现了两者在协议实现上的关键差异，特别是在`callbackId`的处理机制上。

## 协议格式分析

### 消息基本格式
所有调试消息都遵循以下JSON格式：
```json
{
    "cmd": "命令名称",
    "info": "命令参数对象",
    "callbackId": "回调标识符"
}
```

### 消息传输格式
- **分隔符**: `"|*|"` (TCPSplitChar)
- **完整格式**: `JSON字符串 + " " + "|*|" + "\n"`
- **传输方式**: TCP Socket

## CallbackId 机制深度分析

### VSCode端的CallbackId生成算法

#### DataProcessor.ts 中的实现：
```typescript
public commandToDebugger(cmd: string, sendObject: Object, callbackFunc = null, callbackArgs = null, timeOutSec = 0) {
    let ranNum = 0;
    let sendObj = new Object();

    // 只有在有回调函数时才生成callbackId
    if (callbackFunc != null) {
        let max = 999999999;
        let min = 10;  // 10以内是保留位
        let isSame = false;
        
        do {
            isSame = false;
            ranNum = Math.floor(Math.random() * (max - min + 1) + min);
            
            // 检查随机数唯一性，避免重复
            this.orderList.forEach(element => {
                if (element["callbackId"] == ranNum) {
                    isSame = true;
                }
            });
        } while (isSame)

        // 存储回调信息
        let dic = new Object();
        dic["callbackId"] = ranNum;
        dic["callback"] = callbackFunc;
        if(timeOutSec > 0) {
            dic["timeOut"] = Date.now() + timeOutSec * 1000; 
        }
        if (callbackArgs != null) {
            dic["callbackArgs"] = callbackArgs;
        }
        this.orderList.push(dic);
        sendObj["callbackId"] = ranNum.toString();
    }
    // 如果没有回调函数，则不设置callbackId字段
}
```

#### 关键特点：
1. **条件生成**: 只有当`callbackFunc != null`时才生成callbackId
2. **随机范围**: 10 - 999999999 (10以内为保留位)
3. **唯一性检查**: 确保不与现有的callbackId重复
4. **回调管理**: 维护orderList数组管理回调关系
5. **超时处理**: 支持回调超时机制

### Lua端的CallbackId处理

#### LuaPanda.lua 中的实现：
```lua
-- 保存CallbackId(通信序列号)
function this.setCallbackId(id)
    if id ~= nil and id ~= "0" then
        recCallbackId = tostring(id);
    end
end

-- 读取CallbackId(通信序列号)。读取后记录值将被置空
function this.getCallbackId()
    if recCallbackId == nil then
        recCallbackId = "0";
    end
    local id = recCallbackId;
    recCallbackId = "0";  -- 读取后重置为"0"
    return id;
end
```

#### 关键特点：
1. **回显机制**: Lua端接收到callbackId后，在响应中原样返回
2. **默认值**: 当没有callbackId时，默认返回"0"
3. **一次性使用**: 读取后立即重置为"0"

## 单步调试命令分析

### VSCode端的单步调试实现

#### luaDebug.ts 中的请求处理：
```typescript
// Step Over (Next)
protected nextRequest(response: DebugProtocol.NextResponse, args: DebugProtocol.NextArguments): void {
    let callbackArgs = new Array();
    callbackArgs.push(this);
    callbackArgs.push(response);
    this._runtime.step(arr => {
        DebugLogger.AdapterInfo("确认单步");
        let ins = arr[0];
        ins.sendResponse(arr[1]);
    }, callbackArgs);  // 有回调函数
}

// Step In
protected stepInRequest(response: DebugProtocol.StepInResponse, args: DebugProtocol.StepInArguments): void {
    let callbackArgs = new Array();
    callbackArgs.push(this);
    callbackArgs.push(response);
    this._runtime.step(arr => {
        DebugLogger.AdapterInfo("确认StepIn");
        let ins = arr[0];
        ins.sendResponse(arr[1]);
    }, callbackArgs, 'stopOnStepIn');  // 有回调函数
}

// Step Out
protected stepOutRequest(response: DebugProtocol.StepOutResponse, args: DebugProtocol.StepOutArguments): void {
    let callbackArgs = new Array();
    callbackArgs.push(this);
    callbackArgs.push(response);
    this._runtime.step(arr => {
        DebugLogger.AdapterInfo("确认StepOut");
        let ins = arr[0];
        ins.sendResponse(arr[1]);
    }, callbackArgs, 'stopOnStepOut');  // 有回调函数
}
```

#### luaDebugRuntime.ts 中的step函数：
```typescript
public step(callback, callbackArgs, event = 'stopOnStep') {
    DebugLogger.AdapterInfo("step:" + event);
    let arrSend = new Object();
    this._dataProcessor.commandToDebugger(event, arrSend, callback, callbackArgs);
}
```

**重要发现**: VSCode端的所有单步调试命令都**有回调函数**，因此会生成真实的callbackId（10-999999999范围内的随机数）。

### IDEA插件的单步调试实现

#### LuaPandaDebugProcess.kt 中的实现：
```kotlin
override fun startStepOver(context: XSuspendContext?) {
    val message = LuaPandaMessage(LuaPandaCommands.STEP_OVER, null, "0")  // 固定使用"0"
    transporter?.sendMessage(message)
}

override fun startStepInto(context: XSuspendContext?) {
    val message = LuaPandaMessage(LuaPandaCommands.STEP_IN, null, "0")   // 固定使用"0"
    transporter?.sendMessage(message)
}

override fun startStepOut(context: XSuspendContext?) {
    val message = LuaPandaMessage(LuaPandaCommands.STEP_OUT, null, "0")  // 固定使用"0"
    transporter?.sendMessage(message)
}
```

**问题所在**: IDEA插件固定使用callbackId="0"，这与VSCode的动态生成机制不一致。

## 协议兼容性问题分析

### 问题根源
1. **VSCode期望**: 单步调试命令应该有真实的callbackId（用于确认命令执行）
2. **IDEA实现**: 固定使用callbackId="0"（表示无需回调）
3. **Lua端处理**: 当接收到callbackId="0"时，认为是无需回调的命令

### 影响范围
这种不一致可能导致：
1. 单步调试响应机制异常
2. 命令确认流程不完整
3. 调试器状态同步问题

## 解决方案

### 当前采用的修复方案
将IDEA插件中所有命令的callbackId从"0"改为""（空字符串）：

```kotlin
// 修改前
val message = LuaPandaMessage(LuaPandaCommands.STEP_OVER, null, "0")

// 修改后  
val message = LuaPandaMessage(LuaPandaCommands.STEP_OVER, null, "")
```

### 方案原理
1. **空字符串处理**: Lua端的`setCallbackId`函数会忽略空字符串
2. **默认行为**: `getCallbackId`返回"0"作为默认值
3. **兼容性**: 既不会触发回调机制，又保持了协议的一致性

### 更优的解决方案建议

#### 方案一：实现真实的回调机制
```kotlin
override fun startStepOver(context: XSuspendContext?) {
    val message = LuaPandaMessage(LuaPandaCommands.STEP_OVER, null, generateCallbackId())
    transporter?.sendMessage(message) { response ->
        // 处理单步调试确认响应
        handleStepResponse(response)
    }
}

private fun generateCallbackId(): String {
    return (10..999999999).random().toString()
}
```

#### 方案二：统一无回调模式
如果确实不需要回调，可以在协议层面统一处理：
```kotlin
// 在LuaPandaMessage中
class LuaPandaMessage(
    val cmd: String,
    val info: Any?,
    val callbackId: String? = null  // 允许为null
) {
    fun toJson(): String {
        val obj = JsonObject()
        obj.addProperty("cmd", cmd)
        obj.add("info", gson.toJsonTree(info))
        if (callbackId != null && callbackId.isNotEmpty()) {
            obj.addProperty("callbackId", callbackId)
        }
        return gson.toJson(obj)
    }
}
```

## CallbackId详细使用规则

### 按协议类型分类的CallbackId要求

#### 1. 响应类协议（需要使用接收到的callbackId）
这些协议是对IDE发送命令的响应，必须使用`getCallbackId()`返回接收到的callbackId：

| 协议名称 | 使用方式 | 说明 |
|---------|---------|------|
| `continue` | `getMsgTable("continue", this.getCallbackId())` | 继续执行响应 |
| `stopOnStep` | `getMsgTable("stopOnStep", this.getCallbackId())` | 单步执行响应 |
| `stopOnStepIn` | `getMsgTable("stopOnStepIn", this.getCallbackId())` | 步入响应 |
| `stopOnStepOut` | `getMsgTable("stopOnStepOut", this.getCallbackId())` | 步出响应 |
| `setBreakPoint` | `getMsgTable("setBreakPoint", this.getCallbackId())` | 设置断点响应 |
| `setVariable` | `getMsgTable("setVariable", this.getCallbackId())` | 设置变量响应 |
| `getVariable` | `getMsgTable("getVariable", this.getCallbackId())` | 获取变量响应 |
| `getWatchedVariable` | `getMsgTable("getWatchedVariable", this.getCallbackId())` | 获取监视变量响应 |
| `stopRun` | `getMsgTable("stopRun", this.getCallbackId())` | 停止运行响应 |
| `runREPLExpression` | `getMsgTable("runREPLExpression", this.getCallbackId())` | REPL表达式执行响应 |
| `initSuccess` | `getMsgTable("initSuccess", this.getCallbackId())` | 初始化成功响应 |

#### 2. 主动通知类协议（使用固定"0"）
这些协议是Lua端主动发送的通知消息，不需要回调：

| 协议名称 | 使用方式 | 说明 |
|---------|---------|------|
| `output` | `sendTab["callbackId"] = "0"` | 输出日志到VSCode控制台 |
| `tip` | `sendTab["callbackId"] = "0"` | 发送提示消息 |
| `debug_console` | `sendTab["callbackId"] = "0"` | 调试控制台输出 |
| `refreshLuaMemory` | `sendTab["callbackId"] = "0"` | 刷新Lua内存信息 |

#### 3. 状态通知类协议（使用默认callbackId=0）
这些协议通过`SendMsgWithStack`发送，使用`getMsgTable`的默认参数（callbackId=0）：

| 协议名称 | 使用方式 | 说明 |
|---------|---------|------|
| `stopOnCodeBreakpoint` | `SendMsgWithStack("stopOnCodeBreakpoint")` | 代码断点命中通知 |
| `stopOnBreakpoint` | `SendMsgWithStack("stopOnBreakpoint")` | 断点命中通知 |
| `stopOnStep` | `SendMsgWithStack("stopOnStep")` | 单步停止通知 |
| `stopOnEntry` | `SendMsgWithStack("stopOnEntry")` | 入口停止通知 |
| `stopOnStepIn` | `SendMsgWithStack("stopOnStepIn")` | 步入停止通知 |
| `stopOnStepOut` | `SendMsgWithStack("stopOnStepOut")` | 步出停止通知 |

### CallbackId处理机制详解

#### Lua端的CallbackId管理
```lua
-- 全局变量
local recCallbackId = "0";

-- 保存接收到的callbackId
function this.setCallbackId(id)
    if id ~= nil and id ~= "0" then
        recCallbackId = tostring(id);
    end
end

-- 获取并重置callbackId
function this.getCallbackId()
    if recCallbackId == nil then
        recCallbackId = "0";
    end
    local id = recCallbackId;
    recCallbackId = "0";  -- 读取后立即重置
    return id;
end

-- 生成消息表
function this.getMsgTable(cmd, callbackId)
    callbackId = callbackId or 0;  -- 默认为0
    local msgTable = {};
    msgTable["cmd"] = cmd;
    msgTable["callbackId"] = callbackId;
    msgTable["info"] = {};
    return msgTable;
end
```

#### 消息处理流程
1. **接收消息**: 当收到IDE发送的消息时，检查`callbackId`字段
2. **保存callbackId**: 如果`callbackId != "0"`，则调用`setCallbackId`保存
3. **处理命令**: 执行相应的调试命令
4. **发送响应**: 使用`getCallbackId()`获取保存的callbackId并发送响应
5. **重置状态**: `getCallbackId()`调用后自动重置为"0"

### IDE端的CallbackId要求

#### VSCode端（正确实现）
- **单步调试命令**: 生成10-999999999范围内的随机callbackId
- **断点设置命令**: 生成随机callbackId
- **变量操作命令**: 生成随机callbackId
- **其他控制命令**: 根据是否需要回调决定是否生成callbackId

#### IDEA插件端（需要修复）
当前所有命令都使用固定的"0"，应该按以下规则修复：

| 命令类型 | 推荐callbackId值 | 原因 |
|---------|-----------------|------|
| 单步调试命令 | 生成随机值或使用空字符串 | 需要确认命令执行状态 |
| 断点设置命令 | 生成随机值或使用空字符串 | 需要确认断点设置结果 |
| 变量操作命令 | 生成随机值或使用空字符串 | 需要获取操作结果 |
| 简单控制命令 | 空字符串 | 不需要复杂回调机制 |

### C++端（libpdebug）的CallbackId处理

通过分析`libpdebug.cpp`和`libpdebug.h`，发现：

1. **C++端不直接处理callbackId**: libpdebug主要负责hook机制和断点管理
2. **消息传递**: C++端通过调用Lua函数来发送消息，callbackId的处理完全在Lua层
3. **状态同步**: C++端与Lua端同步运行状态和hook状态，但不涉及callbackId逻辑

### 协议兼容性最佳实践

#### 对于新的IDE插件开发
1. **实现完整回调机制**: 为需要确认的命令生成真实的callbackId
2. **区分命令类型**: 根据命令是否需要回调来决定callbackId的值
3. **错误处理**: 实现回调超时和错误处理机制

#### 对于现有IDEA插件的修复
1. **短期方案**: 将所有"0"改为空字符串，避免与Lua端的"0"逻辑冲突
2. **中期方案**: 为重要命令实现真实的callbackId生成
3. **长期方案**: 实现完整的异步回调机制

## IDEA插件修复示例

根据上述分析，IDEA插件的修复方案如下：

### 1. 状态通知协议（使用空字符串）
```kotlin
// 初始化消息
val initMessage = LuaPandaMessage(LuaPandaCommands.INIT_SUCCESS, initInfo, "")

// 断点设置
val message = LuaPandaMessage(LuaPandaCommands.SET_BREAKPOINT, breakpointInfo, "")

// 继续运行
val message = LuaPandaMessage(LuaPandaCommands.CONTINUE, null, "")

// 停止运行
val message = LuaPandaMessage(LuaPandaCommands.STOP_RUN, null, "")
```

### 2. 响应协议（使用回调机制）
```kotlin
// 单步调试命令 - 使用回调
override fun startStepOver(context: XSuspendContext?) {
    val message = LuaPandaMessage(LuaPandaCommands.STEP_OVER, null, "")
    transporter?.sendMessage(message) { response ->
        // 处理单步跳过的回调响应
        println("收到单步跳过响应")
    }
}

// 获取变量命令 - 使用回调
val message = LuaPandaMessage(LuaPandaCommands.GET_VARIABLE, info, "")
debugProcess.sendMessage(message) { response ->
    // 处理变量获取的回调响应
    if (response != null) {
        // 解析变量信息
    }
}
```

### 3. 传输器自动生成callbackId
```kotlin
// LuaPandaTransporter.kt
fun sendMessage(message: LuaPandaMessage, callback: (LuaPandaMessage?) -> Unit) {
    val callbackId = generateCallbackId()  // 自动生成真实的callbackId
    val messageWithCallback = LuaPandaMessage(message.cmd, message.info, callbackId, message.stack)
    registerCallback(callbackId) { response -> callback(response) }
    sendMessage(messageWithCallback)
}
```

### 修复效果

1. **状态通知协议**：使用空字符串`""`，Lua端会忽略callbackId，不会尝试发送响应
2. **响应协议**：使用回调机制，传输器自动生成真实的callbackId并管理回调
3. **兼容性**：与VSCode插件保持协议兼容，同时避免了callbackId冲突问题

## 总结

通过深入分析，我们发现：

1. **VSCode LuaPanda的callbackId机制**是一个完整的异步回调系统，用于确认命令执行状态
2. **IDEA插件的简化实现**忽略了这个机制，导致协议不完全兼容
3. **当前的修复方案**（使用空字符串）是一个有效的临时解决方案
4. **长期来看**，应该考虑实现完整的回调机制或在协议层面进行统一
5. **不同类型的协议有不同的callbackId要求**：响应类需要回显，通知类使用固定值，状态类使用默认值

这个分析为后续的插件改进提供了明确的方向和技术基础，特别是为正确实现各种协议的callbackId提供了详细的规范。