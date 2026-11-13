# Splitscreen Fixes

Java mod for Project Zomboid B42 (ZombieBuddy) that fixes splitscreen players on a
multiplayer server (Host from the main menu or a dedicated server).

Source: <https://github.com/fu11zero/splitscreen-fixes>

## Options

Settings > Mods > Splitscreen Fixes (`PZAPI.ModOptions`, `lua/client/SplitscreenFixes/Options.lua`,
saved in `~/Zomboid/Lua/ModOptions.ini`). Features that change how vanilla lays out the
splitscreen UI have a tick box (on by default, except `spanMonitors`): someone on a small monitor may prefer
vanilla's shared map or centred windows. Bug fixes have no option. Each check runs when the
feature acts, so a change applies without reloading the save (to windows opened after it).
`SplitscreenFixes.isEnabled(id)` in Lua; the Java clock patch gets the value through the
global `SplitscreenFixes_setOption` (`Options.java`).

| Option id         | Feature                                     |
|-------------------|---------------------------------------------|
| `ownMap`          | own world map per player                    |
| `flierWindow`     | flier window per player (size, closing)     |
| `ownCrafting`     | own crafting/building windows               |
| `mechanicsWindow` | mechanics window on the player's screen     |
| `contextMenus`    | context menus on the player's screen        |
| `dialogs`         | dialogs centred on the player's screen      |
| `ownClocks`       | own clock per player                        |
| `spanMonitors`    | one player per monitor (off by default)     |

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
  - 42.22 fixes this in vanilla: the three packets override `shouldInstantiate()`, so
    `onServerPacket` parses each one into a new instance. The patch then steps aside (it still
    matters on 42.21 servers).

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
  - Option `mechanicsWindow`.

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
    every `calculateLayout` of the crafting/build window caps width and height by the player's
    screen (vanilla restores the last size, capped only by the whole screen, so a window sized
    before the second player joined spilled onto their screen); a window already open when the
    screen splits is relaid out once in `prerender` (vanilla relays out only on resize) and,
    while not dragged or resized, moved back whenever it leaves the player's screen (vanilla
    moves it only on open, so a window open when 2 players became 3-4 covered the player below);
    build panels are kept per player (`ISBuildWindow.instances`) and `ISBuildWindow.instance` is
    switched to the acting player's panel before vanilla uses it.
  - Option `ownCrafting` (off: all of the above is vanilla).

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
  - Option `ownMap` (off: every wrapper calls vanilla; `IsVisibleFor` reports the shared map).
  - Bug fix, always on: a gamepad stuck on a closed map. Vanilla `WorldMapOptions:onMouseDownOutside`
    calls `setJoypadFocus(joypadData.player, ...)` with an undeclared `joypadData`, i.e. a leaked
    global holding the last handled gamepad (often another splitscreen player's). Player 0 using
    the map options with a gamepad and then the mouse/keyboard hands the other player's gamepad to
    player 0's map buttons; their B calls player 0's `ISWorldMap:close` (close sound, focus restored
    only for player 0) and the player is stuck. Patch: the options use their own `joyfocus` and only
    while the map is open; `close` drops every gamepad focus left inside the map; a button press on
    a closed map (or its button panel) releases the gamepad (`[SplitscreenFixes] ... released` in the log).
  - Keyboard keys (`UIManager.onKeyPress/Release` go to the topmost visible element with key events)
    reach only player 0's map in splitscreen; before, Esc could close a gamepad player's map on top.
  - Bug fix, always on: debug "Teleport here" (`ISWorldMap:onTeleport`) moved player 0 from any map
    (`getSpecificPlayer(0)` offline, `/teleportto x,y,z` = `connection.players[0]` online). Now it moves
    the map's own player (`/teleportto "name" x,y,z` online).

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
  - Option `flierWindow` (closing, size, position); the map buttons' player is always fixed.

- **Context menus stay on their player's screen** (`lua/client/SplitscreenFixes/ContextMenu.lua`;
  client side).
  - Vanilla: `ISContextMenu:calcHeight` starts scrolling only when the options don't fit the
    whole window, and `keepOnScreen` (`ISUIElement:setX/setY`) clamps to the whole window, so a
    long menu (a pot with many recipes) runs over the player below. `calcWidth` opens a submenu
    on the left only at the window's right edge.
  - Patch (splitscreen): the height limit and the clamping use the player's screen; a submenu
    that would cross its right edge opens left of its parent; a slide-in whose goal was clamped
    away ends after its 100 ms; `prerender` puts back a menu that got past the clamp and logs
    it once (`[SplitscreenFixes] context menu of player N ... outside its screen`).
  - Option `contextMenus`.

