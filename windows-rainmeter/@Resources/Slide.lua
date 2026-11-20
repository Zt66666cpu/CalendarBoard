-- Keep the existing sidebar visible at the right edge until hovered.
local target

local function number(name, fallback)
    return tonumber(SKIN:GetVariable(name)) or fallback
end

local function screenRight()
    return number('SCREENAREAX', 0) + number('SCREENAREAWIDTH', 1875)
end

local function moveTo(x)
    local y = number('SCREENAREAY', 0)
    SKIN:Bang('!Move', tostring(math.floor(x + 0.5)), tostring(y))
end

function Initialize()
    target = nil
end

function Reset()
    target = screenRight() - number('DockVisibleWidth', 476)
    moveTo(target)
end

function Expand()
    target = screenRight() - number('DockWidth', 1875)
end

function Collapse()
    target = screenRight() - number('DockVisibleWidth', 476)
end

function Step()
    if not target then return end
    local current = number('CURRENTCONFIGX', target)
    local distance = target - current
    if math.abs(distance) <= 6 then
        moveTo(target)
    else
        moveTo(current + distance * 0.36)
    end
end

