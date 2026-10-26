-- Options in Settings > Mods (PZAPI.ModOptions, saved to ~/Zomboid/Lua/ModOptions.ini).
--
-- Only the features that change how vanilla lays out the splitscreen UI can be turned off:
-- vanilla's shared map, centred windows and single clock may be what a player on a small
-- monitor prefers. Bug fixes (item transfers, cars, aiming, search mode, moodles, gamepad
-- focus in dialogs, "show on map" player) always run. Every option is checked when the
-- feature acts, so a change applies to windows opened after it, without reloading the save.

require "PZAPI/ModOptions"

SplitscreenFixes = SplitscreenFixes or {}

local MOD_ID = "SplitscreenFixes"
local FEATURES = {
    "ownMap",
    "flierWindow",
    "ownCrafting",
    "mechanicsWindow",
    "contextMenus",
    "dialogs",
    "ownClocks",
}

local options = PZAPI.ModOptions:create(MOD_ID, "UI_SplitscreenFixes_Options")
options:addDescription("UI_SplitscreenFixes_Options_desc")
for _, id in ipairs(FEATURES) do
    options:addTickBox(id, "UI_SplitscreenFixes_" .. id, true, "UI_SplitscreenFixes_" .. id .. "_tooltip")
end

function SplitscreenFixes.isEnabled(id)
    local option = options:getOption(id)
    return option == nil or option:getValue() ~= false
end

-- The Java side (per-player clocks) keeps its own copy; the global comes from Options.java.
local function pushToJava()
    if not SplitscreenFixes_setOption then return end
    for _, id in ipairs(FEATURES) do
        SplitscreenFixes_setOption(id, SplitscreenFixes.isEnabled(id))
    end
end

-- Called by MainOptions:apply after the tick boxes are stored.
function options:apply()
    pushToJava()
end

-- Vanilla loads the file only when MainOptions builds its Mods page, which may happen before
-- this file registers the options.
PZAPI.ModOptions:load()
pushToJava()
Events.OnGameStart.Add(pushToJava)
