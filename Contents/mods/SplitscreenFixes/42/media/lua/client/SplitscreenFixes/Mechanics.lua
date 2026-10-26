-- Vehicle mechanics window on its own player's screen.
--
-- Vanilla already gives every player their own ISVehicleMechanics (ISPlayerDataObject), but
-- ISVehicleMechanics:new centres the 800x600 window on the whole screen and
-- ISOpenMechanicsUIAction:perform shows it there, so in splitscreen all players' windows open
-- in the same spot across the seam. Patch: after perform, fit the window to the player's screen.
-- It may lose height (the part lists are anchored to the bottom and scroll), the width stays
-- at least 800 because ISVehicleMechanics:updateLayout forces it. Option "mechanicsWindow".

require "SplitscreenFixes/SplitscreenUI"
require "SplitscreenFixes/Options"
require "Vehicles/TimedActions/ISOpenMechanicsUIAction"

local MIN_HEIGHT = 300

local vanillaPerform = ISOpenMechanicsUIAction.perform

function ISOpenMechanicsUIAction:perform()
    vanillaPerform(self)
    if not (SplitscreenUI.isSplitscreen() and SplitscreenFixes.isEnabled("mechanicsWindow")) then return end
    local playerNum = self.character:getPlayerNum()
    local ui = getPlayerMechanicsUI(playerNum)
    if not ui then return end
    -- Vanilla minimumHeight is the initial 600; let the resize widget go as low as we shrink.
    ui.minimumHeight = math.min(ui.minimumHeight or MIN_HEIGHT, math.max(MIN_HEIGHT, getPlayerScreenHeight(playerNum)))
    SplitscreenUI.fitToPlayerScreen(ui, playerNum, nil, MIN_HEIGHT)
end
