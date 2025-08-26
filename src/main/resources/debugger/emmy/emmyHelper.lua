---@meta

-- Copyright (c) 2017. tangzx(love.tangzx@qq.com)
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
-- http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

---@class emmy
---@field createNode fun(): Variable
local emmy = {}

---@class Variable
---@field query fun(self: Variable, obj: any, depth: number, queryHelper: boolean):void
---@field name string
---@field value string
---@field valueTypeName string

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

local processedForToTable = setmetatable({}, { __mode = 'k' })
rawset(_G, 'processedForToTable', processedForToTable)


local unluaDebugger = {
    queryVariable = function(variable, obj, typeName, depth)
        -- 检查是否为 table 类型，并且看起来像一个 UnLua 代理对象
        if typeName == 'table' then
            -- 使用 rawget 避免触发元方法，安全地获取 Object 字段
            local uObject = rawget(obj, 'Object')
            if uObject and type(uObject) == 'userdata' then
                local mt = getmetatable(uObject)
                if mt then
                    local name = rawget(mt, '__name')
                    if name and type(name) == 'string' then
                        -- 让调试器正常展开这个 table
                        variable:query(obj, depth, true)
                        -- 将 table 的类型名也显示为UE对象名
                        variable.valueTypeName = name
                        return true
                    end
                end
            end
        elseif typeName == 'userdata' then
            if processedForToTable[variable] then
                return false
            end
            local mt = getmetatable(obj)
            if not mt then return false end

            local name = rawget(mt, '__name')
            if not name or type(name) ~= 'string' then
                return
            end

            processedForToTable[variable] = true
            variable.valueTypeName = name

            local toStringFunc = rawget(mt, '__tostring')
            if toStringFunc and type(toStringFunc) == 'function' then
                variable.value = tostring(toStringFunc(obj))
                variable.valueType = 7
            end

            local toTableFunc = rawget(mt, 'ToTable')
            if toTableFunc and type(toTableFunc) == 'function' then
                local resultNode = emmy.createNode()
                local resultTable = toTableFunc(obj)
                resultNode.name = name.."_Value"
                resultNode.value = resultTable
                resultNode.valueType = 5
                resultNode.valueTypeName = 'table'
                resultNode:query(resultTable, depth - 1, true)
                variable:addChild(resultNode)
            end

            if depth > 1 then
                local parent = variable
                for property, val in pairs(mt) do
                    local v = emmy.createNode()
                    v.name = property
                    v:query(obj[property], depth - 1, true)
                    parent:addChild(v)
                end
            end
            return true
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
