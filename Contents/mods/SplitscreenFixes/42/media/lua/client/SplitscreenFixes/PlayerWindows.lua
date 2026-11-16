-- Any player's window opens on that player's screen.
--
-- Vanilla places many per-player windows against the whole game window or at fixed
-- coordinates (~140 client files use getCore():getScreenWidth/Height). Example:
-- ISInventoryPaneContextMenu.onInspectClothingUI opens the garment (inspect clothing /
-- sewing) window with ISGarmentUI:new(-1, 500, ...): centred on the player's screen
-- horizontally, but always at y = 500, so for the lower players it crosses onto the
-- screen above, and its gamepad context menu follows it.
--
-- Patch (splitscreen only): when a window (ISCollapsableWindow, or a draggable panel) whose
-- player is known (playerNum / player / character / chr / playerObj fields) is added to the
-- UIManager and is not fully inside that player's screen, centre it there
-- (SplitscreenUI.moveIntoPlayerScreen). Checked on addToUIManager and before the next two
-- frames' UI, after the window's first layout. A window the player moves later is left
-- alone. Context menus, tooltips, the world map and dialogs have their own fixes. Every
-- moved window is logged once per type to find the remaining vanilla offenders.
-- Option "playerWindows".

require "SplitscreenFixes/SplitscreenUI"
require "SplitscreenFixes/Options"
require "ISUI/ISUIElement"
require "ISUI/ISCollapsableWindow"

local SKIP_TYPES = {
    ISToolTip = true,
    ISToolTipInv = true,
    ISContextMenu = true,
    ISWorldMap = true,
}

local OWNER_FIELDS = { "playerNum", "player", "playerIndex", "character", "chr", "playerObj" }

local function isA(obj, class)
    local mt = getmetatable(obj)
    while mt do
        if mt == class then return true end
        mt = getmetatable(mt)
    end
    return false
end

local function ownerOf(element)
    for _, field in ipairs(OWNER_FIELDS) do
        local v = element[field]
        if type(v) == "number" then
            if v >= 0 and v < 4 and v == math.floor(v) then return v end
        elseif v ~= nil and type(v) == "userdata" and instanceof(v, "IsoPlayer") then
            if v:isLocalPlayer() then return v:getPlayerNum() end
            return nil
        end
    end
    return nil
end

local logged = {}

local function keepOnPlayerScreen(element)
    if not (SplitscreenUI.isSplitscreen() and SplitscreenFixes.isEnabled("playerWindows")) then return end
    if element.javaObject == nil or element.parent ~= nil or not element:isVisible() then return end
    if SKIP_TYPES[element.Type] then return end
    if not (isA(element, ISCollapsableWindow) or element.moveWithMouse) then return end
    local playerNum = ownerOf(element)
    if playerNum == nil or not getSpecificPlayer(playerNum) then return end

    local x, y, w, h = element:getX(), element:getY(), element:getWidth(), element:getHeight()
    local coreW, coreH = getCore():getScreenWidth(), getCore():getScreenHeight()
    -- Full-window overlays, and elements parked off the window on purpose.
    if w >= coreW or h >= coreH then return end
    if x >= coreW or y >= coreH or x + w <= 0 or y + h <= 0 then return end

    SplitscreenUI.moveIntoPlayerScreen(element, playerNum)
    local nx, ny = element:getX(), element:getY()
    if (nx ~= x or ny ~= y) and not logged[element.Type or "?"] then
        logged[element.Type or "?"] = true
        print(string.format("[SplitscreenFixes] window %s of player %d moved from %d,%d to %d,%d (%dx%d)",
            tostring(element.Type), playerNum, x, y, nx, ny, w, h))
    end
end

-- Windows added recently, checked again on the next two frames: a window added from an input
-- handler gets its first prerender/layout only after the next OnPreUIDraw.
local RECHECK_FRAMES = 2
local MAX_PENDING = 64
local pending = {}

local function check(element)
    local ok, err = pcall(keepOnPlayerScreen, element)
    if not ok then print("[SplitscreenFixes] playerWindows: " .. tostring(err)) end
    return ok
end

local vanillaAddToUIManager = ISUIElement.addToUIManager
function ISUIElement:addToUIManager()
    vanillaAddToUIManager(self)
    if not SplitscreenUI.isSplitscreen() then return end
    -- No OnPreUIDraw while the pause menu is open; don't let the list grow then.
    if check(self) and #pending < MAX_PENDING then
        pending[#pending + 1] = { element = self, frames = RECHECK_FRAMES }
    end
end

local function onPreUIDraw()
    if #pending == 0 then return end
    local list = pending
    pending = {}
    for _, entry in ipairs(list) do
        if check(entry.element) then
            entry.frames = entry.frames - 1
            if entry.frames > 0 then pending[#pending + 1] = entry end
        end
    end
end

Events.OnPreUIDraw.Add(onPreUIDraw)