- **Confirmation dialogs on their player's screen, answerable with a gamepad**
  (`lua/client/SplitscreenFixes/Dialogs.lua`; client side).
  - Vanilla: `ISModalDialog/ISModalRichText/ISTextBox:new` centre on the player's screen only
    for `x == 0`/`y == 0` with a gamepad player. Many callers pass whole-window coordinates
    (`getCore():getScreenWidth() / 2 ...`) or coordinates of a window that is no longer at 0,0
    (`ISWorldMap:onForget`, "forget the whole map", lands on player 0's screen). Dialogs aimed at
    a player often don't pass the player at all, so they get no gamepad focus and only the mouse
    can answer: `ISHealthPanel.ReceiveMedicalCheckRequest` (another player asks to inspect you),
    faction/safehouse invites, remove designation zone, kill animal, the "i" help popups.
  - Patch: in splitscreen a dialog with a known player that isn't fully inside that player's
    screen is centred there. The entry points above (event handlers re-registered) run with
    their player as the dialog's player, and a gamepad player gets the focus on the new Yes/No
    dialog.
  - Option `dialogs` turns off only the centring; the player and gamepad focus are always on.

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

- **Every splitscreen player has their own clock** (`Patches.ClockResize/ClockLayout/ClockClick`,
  `PlayerClocks`; client side).
  - Vanilla: one `Clock` (`UIManager.clock`). `Clock.resize` shows it when any local player has a
    watch and takes `clockPlayer` (whose air temperature the digital display shows) from the
    first such player; `UIManager.resize` puts it at the bottom centre of the window in
    splitscreen. Everybody sees player 0's (or the first watch owner's) temperature, and
    `Clock.onMouseDown` sets/stops every local player's alarm.
  - Patch (splitscreen): the vanilla clock is player 0's, extra `Clock` objects are added to
    the UI for players 1-3 (`Clock.instance` restored to the vanilla one). After `Clock.resize`
    each clock reflects only its player's watch (visible, digital, alarm, temperature); after
    `UIManager.resize` each sits at the top-right of its player's screen, as in single player,
    with that player's moodles below it. A click changes only that player's alarm. A player
    without a watch has no clock, as in single player. Leftovers: `ISButtonPrompt` still adds a
    side margin for the clock, `ISCharacterScreen` "survived for" looks at player 0's clock.
  - Option `ownClocks` (`Options.ownClocks`; off: extra clocks hidden, vanilla clock untouched).

- **Search mode (foraging) shows the visible circle for every splitscreen player**
  (`Patches.SearchModeOffset`, `SearchModeOverlay`; client side).
  - Vanilla: the overlay is drawn by `media/shaders/screen.frag` when `MultiTextureFBO2.render`
    puts each player's view on the screen with the weather shader. The circle is found from
    `gl_FragCoord` minus `SearchMode.zw`, which `WeatherShader.startMainThread` sets to
    `IsoCamera.getOffscreenLeft/Top(player)`. That pass runs with `glDoStartFrame(width, height, 1, -1)`:
    player -1 sets an ortho projection over the window but leaves the viewport from the previous
    pass, so the player's screen does not start at that offset in `gl_FragCoord` (Top is also
    counted from the top, `gl_FragCoord.y` from the bottom). Only player 0 reliably gets the circle;
    for the others it lands off their screen and the whole screen goes dark. The drunk and glasses
    blur circles use the same offsets.
  - Patch (splitscreen): `startMainThread` stores the player's screen measured from the window's
    bottom-left; after `WeatherShader.startRenderThread` the render thread reads the actual viewport,
    maps that point to `gl_FragCoord` and uploads `SearchMode.zw` again
    (`Patches.SearchModeOffsetRender`). The first 20 distinct results are logged
    (`search mode: viewport ...`). An earlier constant offset 0, 0 was right only for one viewport
    state.

