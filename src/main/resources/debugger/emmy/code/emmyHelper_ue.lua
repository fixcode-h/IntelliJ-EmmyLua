-------------------------------------------------------------------------------
-- emmyHelper_ue.lua: UE 类型处理器扩展
-- 为 Unreal Engine 特定类型提供调试显示支持
-- 可通过 require("emmyHelper_ue") 加载，支持断点调试
-------------------------------------------------------------------------------

-------------------------------------------------------------------------------
-- 模块加载
-------------------------------------------------------------------------------

-- 获取 emmyHelper 引用
local emmyHelper = rawget(_G, 'emmyHelper')
if not emmyHelper then
    EmmyLog.error("[UE]", "emmyHelper not found in _G, module load failed")
    return nil
end

-- 获取处理器基类
local ProcessorBase = emmyHelper.getProcessorBase and emmyHelper.getProcessorBase()

if not ProcessorBase then
    EmmyLog.error("[UE]", "ProcessorBase not available, emmyHelper.getProcessorBase =", tostring(emmyHelper.getProcessorBase))
    return nil
end

-------------------------------------------------------------------------------
-- FVector 类型处理器
-------------------------------------------------------------------------------

local FVectorProcessor = ProcessorBase:extend()

function FVectorProcessor:processSpecific(variable, obj, name, depth)
    variable.value = string.format("(X=%f, Y=%f, Z=%f)", obj.X or 0, obj.Y or 0, obj.Z or 0)
end

emmyHelper.registerProcessor('FVector', FVectorProcessor)

-------------------------------------------------------------------------------
-- FRotator 类型处理器
-------------------------------------------------------------------------------

local FRotatorProcessor = ProcessorBase:extend()

function FRotatorProcessor:processSpecific(variable, obj, name, depth)
    variable.value = string.format("(P=%f, Y=%f, R=%f)", obj.Pitch or 0, obj.Yaw or 0, obj.Roll or 0)
end

emmyHelper.registerProcessor('FRotator', FRotatorProcessor)

-------------------------------------------------------------------------------
-- FDateTime 类型处理器
-------------------------------------------------------------------------------

local FDateTimeProcessor = ProcessorBase:extend()

function FDateTimeProcessor:processSpecific(variable, obj, name, depth)
    variable.value = _G.UE4.UUAGameStatics.GetDateTimeString(obj)
end

emmyHelper.registerProcessor('FDateTime', FDateTimeProcessor)

-------------------------------------------------------------------------------
-- TArray/TMap/TSet 容器处理器
-------------------------------------------------------------------------------

local TContainerProcessor = ProcessorBase:extend()

function TContainerProcessor:processSpecific(variable, obj, name, depth)
    local mt = getmetatable(obj)
    if not mt then return end

    local toTableFunc = rawget(mt, 'ToTable')
    if toTableFunc and type(toTableFunc) == 'function' then
        local resultNode = emmyHelper.createNode()
        local resultTable = toTableFunc(obj)
        resultNode.name = "ToTable"
        resultNode.value = resultTable
        resultNode:query(resultTable, depth - 1, true)
        variable:addChild(resultNode)
    end
end

-- 注册容器处理器
emmyHelper.registerProcessor('TArray', TContainerProcessor)
emmyHelper.registerProcessor('TMap', TContainerProcessor)
emmyHelper.registerProcessor('TSet', TContainerProcessor)

-------------------------------------------------------------------------------
-- 返回模块（标准 Lua 模块格式）
-------------------------------------------------------------------------------
return {
    FVectorProcessor = FVectorProcessor,
    FRotatorProcessor = FRotatorProcessor,
    FDateTimeProcessor = FDateTimeProcessor,
    TContainerProcessor = TContainerProcessor,
}
