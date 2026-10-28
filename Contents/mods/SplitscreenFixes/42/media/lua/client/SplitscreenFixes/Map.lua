-- World map per splitscreen player.
--
-- Vanilla (ISUI/Maps/ISWorldMap.lua) has a single map, the global ISWorldMap_instance:
-- - ShowWorldMap reassigns it to whichever player opened it last;
-- - ShowWorldMap and ISWorldMap:render size it to the whole window (getCore():getScreenWidth/
--   Height), so it covers every player's screen;
-- - ShowWorldMap calls setBlockMovement(true) for every local player and ISWorldMap:close
--   unblocks every player, so nobody can move while one player reads the map;
-- - ISWorldMap:render calls getWorld():setDrawWorld(false), a global flag
--   (IsoWorld.renderInternal) that stops drawing the world on every screen;
-- - ToggleWorldMap refuses to open while any splitscreen player is dead, OnPlayerDeath closes
--   the map whoever died, and ISWorldObjectContextMenu.createMenu / ISEntityUI.CanOpenWindowFor
--   are blocked for everybody while the map is open.
--
-- Patch: each player gets their own ISWorldMap (ISWorldMap.instances[playerNum]) sized to
-- their screen; only the owner is blocked; the world keeps drawing in splitscreen (the map is
-- opaque over its own screen anyway); pause-on-map is off in splitscreen; death, context menu
-- and entity window checks look at the player's own map. ISWorldMap_instance stays as player
-- 0's map for vanilla code and mods that check it (the keyboard/mouse player).
-- Single player keeps vanilla behaviour except for using the same code path.
-- Option "ownMap": when it is off every function below is vanilla (one shared map). A map
-- already open when the option changes keeps working until it is closed.

require "SplitscreenFixes/SplitscreenUI"
require "SplitscreenFixes/Options"
require "ISUI/Maps/ISWorldMap"
require "ISUI/ISWorldObjectContextMenu"
require "Entity/ISEntityUI"

ISWorldMap.instances = ISWorldMap.instances or {}

local function enabled()
    return SplitscreenFixes.isEnabled("ownMap")
end

local function getMap(playerNum)
    return ISWorldMap.instances[playerNum]
end

function ISWorldMap.IsVisibleFor(playerNum)
    if not enabled() then
        -- The vanilla map covers every player's screen.
        return ISWorldMap_instance ~= nil and ISWorldMap_instance:isVisible()
    end
    local map = getMap(playerNum)
    return map ~= nil and map:isVisible()
end

local function syncGlobalInstance()
    ISWorldMap_instance = ISWorldMap.instances[0]
end

local function setRectToPlayerScreen(map)
    local x, y, w, h = SplitscreenUI.getPlayerRect(map.playerNum)
    if map.x ~= x then map:setX(x) end
    if map.y ~= y then map:setY(y) end
    if map.width ~= w then map:setWidth(w) end
    if map.height ~= h then map:setHeight(h) end
end

local function assignPlayer(map, playerNum)
    map.character = getSpecificPlayer(playerNum)
    map.playerNum = playerNum
    map.symbolsUI.character = map.character
    map.symbolsUI.playerNum = playerNum
    map.symbolsUI:checkInventory()
end

local vanillaShouldPause = ISWorldMap.shouldPause
function ISWorldMap.shouldPause()
    -- Pausing for one player's map would freeze the others in offline splitscreen.
    if enabled() and SplitscreenUI.isSplitscreen() then return false end
    return vanillaShouldPause()
end

