-------------------------------------------------------------------------------
-- emmyFrameworks.lua: 框架支持
-- 提供 tolua/xlua/cocos/unlua 等框架的调试支持
-------------------------------------------------------------------------------

-- 前向声明（由主模块设置）
local emmy = nil
local ProxyRegistry = nil
local HandlerRegistry = nil
local TypeMatcher = nil

-------------------------------------------------------------------------------
-- tolua 框架支持
-------------------------------------------------------------------------------
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

-------------------------------------------------------------------------------
-- xlua 框架支持
-------------------------------------------------------------------------------
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

-------------------------------------------------------------------------------
-- cocos2d-x 框架支持
-------------------------------------------------------------------------------
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

-------------------------------------------------------------------------------
-- UnLua 框架支持 (UE4/UE5) - 使用模块化架构
-------------------------------------------------------------------------------

--- 处理代理表（UnLua 绑定的 Lua table）
---@param variable Variable
---@param obj table
---@param depth number
---@param proxyResult ProxyRecognizeResult
---@return boolean
local function processProxyTable(variable, obj, depth, proxyResult)
    -- 设置类型信息
    variable.valueTypeName = proxyResult.typeName
    
    -- 如果存在内部 Object，使用 tostring 设置 value
    if proxyResult.innerObject then
        variable.value = tostring(proxyResult.innerObject)
    end
    
    -- 遍历 table 字段
    for key, value in pairs(obj) do
        local childNode = emmy.createNode()
        childNode.name = tostring(key)
        childNode:query(value, depth - 1, true)
        variable:addChild(childNode)
    end
    
    return true
end

--- 处理 userdata 对象
---@param variable Variable
---@param obj userdata
---@param depth number
---@return boolean
local function processUserdata(variable, obj, depth)
    local processor, typeName = TypeMatcher:getHandler(obj)
    
    if not typeName then
        -- 无法从 metatable 提取 __name，无法处理
        return false
    end
    
    -- 设置类型名
    variable.valueTypeName = typeName
    
    -- 如果没有匹配到处理器，使用默认处理器
    if not processor then
        local defaultProcessor = HandlerRegistry:getProcessorBase():new()
        return defaultProcessor:process(variable, obj, typeName, depth)
    end
    
    -- 调用匹配的处理器
    return processor:process(variable, obj, typeName, depth)
end

-- UnLua 调试器
local unluaDebugger = {
    queryVariable = function(variable, obj, typeName, depth)
        -- 检查是否为代理表
        if typeName == 'table' then
            local proxyResult = ProxyRegistry:recognize(obj)
            if proxyResult and proxyResult.isProxy then
                return processProxyTable(variable, obj, depth, proxyResult)
            end
        elseif typeName == 'userdata' then
            return processUserdata(variable, obj, depth)
        end
        
        return false
    end,
    
    -- 动态注册 API（委托给子模块）
    registerProcessor = function(typeName, processor)
        local ok, err = pcall(function()
            HandlerRegistry:register(typeName, processor)
        end)
        if not ok then
            EmmyLog.error("registerProcessor failed:", typeName, err)
        end
        return ok
    end,
    
    unregisterProcessor = function(typeName)
        local ok, err = pcall(function()
            HandlerRegistry:unregister(typeName)
        end)
        if not ok then
            EmmyLog.error("unregisterProcessor failed:", typeName, err)
        end
        return ok
    end,
    
    getProcessorBase = function()
        local ok, result = pcall(function()
            return HandlerRegistry:getProcessorBase()
        end)
        if not ok then
            EmmyLog.error("getProcessorBase failed:", result)
            return nil
        end
        return result
    end,
    
    -- 代理表识别函数注册 API
    registerProxyRecognizer = function(recognizer, priority)
        local ok, err = pcall(function()
            ProxyRegistry:register(recognizer, priority)
        end)
        if not ok then
            EmmyLog.error("registerProxyRecognizer failed:", err)
        end
        return ok
    end,
    
    unregisterProxyRecognizer = function(recognizer)
        local ok, result = pcall(function()
            return ProxyRegistry:unregister(recognizer)
        end)
        if not ok then
            EmmyLog.error("unregisterProxyRecognizer failed:", result)
            return false
        end
        return result
    end
}

-------------------------------------------------------------------------------
-- 模块接口
-------------------------------------------------------------------------------

local M = {}

--- 设置依赖引用
---@param emmyRef table emmy 引用
---@param proxyRef table ProxyRegistry 引用
---@param handlerRef table HandlerRegistry 引用
---@param matcherRef table TypeMatcher 引用
function M.setDependencies(emmyRef, proxyRef, handlerRef, matcherRef)
    emmy = emmyRef
    ProxyRegistry = proxyRef
    HandlerRegistry = handlerRef
    TypeMatcher = matcherRef
end

--- 根据环境选择合适的框架
---@return table 选中的框架调试器
function M.selectFramework()
    if tolua then
        if tolua.gettag then
            return toluaHelper
        else
            return cocosLuaDebugger
        end
    elseif xlua then
        return xluaDebugger
    else
        return unluaDebugger
    end
end

-- 导出各框架调试器（供测试或直接使用）
M.toluaHelper = toluaHelper
M.xluaDebugger = xluaDebugger
M.cocosLuaDebugger = cocosLuaDebugger
M.unluaDebugger = unluaDebugger

return M
