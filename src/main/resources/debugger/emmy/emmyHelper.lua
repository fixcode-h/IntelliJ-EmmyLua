
---@class emmy
---@field createNode fun(): Variable
local emmy = {}

---@class Variable
---@field query fun(self: Variable, obj: any, depth: number, queryHelper: boolean):void
---@field name string
---@field value string
---@field valueTypeName string


---@enum LuaValueType
local LuaValueType = {
    ["nil"] = 0,           -- nil 类型
    ["boolean"] = 1,       -- boolean 类型
    ["lightuserdata"] = 2, -- lightuserdata 类型
    ["number"] = 3,        -- number 类型
    ["string"] = 4,        -- string 类型
    ["table"] = 5,         -- table 类型
    ["function"] = 6,      -- function 类型
    ["userdata"] = 7,      -- userdata 类型
    ["thread"] = 8,        -- thread 类型
    ["GROUP"] = 9          -- 特殊分组类型 C# or C++
}

local toluaHelper = {
    ---@param variable Variable
    queryVariable = function(variable, obj, typeName, depth)
        if typeName == 'table' then
            local cname = rawget(obj, '__cname')
            if cname then
                variable:query(obj, depth)
                variable.valueTypeName = cname
                return true
            end
        elseif typeName == 'userdata' then
            local mt = getmetatable(obj)
            if mt == nil then return false end

            variable.valueTypeName = 'C#'
            variable.value = tostring(obj)

            if depth > 1 then
                local parent = variable
                local propMap = {}
                while mt ~= nil do
                    local getTab = mt[tolua.gettag]
                    if getTab then
                        for property, _ in pairs(getTab) do
                            if not propMap[property] then
                                propMap[property] = true
                                local v = emmy.createNode()
                                v.name = property
                                v:query(obj[property], depth - 1, true)
                                parent:addChild(v)
                            end
                        end
                    end
                    mt = getmetatable(mt)
                    if mt then
                        local super = emmy.createNode()
                        super.name = "base"
                        super.value = mt[".name"]
                        super.valueType = 9
                        super.valueTypeName = "C#"
                        parent:addChild(super)
                        parent = super
                    end
                end
            end
            return true
        end
    end
}

local xluaDebugger = {
    queryVariable = function(variable, obj, typeName, depth)
        if typeName == 'userdata' then
            local mt = getmetatable(obj)
            if mt == nil then
                return false
            end

            local CSType = obj:GetType()
            if CSType then
                variable.valueTypeName = 'C#'
                variable.value = tostring(obj) --CSType.FullName

                if depth > 1 then
                    local Type = CS.System.Type
                    local ObsoleteType = Type.GetType('System.ObsoleteAttribute')
                    local BindType = Type.GetType('System.Reflection.BindingFlags')
                    local bindValue = CS.System.Enum.ToObject(BindType, 5174) -- Instance | Public | NonPublic | GetProperty | DeclaredOnly | GetField

                    local parent = variable
                    while CSType do
                        local properties = CSType:GetProperties(bindValue)
                        for i = 1, properties.Length do
                            local p = properties[i - 1]
                            if CS.System.Attribute.GetCustomAttribute(p, ObsoleteType) == nil then
                                local property = p.Name
                                local value = obj[property]

                                local v = emmy.createNode()
                                v.name = property
                                v:query(value, depth - 1, true)
                                parent:addChild(v)
                            end
                        end
                        local fields = CSType:GetFields(bindValue)
                        for i = 1, fields.Length do
                            local p = fields[i - 1]
                            if CS.System.Attribute.GetCustomAttribute(p, ObsoleteType) == nil then
                                local property = p.Name
                                local value = obj[property]

                                local v = emmy.createNode()
                                v.name = property
                                v:query(value, depth - 1, true)
                                parent:addChild(v)
                            end
                        end

                        CSType = CSType.BaseType
                        if CSType then
                            local super = emmy.createNode()
                            super.name = "base"
                            super.value = CSType.FullName
                            super.valueType = 9
                            super.valueTypeName = "C#"
                            parent:addChild(super)
                            parent = super
                        end
                    end
                end

                return true
            end
        end
    end
}

local cocosLuaDebugger = {
    queryVariable = function(variable, obj, typeName, depth)
        if typeName == 'userdata' then
            local mt = getmetatable(obj)
            if mt == nil then return false end
            variable.valueTypeName = 'C++'
            variable.value = mt[".classname"]

            if depth > 1 then
                local parent = variable
                local propMap = {}
                while mt ~= nil do
                    for property, _ in pairs(mt) do
                        if not propMap[property] then
                            propMap[property] = true
                            local v = emmy.createNode()
                            v.name = property
                            v:query(obj[property], depth - 1, true)
                            parent:addChild(v)
                        end
                    end
                    mt = getmetatable(mt)
                    if mt then
                        local super = emmy.createNode()
                        super.name = "base"
                        super.value = mt[".classname"]
                        super.valueType = 9
                        super.valueTypeName = "C++"
                        parent:addChild(super)
                        parent = super
                    end
                end
            end
            return true
        end
    end
}

