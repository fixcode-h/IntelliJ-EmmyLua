-------------------------------------------------------------------------------
-- emmyLog.lua: 日志系统
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

return EmmyLog
