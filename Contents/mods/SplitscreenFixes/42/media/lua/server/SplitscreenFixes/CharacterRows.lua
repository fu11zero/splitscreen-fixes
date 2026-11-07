-- Server half of choosing a saved splitscreen character out of order; the reason is in
-- CharacterRows.java. The answer tells the client whether it may join: without the Java part
-- (a server without ZombieBuddy) the swap can't happen and the join would damage the save.

if not isServer() then return end

local MODULE = "SplitscreenFixes"

local function onClientCommand(module, command, player, args)
    if module ~= MODULE or not args then return end
    if command == "queueRowSwap" then
        local ok = SplitscreenFixes_queueRowSwap ~= nil
            and SplitscreenFixes_queueRowSwap(player, args.row, args.slot)
        sendServerCommand(player, MODULE, "rowSwapReply", { ok = ok })
    elseif command == "cancelRowSwap" and SplitscreenFixes_cancelRowSwap then
        SplitscreenFixes_cancelRowSwap(player, args.slot)
    end
end

Events.OnClientCommand.Add(onClientCommand)
