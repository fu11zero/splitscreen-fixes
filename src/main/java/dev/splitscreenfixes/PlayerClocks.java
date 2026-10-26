package dev.splitscreenfixes;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.debug.DebugOptions;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.AlarmClock;
import zombie.inventory.types.AlarmClockClothing;
import zombie.iso.IsoCamera;
import zombie.scripting.objects.ItemTag;
import zombie.ui.Clock;
import zombie.ui.MoodlesUI;
import zombie.ui.UIManager;

// Vanilla has one Clock (UIManager.clock). Clock.resize shows it when any local player wears or
// holds a watch and takes clockPlayer, whose air temperature the digital display shows, from
// the first such player in IsoPlayer.players. UIManager.resize puts it at the bottom centre of
// the window in splitscreen. So everybody sees the time and temperature of player 0 (or of the
// first player with a watch), and the alarm button sets and stops the alarms of every player.
//
// Patch, splitscreen only: one Clock per local player. The vanilla clock belongs to player 0,
// extra Clock objects are added to the UI for players 1-3. After vanilla Clock.resize each clock
// shows only its own player's watch (visible, digital, alarm, temperature), after vanilla
// UIManager.resize each clock is put at the top-right corner of its player's screen (as in
// single player) and the moodles start below that player's own clock. Clicking a clock sets or
// stops only its player's alarm. Single player is untouched.
public final class PlayerClocks {
    private static final Clock[] extra = new Clock[4];

    private static final Field DIGITAL = field("digital");
    private static final Field CLOCK_PLAYER = field("clockPlayer");
    private static final Field ALARM_SET = field("isAlarmSet");
    private static final Field ALARM_RINGING = field("isAlarmRinging");
    private static boolean failed = DIGITAL == null || CLOCK_PLAYER == null || ALARM_SET == null || ALARM_RINGING == null;

    private PlayerClocks() {}

    private static Field field(String name) {
        try {
            Field f = Clock.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            Log.error("Clock." + name + " not found, per-player clocks disabled", t);
            return null;
        }
    }

    private static boolean active() {
        return !failed && Options.ownClocks && IsoPlayer.numPlayers > 1;
    }

    // Player index of a clock, -1 if it is not one of ours.
    private static int playerOf(Clock clock) {
        if (clock == UIManager.getClock()) {
            return 0;
        }
        for (int i = 1; i < 4; i++) {
            if (extra[i] == clock) {
                return i;
            }
        }
        return -1;
    }

    private static Clock clockOf(int index) {
        return index == 0 ? UIManager.getClock() : extra[index];
    }

    // After Clock.resize: keep only this clock's player's watch.
    public static void afterClockResize(Clock clock) {
        if (!active()) {
            return;
        }
        int index = playerOf(clock);
        if (index < 0) {
            return;
        }
        try {
            boolean visible = false;
            boolean digital = false;
            boolean alarmSet = false;
            boolean ringing = false;
            IsoPlayer owner = null;
            IsoPlayer player = IsoPlayer.players[index];
            if (player != null && !player.isDead()) {
                for (InventoryItem item : watches(player)) {
                    visible = true;
                    digital |= item.hasTag(ItemTag.DIGITAL);
                    alarmSet |= isAlarmSet(item);
                    ringing |= isRinging(item);
                    owner = player;
                }
            }
            if (DebugOptions.instance.cheat.clock.visible.getValue()) {
                visible = true;
                digital = true;
            }
            clock.setVisible(visible && UIManager.visibleAllUi);
            DIGITAL.setBoolean(clock, digital);
            CLOCK_PLAYER.set(clock, owner);
            ALARM_SET.setBoolean(clock, alarmSet);
            ALARM_RINGING.setBoolean(clock, ringing);
        } catch (Throwable t) {
            failed = true;
            Log.error("per-player clock failed, disabled", t);
        }
    }

