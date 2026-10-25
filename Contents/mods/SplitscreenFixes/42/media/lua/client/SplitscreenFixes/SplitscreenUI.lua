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

-- Shrinks the window to the player's screen (not below minWidth/minHeight) and, if it is not
-- fully inside that screen, centres it there. A window the player moved within their own
-- screen stays where it is.
function SplitscreenUI.fitToPlayerScreen(window, playerNum, minWidth, minHeight)
    local sx, sy, sw, sh = SplitscreenUI.getPlayerRect(playerNum)
    local w = math.max(math.min(window:getWidth(), sw), minWidth or 0)
    local h = math.max(math.min(window:getHeight(), sh), minHeight or 0)
    if w ~= window:getWidth() then window:setWidth(w) end
    if h ~= window:getHeight() then window:setHeight(h) end

    local x, y = window:getX(), window:getY()
    if x < sx or y < sy or x + w > sx + sw or y + h > sy + sh then
        -- A window wider than the screen is aligned to its left/top edge instead.
        window:setX(sx + math.max(0, (sw - w) / 2))
        window:setY(sy + math.max(0, (sh - h) / 2))
    end
end
