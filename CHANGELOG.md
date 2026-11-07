# Changelog

Player-facing change notes, newest first. Each section is copied as is into the Steam Workshop
change notes when the mod is updated. Keep it in English, short, one line per change.

## Unreleased

## Update: 7 Nov @ 5:29pm

- Fixed: on a server, a saved splitscreen character chosen out of order (the third one as the
  second player) no longer gets another character's skills, a broken inventory and lost progress.
- Fixed: "Add new player" no longer overwrites a saved splitscreen character on a server.
- Fixed: a splitscreen character already in play is no longer offered in the join list.

## Update: 1 Nov @ 11:45am

- Fixed: with 3-4 players the crafting, building and entity windows no longer cover the player
  below; a window that leaves its player's screen is moved back.
- Fixed: context menus are put back on their player's screen if something moved them past it.

## Update: 30 Oct @ 6:24pm 

- Changed: the Java part is no longer signed for now. ZombieBuddy blocked the signed jar as "signature invalid"
  when it could not load the author's key from the Steam profile; now it just asks to allow the mod.

## Update: 28 Oct @ 7:16pm

- Fixed: the crafting and building windows no longer spill onto the other player's screen when
  they were sized before the second player joined; a window open while a player joins shrinks
  to its half.
- Fixed: after a mod update the host server could skip Splitscreen Fixes without a word, so
  item transfers hung again. The server now loads Java mods of authors you trust in
  ZombieBuddy; for unknown Java mods ZombieBuddy asks while the host starts (denied after 30 s).
- Fixed: a splitscreen player's world map shows the gamepad cursor and button prompts again.
- Added: the view cone works for every splitscreen player.
- Added: debug cheats (and admin powers) for every splitscreen player.
- Compatibility: Project Zomboid 42.22, where vanilla fixed the item transfer packets itself;
  the mod's packet fix steps aside there.

## Unreleased

- Fixed: the second player's gamepad no longer gets stuck on a closed world map (B only played
  the map close sound) after the first player closed their map with the keyboard or mouse.
- Fixed: Esc on the keyboard closes the first player's map, not another player's map on top of it.
- Fixed: debug "Teleport here" on a player's map teleports that player, not always the first one.
