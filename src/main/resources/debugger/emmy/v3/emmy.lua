-------------------------------------------------------------------------------
-- emmy.lua：新调试核心（协议 v3）的类型显示接口，helper v2（docs/design/variable-view.md 11.2）。
--
-- 用法（在 helper 脚本里，例如 emmy_ue.lua）：
--   local emmy = require("emmy")
--   emmy.registerFormatter {
--       name = "TArray",
--       match = { typeName = "TArray" },          -- 或 match = function(obj, typeName) return ... end
--       priority = 100,
--       typeName = function(obj) return "TArray" end,  -- 可选，字符串或函数
--       summary = function(obj) return "Num=" .. obj:Length() end,
--       count = function(obj) return obj:Length() end,
--       children = function(obj, start, count) return { { name = "[1]", value = obj:Get(1) } } end,
--       isValid = function(obj) return true end,
--   }
--
-- 惰性、分页：summary 只在生成父节点那一页时调，count / children 只在展开时调，每次只要一页。
-- runtime 在私有协程里、带 pcall 和指令预算（helperBudgetMs）调用下面的 dispatch；出错这一项退回默认显示，
-- 行尾标「helper 出错」。这些代码运行在调试器私有环境里，不会触发断点。
-------------------------------------------------------------------------------

local emmy = {}

local formatters = {}  -- 全部，按 priority 从高到低
local byType = {}      -- typeName -> { formatter... }（match.typeName 的）
local dynamic = {}     -- match 是函数的

