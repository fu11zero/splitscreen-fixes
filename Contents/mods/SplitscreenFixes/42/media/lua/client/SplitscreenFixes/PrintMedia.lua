-- Flier / brochure window (PZAPI.UI.PrintMedia) per splitscreen player.
--
-- Vanilla (PZAPI/ui/organisms/PrintMedia.lua):
-- - one module-local flag, worldMapFlier, set in printMedia:init when ISWorldMap.instance
--   exists; renderUpdate removes the window whenever ISWorldMap.instance exists and the flag is
--   false. The flag is shared by every window and ISWorldMap.instance is whichever map
--   prerendered first, so a flier one player opens from the inventory closes at once while
--   another player has the map open, and each new flier overwrites the flag of the others;
-- - the "show on map" buttons (PrintMedia init) use getPlayer() ("FIXME: splitscreen"), so the
--   map opens for, and the locations are sent to the server as, whichever player was updated
--   last;
-- - updateSize caps the height at 80% of the whole window, not the player's screen;
-- - ISWorldMap:onMouseUpPrintMedia places the window at 20,20 (player 0's screen), and PZAPI
--   windows have nothing like stayOnSplitScreen to keep them on their player's screen.
--
-- Patch: ISReadABook:displayPrintMedia and ISWorldMap:onMouseUpPrintMedia tell the new window
-- its player (win.splitscreenPlayer). Such a window decides by itself: it is removed when its
-- player's own map is open and the flier wasn't opened with that map open; vanilla's check is
-- hidden by clearing ISWorldMap.instance around vanilla renderUpdate. The map buttons run with
-- getPlayer() returning the window's player. In splitscreen the size cap uses the player's
-- screen and the window is kept inside it. Windows without a known player (debug menu) stay
-- vanilla. Everything is wrapped, not replaced (GamepadTweaks wraps the same functions).
-- Option "flierWindow" turns off the closing, size and position changes; the map buttons'
-- player is a bug fix and always on.

require "SplitscreenFixes/SplitscreenUI"
require "SplitscreenFixes/Options"
require "PZAPI/ui/organisms/PrintMedia"
require "ISUI/Maps/ISWorldMap"
require "TimedActions/ISReadABook"

local UI = PZAPI.UI

local function enabled()
    return SplitscreenFixes.isEnabled("flierWindow")
end

-- Player whose flier is being created, nil outside the wrapped openers.
local openingPlayer = nil

local function ownMapVisible(playerNum)
    if ISWorldMap.IsVisibleFor then
        return ISWorldMap.IsVisibleFor(playerNum)
    end
    return ISWorldMap.instance ~= nil
end

local function withOpeningPlayer(playerNum, fn, ...)
    local previous = openingPlayer
    openingPlayer = playerNum
    -- No pcall: it would hide vanilla's stack trace; the next opener overwrites the value.
    fn(...)
    openingPlayer = previous
end

local vanillaDisplay = ISReadABook.displayPrintMedia
function ISReadABook:displayPrintMedia(...)
    withOpeningPlayer(self.playerNum, vanillaDisplay, self, ...)
end

local vanillaMapFlier = ISWorldMap.onMouseUpPrintMedia
function ISWorldMap:onMouseUpPrintMedia(...)
    withOpeningPlayer(self.playerNum, vanillaMapFlier, self, ...)
end

-- Runs fn with getPlayer() returning the given player. The global is restored even if fn
-- throws: a stuck override would break every other getPlayer() caller.
local function asPlayer(playerNum, fn, ...)
    local playerObj = getSpecificPlayer(playerNum)
    if not playerObj then return fn(...) end
    local vanillaGetPlayer = getPlayer
    getPlayer = function() return playerObj end
    local ok, err = pcall(fn, ...)
    getPlayer = vanillaGetPlayer
    if not ok then error(err, 0) end
end

local vanillaInit = UI.PrintMedia.init
UI.PrintMedia.init = function(self, ...)
    -- Window init runs before its children's (printMedia:init -> updateSize).
    if openingPlayer ~= nil and self.splitscreenPlayer == nil then
        self.splitscreenPlayer = openingPlayer
        self.splitscreenFromMap = ownMapVisible(openingPlayer)
    end
    vanillaInit(self, ...)
    local playerNum = self.splitscreenPlayer
    if playerNum == nil then return end
    for i = 1, 5 do
        local button = self.children.bottomButtons.children["revealOnMap" .. i]
        local vanillaClick = button.onLeftClick
        button.onLeftClick = function(btn, ...)
            asPlayer(playerNum, vanillaClick, btn, ...)
        end
    end
end

local vanillaUpdateSize = UI.PrintMedia.updateSize
UI.PrintMedia.updateSize = function(self, ...)
    local playerNum = self.splitscreenPlayer
    if playerNum == nil or not (SplitscreenUI.isSplitscreen() and enabled()) then
        return vanillaUpdateSize(self, ...)
    end
    -- Vanilla with the player's screen instead of the whole window.
    local printMedia = self.children.body.children.printMedia
    local w, h = printMedia.width, printMedia.height
    local chromeH = self.children.bar.height + self.children.bottomButtons.height + self.children.bottomBar.height
    local maxH = getPlayerScreenHeight(playerNum) * 0.8
    local maxW = getPlayerScreenWidth(playerNum) * 0.9
    local scale = math.min(1, maxH / h, maxW / w)
    if scale < 1 then
        w = w * scale
        h = h * scale
        printMedia:setScaleX(scale)
        printMedia:setScaleY(scale)
    end
    self:setWidth(w)
    self:setHeight(h + chromeH)
    self.children.body.children.textNode:setX(10)
    self.children.body.children.textNode:setY(10)
    self.children.body.children.textNode:updateSize(w - 20, h - 20)
end

local function keepOnPlayerScreen(win, playerNum)
    local sx, sy, sw, sh = SplitscreenUI.getPlayerRect(playerNum)
    local x, y = win.javaObj:getX(), win.javaObj:getY()
    local w, h = win.javaObj:getWidth(), win.javaObj:getHeight()
    local nx = math.max(sx, math.min(x, sx + sw - w))
    local ny = math.max(sy, math.min(y, sy + sh - h))
    if nx ~= x then win:setX(nx) end
    if ny ~= y then win:setY(ny) end
end

local vanillaRenderUpdate = UI.PrintMedia.renderUpdate
UI.PrintMedia.renderUpdate = function(self, ...)
    local playerNum = self.splitscreenPlayer
    if playerNum == nil or not enabled() then
        return vanillaRenderUpdate(self, ...)
    end
    -- Hide the shared map instance from vanilla's worldMapFlier check.
    local savedInstance = ISWorldMap.instance
    ISWorldMap.instance = nil
    local ok, err = pcall(vanillaRenderUpdate, self, ...)
    ISWorldMap.instance = savedInstance
    if not ok then error(err, 0) end
    if ownMapVisible(playerNum) then
        if not self.splitscreenFromMap then
            UIManager.RemoveElement(self.javaObj)
            return
        end
    elseif self.splitscreenFromMap then
        -- The map it came with was closed; the next map opening should close it, as in vanilla.
        self.splitscreenFromMap = false
    end
    if SplitscreenUI.isSplitscreen() then
        keepOnPlayerScreen(self, playerNum)
    end
end
