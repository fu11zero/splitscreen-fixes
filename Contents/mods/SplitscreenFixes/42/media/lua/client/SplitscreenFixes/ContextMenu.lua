-- Context menus kept on their player's screen.
--
-- Vanilla ISContextMenu (ISUI/ISContextMenu.lua) is per player (getPlayerContextMenu), but:
-- - calcHeight starts scrolling only when the options don't fit the whole window
--   (getCore():getScreenHeight()), so a long menu (a pot full of recipes) is taller than the
--   player's screen and covers the player below;
-- - keepOnScreen in ISUIElement:setX/setY clamps the menu to the whole window, not the
--   player's screen;
-- - calcWidth puts a submenu on the left only when it would leave the whole window.
--
-- Patch (splitscreen only): calcHeight uses the player's screen height; setX/setY clamp
-- menus and submenus to the player's screen, and a submenu that would cross its right edge
-- opens on the left of its parent. A slide-in animation whose goal is now out of reach is
-- finished after its 100 ms. Option "contextMenus".

require "SplitscreenFixes/SplitscreenUI"
require "SplitscreenFixes/Options"
require "ISUI/ISContextMenu"

local SLIDE_MS = 100

local function playerRectOf(menu)
    if not (SplitscreenUI.isSplitscreen() and SplitscreenFixes.isEnabled("contextMenus")) then return nil end
    if type(menu.player) ~= "number" or not menu:getKeepOnScreen() then return nil end
    return SplitscreenUI.getPlayerRect(menu.player)
end

local function isSubMenu(menu)
    -- Root menus have parent nil (ISContextMenu.get); new menus have an empty table.
    return menu.parent ~= nil and menu.parent.options ~= nil and menu.parent.x ~= nil
end

local vanillaSetX = ISContextMenu.setX
function ISContextMenu:setX(x)
    local sx, _, sw = playerRectOf(self)
    -- prerender parks empty menus at x = 100000.
    if sx and x < 100000 then
        local w = self.width or 0
        if x + w > sx + sw and isSubMenu(self) and x > self.parent.x then
            x = self.parent.x - w - 1
        end
        x = math.max(sx, math.min(x, sx + sw - w))
    end
    return vanillaSetX(self, x)
end

local vanillaSetY = ISContextMenu.setY
function ISContextMenu:setY(y)
    local _, sy, _, sh = playerRectOf(self)
    if sy then
        y = math.max(sy, math.min(y, sy + sh - (self.height or 0)))
    end
    return vanillaSetY(self, y)
end

local vanillaCalcHeight = ISContextMenu.calcHeight
function ISContextMenu:calcHeight()
    local _, sy, _, sh = playerRectOf(self)
    if not sy then
        return vanillaCalcHeight(self)
    end
    -- Vanilla with the player's screen height instead of the window's.
    local itemsHgt = (self.numOptions - 1) * self.itemHgt
    if itemsHgt + self.padTopBottom * 2 > sh then
        local numVisibleItems = math.floor((sh - self.padTopBottom * 2 - self.scrollIndicatorHgt * 2) / self.itemHgt)
        self.scrollAreaHeight = numVisibleItems * self.itemHgt
        self:setHeight(self.scrollAreaHeight + self.padTopBottom * 2 + self.scrollIndicatorHgt * 2)
    else
        self.scrollAreaHeight = itemsHgt
        self:setHeight(itemsHgt + self.padTopBottom * 2)
    end
    self:setScrollHeight(itemsHgt)
    -- The menu grows option by option; keep its bottom on the player's screen.
    self:setY(self.y)
end

-- With the goal clamped away the slide would never get within 1 px of it and never end.
local function finishSlide(menu, field)
    if menu[field] and menu.slideGoalTime and getTimestampMs() - menu.slideGoalTime >= SLIDE_MS
            and playerRectOf(menu) then
        menu[field] = nil
    end
end

local vanillaSlideX = ISContextMenu.updateSlideGoalX
function ISContextMenu:updateSlideGoalX()
    vanillaSlideX(self)
    finishSlide(self, "slideGoalX")
end

local vanillaSlideY = ISContextMenu.updateSlideGoalY
function ISContextMenu:updateSlideGoalY()
    vanillaSlideY(self)
    finishSlide(self, "slideGoalY")
end
