-------------------------------------------------------------------------------
-- emmy_main.lua：helper 入口（loadHelpers 的 main）。先建好 emmy 接口，再加载各类型的显示脚本；
-- 一个脚本出错不影响其他脚本，错误放进 emmy.loadErrors，runtime 记到输出里。
-- 返回 emmy 表：runtime 从 emmy.__dispatch 取调度函数。
-------------------------------------------------------------------------------

local emmy = require("emmy")
emmy.loadErrors = {}

for _, name in ipairs({ "emmy_ue" }) do
    local ok, err = pcall(require, name)
    if not ok then emmy.loadErrors[#emmy.loadErrors + 1] = name .. ": " .. tostring(err) end
end

return emmy
