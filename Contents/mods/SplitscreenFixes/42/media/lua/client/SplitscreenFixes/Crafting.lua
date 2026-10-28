-- Crafting, building and entity windows per splitscreen player.
--
-- Vanilla ISEntityUI keeps windows per player (ISEntityUI.players[playerNum]), but the local
-- createWindow (used by OpenWindow, OpenBuildWindow and OpenHandcraftWindow) starts with
-- ISEntityUI.CloseWindows(), which closes every player's windows ("we only allow one open at
-- the moment"), so only one player can have a crafting window at a time. It also centres a
-- new window on the whole screen and caps its height by the whole screen
-- (ISHandcraftWindow/ISBuildWindow/ISBaseEntityWindow:initialise). And ISBuildPanel:new
-- stores itself in the global ISBuildWindow.instance, which the build button
-- (ISWidgetBuildControl:startBuild), recipe double click (ISBuildPanel:onDoubleClick) and
-- ISBuildingObject:onActionComplete use, so with two build windows open one player builds
-- through the other player's panel.
--
-- Patch: while one of the Open* functions runs, CloseWindows closes only that player's
-- windows; the new window is moved onto the player's screen; the size cap uses the player's
-- screen; ISBuildWindow.instance is switched to the acting player's panel before vanilla
-- uses it. Option "ownCrafting"; when it is off everything is vanilla.

local function enabled()
    return SplitscreenUI.isSplitscreen() and SplitscreenFixes.isEnabled("ownCrafting")
end

require "SplitscreenFixes/SplitscreenUI"
require "SplitscreenFixes/Options"
require "Entity/ISEntityUI"
require "Entity/ISUI/ISBaseEntityWindow"
require "ISUI/Crafting/ISHandcraftWindow"
require "ISUI/Building/ISBuildWindow"
require "Entity/ISUI/BuildRecipe/ISBuildPanel"
require "Entity/ISUI/BuildRecipe/ISWidgetBuildControl"

-- Player whose window is being opened, nil outside the Open* functions.
local openingPlayer = nil

local vanillaCloseWindows = ISEntityUI.CloseWindows

function ISEntityUI.CloseWindows()
    if openingPlayer == nil or not enabled() then
        return vanillaCloseWindows()
    end
    ISEntityUI.EnsurePlayers()
    local data = ISEntityUI.players[openingPlayer]
    if data and data.windows then
        for _, w in pairs(data.windows) do
            if w.instance and w.instance.close then
                w.instance:close()
            end
        end
    end
end

local function moveWindowsOf(playerNum)
    local data = ISEntityUI.players and ISEntityUI.players[playerNum]
    if not (data and data.windows) then return end
    for _, w in pairs(data.windows) do
        if w.instance and w.instance:isVisible() then
            SplitscreenUI.moveIntoPlayerScreen(w.instance, playerNum)
        end
    end
end

local function wrapOpen(name)
    local vanilla = ISEntityUI[name]
    ISEntityUI[name] = function(player, ...)
        if not (player and enabled()) then
            return vanilla(player, ...)
        end
        local playerNum = player:getPlayerNum()
        local previous = openingPlayer
        -- No pcall: it would hide vanilla's stack trace. If vanilla throws, the next Open*
        -- call overwrites openingPlayer anyway.
        openingPlayer = playerNum
        vanilla(player, ...)
        openingPlayer = previous
        moveWindowsOf(playerNum)
    end
end

wrapOpen("OpenWindow")
wrapOpen("OpenBuildWindow")
wrapOpen("OpenHandcraftWindow")

-- Height cap from the player's screen instead of the whole screen.
local function capHeightByPlayerScreen(class)
    local vanilla = class.initialise
    class.initialise = function(self)
        vanilla(self)
        if not (self.playerNum and enabled()) then return end
        local screenH = getPlayerScreenHeight(self.playerNum)
        local pct = self.maximumHeightPercent or -1
        local cap = pct > 0 and screenH * math.min(pct, 1) or screenH
        if not self.maximumHeight or self.maximumHeight <= 0 or self.maximumHeight > cap then
            self.maximumHeight = cap
        end
    end
end

