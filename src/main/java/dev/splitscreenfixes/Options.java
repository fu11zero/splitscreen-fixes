package dev.splitscreenfixes;

import se.krka.kahlua.integration.annotations.LuaMethod;

// Copy of the Lua mod options (Settings > Mods, lua/client/SplitscreenFixes/Options.lua) that
// the Java patches need. Lua pushes the values on load and whenever the options are applied;
// until then every feature is on.
public final class Options {
    public static volatile boolean ownClocks = true;

    private Options() {}

    @LuaMethod(name = "SplitscreenFixes_setOption", global = true)
    public static void set(String id, boolean value) {
        if ("ownClocks".equals(id)) {
            if (ownClocks != value) {
                Log.info("option " + id + " = " + value);
            }
            ownClocks = value;
        }
    }
}