- **View cone for every splitscreen player** (`Patches.ViewConeShader`, `ViewCone`; client side).
  - Vanilla: `VisibilityPolygon2$Drawer.renderToScreen` draws the cone with
    `media/shaders/visibilityBlur.frag` into the shared offscreen buffer, viewport at the
    player's screen, so `gl_FragCoord` includes `IsoCamera.getScreenLeft/Top(player)`
    (`displayOrigin`). The shader subtracts it for `screenUV`, but not for the blur samples
    (`pixel = gl_FragCoord / screenSize * texSize`): for every player but 0 they land outside
    the polygon texture and there is no cone.
  - Patch: after `ShaderUnit.loadShaderFile` reads `visibilityBlur.frag`, `displayOrigin` is
    subtracted for the samples too (no-op with one player, offset 0, 0). Same shader in 42.22.
  - The depth lookup `screenUV = (gl_FragCoord - displayOrigin) / displaySize` gets
    `displaySize` from the offscreen texture split like the window (`osWidth / 2`), which equals
    the player's screen × `texSize / TextureSize` only for vanilla's halves/quarters. With one
    player per monitor of different sizes the shadows shifted; the shader computes it from
    `screenSize` (same value for vanilla layouts).

- **Saved splitscreen characters can join in any order** (`CharacterRows`,
  `lua/*/SplitscreenFixes/CharacterRows.lua`; server and client).
  - Vanilla: a saved character joins the first free local slot (`ISJoypadListBox`), but only
    the slot reaches the server (`ConnectCoopPacket`), which loads and saves the
    `networkPlayers` row whose `playerIndex` equals it. With rows 1 Roma, 2 Otis, choosing Otis
    as the second player makes the client play Otis and the server hold Roma: Roma's skills on
    screen, failing inventory actions, Otis's progress lost on exit. "Add new player" writes
    over the row of the slot (`CreatePlayerPacket`), so with a free slot and a saved character
    in its row it destroys that character. A character in play also stays in the join list:
    `IsoPlayer.isSaveFileInUse` compares fields network characters don't have.
  - Patch: the row follows the slot. Before such a join the client asks the server to swap the
    rows and waits for the answer; the server does the swap right before the slot's row is used
    (`GameServer.receivePlayerConnect`, `CreatePlayerPacket.processServer`), so an abandoned join
    changes nothing. The saved order of the characters changes, and nothing else. A slot can't
    follow the row instead: a hole in `IsoPlayer.players` breaks loops over `numPlayers`.
  - Without the Java part on the server (or no answer in 3 s) the out-of-order join is refused
    with a message; joining in order works as in vanilla. Row 0 (main character) never moves.
    Saves already damaged by this bug are not repaired.

- **A refused splitscreen join can be retried** (`CharacterRows.clearFailedSlot`,
  `lua/client/SplitscreenFixes/CharacterRows.lua`; client side).
  - Vanilla: the server refuses a name in use anywhere (`ConnectCoopPacket`), and saved
    characters often share one stored name. `setPlayerJoypad` has already put the player in
    `IsoPlayer.players`, and `AddCoopPlayer.accessDenied` leaves it there, with `joypadData.player`
    set. The player has no player data, so every gamepad press fails in Lua and the slot stays taken.
    `JoypadState.onCoopJoinFailed` stops on its own `error "joypadData.player ~= nil"`.
  - Patch: on refusal the slot and the gamepad are cleared, and A opens the join list again. A name
    used on this client is rejected before joining. Only the server knows names on other clients.