    // After UIManager.resize: create the extra clocks, refresh them and lay out every clock.
    public static void afterUiResize() {
        Clock main = UIManager.getClock();
        if (main == null) {
            return;
        }
        try {
            if (!active()) {
                for (int i = 1; i < 4; i++) {
                    if (extra[i] != null) {
                        extra[i].setVisible(false);
                    }
                }
                return;
            }
            int width = Core.getInstance().getOptionClockSize() == 2 ? 166 : 91;
            for (int i = 0; i < 4; i++) {
                IsoPlayer player = IsoPlayer.players[i];
                Clock clock = i == 0 ? main : extraClock(i, player != null);
                if (clock == null) {
                    continue;
                }
                if (i > 0) {
                    clock.resize();
                }
                if (player == null) {
                    clock.setVisible(false);
                    continue;
                }
                int top = IsoCamera.getScreenTop(i);
                int right = IsoCamera.getScreenLeft(i) + IsoCamera.getScreenWidth(i);
                clock.setX(right - width);
                clock.setY(top + 10);
                MoodlesUI moodles = UIManager.getMoodleUI(i);
                if (moodles != null) {
                    moodles.setY(top + (clock.isVisible() ? 64 : 24));
                }
            }
        } catch (Throwable t) {
            failed = true;
            Log.error("per-player clock layout failed, disabled", t);
        }
    }

    private static Clock extraClock(int index, boolean create) {
        Clock clock = extra[index];
        if (clock != null && UIManager.getUI().contains(clock)) {
            return clock;
        }
        if (!create) {
            return null;
        }
        Clock main = Clock.instance;
        clock = new Clock(0, 0);
        // The constructor makes itself Clock.instance; vanilla code means the main clock.
        Clock.instance = main;
        UIManager.getUI().add(clock);
        extra[index] = clock;
        return clock;
    }

    // Instead of vanilla Clock.onMouseDown, which changes every local player's alarm. Returns
    // true if the click was handled here (vanilla is skipped).
    public static boolean onMouseDown(Clock clock) {
        if (!active() || !clock.isVisible()) {
            return false;
        }
        int index = playerOf(clock);
        IsoPlayer player = index < 0 ? null : IsoPlayer.players[index];
        if (player == null || player.isDead()) {
            return false;
        }
        try {
            List<InventoryItem> items = watches(player);
            if (ALARM_RINGING.getBoolean(clock)) {
                for (InventoryItem item : items) {
                    if (item instanceof AlarmClock a) {
                        a.stopRinging();
                    } else if (item instanceof AlarmClockClothing a) {
                        a.stopRinging();
                    }
                }
            } else if (ALARM_SET.getBoolean(clock)) {
                for (InventoryItem item : items) {
                    if (isAlarmSet(item)) {
                        setAlarm(item, false);
                    }
                }
            } else {
                for (InventoryItem item : items) {
                    if (isDigital(item)) {
                        setAlarm(item, true);
                        break;
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            Log.error("per-player clock click failed, disabled", t);
            return false;
        }
    }

    // Worn watches, then watches worn or equipped from the inventory, as in Clock.resize.
    private static List<InventoryItem> watches(IsoPlayer player) {
        List<InventoryItem> result = new ArrayList<>();
        for (int j = 0; j < player.getWornItems().size(); j++) {
            InventoryItem item = player.getWornItems().getItemByIndex(j);
            if (isWatch(item)) {
                result.add(item);
            }
        }
        ArrayList<InventoryItem> items = player.getInventory().getItems();
        for (InventoryItem item : items) {
            if (isWatch(item) && (item.isWorn() || item.isEquipped()) && !result.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }

    private static boolean isWatch(InventoryItem item) {
        return item instanceof AlarmClock || item instanceof AlarmClockClothing;
    }

    private static boolean isAlarmSet(InventoryItem item) {
        return item instanceof AlarmClock a ? a.isAlarmSet() : item instanceof AlarmClockClothing c && c.isAlarmSet();
    }

    private static boolean isRinging(InventoryItem item) {
        return item instanceof AlarmClock a ? a.isRinging() : item instanceof AlarmClockClothing c && c.isRinging();
    }

    private static boolean isDigital(InventoryItem item) {
        return item instanceof AlarmClock a ? a.isDigital() : item instanceof AlarmClockClothing c && c.isDigital();
    }

    private static void setAlarm(InventoryItem item, boolean set) {
        if (item instanceof AlarmClock a) {
            a.setAlarmSet(set);
            a.syncAlarmClock();
        } else if (item instanceof AlarmClockClothing c) {
            c.setAlarmSet(set);
            c.syncAlarmClock();
        }
    }
}
