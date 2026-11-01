# Backlog

What to check in game and what to look into. Each item has a scenario to repeat. Sources are
Indie Stone forum bug reports (checked 2026-10-28).

Common setup: Sandbox, two players (P1 and P2 on gamepads unless stated), 42.22.
Log: `grep '\[SplitscreenFixes\]' ~/Zomboid/console.txt`, errors: `grep -n 'ERROR\|Exception' console.txt`.

## Verify our fixes

### Crafting window size (fix 2026-10-28, `Crafting.lua` `capLayoutByPlayerScreen`, `shrinkToPlayerScreen`)
Step 2 ok. Steps 3–4: open window did not shrink, second fix added, retest.
1. P1 alone: open the crafting window, widen it to more than half of the screen, close it.
2. Add P2. P1 opens crafting again: the window must fit in P1's half.
3. P1 opens crafting, then P2 joins while it is open: the window shrinks to P1's half.
4. Same with the build window (`ISBuildWindow`) and with option `ownCrafting` off (vanilla).

### Windows and context menus in a 2x2 split (fix 2026-11-01, `Crafting.lua` `shrinkToPlayerScreen`, `ContextMenu.lua` `prerender`)
User report: with 3 players the crafting window of P1 covered P3 below; the world context menu
was not limited to P1's screen (the cooking menu was fine).
1. Two players, P1 opens crafting, P3 joins: the window moves into P1's top-left quarter.
2. P1 drags the window partly onto P3's screen: it snaps back after release, not while dragging.
3. 3-4 players: P1 (mouse) right-clicks the world near the bottom of P1's screen, opens a
   submenu; same with a gamepad player and Interact. Menus stay on the player's screen.
4. Log: `context menu of player N ... outside its screen` shows the path that escaped the
   clamp (fix the cause, the safety net only hides it); `larger than its screen` for windows.

### Melee target outline for P2..P4
Steam question about the outline. Our aiming fix made it work in the tested case.
1. Settings → Display → Target outline: "Any weapon" and the default value, check both.
2. P2 with a melee weapon, combat mode (LT), walk up to a zombie: the outline is shown.
3. Bare hands, a spear (long reach), a downed (crawling) zombie, a zombie behind a fence/window.
4. 3–4 players (a 41.50 report says the outline was missing only with 3–4 players).
5. P1 on keyboard/mouse + P2 on gamepad, and the other way around.
6. Iso cursor guide (forum 42.21: "hard-coded to player 1 in Java"), check for P2.

### Map → P2 loses gamepad (forum 42.19)
1. P2 opens the world map (and the paper map), closes it.
2. P2: inventory (Y), item use, pause menu must still work.

### Two players in a car (forum 42.20.3: vehicle desync / rollback, trunk transfers)
SP:
1. P1 drives, P2 passenger, two-seat and four-seat car, drive 5+ minutes through a town.
2. Look for `BaseVehicle.update ... this.chunk is null` in the log (crash to menu in vanilla).
MP (dedicated or Host), with the mod on the server:
3. Same drive: parked cars ahead must load; after both get out the car stays where it is.
4. Move one item into the trunk and back.

## To investigate

### Fishing rod swings like a weapon (forum 42.13.2, 42.20.2)
"Fishing works until P2 joins; picking up the rod after both joined sometimes helps."
1. P1 has a rod in inventory, P2 joins, P1 equips the rod and casts (Sandbox SP).
2. Same with the rod picked up after P2 joined; same for P2.
3. 42.22 notes fixed "game freezes if a player re-joins while the other is fishing" only.

### Pausing closes the other player's inventory panels (forum 42.19.0)
1. P1 on keyboard/mouse, P2 on gamepad, both open inventory/loot windows.
2. One of them pauses and unpauses: the other's panels must stay as they were.

### P2 cannot rejoin an existing character (forum 42.16)
1. Save with P1 + P2, quit, load, P2 presses Start: is the old P2 offered or only a new one?

### P2 tapping A opens the "add player" menu (forum, Oct 2026)
1. P2 taps A repeatedly while nothing is focused: does the join menu appear in the top-left?

### Vehicle seat HUD breaks after P2 changes seats (forum 42.19)
1. P2 gets in, uses "change seat": the seat HUD of P1/P2 must be correct.

### Ambient sounds vanish when P2 joins (forum 42.12)
1. P1 alone, listen to wind/birds; P2 joins on a gamepad: are ambient sounds still there?

## Not ours

- Furniture rotation ignored on placement with a gamepad (forum 42.20.3, MP split-screen).
  Probably the gamepad placement path, not split-screen: hand to GamepadTweaks after a solo check.
- P2 barricading puts P1 into the animation (forum 42.19): barricading moved to building since.