- **Cheats for every splitscreen player** (`Cheats.lua`; client side, debug mode or admin).
  - Vanilla: the Cheats panel (`ISAdminPowerUI`) binds to `getPlayer()` when it opens and
    "Save" applies the ticks (Invisible, God mode, No clip, ...) only to that player;
    `ISAdminPowerUI.onGameStart` restores `CheatPanel.ini` only for player 0. So player 0 can be
    invisible while the others are chased by zombies.
  - Patch: after vanilla "Save", the same ticks go to every other living local player
    (`sendPlayerExtraInfo` for each); a splitscreen player who joins gets player 0's cheats
    (in MP once they have an online id). One cheat set for all local players.

- **The host server runs ZombieBuddy too** (`CoopServerAgent`; client side).
  - Vanilla: `CoopMaster.launchServer` starts the host server as a separate JVM with a fixed
    command line, without the client's `-javaagent`, so server-side Java mods never load.
  - Patch: an ASM hook before `command.add("zombie.network.GameServer")` copies the
    ZombieBuddy agent argument from the client's JVM arguments (relative jar path made
    absolute), plus `--enable-native-access`/`--add-exports` like `start-server.sh`. Unless the
    client set a policy, the server agent gets `policy=prompt,batch_approval_timeout=30`.
    `deny-new` loads only hashes persisted in `mod_approvals.json`, but ZombieBuddy approves a
    signed jar of a trusted author in memory only and only under `prompt`, so after every
    update the server silently skipped this mod. Now trusted and remembered jars load without
    a dialog; a new jar shows ZombieBuddy's dialog while the host starts, denied after 30 s.

- **One player per monitor** (option `spanMonitors`, off by default; `MultiMonitor.java`,
  `Patches.Monitor*`).
  - Vanilla: one GLFW window for everyone, split by the window size only (2 players:
    left/right halves, 3-4: 2x2). Borderless covers only the primary monitor
    (`Core.setDisplayModeInternal`).
  - Patch: with 2+ local players in game and the pause menu closed (`GameWindow.logic` checks
    every frame, also while paused: `GameStateMachine.update` is skipped then; the menu state is
    read from Lua tables, `MainScreen.instance`) the window leaves fullscreen, loses its frame
    (`GLFW_DECORATED`), gets a fixed size and is moved over the bounding box of all monitors
    (`glfwGetMonitors`). The game follows the size itself (`GameWindow` →
    `Core.setScreenSize`). The layout is the part of each monitor the window actually covers
    (`Display.getX/Y` + `Core.width/height`), monitors in reading order (rows by overlapping
    height, then left to right), players in order; with more players than monitors the first
    monitors are split as vanilla splits the window. A window that settles off the target for
    1.5 s (dragged, or kept by the window manager) is moved back once per position. A change of
    the wanted state acts after 10 frames and is logged with its reason. The pause menu is read
    from Lua tables in Java every frame (`MainScreen.instance.inGame` and its `javaObject`
    visible): while it is up the Lua events (`OnPreUIDraw`, `OnTickEvenPaused`) stop. Monitors are looked up
    again every 2 s (a resolution or scale change fires no GLFW monitor callback) and the window
    follows them. Vanilla re-places UI that follows `getPlayerScreen*` only on
    `OnResolutionChange`, fired by `Core.setScreenSize` before the new layout exists and never
    for a move: the mod fires it again 5 frames after the layout settles. With one
    player, in the pause menu (laid out against the whole window and scaled by width / 1920,
    its items end up off screen) or with the option off the window goes back through
    `Core.setDisplayMode` to the size/fullscreen/borderless from the options.
  - Vanilla code that splits the window by itself: `IsoCamera.getScreen*`,
    `getOffscreenLeft/Top`; `Core.DoStartFrameStuffInternal` (viewport, scissor and
    projection of the player's part of the offscreen buffer: `w / 2`, `x = w * (player % 2)`;
    redone in OnExit with the player's rectangle, the projection on top of
    `projectionMatrixStack` rewritten); `Core.DoStartFrameNoZoom` (the chunk texture pass of
    `FBORenderChunkManager.endFrame`: with the debug option `CombinedFBO` off, the default, it
    halves w/h the same way); `WorldItemAtlas.RenderJob.restoreScreenStencil`
    (scissor); `UIManager.resize` (moodle columns, speed controls).
  - Side effects handled: `MultiTextureFBO2.getDisplayZoom` scales the zoom by
    `initialWidth / width`, so `Core.initialWidth/Height` follow the span; vanilla
    `setDisplayModeInternal` while spanned only records a real change of the mode to restore
    (applying any option re-applies the current, spanned size); `Core.saveOptions` writes the
    restore size. Windows: the window is topmost while focused (`GLFW_FLOATING`), otherwise the
    taskbars of the other monitors stay over it.
  - Not handled: different refresh rates (one swap interval for the window); different DPI
    (UI scale is global). Linux/Hyprland: the game runs on XWayland, a monitor with scale 2 is
    seen as half its resolution and upscaled (blurry); `xwayland { force_zero_scaling = true }`
    gives the real resolution. Bars (waybar, layer `top`) are drawn over floating windows.
    Lua windows centred on `getCore():getScreenWidth()` open on the seam; the paused message
    is at the top centre of the window; mouse picking assumes player 0 at the window's
    top-left corner.