local vanillaShow = ISWorldMap.ShowWorldMap
function ISWorldMap.ShowWorldMap(playerNum, centerX, centerY, zoom)
    if not ISWorldMap.IsAllowed() then
        return
    end
    if not (enabled() and MainScreen.instance and MainScreen.instance.inGame) then
        -- Debug map in the main menu.
        return vanillaShow(playerNum, centerX, centerY, zoom)
    end
    local map = getMap(playerNum)
    if not map then
        -- Same setup as vanilla ShowWorldMap, on the player's screen.
        local x, y, w, h = SplitscreenUI.getPlayerRect(playerNum)
        map = ISWorldMap:new(x, y, w, h)
        map:initialise()
        map:instantiate()
        assignPlayer(map, playerNum)
        map:initDataAndStyle()
        map:setHideUnvisitedAreas(map.hideUnvisitedAreas)
        map:setShowPlayers(map.showPlayers)
        map:setShowRemotePlayers(map.showRemotePlayers)
        map:setShowPlayerNames(map.showPlayerNames)
        map:setShowCellGrid(map.showCellGrid)
        map:setShowTileGrid(map.showTileGrid)
        map:setIsometric(map.isometric)
        map.mapAPI:resetView()
        if map.character then
            map.mapAPI:centerOn(map.character:getX(), map.character:getY())
            map.mapAPI:setZoom(zoom and zoom or 18.0)
        end
        map:restoreSettings()
        ISWorldMap.instances[playerNum] = map
        syncGlobalInstance()
    else
        assignPlayer(map, playerNum)
        setRectToPlayerScreen(map)
    end
    if centerX and centerY then
        map.mapAPI:centerOn(centerX, centerY)
        map.mapAPI:setZoom(zoom and zoom or 18.0)
    end
    map:setVisible(true)
    map:addToUIManager()
    map.getJoypadFocus = true
    if map.character then
        map.character:setBlockMovement(true)
    end
    if ISWorldMap.shouldPause() then
        setGameSpeed(0)
    end
end

local vanillaHide = ISWorldMap.HideWorldMap
function ISWorldMap.HideWorldMap(playerNum)
    if not enabled() then
        return vanillaHide(playerNum)
    end
    if not ISWorldMap.IsAllowed() then
        return
    end
    local map = getMap(playerNum)
    if map then
        map:close()
    elseif ISWorldMap_instance then
        -- Main menu debug map created by vanilla.
        ISWorldMap_instance:close()
    end
end

local vanillaToggle = ISWorldMap.ToggleWorldMap
function ISWorldMap.ToggleWorldMap(playerNum)
    if not enabled() then
        return vanillaToggle(playerNum)
    end
    if not ISWorldMap.IsAllowed() then
        return
    end
    if not (MainScreen.instance and MainScreen.instance.inGame) then
        if ISWorldMap_instance and ISWorldMap_instance:isVisible() then
            ISWorldMap_instance:close()
        else
            vanillaShow(playerNum)
        end
        return
    end
    -- Closing is allowed even when it is too dark to read.
    if ISWorldMap.IsVisibleFor(playerNum) then
        ISWorldMap.HideWorldMap(playerNum)
        return
    end
    local playerObj = getSpecificPlayer(playerNum)
    if not playerObj then
        return
    end
    local tooDarkToRead = playerObj:tooDarkToRead()
    if playerObj:getVehicle() then
        if playerObj:getVehicle():hasLiveBattery() then tooDarkToRead = false end
        if playerObj:getTorchStrength() > 0 then tooDarkToRead = false end
    end
    if getCore():getDebug() then tooDarkToRead = false end
    if ISWorldMap.NeedsLight() and tooDarkToRead and not isAdmin() then
        -- Vanilla kludge for vehicle interior lights.
        if not (playerObj:getVehicle() and playerObj:getVehicle():getBatteryCharge() > 0) then
            HaloTextHelper.addBadText(playerObj, getText("ContextMenu_TooDark"))
            return
        end
    end
    -- Vanilla forbids the map while any splitscreen player is dead (the full-screen map would
    -- hide their death screen); only the dead player's own map matters now.
    if ISPostDeathUI and ISPostDeathUI.instance and ISPostDeathUI.instance[playerNum] then
        return
    end
    playerObj:setJoypadIgnoreAimUntilCentered(true)
    ISTimedActionQueue.clear(playerObj)
    ISTimedActionQueue.add(ISReadWorldMap:new(playerObj))
