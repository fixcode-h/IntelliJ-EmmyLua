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

## 总结

通过深入分析，我们发现：

1. **VSCode LuaPanda的callbackId机制**是一个完整的异步回调系统，用于确认命令执行状态
2. **IDEA插件的简化实现**忽略了这个机制，导致协议不完全兼容
3. **当前的修复方案**（使用空字符串）是一个有效的临时解决方案
4. **长期来看**，应该考虑实现完整的回调机制或在协议层面进行统一

这个分析为后续的插件改进提供了明确的方向和技术基础。