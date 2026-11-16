-------------------------------------------------------------------------------
-- emmy_ue.lua：UnLua 下 UE 值的显示（helper v2，variable-view.md 8.2、8.3、11.2）。
--
-- UObject 本身由 UELink 的宿主值提供者显示（按 UE 反射列属性，不执行 Lua）；这里只管
-- 那边不认领、但 Lua 里很常见的值：常用结构体的一行摘要，以及 TArray / TMap / TSet 的分页展开。
-- 类型名是 UnLua 元表的 __name。所有回调都在调试器的预算内执行，出错只影响这一项。
-------------------------------------------------------------------------------

local emmy = require("emmy")

local function num(v)
    if type(v) ~= "number" then return tostring(v) end
    if v == math.floor(v) and v > -1e15 and v < 1e15 then return string.format("%d", v) end
    return (string.format("%.3f", v):gsub("0+$", ""):gsub("%.$", ""))
end

-- 常用结构体：摘要 + 字段（结构体字段由 UnLua 的 __index 读，属于 helper 允许执行的代码）
local structs = {
    FVector = { "X", "Y", "Z" },
    FVector2D = { "X", "Y" },
    FVector4 = { "X", "Y", "Z", "W" },
    FIntPoint = { "X", "Y" },
    FIntVector = { "X", "Y", "Z" },
    FRotator = { "Pitch", "Yaw", "Roll" },
    FQuat = { "X", "Y", "Z", "W" },
    FLinearColor = { "R", "G", "B", "A" },
    FColor = { "R", "G", "B", "A" },
}

for typeName, fields in pairs(structs) do
    emmy.registerFormatter {
        name = "ue:" .. typeName,
        match = { typeName = typeName },
        priority = 50,
        kind = "record",
        summary = function(obj)
            local parts = {}
            for i, f in ipairs(fields) do parts[i] = f .. "=" .. num(obj[f]) end
            return "(" .. table.concat(parts, ", ") .. ")"
        end,
        count = function() return #fields end,
        children = function(obj, start, count)
            local out = {}
            for i = start + 1, math.min(#fields, start + count) do
                out[#out + 1] = { name = fields[i], value = obj[fields[i]] }
            end
            return out
        end,
    }
end

emmy.registerFormatter {
    name = "ue:FTransform",
    match = { typeName = "FTransform" },
    priority = 50,
    kind = "record",
    summary = function(obj)
        local t = obj.Translation
        return t and string.format("T=(%s, %s, %s)", num(t.X), num(t.Y), num(t.Z)) or "FTransform"
    end,
    count = function() return 3 end,
    children = function(obj, start, count)
        local names = { "Translation", "Rotation", "Scale3D" }
        local out = {}
        for i = start + 1, math.min(3, start + count) do
            out[#out + 1] = { name = names[i], value = obj[names[i]] }
        end
        return out
    end,
}

-- 容器：只按页取，不 ToTable（大数组整体拷贝太慢）。UnLua 的容器下标从 1 开始。
emmy.registerFormatter {
    name = "ue:TArray",
    match = { typeName = "TArray" },
    priority = 50,
    kind = "array",
    summary = function(obj) return "Num=" .. obj:Length() end,
    count = function(obj) return obj:Length() end,
    children = function(obj, start, count)
        local out = {}
        local n = obj:Length()
        for i = start + 1, math.min(n, start + count) do
            out[#out + 1] = { name = "[" .. i .. "]", value = obj:Get(i) }
        end
        return out
    end,
}

emmy.registerFormatter {
    name = "ue:TSet",
    match = { typeName = "TSet" },
    priority = 50,
    kind = "array",
    summary = function(obj) return "Num=" .. obj:Length() end,
    count = function(obj) return obj:Length() end,
    children = function(obj, start, count)
        local arr = obj:ToArray()
        local out = {}
        for i = start + 1, math.min(arr:Length(), start + count) do
            out[#out + 1] = { name = "[" .. i .. "]", value = arr:Get(i) }
        end
        return out
    end,
}

emmy.registerFormatter {
    name = "ue:TMap",
    match = { typeName = "TMap" },
    priority = 50,
    kind = "record",
    summary = function(obj) return "Num=" .. obj:Length() end,
    count = function(obj) return obj:Length() end,
    children = function(obj, start, count)
        local keys = obj:Keys()
        local out = {}
        for i = start + 1, math.min(keys:Length(), start + count) do
            local k = keys:Get(i)
            local name = type(k) == "string" and string.format("[%q]", k) or "[" .. tostring(k) .. "]"
            out[#out + 1] = { name = name, value = obj:Find(k) }
        end
        return out
    end,
}

return true