capHeightByPlayerScreen(ISBaseEntityWindow)
capHeightByPlayerScreen(ISHandcraftWindow)
capHeightByPlayerScreen(ISBuildWindow)

-- Width and height cap from the player's screen on every layout pass. createWindow restores
-- the size the player last had (ISEntityUI.players[n].windows[key].width), capped only by the
-- whole screen, so a window sized before the second player joined spills onto their screen;
-- a window open while a player joins keeps its size too. calculateLayout clamps the preferred
-- size to maximumWidth/Height, so they are narrowed for the call and restored after it.
local function capLayoutByPlayerScreen(class)
    local vanilla = class.calculateLayout
    if not vanilla then return end
    class.calculateLayout = function(self, preferredWidth, preferredHeight)
        if not (self.playerNum and enabled()) then
            return vanilla(self, preferredWidth, preferredHeight)
        end
        local _, _, sw, sh = SplitscreenUI.getPlayerRect(self.playerNum)
        local maxW, maxH = self.maximumWidth, self.maximumHeight
        local capW = math.max(sw, self.minimumWidth or 0)
        local capH = math.max(sh, self.minimumHeight or 0)
        self.maximumWidth = (maxW and maxW > 0) and math.min(maxW, capW) or capW
        self.maximumHeight = (maxH and maxH > 0) and math.min(maxH, capH) or capH
        local result = vanilla(self, preferredWidth, preferredHeight)
        self.maximumWidth, self.maximumHeight = maxW, maxH
        return result
    end
end

capLayoutByPlayerScreen(ISHandcraftWindow)
capLayoutByPlayerScreen(ISBuildWindow)

-- Vanilla prerender recalculates the layout only when dirtyLayout is set (resize), so a window
-- that was open when the screen split keeps its full-screen size. Request one relayout to the
-- player's screen; once per screen size, in case the content can't get that small.
local function shrinkToPlayerScreen(class)
    local vanilla = class.prerender
    class.prerender = function(self)
        if self.playerNum and self.xuiRecalculateLayout and enabled() then
            local _, _, sw, sh = SplitscreenUI.getPlayerRect(self.playerNum)
            local key = sw .. "x" .. sh
            if (self:getWidth() > sw or self:getHeight() > sh) and self.splitscreenFitTried ~= key then
                self.splitscreenFitTried = key
                self:xuiRecalculateLayout(math.min(self:getWidth(), sw), math.min(self:getHeight(), sh), true)
            end
        end
        return vanilla(self)
    end
end

shrinkToPlayerScreen(ISHandcraftWindow)
shrinkToPlayerScreen(ISBuildWindow)

-- One build panel per player instead of the last one created.
ISBuildWindow.instances = ISBuildWindow.instances or {}

local function usePanelOf(playerNum)
    if not enabled() then return end
    local panel = playerNum and ISBuildWindow.instances[playerNum]
    if panel then
        ISBuildWindow.instance = panel
    end
end

local vanillaPanelNew = ISBuildPanel.new
function ISBuildPanel:new(x, y, width, height, player, ...)
    local o = vanillaPanelNew(self, x, y, width, height, player, ...)
    if player then
        ISBuildWindow.instances[player:getPlayerNum()] = o
    end
    return o
end

local vanillaDoubleClick = ISBuildPanel.onDoubleClick
function ISBuildPanel:onDoubleClick(item)
    if enabled() then ISBuildWindow.instance = self end
    return vanillaDoubleClick(self, item)
end

local vanillaStartBuild = ISWidgetBuildControl.startBuild
function ISWidgetBuildControl:startBuild(force)
    if self.player then usePanelOf(self.player:getPlayerNum()) end
    return vanillaStartBuild(self, force)
end

-- ISBuildingObject lives in lua/server, which loads after lua/client.
local function patchBuildingObject()
    if not ISBuildingObject or ISBuildingObject.splitscreenFixesPatched then return end
    ISBuildingObject.splitscreenFixesPatched = true
    local vanillaComplete = ISBuildingObject.onActionComplete
    function ISBuildingObject:onActionComplete(...)
        usePanelOf(self.player)
        return vanillaComplete(self, ...)
    end
end

Events.OnGameStart.Add(patchBuildingObject)