local function rebuild()
    table.sort(formatters, function(a, b)
        if a.priority ~= b.priority then return a.priority > b.priority end
        return a.order < b.order
    end)
    byType, dynamic = {}, {}
    for _, f in ipairs(formatters) do
        if type(f.match) == "function" then
            dynamic[#dynamic + 1] = f
        else
            local names = f.match.typeName
            if type(names) == "string" then names = { names } end
            for _, n in ipairs(names or {}) do
                local list = byType[n]
                if not list then
                    list = {}
                    byType[n] = list
                end
                list[#list + 1] = f
            end
        end
    end
end

local order = 0

--- 注册一个类型显示器。同名的会被替换。
---@param def table
function emmy.registerFormatter(def)
    assert(type(def) == "table", "registerFormatter 需要一个表")
    assert(type(def.name) == "string" and def.name ~= "", "formatter 必须有 name")
    local match = def.match
    assert(type(match) == "function" or (type(match) == "table" and match.typeName ~= nil),
        "formatter.match 必须是函数或 { typeName = ... }")
    assert(def.summary == nil or type(def.summary) == "function", "formatter.summary 必须是函数")
    assert(def.children == nil or type(def.children) == "function", "formatter.children 必须是函数")
    emmy.unregisterFormatter(def.name)
    order = order + 1
    local f = {}
    for k, v in pairs(def) do f[k] = v end
    f.priority = tonumber(def.priority) or 0
    f.order = order
    formatters[#formatters + 1] = f
    rebuild()
    return f
end

function emmy.unregisterFormatter(name)
    for i = #formatters, 1, -1 do
        if formatters[i].name == name then table.remove(formatters, i) end
    end
    rebuild()
end

local function find(obj, typeName)
    local list = typeName and byType[typeName]
    if list then return list[1] end
    for _, f in ipairs(dynamic) do
        if f.match(obj, typeName) then return f end
    end
    return nil
end

-------------------------------------------------------------------------------
-- 旧接口兼容：registerProcessor（emmyHelper.lua 时代的写法）。
-- 旧 processor 一次构造全部子项；这里每次展开时跑一遍 process，再按页切出来。
-------------------------------------------------------------------------------

local Node = {}
Node.__index = Node

function emmy.createNode()
    return setmetatable({ name = "", value = "", children = {} }, Node)
end

--- 旧写法 node:query(value, depth, queryHelper)：记下原值，由 runtime 生成显示
function Node:query(value)
    self.__value = value
    self.__hasValue = true
end

function Node:addChild(child)
    self.children[#self.children + 1] = child
end

local ProcessorBase = {}
ProcessorBase.__index = ProcessorBase

function ProcessorBase:extend()
    local cls = setmetatable({}, { __index = self })
    cls.__index = cls
    return cls
end

function ProcessorBase:process(variable, obj, name, depth)
    if self.processSpecific then self:processSpecific(variable, obj, name, depth) end
    return true
end

function emmy.getProcessorBase()
    return ProcessorBase
end

function emmy.registerProcessor(typeName, processor)
    local function run(obj, depth)
        local node = emmy.createNode()
        local p = processor
        if type(p) == "table" and getmetatable(p) == nil and p.process == nil then p = setmetatable(p, ProcessorBase) end
        p:process(node, obj, typeName, depth)
        return node
    end
    emmy.registerFormatter {
        name = "processor:" .. typeName,
        match = { typeName = typeName },
        priority = -100, -- 新写的 formatter 优先
        summary = function(obj) return tostring(run(obj, 1).value) end,
        count = function(obj) return #run(obj, 2).children end,
        children = function(obj, start, count)
            local all = run(obj, 2).children
            local out = {}
            for i = start + 1, math.min(#all, start + count) do
                local c = all[i]
                if c.__hasValue then
                    out[#out + 1] = { name = tostring(c.name), value = c.__value }
                else
                    out[#out + 1] = { name = tostring(c.name), summary = tostring(c.value) }
                end
            end
            return out
        end,
    }
end

function emmy.unregisterProcessor(typeName)
    emmy.unregisterFormatter("processor:" .. typeName)
end

-------------------------------------------------------------------------------
-- runtime 入口（只给 C++ 调；协议见 variable-view.md 11.2）
--   dispatch("describe", obj, typeName)
--     → nil（不认领）或 { valid?, typeName?, summary?, kind?, hint?, expandable }
--   dispatch("children", obj, typeName, start, count)
--     → { total?, items = { { name, value } 或 { name, summary, typeName?, kind? } ... } }
--   dispatch("count")  → 已注册的 formatter 个数（runtime 为 0 时完全不调用）
--   dispatch("types")  → { names = { typeName... }, dynamic = 有没有函数匹配 }（runtime 用来跳过不可能匹配的值）
-------------------------------------------------------------------------------

local function describe(obj, typeName)
    local f = find(obj, typeName)
    if not f then return nil end
    local r = { formatter = f.name }
    if f.isValid and not f.isValid(obj) then
        r.valid = false
        return r
    end
    local tn = f.typeName
    if type(tn) == "function" then tn = tn(obj) end
    if tn ~= nil then r.typeName = tostring(tn) end
    if f.summary then
        local s = f.summary(obj)
        if s ~= nil then r.summary = tostring(s) end
    end
    r.kind = f.kind
    r.hint = f.hint
    r.expandable = f.children ~= nil
    return r
end

local function children(obj, typeName, start, count)
    local f = find(obj, typeName)
    if not f or not f.children then return { total = 0, items = {} } end
    local total = f.count and tonumber(f.count(obj)) or nil
    local items = f.children(obj, start, count) or {}
    return { total = total, items = items }
end

local function dispatch(op, obj, typeName, start, count)
    if op == "describe" then return describe(obj, typeName) end
    if op == "children" then return children(obj, typeName, start or 0, count or 100) end
    if op == "count" then return #formatters end
    if op == "types" then
        local names = {}
        for n in pairs(byType) do names[#names + 1] = n end
        return { names = names, dynamic = #dynamic > 0 }
    end
    error("未知操作 " .. tostring(op))
end

-- runtime 加载完 helper 后从主模块的返回值里取这个函数，放进 registry（不依赖 debug 库）
emmy.__dispatch = dispatch

return emmy
