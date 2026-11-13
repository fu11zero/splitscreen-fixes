-- The mouse player's inventory on their own screen (also after the window spans the monitors,
-- MultiMonitor.java).

require "SplitscreenFixes/SplitscreenUI"

-- Vanilla places the mouse player's inventory and loot windows once, when they are created,
-- against the whole window if that player was alone then (ISPlayerDataObject:createInventoryInterface).
-- ISPlayerDataObject:onResolutionChange moves only gamepad players' windows, so after a
-- second player joins (or the window spans the monitors) the loot window sits on another
-- player's screen. Put a window back when its centre leaves player 0's screen, as
-- revertToKeyboardAndMouse would place it.
local function centreInside(window, x, y, w, h)
    local cx = window:getX() + window:getWidth() / 2
    local cy = window:getY() + window:getHeight() / 2
    return cx >= x and cy >= y and cx < x + w and cy < y + h
end

local function keepMouseInventoryOnScreen()
    if getNumActivePlayers() < 2 or JoypadState.players[1] or not getSpecificPlayer(0) then
        return
    end
    local data = getPlayerData and getPlayerData(0)
    if not data or not data.playerInventory or not data.lootInventory then
        return
    end
    local x, y, w, h = SplitscreenUI.getPlayerRect(0)
    if not centreInside(data.playerInventory, x, y, w, h) or not centreInside(data.lootInventory, x, y, w, h) then
        data:revertToKeyboardAndMouse()
    end
end

local frame = 0
local function onPreUIDraw()
    frame = frame + 1
    if frame >= 30 then
        frame = 0
        keepMouseInventoryOnScreen()
    end
end

Events.OnPreUIDraw.Add(onPreUIDraw)
