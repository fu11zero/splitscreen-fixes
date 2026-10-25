package dev.splitscreenfixes;

import java.lang.reflect.Field;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.ui.MoodlesUI;

// Vanilla MoodlesUI stacks moodles in one column, slot i at y + i * (10 + size), with the icon
// size from the options only (getTextureSizeForOption). Nothing limits the column to the
// player's screen: with 3-4 splitscreen players the screen is a 2x2 grid and player 0's (or
// 1's) column runs past the middle onto player 2's (3's) moodles. Shrink the icons to the
// largest vanilla size whose column still ends at the bottom of the player's screen. The
// spacing (moodleDistY) follows the size, and MoodlesUI.render re-lays the slots when it
// changes.
public final class MoodleSize {
    private static final int[] SIZES = {128, 96, 80, 64, 48, 32};
    private static final int SLOT_GAP = 10;
    private static final int BOTTOM_MARGIN = 10;

    private static final Field NUM_USED_SLOTS = field("numUsedSlots");
    private static final Field CHARACTER = field("isoGameCharacter");
    private static boolean failed = NUM_USED_SLOTS == null || CHARACTER == null;

    private MoodleSize() {}

    private static Field field(String name) {
        try {
            Field f = MoodlesUI.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            Log.error("MoodlesUI." + name + " not found, moodle size fix disabled", t);
            return null;
        }
    }

    public static int fit(MoodlesUI ui, int size) {
        if (failed || IsoPlayer.numPlayers <= 1) {
            return size;
        }
        try {
            if (!(CHARACTER.get(ui) instanceof IsoPlayer player)) {
                return size;
            }
            int slots = NUM_USED_SLOTS.getInt(ui);
            if (slots <= 1) {
                return size;
            }
            int index = player.getIndex();
            if (index < 0 || index > 3) {
                return size;
            }
            double bottom = IsoCamera.getScreenTop(index) + IsoCamera.getScreenHeight(index) - BOTTOM_MARGIN;
            double available = bottom - ui.getY();
            // Column height: slots * size + (slots - 1) * gap.
            for (int candidate : SIZES) {
                if (candidate <= size && slots * candidate + (slots - 1) * SLOT_GAP <= available) {
                    return candidate;
                }
            }
            return Math.min(size, SIZES[SIZES.length - 1]);
        } catch (Throwable t) {
            failed = true;
            Log.error("moodle size fix failed, disabled", t);
            return size;
        }
    }
}
