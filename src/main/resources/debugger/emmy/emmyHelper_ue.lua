-------------------------------------------------------------------------------
-- emmyHelper_ue.lua: UE 类型处理器扩展
-- 为 Unreal Engine 特定类型提供调试显示支持
-------------------------------------------------------------------------------

-------------------------------------------------------------------------------
-- 模块加载
-------------------------------------------------------------------------------

-- 获取 emmyHelper 引用
local emmyHelper = rawget(_G, 'emmyHelper')
if not emmyHelper then
    EmmyLog.error("[UE]", "emmyHelper not found in _G, module load failed")
    return
end

-- 获取处理器基类
local ProcessorBase = emmyHelper.getProcessorBase and emmyHelper.getProcessorBase()

if not ProcessorBase then
    EmmyLog.error("[UE]", "ProcessorBase not available, emmyHelper.getProcessorBase =", tostring(emmyHelper.getProcessorBase))
    return
end

-------------------------------------------------------------------------------
-- FVector 类型处理器
-------------------------------------------------------------------------------

local FVectorProcessor = ProcessorBase:extend()

function FVectorProcessor:processSpecific(variable, obj, name, depth)
    local ok, x = pcall(function() return obj.X end)
    local ok2, y = pcall(function() return obj.Y end)
    local ok3, z = pcall(function() return obj.Z end)
    
    if ok and ok2 and ok3 then
        variable.value = string.format("(X=%f, Y=%f, Z=%f)", x or 0, y or 0, z or 0)
    end
end

local okFVector, errFVector = pcall(function()
    emmyHelper.registerProcessor('FVector', FVectorProcessor)
end)
if not okFVector then
    EmmyLog.error("[UE]", "FVector processor registration failed:", errFVector)
end

-------------------------------------------------------------------------------
-- FRotator 类型处理器
-------------------------------------------------------------------------------

local FRotatorProcessor = ProcessorBase:extend()

function FRotatorProcessor:processSpecific(variable, obj, name, depth)
    local ok, pitch = pcall(function() return obj.Pitch end)
    local ok2, yaw = pcall(function() return obj.Yaw end)
    local ok3, roll = pcall(function() return obj.Roll end)
    
    if ok and ok2 and ok3 then
        variable.value = string.format("(P=%f, Y=%f, R=%f)", 
            pitch or 0, yaw or 0, roll or 0)
    end
end

local okFRotator, errFRotator = pcall(function()
    emmyHelper.registerProcessor('FRotator', FRotatorProcessor)
end)
if not okFRotator then
    EmmyLog.error("[UE]", "FRotator processor registration failed:", errFRotator)
end

-------------------------------------------------------------------------------
-- FDateTime 类型处理器
-------------------------------------------------------------------------------

local FDateTimeProcessor = ProcessorBase:extend()

function FDateTimeProcessor:processSpecific(variable, obj, name, depth)
    local ok, ticks = pcall(function()
        if _G.UE4 and _G.UE4.UUAGameStatics and _G.UE4.UUAGameStatics.GetDateTimeString then
            return _G.UE4.UUAGameStatics.GetDateTimeString(obj)
        end
        return nil
    end)
    
    if ok and ticks then
        variable.value = ticks
    else
        variable.value = "DateTime"
    end
end

local okFDateTime, errFDateTime = pcall(function()
    emmyHelper.registerProcessor('FDateTime', FDateTimeProcessor)
end)
if not okFDateTime then
    EmmyLog.error("[UE]", "FDateTime processor registration failed:", errFDateTime)
end
