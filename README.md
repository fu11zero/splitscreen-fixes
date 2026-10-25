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
preview.png, workshop.txt             Workshop page
build.sh                              build (JDK 25)
tools/decompile.sh                    decompile game classes into decompiled/ (not in git)
```
