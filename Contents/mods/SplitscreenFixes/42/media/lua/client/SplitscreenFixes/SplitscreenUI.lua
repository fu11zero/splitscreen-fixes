-- Helpers for keeping a player's window on that player's part of the screen.
-- Vanilla windows are positioned and sized against getCore():getScreenWidth/Height(), i.e.
-- the whole window, so in splitscreen they land on the seam or on another player's screen.

SplitscreenUI = SplitscreenUI or {}

function SplitscreenUI.isSplitscreen()
    return getNumActivePlayers() > 1
end

function SplitscreenUI.getPlayerRect(playerNum)
    return getPlayerScreenLeft(playerNum), getPlayerScreenTop(playerNum),
        getPlayerScreenWidth(playerNum), getPlayerScreenHeight(playerNum)
end

-- If the window is not fully inside the player's screen, centres it there. A window the player
-- moved within their own screen stays where it is. A window larger than the screen is aligned
-- to its left/top edge.
function SplitscreenUI.moveIntoPlayerScreen(window, playerNum)
    local sx, sy, sw, sh = SplitscreenUI.getPlayerRect(playerNum)
    local x, y, w, h = window:getX(), window:getY(), window:getWidth(), window:getHeight()
    if x < sx or y < sy or x + w > sx + sw or y + h > sy + sh then
        window:setX(sx + math.max(0, (sw - w) / 2))
        window:setY(sy + math.max(0, (sh - h) / 2))
    end
end

-- Shrinks the window to the player's screen (not below minWidth/minHeight), then
-- moveIntoPlayerScreen. Only for windows whose children follow setWidth/setHeight (anchors);
-- XUI windows lay out in calculateLayout and must be capped through maximumWidth/Height.
function SplitscreenUI.fitToPlayerScreen(window, playerNum, minWidth, minHeight)
    local _, _, sw, sh = SplitscreenUI.getPlayerRect(playerNum)
    local w = math.max(math.min(window:getWidth(), sw), minWidth or 0)
    local h = math.max(math.min(window:getHeight(), sh), minHeight or 0)
    if w ~= window:getWidth() then window:setWidth(w) end
    if h ~= window:getHeight() then window:setHeight(h) end
    SplitscreenUI.moveIntoPlayerScreen(window, playerNum)
end
