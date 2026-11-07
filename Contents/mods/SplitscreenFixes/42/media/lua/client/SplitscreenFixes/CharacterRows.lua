-- Client half of joining a saved splitscreen character whose players.db row isn't the slot
-- it gets, and of a new character taking a slot whose row holds a saved one. The reason is in
-- CharacterRows.java. Only multiplayer joins pass through here, and only with the Java part:
-- without it the server's answer can't be applied, so vanilla runs.

require "JoyPad/ISJoyPadListBox"
require "OptionScreens/CoopCharacterCreation"

local MODULE = "SplitscreenFixes"
local REPLY_TIMEOUT_MS = 3000

local request = nil       -- the question sent to the server: row, slot, done, deadline
local joining = {}        -- slot -> row of a saved character until the join ends

local function failed(joypadData)
    if joypadData then
        joypadData.focus = nil
    end
    local player = getSpecificPlayer(0)
    if player then
        HaloTextHelper.addBadText(player, getText("UI_SplitscreenFixes_rowSwapFailed"))
    end
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

-- The server refused the join (the name is taken, the server is full) before any row moved.
local function onCoopJoinFailed(playerNum)
    local row = joining[playerNum]
    if not row then return end
    joining[playerNum] = nil
    SplitscreenFixes_swapProfileRows(row, playerNum)
    sendClientCommand(MODULE, "cancelRowSwap", { slot = playerNum })
end

Events.OnServerCommand.Add(onServerCommand)
-- OnTick may stop while the game is paused for character creation.
Events.OnPreUIDraw.Add(onPreUIDraw)
Events.OnCreatePlayer.Add(onCreatePlayer)
Events.OnCoopJoinFailed.Add(onCoopJoinFailed)
