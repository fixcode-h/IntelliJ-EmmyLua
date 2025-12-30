-- 获取 emmyHelper 引用
local emmyHelper = rawget(_G, 'emmyHelper')
if not emmyHelper then
    return
end

-- 获取处理器基类
local ProcessorBase = emmyHelper.getProcessorBase and emmyHelper.getProcessorBase()

if ProcessorBase then
    -- FVector 类型处理器
    local FVectorProcessor = ProcessorBase:extend()
    
    function FVectorProcessor:processSpecific(variable, obj, name, depth)
        local ok, x1 = pcall(function() return obj.X end)
        local ok2, y1 = pcall(function() return obj.Y end)
        local ok3, z1 = pcall(function() return obj.Z end)
        
        if ok and ok2 and ok3 then
            variable.value = string.format("(X=%.2f, Y=%.2f, Z=%.2f)", x1 or 0, y1 or 0, z1 or 0)
        end
    end
    
    emmyHelper.registerProcessor('FVector', FVectorProcessor)
    
    -- FRotator 类型处理器
    local FRotatorProcessor = ProcessorBase:extend()
    
    function FRotatorProcessor:processSpecific(variable, obj, name, depth)
        local ok, pitch = pcall(function() return obj.Pitch end)
        local ok2, yaw = pcall(function() return obj.Yaw end)
        local ok3, roll = pcall(function() return obj.Roll end)
        
        if ok and ok2 and ok3 then
            variable.value = string.format("(P=%.2f, Y=%.2f, R=%.2f)", 
                pitch or 0, yaw or 0, roll or 0)
        end
    end
    
    emmyHelper.registerProcessor('FRotator', FRotatorProcessor)
    
    -- FTransform 类型处理器
    local FTransformProcessor = ProcessorBase:extend()
    
    function FTransformProcessor:processSpecific(variable, obj, name, depth)
        local ok1, loc = pcall(function() return obj:GetLocation() end)
        local ok2, rot = pcall(function() return obj:GetRotation() end)
        local ok3, scale = pcall(function() return obj:GetScale3D() end)
        
        if ok1 and ok2 and ok3 and loc and rot and scale then
            variable.value = string.format("Loc=(%.1f,%.1f,%.1f) Rot=(%.1f,%.1f,%.1f) Scale=(%.2f,%.2f,%.2f)",
                loc.X or 0, loc.Y or 0, loc.Z or 0,
                rot.Pitch or 0, rot.Yaw or 0, rot.Roll or 0,
                scale.X or 1, scale.Y or 1, scale.Z or 1)
        else
            variable.value = "Transform"
        end
    end
    
    emmyHelper.registerProcessor('FTransform', FTransformProcessor)
    
    -- FDateTime 类型处理器
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
    
    emmyHelper.registerProcessor('FDateTime', FDateTimeProcessor)
end
