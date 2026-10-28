-- Debug/admin cheats (Invisible, God mode, No clip, ...) for every splitscreen player.
--
-- ISAdminPowerUI binds to getPlayer() when it opens and "Save" applies the ticks only to that
-- player; ISAdminPowerUI.onGameStart restores CheatPanel.ini only for player 0, so a player who
-- joins later starts without cheats. In splitscreen this leaves e.g. player 0 invisible while
-- the others are chased by zombies. Patch: after vanilla "Save", apply the same ticks to every
-- other living local player; when a splitscreen player joins, copy player 0's cheats to them.
-- One cheat set for all local players, which is what testing needs.

require "ISUI/AdminPanel/ISAdminPowerUI"

local function canUseCheats(player)
    if isDebugEnabled() then return true end
    local role = player:getRole()
    return role ~= nil and role:hasAdminPower()
end

local function localPlayers()
    local list = {}
    for i = 0, getNumActivePlayers() - 1 do
        local p = getSpecificPlayer(i)
        if p and not p:isDead() then table.insert(list, p) end
    end
    return list
end

-- Options are shared tables whose setValue/getValue act on option.player.
local function applyOptions(options, tickBox, player)
    for i = 1, #tickBox.options do
        local option = options[i]
        if option then
            option.player = player
            option:setValue(tickBox:isSelected(i))
        end
    end
end

local vanillaOnClick = ISAdminPowerUI.onClick

function ISAdminPowerUI:onClick(button)
    vanillaOnClick(self, button)
    if button.internal ~= "SAVE" or not self.player or self.player:isDead() then return end
    if not canUseCheats(self.player) then return end
    for _, p in ipairs(localPlayers()) do
        if p ~= self.player and canUseCheats(p) then
            applyOptions(self.optionsLeft, self.tickBoxLeft, p)
            applyOptions(self.optionsRight, self.tickBoxRight, p)
            sendPlayerExtraInfo(p)
            print("[SplitscreenFixes] cheats applied to player " .. p:getPlayerNum())
        end
    end
    for _, option in ipairs(ISAdminPowerUI.OptionList) do
        option.player = self.player
    end
end

-- A joining player is copied once they are ready: in MP the server must know their online id
-- before sendPlayerExtraInfo can reach it.
local pending = {}

local function copyCheats(from, to)
    for _, option in ipairs(ISAdminPowerUI.OptionList) do
        option.player = from
        local value = option:getValue()
        option.player = to
        option:setValue(value)
    end
    for _, option in ipairs(ISAdminPowerUI.OptionList) do
        option.player = from
    end
    sendPlayerExtraInfo(to)
end

local function onCreatePlayer(playerNum, player)
    if playerNum > 0 then pending[playerNum] = player end
end

local function onTick()
    if next(pending) == nil then return end
    local source = getSpecificPlayer(0)
    for playerNum, player in pairs(pending) do
        if getSpecificPlayer(playerNum) ~= player or player:isDead() then
            pending[playerNum] = nil
        elseif not isClient() or player:getOnlineID() ~= -1 then
            pending[playerNum] = nil
            if source and source ~= player and not source:isDead() and canUseCheats(source) and canUseCheats(player) then
                copyCheats(source, player)
                print("[SplitscreenFixes] cheats of player 0 copied to player " .. playerNum)
            end
        end
    end
end

Events.OnCreatePlayer.Add(onCreatePlayer)
Events.OnTick.Add(onTick)
