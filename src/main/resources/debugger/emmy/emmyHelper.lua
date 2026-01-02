-------------------------------------------------------------------------------
-- EmmyHelper: 调试器辅助模块入口
-- 整合各子模块，提供统一的 API 接口
-------------------------------------------------------------------------------

-------------------------------------------------------------------------------
-- EmmyLog: 日志系统
-- 提供统一的日志输出接口，支持日志级别和全局开关
-------------------------------------------------------------------------------

---@class EmmyLog
---@field enableLog boolean 全局日志开关
---@field minLogLevel number 最低日志级别
local EmmyLog = {
    enableLog = true,  -- 全局开关，默认开启
    minLogLevel = 0,   -- 默认 Info 级别
}

---@enum LogLevel
EmmyLog.LogLevel = {
    Debug = 0,    -- 调试信息（调试器内部使用）
    Info = 1,     -- 普通信息
    Warning = 2,  -- 警告
    Error = 3,    -- 错误
}

-- 日志标识
_G.EmmyLuaLogType = "[EmmyLua]"

--- 日志级别名称映射
local LogLevelNames = {
    [0] = "DEBUG",
    [1] = "INFO",
    [2] = "WARN",
    [3] = "ERROR",
}

--- 将多个参数拼接成字符串
---@param ... any
---@return string
local function concatArgs(...)
    local args = {...}
    local parts = {}
    for i = 1, select('#', ...) do
        parts[i] = tostring(args[i])
    end
    return table.concat(parts, " ")
end

--- 输出日志
---@param level number 日志级别
---@param ... any 日志内容
function EmmyLog.log(level, ...)
    if not EmmyLog.enableLog then
        return
    end
    if level < EmmyLog.minLogLevel then
        return
    end
    
    local message = concatArgs(...)
    local levelName = LogLevelNames[level] or "UNKNOWN"
    local fullMessage = string.format("%s [%s] %s", _G.EmmyLuaLogType, levelName, message)
    
    -- 尝试通过 emmy_core.sendLog 发送到 IDE
    local emmy_core = rawget(_G, 'emmy_core')
    if emmy_core and emmy_core.sendLog then
        pcall(emmy_core.sendLog, level, fullMessage)
    else
        -- 回退到 print 输出
        print(fullMessage)
    end
end

--- Debug 级别日志（调试器内部使用）
---@param ... any 日志内容
function EmmyLog.debug(...)
    EmmyLog.log(EmmyLog.LogLevel.Debug, ...)
end

--- Info 级别日志
---@param ... any 日志内容
function EmmyLog.info(...)
    EmmyLog.log(EmmyLog.LogLevel.Info, ...)
end

--- Warning 级别日志
---@param ... any 日志内容
function EmmyLog.warn(...)
    EmmyLog.log(EmmyLog.LogLevel.Warning, ...)
end

--- Error 级别日志
---@param ... any 日志内容
function EmmyLog.error(...)
    EmmyLog.log(EmmyLog.LogLevel.Error, ...)
end

-- 注册到全局变量
rawset(_G, 'EmmyLog', EmmyLog)

-------------------------------------------------------------------------------

---@class emmy
---@field createNode fun(): Variable
---@field registerProcessor fun(typeName: string, processor: UserdataProcessor): void
---@field unregisterProcessor fun(typeName: string): void
---@field getProcessorBase fun(): UserdataProcessorBase
---@field registerProxyRecognizer fun(recognizer: ProxyRecognizer, priority?: number): void
---@field unregisterProxyRecognizer fun(recognizer: ProxyRecognizer): boolean
local emmy = {}

---@class Variable
---@field query fun(self: Variable, obj: any, depth: number, queryHelper: boolean):void
---@field addChild fun(self: Variable, child: Variable):void
---@field name string
---@field value string
---@field valueType number
---@field valueTypeName string

---@class UserdataProcessor
---@field process fun(self: UserdataProcessor, variable: Variable, obj: any, name: string, depth: number): boolean

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

-------------------------------------------------------------------------------
-- ProxyRegistry: 代理表识别函数注册器
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

-------------------------------------------------------------------------------
-- HandlerRegistry: 类型处理器注册器
-- 负责管理 userdata 类型处理器的注册、查询，使用精确类型名匹配
-------------------------------------------------------------------------------

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
-- TArray/TMap/TSet 容器处理器
-------------------------------------------------------------------------------

local TContainerProcessor = UserdataProcessorBase:extend()

--- 处理 ToTable 转换
---@param variable Variable
---@param obj any
---@param name string
---@param depth number
---@return boolean
function TContainerProcessor:processToTable(variable, obj, name, depth)
    local mt = getmetatable(obj)
    if not mt then return false end

    local toTableFunc = rawget(mt, 'ToTable')
    if toTableFunc and type(toTableFunc) == 'function' then
        local resultNode = emmy.createNode()
        local resultTable = toTableFunc(obj)
        resultNode.name = "ValueTable"
        resultNode.value = resultTable
        -- resultNode.valueType = 5
        -- resultNode.valueTypeName = 'table'
        resultNode:query(resultTable, depth - 1, true)
        variable:addChild(resultNode)
        return true
    end
    return false
end

function TContainerProcessor:processSpecific(variable, obj, name, depth)
    self:processToTable(variable, obj, name, depth)
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
    self.handlers = {
        ['TArray'] = TContainerProcessor,
        ['TMap'] = TContainerProcessor,
        ['TSet'] = TContainerProcessor,
    }
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

-------------------------------------------------------------------------------
-- TypeMatcher: 类型匹配器
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

-------------------------------------------------------------------------------
-- 初始化子模块
-------------------------------------------------------------------------------

-- 初始化类型匹配器（关联 HandlerRegistry）
TypeMatcher:init(HandlerRegistry)

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
    --variable.valueType = 5  -- table 类型
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
-- 框架选择与初始化
-------------------------------------------------------------------------------
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

-- 设置 HandlerRegistry 的 emmy 引用（用于 createNode）
-- 只在框架选择后设置一次，确保 emmy 对象已包含 createNode 方法
HandlerRegistry:setEmmy(emmy)

-------------------------------------------------------------------------------
-- __emmyDebuggerExtInit: 扩展初始化函数（内部使用）
-- emmyHelper_ue.lua 的内容将被插入到此函数体内
-------------------------------------------------------------------------------
local function __emmyDebuggerExtInit()
    do
        -- [EMMY_HELPER_INIT_CONTENT]
    end
end

local success, err = pcall(__emmyDebuggerExtInit)
if not success then
    EmmyLog.error("[EmmyHelper]", "__emmyDebuggerExtInit failed:", tostring(err))
end


local emmyHelperInit = rawget(_G, 'emmyHelperInit')
if emmyHelperInit then
    local ok, initErr = pcall(emmyHelperInit)
    if not ok then
        EmmyLog.error("[EmmyHelper]", "emmyHelperInit failed:", tostring(initErr))
    end
end