-- 基于 __name 的处理器系统
local UserdataProcessorBase = {}
UserdataProcessorBase.__index = UserdataProcessorBase

function UserdataProcessorBase:new()
    local obj = {}
    setmetatable(obj, self)
    return obj
end

-- 通用的 __tostring 处理函数
function UserdataProcessorBase:processToString(variable, obj)
    local mt = getmetatable(obj)
    if not mt then return false end

    local toStringFunc = rawget(mt, '__tostring')
    if toStringFunc and type(toStringFunc) == 'function' then
        local ok, s = pcall(function() return toStringFunc(obj) end)
        if ok then
            variable.value = tostring(s)
        else
            variable.value = tostring(obj)
        end
        variable.valueType = 7
        if not variable.valueTypeName then
            variable.valueTypeName = 'userdata'
        end
        return true
    end
    return false
end

function UserdataProcessorBase:process(variable, obj, name, depth)
    self:processToString(variable, obj)

    self:processSpecific(variable, obj, name, depth)

    if self:shouldProcessMetatable(depth) then
        self:processMetatable(variable, obj, depth)
    end
    return true
end


function UserdataProcessorBase:processSpecific(variable, obj, name, depth)
    -- 子类可覆写此方法处理特定类型的逻辑
end

function UserdataProcessorBase:shouldProcessMetatable(depth)
    return depth > 1
end

function UserdataProcessorBase:processMetatable(variable, obj, depth)
    local mt = getmetatable(obj)
    if not mt then return end
    local parent = variable
    for property, _ in pairs(mt) do
        local v = emmy.createNode()
        v.name = tostring(property)
        local ok, val = pcall(function() return obj[property] end)
        if ok then
            v:query(val, depth - 1, true)
        else
            v.value = "<unreadable>"
            v.valueTypeName = "metatable"
        end
        parent:addChild(v)
    end
end

local TArrayProcessor = UserdataProcessorBase:new()
-- 通用的 ToTable 处理函数
function TArrayProcessor:processToTable(variable, obj, name, depth)
    local mt = getmetatable(obj)
    if not mt then return false end

    local toTableFunc = rawget(mt, 'ToTable')
    if toTableFunc and type(toTableFunc) == 'function' then
        local resultNode = emmy.createNode()
        local resultTable = toTableFunc(obj)
        resultNode.name = "ValueTable"
        resultNode.value = resultTable
        resultNode.valueType = 5
        resultNode.valueTypeName = 'table'
        resultNode:query(resultTable, depth - 1, true)
        variable:addChild(resultNode)
        return true
    end
end
function TArrayProcessor:processSpecific(variable, obj, name, depth)
    self:processToTable(variable, obj, name, depth)
end

-- 处理器注册表
local userdataProcessors = {
    ['TArray'] = TArrayProcessor,
    ['TMap'] = TArrayProcessor,
    ['TSet'] = TArrayProcessor,
}

-- 获取处理器的函数
local function getUserdataProcessor(typeName)
    local processor = userdataProcessors[typeName]
    if processor then
        return processor
    end
    -- 返回默认的基类处理器
    return UserdataProcessorBase:new()
end


local unluaDebugger = {
    queryVariable = function(variable, obj, typeName, depth)
        -- 检查是否为 table 类型，并且看起来像一个 UnLua 代理对象
        if typeName == 'table' then
            local uObject = rawget(obj, 'Object')
            if uObject and type(uObject) == 'userdata' then
                local mt = getmetatable(uObject)
                if mt then
                    local name = rawget(mt, '__name')
                    if name and type(name) == 'string' then
                        -- 手动遍历 table 而不是调用 query
                        for key, value in pairs(obj) do
                            local childNode = emmy.createNode()
                            childNode.name = tostring(key)
                            childNode:query(value, depth - 1, true)
                            variable:addChild(childNode)
                        end
                        -- 将 table 的类型名也显示为UE对象名
                        variable.valueTypeName = name
                        return true
                    end
                end
            end
        elseif typeName == 'userdata' then
            local mt = getmetatable(obj)
            if not mt then return false end

            local name = rawget(mt, '__name')
            if not name or type(name) ~= 'string' then
                return false
            end
            variable.valueTypeName = name

            local processor = getUserdataProcessor(name)
            return processor:process(variable, obj, name, depth)
        end
        return false
    end
}

if tolua then
    if tolua.gettag then
        emmy = toluaHelper
    else
        emmy = cocosLuaDebugger
    end
elseif xlua then
    emmy = xluaDebugger
else
    emmy = unluaDebugger
end

rawset(_G, 'emmyHelper', emmy)

local emmyHelperInit = rawget(_G, 'emmyHelperInit')
if emmyHelperInit then
    emmyHelperInit()
end
