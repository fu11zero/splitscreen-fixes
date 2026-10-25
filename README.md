# Splitscreen Fixes

Java mod for Project Zomboid B42 (ZombieBuddy) that fixes splitscreen players on a
multiplayer server (Host from the main menu or a dedicated server).

## Features

- **Item transfers don't hang when two splitscreen players act at once** (`PacketSlots`,
  `Patches.ServerPacket`, `Patches.CachedPacket`; server side).
  - Vanilla: `PacketTypes.PacketType.onServerPacket` parses every incoming packet into the
    instance the connection caches per packet type (`PacketsCache.getPacket`). Three handlers
    keep that instance after `processServer`:
    `ItemTransactionPacket` (`TransactionManager.add(this)`, used by
    `ISInventoryTransferAction`/`ISGrabItemAction`, i.e. almost every inventory action),
    `BuildActionPacket` and `FishingActionPacket` (`ActionManager.start(this)`).
    Splitscreen players share one connection, so player 2's request is parsed over player 1's
    pending one: player 1's transaction disappears from the server (the progress bar sits at
    100% until the client times out after `duration + 10 s` and pretends it is done), and
    player 2's is often rejected because `TransactionManager.isConsistent` finds the same item
    in the queue (it is comparing the packet with itself).
  - Patch: for these three packet types each local player index gets its own instance, the
    same as a player on a separate connection. The player index is peeked from the packet
    header (`byte id, byte state, short onlineId, byte playerIndex`) and checked against
    `UdpConnection.playerIds`; player 0 keeps the vanilla instance. Packets without a readable
    player (an `ItemTransaction` reject) get a throwaway instance so they can't overwrite a
    pending request either.

- **Cars driven by a splitscreen player move on the server** (`Patches.VehicleAuthorization`;
  client side, works on any server).
  - Vanilla: on the client `BaseVehicle.hasAuthorization` compares the vehicle's authorized
    player with `IsoPlayer.getInstance()` only. With several local players that is whichever
    one `IsoPlayer.update` ran for last, so for other drivers `BaseVehicle.update` never calls
    `updatePhysicsNetwork`. The car is simulated locally (the driver sees it moving) but the
    server keeps it parked: other players see it standing, the server streams no chunks around
    the driver (the road is blocked at the chunk edge), and on exit the car snaps back.
  - Patch: any local player whose online id holds the authorization counts.

- **Firearm aiming works for every splitscreen player** (`Patches.AimingReticle`,
  `CombatReticle`; client side, also offline splitscreen).
  - Vanilla: `CombatManager.update` (called once per frame from `GameWindow.logic`) runs
    `updateReticle` and `highlightTargets` for `IsoPlayer.players[0]` only. For players 2-4 the
    aiming hit list is never computed while they aim: `hasTarget` stays false, so `AimingMode`
    never becomes `*_TARGET_FOUND`, `IsoReticle` gets no hit chance and the crosshair stays
    wide open, the reticle never turns to the target colour, melee targets are not outlined,
    and the ballistics camera targets that `IsoPlayer.setAngleFromAim` turns the character to
    are not refreshed.
  - Patch: after `CombatManager.update` the same two calls run for every other local player
    with a ready weapon, with `IsoPlayer.instance` and the camera character switched to that
    player (as `IsoPlayer.update` does) and restored afterwards.

- **Vehicle mechanics window opens on its player's screen** (`lua/client/SplitscreenFixes/Mechanics.lua`;
  client side).
  - Vanilla: every player has their own `ISVehicleMechanics` (`ISPlayerDataObject`), but
    `ISVehicleMechanics:new` centres the 800x600 window on the whole screen, so all players'
    windows open in the same spot across the seam and look like one shared window.
  - Patch: after `ISOpenMechanicsUIAction:perform` the window is fitted to the player's screen
    (`SplitscreenUI.fitToPlayerScreen`): shrunk in height if needed (the part lists scroll) and
    centred there unless the player already moved it somewhere inside their screen.

- **Every splitscreen player has their own crafting/building window** (`lua/client/SplitscreenFixes/Crafting.lua`;
  client side).
  - Vanilla: `ISEntityUI` keeps windows per player, but its local `createWindow` (behind
    `OpenWindow`, `OpenBuildWindow`, `OpenHandcraftWindow`) first calls
    `ISEntityUI.CloseWindows()`, which closes every player's windows ("we only allow one open at
    the moment"). The window is centred on the whole screen and its height is capped by the whole
    screen (`ISHandcraftWindow/ISBuildWindow/ISBaseEntityWindow:initialise`). `ISBuildPanel:new`
    stores itself in the global `ISBuildWindow.instance`, used by the build button
    (`ISWidgetBuildControl:startBuild`), recipe double click and
    `ISBuildingObject:onActionComplete`, so with two build windows one player builds through the
    other's panel.
  - Patch: while an `Open*` function runs, `CloseWindows` closes only that player's windows; the
    new window is moved onto the player's screen; the height cap uses the player's screen;
    build panels are kept per player (`ISBuildWindow.instances`) and `ISBuildWindow.instance` is
    switched to the acting player's panel before vanilla uses it.

