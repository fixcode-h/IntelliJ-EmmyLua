-------------------------------------------------------------------------------
-- emmyHandler.lua: 类型处理器注册器
-- 负责管理 userdata 类型处理器的注册、查询，使用精确类型名匹配
-------------------------------------------------------------------------------

-- 前向声明 emmy 引用（由主模块设置）
local emmy = nil

-------------------------------------------------------------------------------
-- Userdata 处理器基类
-------------------------------------------------------------------------------

---@class UserdataProcessorBase : UserdataProcessor
local UserdataProcessorBase = {}
UserdataProcessorBase.__index = UserdataProcessorBase

--- 创建新的处理器实例
---@return UserdataProcessorBase
function UserdataProcessorBase:new()
    local obj = {}
    setmetatable(obj, self)
    return obj
end

--- 继承基类创建子类处理器
---@return UserdataProcessorBase
function UserdataProcessorBase:extend()
    local subclass = {}
    setmetatable(subclass, { __index = self })
    subclass.__index = subclass
    return subclass
end

--- 通用的 __tostring 处理函数
---@param variable Variable
---@param obj any
---@return boolean
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

--- 主处理入口
---@param variable Variable
---@param obj any
---@param name string
---@param depth number
---@return boolean
function UserdataProcessorBase:process(variable, obj, name, depth)
    self:processToString(variable, obj)
    self:processSpecific(variable, obj, name, depth)
    if self:shouldProcessMetatable(depth) then
        self:processMetatable(variable, obj, depth)
    end
    return true
end

--- 子类可覆写此方法处理特定类型的逻辑
---@param variable Variable
---@param obj any
---@param name string
---@param depth number
function UserdataProcessorBase:processSpecific(variable, obj, name, depth)
    -- 默认空实现，子类可覆写
end

--- 是否应该处理元表
---@param depth number
---@return boolean
function UserdataProcessorBase:shouldProcessMetatable(depth)
    return depth > 1
end

--- 处理元表属性
---@param variable Variable
---@param obj any
---@param depth number
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

-------------------------------------------------------------------------------
-- HandlerRegistry: 处理器注册表
-------------------------------------------------------------------------------

---@class HandlerRegistry
---@field private handlers table<string, UserdataProcessor> 类型名 -> 处理器映射
---@field private defaultProcessor UserdataProcessorBase 默认处理器
local HandlerRegistry = {
    handlers = {},
    defaultProcessor = nil
}

--- 初始化注册表
function HandlerRegistry:init()
    self.handlers = {}
    self.defaultProcessor = UserdataProcessorBase
end

--- 注册自定义处理器（精确类型名匹配）
---@param typeName string 类型名称（对应 __name 元表字段）
---@param processor UserdataProcessor 处理器实例或类
function HandlerRegistry:register(typeName, processor)
    if type(typeName) ~= 'string' or typeName == '' then
        error('HandlerRegistry:register: typeName must be a non-empty string')
    end
    if type(processor) ~= 'table' then
        error('HandlerRegistry:register: processor must be a table with process method')
    end
    self.handlers[typeName] = processor
end

--- 注销处理器
---@param typeName string 类型名称
function HandlerRegistry:unregister(typeName)
    self.handlers[typeName] = nil
end

--- 获取处理器（精确匹配）
---@param typeName string 类型名称
---@return UserdataProcessor 匹配的处理器或默认处理器
function HandlerRegistry:get(typeName)
    local processor = self.handlers[typeName]
    if processor then
        return processor
    end
    -- 返回默认的基类处理器实例
    return self.defaultProcessor:new()
end

--- 检查是否存在指定类型的处理器
---@param typeName string 类型名称
---@return boolean
function HandlerRegistry:has(typeName)
    return self.handlers[typeName] ~= nil
end

--- 获取所有已注册的类型名
---@return string[]
function HandlerRegistry:getRegisteredTypes()
    local types = {}
    for typeName, _ in pairs(self.handlers) do
        table.insert(types, typeName)
    end
    return types
end

--- 获取处理器基类（用于用户扩展）
---@return UserdataProcessorBase
function HandlerRegistry:getProcessorBase()
    return UserdataProcessorBase
end

--- 设置 emmy 引用（用于 createNode）
---@param emmyRef table
function HandlerRegistry:setEmmy(emmyRef)
    emmy = emmyRef
end

-- 初始化
HandlerRegistry:init()

-- 注册到全局变量，供用户自定义脚本访问
rawset(_G, 'HandlerRegistry', HandlerRegistry)

return {
    UserdataProcessorBase = UserdataProcessorBase,
    HandlerRegistry = HandlerRegistry,
}
