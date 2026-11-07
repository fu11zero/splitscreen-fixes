-- Client half of joining a saved splitscreen character whose players.db row isn't the slot
-- it gets, and of a new character taking a slot whose row holds a saved one. The reason is in
-- CharacterRows.java. Only multiplayer joins pass through here, and only with the Java part:
-- without it the server's answer can't be applied, so vanilla runs.
--
-- Also handles a join the server refuses. Saved splitscreen characters often share one stored
-- name, and the server refuses a name already in use (ConnectCoopPacket) after the client has
-- put the player in the slot, which leaves a player without data and a stuck gamepad.

require "JoyPad/ISJoyPadListBox"
require "OptionScreens/CoopCharacterCreation"
require "ISUI/Gamepad/JoyPadSetup"
require "OptionScreens/CoopUserName"

local MODULE = "SplitscreenFixes"
local REPLY_TIMEOUT_MS = 3000

local request = nil       -- the question sent to the server: row, slot, done, deadline
local joining = {}        -- slot -> row of a saved character until the join ends
local reopen = nil        -- the name box to show again: box, playerObj

local function showError(text)
    local player = getSpecificPlayer(0)
    if player then
        HaloTextHelper.addBadText(player, getText(text))
    end
end

local function failed(joypadData)
    if joypadData then
        joypadData.focus = nil
    end
    showError("UI_SplitscreenFixes_rowSwapFailed")
end

-- What the server checks, among this client's players: a dead player's slot is about to be
-- replaced, so its name is free. Names on other clients are only known to the server.
local function isNameTaken(username)
    if username == getClientUsername() then
        return true
    end
    for i = 1, getMaxActivePlayers() - 1 do
        local player = getSpecificPlayer(i)
        if player and not player:isDead() and player:getUsername() == username then
            return true
        end
    end
    return false
end

local function requestSwap(row, slot, done)
    if request then return end
    request = { row = row, slot = slot, done = done, deadline = getTimestampMs() + REPLY_TIMEOUT_MS }
    sendClientCommand(MODULE, "queueRowSwap", { row = row, slot = slot })
end

local function onServerCommand(module, command, args)
    if module ~= MODULE or command ~= "rowSwapReply" or not request then return end
    local finished = request
    request = nil
    local ok = args ~= nil and args.ok == true
    if ok then
        SplitscreenFixes_swapProfileRows(finished.row, finished.slot)
    end
    finished.done(ok)
end

local function onPreUIDraw()
    if reopen then
        local again = reopen
        reopen = nil
        again.box:cmdAddSaved(again.playerObj)
    end
    if not request or getTimestampMs() < request.deadline then return end
    local finished = request
    request = nil
    -- A late answer must not leave a swap waiting for the next player of this slot.
    sendClientCommand(MODULE, "cancelRowSwap", { slot = finished.slot })
    finished.done(false)
end

-- The slot ISJoypadListBox:getUserNameCallback picks.
local function firstFreeSlot()
    for i = 0, getMaxActivePlayers() - 1 do
        local player = getSpecificPlayer(i)
        if not player or player:isDead() then
            return i
        end
    end
    return nil
end

local vanillaUserNameCallback = ISJoypadListBox.getUserNameCallback

function ISJoypadListBox:getUserNameCallback(button, playerObj)
    local username = button.internal == "OK" and button.parent.entry:getText()
    if username and isNameTaken(username) then
        showError("UI_SplitscreenFixes_nameTaken")
        -- The box that called us is still closing.
        reopen = { box = self, playerObj = playerObj }
        return
    end
    local slot = firstFreeSlot()
    if not SplitscreenFixes_savedRow or not username or username == "" or not slot or slot == 0
            or SplitscreenFixes_savedRow(playerObj) == slot then
        return vanillaUserNameCallback(self, button, playerObj)
    end
    local row = SplitscreenFixes_savedRow(playerObj)
    requestSwap(row, slot, function(ok)
        if not ok then
            failed(self.joypadData)
            return
        end
        joining[slot] = row
        -- The text box is gone by the time the server answers.
        local entry = { getText = function() return username end }
        vanillaUserNameCallback(self, { internal = "OK", parent = { entry = entry } }, playerObj)
    end)
end

local vanillaClickNext = CoopUserName.clickNext

function CoopUserName:clickNext()
    if isNameTaken(self:getUserName()) then
        showError("UI_SplitscreenFixes_nameTaken")
        return
    end
    vanillaClickNext(self)
end

local vanillaAccept = CoopCharacterCreation.accept

function CoopCharacterCreation:accept()
    local slot = self.playerIndex
    if not isClient() or not SplitscreenFixes_freeRow or not slot or getSpecificPlayer(slot) then
        return vanillaAccept(self)
    end
    local row = SplitscreenFixes_freeRow(slot)
    if row < 0 then
        return vanillaAccept(self)
    end
    requestSwap(row, slot, function(ok)
        if not ok then
            failed(nil)
        elseif CoopCharacterCreation.instance == self then
            vanillaAccept(self)
        end
    end)
end

local function onCreatePlayer(playerNum)
    joining[playerNum] = nil
end

-- Refused before any row moved. Vanilla's handler (JoypadState.onCoopJoinFailed) only clears the
-- focus and then stops on "joypadData.player ~= nil", because the join has set that field. Clear
-- the slot first: the state is then the one a dead player leaves, and A opens the join list again.
local function onCoopJoinFailed(playerNum)
    local row = joining[playerNum]
    if row then
        joining[playerNum] = nil
        SplitscreenFixes_swapProfileRows(row, playerNum)
        sendClientCommand(MODULE, "cancelRowSwap", { slot = playerNum })
    end
    if SplitscreenFixes_clearFailedSlot then
        SplitscreenFixes_clearFailedSlot(playerNum)
        local joypadData = JoypadState.players[playerNum + 1]
        if joypadData then
            joypadData.player = nil
            joypadData.lastfocus = nil
            joypadData.prevfocus = nil
            joypadData.prevprevfocus = nil
        end
        showError("UI_SplitscreenFixes_joinRefused")
    end
    JoypadState.onCoopJoinFailed(playerNum)
end

Events.OnServerCommand.Add(onServerCommand)
-- OnTick may stop while the game is paused for character creation.
Events.OnPreUIDraw.Add(onPreUIDraw)
Events.OnCreatePlayer.Add(onCreatePlayer)
Events.OnCoopJoinFailed.Remove(JoypadState.onCoopJoinFailed)
Events.OnCoopJoinFailed.Add(onCoopJoinFailed)
