-------------------------------------------------------------------------------
-- emmyMatcher.lua: 类型匹配器
-- 负责从对象中提取类型信息并匹配对应的处理器
-------------------------------------------------------------------------------

---@class TypeMatchResult
---@field matched boolean 是否匹配成功
---@field typeName string|nil 类型名称
---@field processor UserdataProcessor|nil 匹配的处理器

---@class TypeMatcher
---@field private handlerRegistry HandlerRegistry 处理器注册表引用
local TypeMatcher = {
    handlerRegistry = nil
}

--- 初始化类型匹配器
---@param handlerRegistry HandlerRegistry 处理器注册表
function TypeMatcher:init(handlerRegistry)
    self.handlerRegistry = handlerRegistry
end

--- 从 userdata 对象中提取类型名
---@param obj userdata
---@return string|nil 类型名称
function TypeMatcher:extractTypeName(obj)
    if type(obj) ~= 'userdata' then
        return nil
    end
    
    local mt = getmetatable(obj)
    if not mt then
        return nil
    end
    
    local name = rawget(mt, '__name')
    if name and type(name) == 'string' then
        return name
    end
    
    return nil
end

--- 匹配对象并获取处理器
---@param obj any 待匹配的对象
---@return TypeMatchResult 匹配结果
function TypeMatcher:match(obj)
    local result = {
        matched = false,
        typeName = nil,
        processor = nil
    }
    
    -- 只处理 userdata 类型
    if type(obj) ~= 'userdata' then
        return result
    end
    
    -- 提取类型名
    local typeName = self:extractTypeName(obj)
    if not typeName then
        return result
    end
    
    -- 检查 handlerRegistry 是否可用
    if not self.handlerRegistry then
        return result
    end
    
    -- 获取处理器（精确匹配）
    local processor = self.handlerRegistry:get(typeName)
    
    result.matched = true
    result.typeName = typeName
    result.processor = processor
    
    return result
end

--- 获取对象的处理器（简化接口）
---@param obj any 待处理的对象
---@return UserdataProcessor|nil 处理器，无法匹配返回 nil
---@return string|nil 类型名称
function TypeMatcher:getHandler(obj)
    local result = self:match(obj)
    if result.matched then
        return result.processor, result.typeName
    end
    return nil, nil
end

--- 检查对象是否可以被处理
---@param obj any 待检查的对象
---@return boolean
function TypeMatcher:canHandle(obj)
    if type(obj) ~= 'userdata' then
        return false
    end
    return self:extractTypeName(obj) ~= nil
end

-- 注册到全局变量，供用户自定义脚本访问
rawset(_G, 'TypeMatcher', TypeMatcher)

return TypeMatcher