end

local vanillaRender = ISWorldMap.render
function ISWorldMap:render()
    if not (enabled() and SplitscreenUI.isSplitscreen() and ISWorldMap.instances[self.playerNum] == self) then
        return vanillaRender(self)
    end
    -- Vanilla sizes the map to the whole window and calls setDrawWorld(false). Run it with the
    -- resize disabled (the rest of render draws the gamepad cross and prompts by self.width/height)
    -- and turn world drawing back on afterwards.
    setRectToPlayerScreen(self)
    local noop = function() end
    self.setWidth, self.setHeight = noop, noop
    local ok, err = pcall(vanillaRender, self)
    self.setWidth, self.setHeight = nil, nil
    getWorld():setDrawWorld(true)
    if not ok then error(err, 0) end
end

-- Flier and stash map icons (ISWorldMap:renderPrintMedia/renderStashMaps, from prerender) are
-- plain textures, not clipped by the map renderer; with a full-window map the window edge hid
-- them. Clip them to the player's map so they don't spill onto the neighbouring screens.
local function clipToMap(vanilla)
    return function(self, ...)
        if not (enabled() and SplitscreenUI.isSplitscreen() and ISWorldMap.instances[self.playerNum] == self) then
            return vanilla(self, ...)
        end
        self:setStencilRect(0, 0, self.width, self.height)
        local ok, err = pcall(vanilla, self, ...)
        self:clearStencilRect()
        if not ok then error(err, 0) end
    end
end

ISWorldMap.renderPrintMedia = clipToMap(ISWorldMap.renderPrintMedia)
ISWorldMap.renderStashMaps = clipToMap(ISWorldMap.renderStashMaps)

local vanillaClose = ISWorldMap.close
function ISWorldMap:close()
    vanillaClose(self)
    if not enabled() then return end
    -- Vanilla unblocked every player; keep blocked those still reading their own map.
    for playerNum, map in pairs(ISWorldMap.instances) do
        if map ~= self and map:isVisible() and map.character then
            map.character:setBlockMovement(true)
        end
    end
    -- Vanilla turns world drawing back on only if player 0 exists.
    getWorld():setDrawWorld(true)
end

-- Close only the map of the player who died.
local vanillaOnDeath = ISWorldMap.OnPlayerDeath
Events.OnPlayerDeath.Remove(vanillaOnDeath)
function ISWorldMap.OnPlayerDeath(playerObj)
    if not enabled() then
        return vanillaOnDeath(playerObj)
    end
    local playerNum = playerObj and playerObj:isLocalPlayer() and playerObj:getPlayerNum()
    if playerNum and ISWorldMap.IsVisibleFor(playerNum) then
        ISWorldMap.HideWorldMap(playerNum)
    end
end
Events.OnPlayerDeath.Add(ISWorldMap.OnPlayerDeath)

-- The world context menu and entity windows were blocked for everybody while the map was open.
local function withOwnMapAsGlobal(playerNum, fn, ...)
    if not enabled() then return fn(...) end
    local saved = ISWorldMap_instance
    if playerNum then
        ISWorldMap_instance = getMap(playerNum) -- nil if this player never opened a map
    end
    local a, b = fn(...)
    ISWorldMap_instance = saved
    return a, b
end

local vanillaCreateMenu = ISWorldObjectContextMenu.createMenu
ISWorldObjectContextMenu.createMenu = function(player, ...)
    return withOwnMapAsGlobal(player, vanillaCreateMenu, player, ...)
end

local vanillaCanOpen = ISEntityUI.CanOpenWindowFor
function ISEntityUI.CanOpenWindowFor(player, ...)
    local playerNum = player and player.getPlayerNum and player:getPlayerNum()
    return withOwnMapAsGlobal(playerNum, vanillaCanOpen, player, ...)
end