- **Mouse player's inventory stays on their screen** (`MouseInventory.lua`).
  - Vanilla places the keyboard/mouse player's inventory and loot windows once, when they are
    created, against the whole window if the player was alone then
    (`ISPlayerDataObject:createInventoryInterface`); `onResolutionChange` moves only gamepad
    players' windows. After a second player joins, the loot window sits on their screen.
  - Patch: every 30 frames, when the centre of either window is outside player 0's screen,
    `revertToKeyboardAndMouse` places both inside it.

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

- `~/Zomboid/tools/checklog.py SplitscreenFixes` summarizes `console.txt`: mod loads per save
  (a save with its own mod list without this mod runs with no Lua of it, the Java part stays),
  ZombieBuddy patches, errors.

## Game versions

The mod has to work on the stable branch and be ready for the beta (pre-release). `build.sh`
builds the jar against the installed game; with `~/Zomboid/tools/pz-versions.sh` present it also
keeps the installed `projectzomboid.jar` in `~/Zomboid/jars/<version>/` and compiles against every
kept version: "does NOT compile against game X" means the mod would break in X. Before switching
branches, `tools/snapshot.sh` saves the vanilla code of the installed version for `diff -r`.

## Known leftovers (not fixed)

- `TransactionManager` on the server identifies transactions by a byte id only, and every
  client numbers them from 1, so a reject from one client can cancel another client's
  transaction with the same id. Rare and not splitscreen-specific. Fixed in 42.22
  (`removeItemTransaction(id, connection)` checks the connection).
- `WorldSimulation` checks car-to-car collisions only for `IsoPlayer.getPlayerIndex()`'s car.

## Layout

```
src/main/java/dev/splitscreenfixes/   mod source
Contents/mods/SplitscreenFixes/       the mod itself (Workshop layout)
  42/mod.info, poster.png, icon.png
  42/media/java/SplitscreenFixes.jar  <- build.sh output
  42/media/lua/client/SplitscreenFixes/  Lua UI fixes (SplitscreenUI.lua = shared helpers,
                                         Options.lua = Settings > Mods)
  42/media/lua/shared/Translate/        option names (EN, RU)
preview.png, workshop.txt             Workshop page
build.sh                              build (JDK 25)
tools/decompile.sh                    decompile game classes into decompiled/ (not in git)
tools/snapshot.sh                     vanilla Lua, shaders and those classes into decompiled/<version>/
```
