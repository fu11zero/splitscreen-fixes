-- Confirmation dialogs and info popups on their player's screen, answerable with a gamepad.
--
-- Vanilla ISModalDialog/ISModalRichText/ISTextBox:new centre on the player's screen only when
-- called with x == 0 / y == 0, a player number and a gamepad player. Many callers instead pass
-- coordinates of the whole window (getCore():getScreenWidth() / 2 ...), or coordinates local to
-- a window that is no longer at 0,0 in splitscreen (ISWorldMap:onForget positions "forget the
-- whole map" by the map's width, which lands on player 0's screen). Several dialogs aimed at a
-- player don't pass the player at all, so no gamepad focus is given and only the mouse can
-- answer them:
-- - ISHealthPanel.ReceiveMedicalCheckRequest (another player asks to inspect you);
-- - ISFactionUI.ReceiveFactionInvite, ISSafehouseUI.ReceiveSafehouseInvite;
-- - ISDesignationZonePanel:onClick (remove zone), AnimalContextMenu.onKillAnimal;
-- - ISCollapsableWindow:onInfo, ISInventoryPage:onInfo (the "i" help popups).
--
-- Patch: in splitscreen a dialog whose player is known and which is not fully inside that
-- player's screen is centred there. The entry points above run with their player as the
-- dialog's player (o.player, so ISModalDialog:destroy returns the focus) and, for a gamepad
-- player, give the new Yes/No dialog the focus. Option "dialogs" turns off only the centring;
-- the player and the gamepad focus are a bug fix and always on.

require "SplitscreenFixes/SplitscreenUI"
require "SplitscreenFixes/Options"
require "ISUI/ISModalDialog"
require "ISUI/ISModalRichText"
require "ISUI/ISTextBox"
require "ISUI/ISCollapsableWindow"
require "ISUI/ISInventoryPage"
require "XpSystem/ISUI/ISHealthPanel"
require "ISUI/UserPanel/ISFactionUI"
require "ISUI/UserPanel/ISSafehouseUI"
require "ISUI/ISDesignationZonePanel"
require "ISUI/Animal/ISAnimalContextMenu"

-- Player of the entry point being run, and the dialogs it created.
local contextPlayer = nil
local created = nil

-- Uses the element's fields: ISUIElement:getX/getWidth would instantiate it before initialise.
local function centreIfOutside(dialog, playerNum)
    if not (SplitscreenUI.isSplitscreen() and SplitscreenFixes.isEnabled("dialogs")) then return end
    local sx, sy, sw, sh = SplitscreenUI.getPlayerRect(playerNum)
    local x, y, w, h = dialog.x, dialog.y, dialog.width, dialog.height
    if x < sx or y < sy or x + w > sx + sw or y + h > sy + sh then
        dialog:setX(sx + math.max(0, (sw - w) / 2))
        dialog:setY(sy + math.max(0, (sh - h) / 2))
    end
end

local function wrapNew(class)
    local vanilla = class.new
    class.new = function(self, x, y, width, height, text, arg6, target, onclick, player, ...)
        if player == nil and contextPlayer ~= nil then
            player = contextPlayer
        end
        local o = vanilla(self, x, y, width, height, text, arg6, target, onclick, player, ...)
        if created then table.insert(created, o) end
        if type(player) == "number" then
            centreIfOutside(o, player)
        end
        return o
    end
end

wrapNew(ISModalDialog)
wrapNew(ISModalRichText)
wrapNew(ISTextBox)

-- Runs fn with dialogs created inside it belonging to playerNum. Afterwards (the callers move
-- some popups with setY after new) the dialogs are put back on the player's screen, and a
-- gamepad player gets the focus on the first Yes/No/OK dialog unless vanilla already gave it.
local function asDialogPlayer(playerNum, giveFocus, fn, ...)
    if playerNum == nil then return fn(...) end
    local savedPlayer, savedCreated = contextPlayer, created
    contextPlayer, created = playerNum, {}
    local list = created
    -- No pcall: it would hide vanilla's stack trace; the next entry point resets the context.
    local a, b = fn(...)
    contextPlayer, created = savedPlayer, savedCreated
    for _, dialog in ipairs(list) do
        centreIfOutside(dialog, playerNum)
    end
    local joypadData = JoypadState.players[playerNum + 1]
    if giveFocus and joypadData then
        for _, dialog in ipairs(list) do
            if (dialog.yes or dialog.ok) and dialog:isVisible() then
                if joypadData.focus ~= dialog then
                    dialog.prevFocus = joypadData.focus
                    setJoypadFocus(playerNum, dialog)
                end
                break
            end
        end
    end
    return a, b
end

local function localPlayerNum(playerObj)
    if playerObj and playerObj.isLocalPlayer and playerObj:isLocalPlayer() then
        return playerObj:getPlayerNum()
    end
    return nil
end

local function localPlayerNumByName(username)
    for i = 0, getNumActivePlayers() - 1 do
        local playerObj = getSpecificPlayer(i)
        if playerObj and playerObj:getUsername() == username then
            return i
        end
    end
    return nil
end

local function ownPlayerNum(ui)
    if type(ui.playerNum) == "number" then return ui.playerNum end
    if type(ui.player) == "number" then return ui.player end
    return nil
end

-- The event handlers were registered by reference, so they are re-registered.
local function replaceHandler(event, owner, name, wrapper)
    local vanilla = owner[name]
    event.Remove(vanilla)
    owner[name] = wrapper(vanilla)
    event.Add(owner[name])
end

replaceHandler(Events.RequestMedicalCheck, ISHealthPanel, "ReceiveMedicalCheckRequest", function(vanilla)
    return function(target, requester, ...)
        return asDialogPlayer(localPlayerNum(target), true, vanilla, target, requester, ...)
    end
end)

replaceHandler(Events.ReceiveFactionInvite, ISFactionUI, "ReceiveFactionInvite", function(vanilla)
    return function(faction, host, username, ...)
        return asDialogPlayer(localPlayerNumByName(username), true, vanilla, faction, host, username, ...)
    end
end)

replaceHandler(Events.ReceiveSafehouseInvite, ISSafehouseUI, "ReceiveSafehouseInvite", function(vanilla)
    return function(safehouse, host, username, ...)
        return asDialogPlayer(localPlayerNumByName(username), true, vanilla, safehouse, host, username, ...)
    end
end)

local vanillaZoneClick = ISDesignationZonePanel.onClick
function ISDesignationZonePanel:onClick(...)
    return asDialogPlayer(ownPlayerNum(self), true, vanillaZoneClick, self, ...)
end

local vanillaKillAnimal = AnimalContextMenu.onKillAnimal
AnimalContextMenu.onKillAnimal = function(animal, playerObj, ...)
    return asDialogPlayer(localPlayerNum(playerObj), true, vanillaKillAnimal, animal, playerObj, ...)
end

-- Help popups have no buttons; only the position matters.
local vanillaWindowInfo = ISCollapsableWindow.onInfo
function ISCollapsableWindow:onInfo(...)
    return asDialogPlayer(ownPlayerNum(self), false, vanillaWindowInfo, self, ...)
end

local vanillaInventoryInfo = ISInventoryPage.onInfo
function ISInventoryPage:onInfo(...)
    return asDialogPlayer(ownPlayerNum(self), false, vanillaInventoryInfo, self, ...)
end