- **Every splitscreen player has their own world map** (`lua/client/SplitscreenFixes/Map.lua`;
  client side).
  - Vanilla (`ISUI/Maps/ISWorldMap.lua`): one global map, `ISWorldMap_instance`, reassigned to
    whoever opened it last and sized to the whole window (`ShowWorldMap` and every
    `ISWorldMap:render`). `ShowWorldMap` blocks movement of every local player and `close`
    unblocks them all, so the others stand still while one reads the map. `render` calls
    `getWorld():setDrawWorld(false)`, a global flag (`IsoWorld.renderInternal`) that stops
    drawing the world on all screens. The map can't be opened while any splitscreen player is
    dead, `OnPlayerDeath` closes it whoever died, and the world context menu / entity windows
    are blocked for everybody while it is open.
  - Patch: `ISWorldMap.instances[playerNum]`, each sized to its player's screen; only the owner
    is blocked; in splitscreen the world keeps drawing and pause-on-map is off; death, context
    menu (`ISWorldObjectContextMenu.createMenu`) and `ISEntityUI.CanOpenWindowFor` look at the
    player's own map. `ISWorldMap_instance` stays player 0's map for vanilla code and mods.
    Flier and stash map icons (`renderPrintMedia`/`renderStashMaps`, plain textures drawn from
    `prerender`) are clipped to the map with a stencil, so they don't spill onto other screens.
    Small map popups (`ISMapSymbolDialog`, `ISTextBoxMap`, map options) still clamp to the
    whole window.

- **Flier / brochure window per splitscreen player** (`lua/client/SplitscreenFixes/PrintMedia.lua`;
  client side).
  - Vanilla (`PZAPI/ui/organisms/PrintMedia.lua`): a module-local `worldMapFlier` flag shared by
    all windows, checked against the global `ISWorldMap.instance` in `renderUpdate`, so a flier
    one player opens closes at once while another player has the map open. The "show on map"
    buttons use `getPlayer()` ("FIXME: splitscreen") and open the map for the wrong player.
    `updateSize` caps the height by the whole window; the window opened from the map sits at
    20,20 (player 0's screen).
  - Patch: `ISReadABook:displayPrintMedia` / `ISWorldMap:onMouseUpPrintMedia` give the window
    its player; it is closed only by that player's own map (`ISWorldMap.IsVisibleFor`), the map
    buttons run with `getPlayer()` returning that player, and in splitscreen the size cap and
    position use the player's screen. All wrapped, so GamepadTweaks' flier wrappers still run.

- **Moodles of the upper players don't run onto the lower players' screens**
  (`Patches.MoodleIconSize`, `MoodleSize`; client side).
  - Vanilla: `MoodlesUI` stacks moodles in one column (slot i at `y + i * (10 + size)`) with the
    icon size taken from the options only (`getTextureSizeForOption`). With 3-4 players the
    screen is a 2x2 grid, and player 0's (1's) column runs past the middle onto player 2's (3's)
    moodles.
  - Patch: `getTextureSizeForOption` returns the largest vanilla size (128..32) not above the
    option whose column still ends at the bottom of the player's screen; the spacing and slot
    positions follow it. The column's x is set by `UIManager.resize` for the option size, so
    smaller icons sit a little left of the screen edge.

- **The host server runs ZombieBuddy too** (`CoopServerAgent`; client side).
  - Vanilla: `CoopMaster.launchServer` starts the host server as a separate JVM with a fixed
    command line, without the client's `-javaagent`, so server-side Java mods never load.
  - Patch: an ASM hook before `command.add("zombie.network.GameServer")` copies the
    ZombieBuddy agent argument from the client's JVM arguments (relative jar path made
    absolute), plus `--enable-native-access`/`--add-exports` like `start-server.sh`. Unless the
    client set a policy, the server agent gets `policy=deny-new`: it is a background process,
    so it loads only jars already approved in the client's prompt instead of waiting for a
    dialog nobody sees. Approve this mod with "remember" in the client's ZombieBuddy prompt.

## Setup

- ZombieBuddy 2.3.4+ installed as a Java agent.
- Enable the mod in the main menu Mods list (the client must load it before you press Host,
  otherwise the host server starts without ZombieBuddy) and in the host server settings Mods
  list (`~/Zomboid/Server/<name>.ini`, `Mods=`), so the server loads it.
- Dedicated server: start it with `-javaagent:ZombieBuddy.jar` and add the mod.

The jar is at `media/java/SplitscreenFixes.jar` (not `client/`), so ZombieBuddy loads it on
both sides.

## Checking it works

- `~/Zomboid/console.txt` (client): `[SplitscreenFixes] host server launch hooked`, and after
  Host `host server JVM arg: -javaagent:...`.
- `~/Zomboid/coop-console.txt` (host server): `[ZB]` lines,
  `[SplitscreenFixes] loading (server)`, and after the first concurrent action of player 2+
  `separate ItemTransaction packet for splitscreen player 1`.

## Known leftovers (not fixed)

- `TransactionManager` on the server identifies transactions by a byte id only, and every
  client numbers them from 1, so a reject from one client can cancel another client's
  transaction with the same id. Rare and not splitscreen-specific.
- `WorldSimulation` checks car-to-car collisions only for `IsoPlayer.getPlayerIndex()`'s car.

## Layout

```
src/main/java/dev/splitscreenfixes/   mod source
Contents/mods/SplitscreenFixes/       the mod itself (Workshop layout)
  42/mod.info, poster.png, icon.png
  42/media/java/SplitscreenFixes.jar  <- build.sh output
  42/media/lua/client/SplitscreenFixes/  Lua UI fixes (SplitscreenUI.lua = shared helpers)
preview.png, workshop.txt             Workshop page
build.sh                              build (JDK 25)
tools/decompile.sh                    decompile game classes into decompiled/ (not in git)
```
