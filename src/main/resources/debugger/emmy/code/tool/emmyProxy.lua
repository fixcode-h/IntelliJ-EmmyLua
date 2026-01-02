-------------------------------------------------------------------------------
-- emmyProxy.lua: 代理表识别函数注册器
-- 负责管理和执行代理表识别逻辑，支持用户注册自定义识别函数
-------------------------------------------------------------------------------

---@class ProxyRecognizeResult
---@field isProxy boolean 是否为代理表
---@field typeName string|nil 类型名称（来自 __name）
---@field innerObject any|nil 内部对象（如 userdata）

---@alias ProxyRecognizer fun(obj: table): ProxyRecognizeResult|nil

---@class ProxyRegistry
---@field private recognizers ProxyRecognizer[] 识别函数列表
local ProxyRegistry = {
    recognizers = {}
}

--- 内置的 UnLua 代理表识别函数
---@param obj table
---@return ProxyRecognizeResult|nil
local function defaultUnluaRecognizer(obj)
    local uObject = rawget(obj, 'Object')
    if uObject and type(uObject) == 'userdata' then
        local mt = getmetatable(uObject)
        if mt then
            local name = rawget(mt, '__name')
            if name and type(name) == 'string' then
                return {
                    isProxy = true,
                    typeName = name,
                    innerObject = uObject
                }
            end
        end
    end
    return nil
end

--- 注册自定义代理表识别函数
---@param recognizer ProxyRecognizer 识别函数，接收 table 返回 ProxyRecognizeResult 或 nil
---@param priority? number 优先级，数值越小越先执行，默认 100
function ProxyRegistry:register(recognizer, priority)
    if type(recognizer) ~= 'function' then
        error('ProxyRegistry:register: recognizer must be a function')
    end
    priority = priority or 100
    
    -- 插入到合适的位置（按优先级排序）
    local inserted = false
    for i, entry in ipairs(self.recognizers) do
        if priority < entry.priority then
            table.insert(self.recognizers, i, { fn = recognizer, priority = priority })
            inserted = true
            break
        end
    end
    if not inserted then
        table.insert(self.recognizers, { fn = recognizer, priority = priority })
    end
end

--- 注销指定的识别函数
---@param recognizer ProxyRecognizer 要注销的识别函数
---@return boolean 是否成功注销
function ProxyRegistry:unregister(recognizer)
    for i, entry in ipairs(self.recognizers) do
        if entry.fn == recognizer then
            table.remove(self.recognizers, i)
            return true
        end
    end
    return false
end

--- 清空所有自定义识别函数（保留内置识别函数）
function ProxyRegistry:clear()
    self.recognizers = {}
end

--- 检测对象是否为代理表
---@param obj any 待检测的对象
---@return ProxyRecognizeResult|nil 识别结果，非代理表返回 nil
function ProxyRegistry:recognize(obj)
    -- 只处理 table 类型
    if type(obj) ~= 'table' then
        return nil
    end
    
    -- 遍历所有注册的识别函数
    for _, entry in ipairs(self.recognizers) do
        local result = entry.fn(obj)
        if result and result.isProxy then
            return result
        end
    end
    
    -- 最后尝试内置识别函数
    return defaultUnluaRecognizer(obj)
end

--- 判断对象是否为代理表（简化接口）
---@param obj any 待检测的对象
---@return boolean 是否为代理表
function ProxyRegistry:isProxy(obj)
    local result = self:recognize(obj)
    return result ~= nil and result.isProxy == true
end

--- 获取已注册的识别函数数量（不含内置）
---@return number
function ProxyRegistry:count()
    return #self.recognizers
end

-- 注册到全局变量，供用户自定义脚本访问
rawset(_G, 'ProxyRegistry', ProxyRegistry)

return ProxyRegistry
