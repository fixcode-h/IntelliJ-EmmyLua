-------------------------------------------------------------------------------
-- emmyHelper.lua: 调试器辅助模块入口
-- 整合各子模块，提供统一的 API 接口
-------------------------------------------------------------------------------

-------------------------------------------------------------------------------
-- 加载子模块
-------------------------------------------------------------------------------

local EmmyLog = require('tool/emmyLog')
local ProxyRegistry = require('tool/emmyProxy')
local handlerModule = require('tool/emmyHandler')
local TypeMatcher = require('tool/emmyMatcher')
local frameworks = require('tool/emmyFrameworks')

-- 解构 handler 模块
local UserdataProcessorBase = handlerModule.UserdataProcessorBase
local HandlerRegistry = handlerModule.HandlerRegistry

-------------------------------------------------------------------------------
-- 类型定义
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
-- 初始化子模块
-------------------------------------------------------------------------------

-- 初始化类型匹配器（关联 HandlerRegistry）
TypeMatcher:init(HandlerRegistry)

-------------------------------------------------------------------------------
-- 框架选择与初始化
-------------------------------------------------------------------------------

-- 设置框架模块的依赖
frameworks.setDependencies(emmy, ProxyRegistry, HandlerRegistry, TypeMatcher)

-- 选择合适的框架
emmy = frameworks.selectFramework()

-- 注册到全局变量
rawset(_G, 'emmyHelper', emmy)

-- 设置 HandlerRegistry 的 emmy 引用（用于 createNode）
-- 只在框架选择后设置一次，确保 emmy 对象已包含 createNode 方法
HandlerRegistry:setEmmy(emmy)

-- 加载 UE 特定处理器（FVector/FRotator/FDateTime/TArray/TMap/TSet 等）
pcall(require, 'emmyHelper_ue')

-------------------------------------------------------------------------------
-- 用户自定义初始化函数（可选）
-- 用户可以在全局定义 emmyHelperInit 函数，会在 emmyHelper 加载后执行
-------------------------------------------------------------------------------
local emmyHelperInit = rawget(_G, 'emmyHelperInit')
if emmyHelperInit then
    local ok, initErr = pcall(emmyHelperInit)
    if not ok then
        EmmyLog.error("[EmmyHelper]", "emmyHelperInit failed:", tostring(initErr))
    end
end

-------------------------------------------------------------------------------
-- 返回模块（供 require 使用）
-------------------------------------------------------------------------------
return {
    emmy = emmy,
    EmmyLog = EmmyLog,
    ProxyRegistry = ProxyRegistry,
    HandlerRegistry = HandlerRegistry,
    TypeMatcher = TypeMatcher,
}
