package dev.splitscreenfixes;

import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL11;
import org.lwjglx.opengl.Display;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameWindow;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.RenderThread;
import zombie.debug.DebugOptions;
import zombie.gameStates.IngameState;
import zombie.ui.MoodlesUI;
import zombie.ui.UIElement;
import zombie.ui.UIManager;

// One player per monitor (option "spanMonitors", off by default).
//
// Vanilla draws every player into one GLFW window and hardcodes the split by the window size:
// two players get the left and right halves, three or four a 2x2 grid. Monitors are never looked
// at, and vanilla's borderless mode covers the primary monitor only (Core.setDisplayModeInternal).
//
// With the option on, 2+ local players in game and the pause menu closed, the window is made
// undecorated and moved over the bounding box of all monitors. The game follows the window size
// by itself (GameWindow calls Core.setScreenSize with the display size every frame). Each player
// gets a monitor: the layout is the part of each monitor the window actually covers, so a window
// the window manager moved or didn't stretch still gives every player a whole picture, and a
// window that stays off the target for a while is put back once. Several players share a monitor
// when there are more players than monitors. With one player, in the pause menu (laid out against
// the whole window, its items would end up off screen) or with the option off, the window goes
// back to the mode from the options.
//
// Vanilla code that splits the window by itself, patched here:
// - IsoCamera.getScreen* / getOffscreenLeft/Top (see Patches.MonitorScreen*);
// - Core.DoStartFrameStuffInternal: the player's viewport, scissor and projection in the
//   offscreen buffer (w / 2, x = w / 2 * (player % 2)); Core.DoStartFrameNoZoom: the size of
//   the chunk texture pass without the combined FBO;
// - WorldItemAtlas.RenderJob.restoreScreenStencil: the scissor after drawing an item atlas;
// - UIManager.resize: moodle columns at the right edge of the window halves, speed controls.
// And things that would fight the spanned window:
// - MultiTextureFBO2.getDisplayZoom scales the zoom by initialWidth / width when the window is
//   wider than at startup, so the world would look twice as big: initialWidth follows the span;
// - Core.setDisplayModeInternal (resolution options, borderless, fullscreen) would shrink the
//   window back: while spanned it only records a real change of the mode to restore;
// - Core.saveOptions writes the current window size as the resolution: it gets the restore size.
public final class MultiMonitor {
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    // Frames a window off the target must stay put before it is moved back (a drag in progress
    // changes the geometry every frame).
    private static final int SNAP_FRAMES = 90;
    // A monitor counts when the window covers at least this much of it in both directions.
    private static final int MIN_VISIBLE = 200;
    private static final int DEBOUNCE_FRAMES = 10;

    // Player rectangles [x, y, w, h] in window pixels; null = vanilla layout.
    private static volatile int[][] layout;
    private static volatile boolean spanned;
    private static volatile boolean restoring;
    private static boolean menuOpen;
    // Monitors are looked up again every this many frames while spanned (a resolution or scale
    // change doesn't add or remove a monitor, so GLFW's monitor callback doesn't fire).
    private static final int MONITOR_CHECK_FRAMES = 120;
    private static int monitorCheck;
    // Frames since the layout changed; Lua UI is told when it settles.
    private static int layoutChangedFrames = -1;
    private static final int LAYOUT_EVENT_FRAMES = 5;

    private static int failedForPlayers = -1;
    // Target window rectangle (desktop coordinates) and the monitors, sorted in reading order.
    private static int spanX, spanY, spanW, spanH;
    private static int[][] monitors = new int[0][];
    // Geometry the layout was built for: window x, y, w, h and player count.
    private static final int[] layoutKey = new int[5];
    private static final int[] seenGeometry = new int[4];
    private static final int[] snappedGeometry = new int[4];
    private static boolean layoutBuilt;
    private static boolean pendingWant;
    private static int pendingFrames;
    private static int stableFrames;
    private static boolean warned;
    // What to go back to.
    private static int restoreW, restoreH;
    private static boolean restoreFullscreen;
    private static float restoreInitialW, restoreInitialH;
    private static boolean focusListeners;

    private MultiMonitor() {}

    public static boolean isActive() {
        return layout != null;
    }

    // The in-game pause menu: Lua MainScreen.instance with inGame and a visible javaObject
    // (ToggleEscapeMenu, JoyPadSetup). Read here on the main thread every frame: while the menu
    // is up the Lua events (OnPreUIDraw, OnTickEvenPaused) stop, so Lua can't report it.
    private static boolean isPauseMenuShown() {
        KahluaTable env = LuaManager.env;
        if (env == null || !(env.rawget("MainScreen") instanceof KahluaTable mainScreen)
            || !(mainScreen.rawget("instance") instanceof KahluaTable instance)) {
            return false;
        }
        return Boolean.TRUE.equals(instance.rawget("inGame"))
            && instance.rawget("javaObject") instanceof UIElement element
            && element.isVisible();
    }

    // IsoCamera getters (see Patches.MonitorScreen*): part 0..3 = x, y, w, h.
    public static int rect(int playerIndex, int part, int vanilla) {
        int[][] l = layout;
        if (l == null || playerIndex < 0 || playerIndex >= l.length || l[playerIndex] == null) {
            return vanilla;
        }
        return l[playerIndex][part];
    }

    // Main thread, every frame (GameWindow.logic, also while paused).
    public static void update() {
        try {
            boolean menu = isPauseMenuShown();
            if (menu != menuOpen) {
                menuOpen = menu;
                Log.info("pause menu " + (menu ? "shown" : "hidden"));
            }
            boolean inGame = IngameState.instance != null && GameWindow.states != null
                && GameWindow.states.current == IngameState.instance;
            int players = IsoPlayer.numPlayers;
            boolean want = Options.spanMonitors && inGame && players > 1 && !menuOpen;
            // Act on a change only when it lasts: every switch resizes the window, recreates the
            // offscreen buffer and makes the window manager move things around.
            if (want != spanned) {
                if (want != pendingWant) {
                    pendingWant = want;
                    pendingFrames = 0;
                    Log.info("span " + (want ? "wanted" : "not wanted") + ": option=" + Options.spanMonitors
                        + " inGame=" + inGame + " players=" + players + " menu=" + menuOpen);
                }
                // The pause menu is laid out for the window it opens in: shrink at once, or the
                // menu flashes across all monitors for the debounce frames.
                if (++pendingFrames < DEBOUNCE_FRAMES && !(menuOpen && spanned)) {
                    return;
                }
            } else {
                pendingWant = want;
                pendingFrames = 0;
            }
            if (!want) {
                failedForPlayers = -1;
                if (spanned) {
                    unspan();
                }
                return;
            }
            if (!spanned) {
                if (failedForPlayers == players) {
                    return;
                }
                if (!span()) {
                    failedForPlayers = players;
                    return;
                }
            }
            int x = Display.getX(), y = Display.getY(), w = Core.width, h = Core.height;
            if (!layoutBuilt || layoutKey[0] != x || layoutKey[1] != y || layoutKey[2] != w
                || layoutKey[3] != h || layoutKey[4] != players) {
                layoutKey[0] = x;
                layoutKey[1] = y;
                layoutKey[2] = w;
                layoutKey[3] = h;
                layoutKey[4] = players;
                layoutBuilt = true;
                layout = buildLayout(x, y, w, h, players);
                layoutChangedFrames = 0;
                Log.info("window " + w + "x" + h + " at " + x + "," + y + ", layout for " + players
                    + " players: " + describe(layout));
            }
            notifyLayout();
            if (++monitorCheck >= MONITOR_CHECK_FRAMES) {
                monitorCheck = 0;
                checkMonitors();
            }
            checkPlacement(x, y, w, h);
        } catch (Throwable t) {
            Log.error("multi-monitor update failed, option turned off", t);
            Options.spanMonitors = false;
            layout = null;
        }
    }

    // Vanilla re-places UI that follows getPlayerScreen* (gamepad players' inventory, equipped
    // items, character info, dashboard...) only on OnResolutionChange, which Core.setScreenSize
    // fires before this class has the new layout, and never when only the window position
    // changes. Fire it again once the layout has settled.
    private static void notifyLayout() {
        if (layoutChangedFrames < 0 || ++layoutChangedFrames < LAYOUT_EVENT_FRAMES) {
            return;
        }
        layoutChangedFrames = -1;
        int w = Core.width, h = Core.height;
        LuaEventManager.triggerEvent("OnResolutionChange", w, h, w, h);
    }

    // Monitors added, removed or changed (resolution, scale): span over the new set.
    private static void checkMonitors() {
        boolean[] changed = new boolean[1];
        RenderThread.invokeOnRenderContext(() -> {
            int[][] found = sortedMonitors(queryMonitors(false));
            if (found.length >= 2 && !sameMonitors(found, monitors)) {
                changed[0] = true;
                Log.info("monitors changed");
                queryMonitors(true);
                placeOnRenderThread(found);
            }
        });
        if (changed[0]) {
            Core.initialWidth = Math.max(restoreInitialW, spanW);
            Core.initialHeight = Math.max(restoreInitialH, spanH);
            snappedGeometry[2] = -1;
            layoutBuilt = false;
        }
    }

    private static boolean sameMonitors(int[][] a, int[][] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!java.util.Arrays.equals(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }

    // A window that stays off the target (dragged, or kept by the window manager) is put back
    // once per position it settles at.
    private static void checkPlacement(int x, int y, int w, int h) {
        if (x == spanX && y == spanY && w == spanW && h == spanH) {
            stableFrames = 0;
            warned = false;
            return;
        }
        if (seenGeometry[0] != x || seenGeometry[1] != y || seenGeometry[2] != w || seenGeometry[3] != h) {
            seenGeometry[0] = x;
            seenGeometry[1] = y;
            seenGeometry[2] = w;
            seenGeometry[3] = h;
            stableFrames = 0;
            return;
        }
        if (++stableFrames != SNAP_FRAMES) {
            return;
        }
        boolean again = snappedGeometry[0] == x && snappedGeometry[1] == y && snappedGeometry[2] == w
            && snappedGeometry[3] == h;
        if (again) {
            if (!warned) {
                warned = true;
                Log.warn("the window manager keeps the window at " + w + "x" + h + " " + x + "," + y
                    + " instead of " + spanW + "x" + spanH + " " + spanX + "," + spanY
                    + "; on a tiling WM make it floating");
            }
            return;
        }
        System.arraycopy(seenGeometry, 0, snappedGeometry, 0, 4);
        Log.info("window at " + w + "x" + h + " " + x + "," + y + ", moving it back over the monitors");
        RenderThread.invokeOnRenderContext(() -> {
            long window = Display.getWindow();
            if (window != 0L) {
                GLFW.glfwSetWindowMonitor(window, 0L, spanX, spanY, spanW, spanH, GLFW.GLFW_DONT_CARE);
            }
        });
    }

    private static boolean span() {
        boolean[] ok = new boolean[1];
        RenderThread.invokeOnRenderContext(() -> ok[0] = spanOnRenderThread());
        if (ok[0]) {
            spanned = true;
            monitorCheck = 0;
            layout = null;
            layoutBuilt = false;
            stableFrames = 0;
            warned = false;
            snappedGeometry[2] = -1;
            // Keep the world scale (MultiTextureFBO2.getDisplayZoom).
            restoreInitialW = Core.initialWidth;
            restoreInitialH = Core.initialHeight;
            Core.initialWidth = Math.max(Core.initialWidth, spanW);
            Core.initialHeight = Math.max(Core.initialHeight, spanH);
        }
        return ok[0];
    }

    private static boolean spanOnRenderThread() {
        long window = Display.getWindow();
        if (window == 0L) {
            return false;
        }
        boolean fullscreen = Display.isFullscreen();
        int w = Display.getWidth();
        int h = Display.getHeight();
        // Leave exclusive fullscreen first: until then the monitor reports the game's video mode.
        if (fullscreen) {
            GLFW.glfwSetWindowMonitor(window, 0L, 0, 0, w, h, GLFW.GLFW_DONT_CARE);
        }
        int[][] sorted = sortedMonitors(queryMonitors(true));
        if (sorted.length < 2) {
            Log.info("one monitor found, not spanning the window");
            if (fullscreen) {
                GLFW.glfwSetWindowMonitor(window, GLFW.glfwGetPrimaryMonitor(), 0, 0, w, h, GLFW.GLFW_DONT_CARE);
            }
            return false;
        }
        restoreW = w;
        restoreH = h;
        restoreFullscreen = fullscreen;
        GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
        // A window with a fixed size: the player can't resize it by accident.
        GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        placeOnRenderThread(sorted);
        if (WINDOWS) {
            // Windows hides the taskbar only for a window that covers its monitor and is in
            // front; the taskbars of the other monitors stay on top of a spanning window. Keep
            // the game topmost while it has focus.
            if (!focusListeners) {
                focusListeners = true;
                Display.addFocusGainedListener(() -> setTopmost(true));
                Display.addFocusLostListener(() -> setTopmost(false));
            }
            setTopmost(GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE);
        }
        Log.info("was " + w + "x" + h + (fullscreen ? " fullscreen" : ""));
        return true;
    }

    // Moves the window over the bounding box of the monitors.
    private static void placeOnRenderThread(int[][] sorted) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int[] m : sorted) {
            minX = Math.min(minX, m[0]);
            minY = Math.min(minY, m[1]);
            maxX = Math.max(maxX, m[0] + m[2]);
            maxY = Math.max(maxY, m[1] + m[3]);
        }
        spanX = minX;
        spanY = minY;
        spanW = maxX - minX;
        spanH = maxY - minY;
        monitors = sorted;
        long window = Display.getWindow();
        if (window != 0L) {
            GLFW.glfwSetWindowMonitor(window, 0L, spanX, spanY, spanW, spanH, GLFW.GLFW_DONT_CARE);
        }
        Log.info("window spans " + sorted.length + " monitors: " + spanW + "x" + spanH + " at " + spanX + "," + spanY);
    }

    private static int[][] sortedMonitors(List<int[]> found) {
        int[][] sorted = found.toArray(new int[0][]);
        sortReadingOrder(sorted);
        return sorted;
    }

    private static void setTopmost(boolean on) {
        long window = Display.getWindow();
        if (window != 0L) {
            GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_FLOATING, on && spanned ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        }
    }

    private static void unspan() {
        spanned = false;
        layout = null;
        layoutBuilt = false;
        Core.initialWidth = restoreInitialW;
        Core.initialHeight = restoreInitialH;
        RenderThread.invokeOnRenderContext(() -> {
            long window = Display.getWindow();
            if (window != 0L) {
                GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_FLOATING, GLFW.GLFW_FALSE);
                GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
                Display.setBorderlessWindow(Core.getInstance().getOptionBorderlessWindow());
            }
        });
        restoring = true;
        try {
            // Through vanilla, so borderless / fullscreen / centring are as from the options.
            Core.setDisplayMode(restoreW, restoreH, restoreFullscreen);
        } finally {
            restoring = false;
        }
        Log.info("window restored to " + restoreW + "x" + restoreH + (restoreFullscreen ? " fullscreen" : ""));
    }

    // Core.setDisplayModeInternal OnEnter: true = skip vanilla.
    public static boolean beforeSetDisplayMode(int width, int height, boolean fullscreen) {
        if (!spanned || restoring) {
            return false;
        }
        // Applying any option re-applies the current resolution, which is the spanned window.
        if (width == Core.width && height == Core.height || width == spanW && height == spanH) {
            return true;
        }
        restoreW = width;
        restoreH = height;
        restoreFullscreen = fullscreen;
        Log.info("display mode " + width + "x" + height + (fullscreen ? " fullscreen" : "")
            + " kept for after the monitor span");
        return true;
    }

    // Core.saveOptions: write the resolution from the options, not the spanned window.
    private static int savedW, savedH;

    public static void beforeSaveOptions() {
        if (spanned) {
            savedW = Core.width;
            savedH = Core.height;
            Core.width = restoreW;
            Core.height = restoreH;
        }
    }

    public static void afterSaveOptions() {
        if (spanned && savedW != 0) {
            Core.width = savedW;
            Core.height = savedH;
        }
        savedW = savedH = 0;
    }

    // Render thread, Core.DoStartFrameStuffInternal OnExit. Vanilla sets the player's viewport
    // and scissor in the offscreen buffer to the window half/quarter (w = screenW / 2,
    // x = w * (player % 2), y = h for players 2-3; a text frame is flipped:
    // y = screenH - h - y) and pushes ortho(0, w * zoom, h * zoom, 0). Redo it with the
    // player's rectangle. The fx variant scales the caller's size the same way.
    public static void afterStartFrame(Core core, int w, int h, float zoom, int player,
                                       boolean textFrame, boolean fx, boolean smartTexture) {
        int[][] l = layout;
        if (l == null || smartTexture || player < 0 || player >= l.length || l[player] == null) {
            return;
        }
        try {
            int[] r = l[player];
            int screenW = core.getScreenWidth(), screenH = core.getScreenHeight();
            if (screenW <= 0 || screenH <= 0) {
                return;
            }
            int pw = fx ? (int) ((long) w * r[2] / screenW) : r[2];
            int ph = fx ? (int) ((long) h * r[3] / screenH) : r[3];
            int tw = fx && !textFrame ? r[2] : pw;
            int th = fx && !textFrame ? r[3] : ph;
            int x = r[0];
            int y = textFrame ? screenH - th - r[1] : r[1];
            Matrix4f projection = core.projectionMatrixStack.peek();
            if (projection != null) {
                if (textFrame) {
                    projection.setOrtho2D(0.0F, pw, ph, 0.0F);
                } else {
                    projection.setOrtho2D(0.0F, pw * zoom, ph * zoom, 0.0F);
                }
            }
            GL11.glViewport(x, y, tw, th);
            GL11.glScissor(x, y, tw, th);
        } catch (Throwable t) {
            Log.error("multi-monitor viewport failed, option turned off", t);
            Options.spanMonitors = false;
            layout = null;
        }
    }

    // Render thread, Core.DoStartFrameNoZoom OnExit (FBORenderChunkManager.endFrame draws the
    // chunk textures through it). Without the combined FBO (debug option fboRenderChunk
    // combinedFbo off) vanilla halves w / h like DoStartFrameStuffInternal and pushes
    // ortho(0, w, h, 0); the viewport is already at IsoCamera.getScreenLeft/Top.
    public static void afterStartFrameNoZoom(Core core, int w, int h, int player, boolean fx, boolean smartTexture) {
        int[][] l = layout;
        if (l == null || smartTexture || player < 0 || player >= l.length || l[player] == null) {
            return;
        }
        try {
            if (DebugOptions.instance.fboRenderChunk.combinedFbo.getValue()) {
                return;
            }
            int[] r = l[player];
            int screenW = core.getScreenWidth(), screenH = core.getScreenHeight();
            if (screenW <= 0 || screenH <= 0) {
                return;
            }
            int pw = fx ? (int) ((long) w * r[2] / screenW) : r[2];
            int ph = fx ? (int) ((long) h * r[3] / screenH) : r[3];
            Matrix4f projection = core.projectionMatrixStack.peek();
            if (projection != null) {
                projection.setOrtho2D(0.0F, pw, ph, 0.0F);
            }
            GL11.glViewport(r[0], r[1], pw, ph);
            GL11.glScissor(r[0], r[1], pw, ph);
        } catch (Throwable t) {
            Log.error("multi-monitor chunk viewport failed, option turned off", t);
            Options.spanMonitors = false;
            layout = null;
        }
    }

    // Render thread, WorldItemAtlas.RenderJob.restoreScreenStencil OnExit: vanilla restores the
    // scissor to a quarter/half of the offscreen texture.
    public static void afterRestoreScreenStencil() {
        int[][] l = layout;
        if (l == null) {
            return;
        }
        int player = SpriteRenderer.instance.getRenderingPlayerIndex();
        if (player < 0 || player >= l.length || l[player] == null) {
            return;
        }
        int[] r = l[player];
        GL11.glScissor(r[0], r[1], r[2], r[3]);
    }

    // UIManager.resize OnExit: vanilla puts moodles at the right edge of the window halves
    // (sw / 2 for player 0 or 2) and players 2-3 at sh / 2. Move each column by the same offset
    // to its player's screen.
    public static void afterUiResize() {
        int[][] l = layout;
        if (l == null) {
            return;
        }
        try {
            int sh = Core.getInstance().getScreenHeight();
            for (int i = 0; i < UIManager.MoodleUI.length && i < 4; i++) {
                MoodlesUI ui = UIManager.MoodleUI[i];
                if (ui == null || l[i] == null) {
                    continue;
                }
                int vanillaTop = i >= 2 ? sh / 2 : 0;
                ui.setX(l[i][0] + l[i][2] - (10.0 + ui.getWidth()));
                ui.setY(l[i][1] + (ui.getY() - vanillaTop));
            }
            if (UIManager.speedControls != null && l[0] != null) {
                UIManager.speedControls.setX(l[0][0] + l[0][2] - UIManager.speedControls.width - 10.0F);
            }
        } catch (Throwable t) {
            Log.error("multi-monitor UI layout failed", t);
        }
    }

    private static List<int[]> queryMonitors(boolean log) {
        List<int[]> out = new ArrayList<>();
        PointerBuffer handles = GLFW.glfwGetMonitors();
        if (handles == null) {
            return out;
        }
        int[] x = new int[1], y = new int[1];
        for (int i = 0; i < handles.limit(); i++) {
            long monitor = handles.get(i);
            GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
            if (mode == null) {
                continue;
            }
            GLFW.glfwGetMonitorPos(monitor, x, y);
            out.add(new int[] {x[0], y[0], mode.width(), mode.height()});
            if (log) {
                Log.info("monitor " + GLFW.glfwGetMonitorName(monitor) + ": " + mode.width() + "x" + mode.height()
                    + " at " + x[0] + "," + y[0]);
            }
        }
        return out;
    }

    // Rows first (monitors whose vertical ranges overlap share a row), then left to right, so a
    // 2x2 wall numbers players as vanilla's grid does.
    private static void sortReadingOrder(int[][] m) {
        for (int i = 1; i < m.length; i++) {
            for (int j = i; j > 0 && before(m[j], m[j - 1]); j--) {
                int[] t = m[j];
                m[j] = m[j - 1];
                m[j - 1] = t;
            }
        }
    }

    private static boolean before(int[] a, int[] b) {
        boolean sameRow = a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
        return sameRow ? a[0] < b[0] : a[1] < b[1];
    }

    // The part of each monitor the window covers, in window pixels. Players go to those parts in
    // order; with more players than parts the first parts take the extra players and are split as
    // vanilla splits the window (halves, then 2x2). Null when the window covers no monitor.
    private static int[][] buildLayout(int wx, int wy, int ww, int wh, int players) {
        List<int[]> parts = new ArrayList<>();
        for (int[] m : monitors) {
            int left = Math.max(m[0], wx), top = Math.max(m[1], wy);
            int right = Math.min(m[0] + m[2], wx + ww), bottom = Math.min(m[1] + m[3], wy + wh);
            if (right - left >= MIN_VISIBLE && bottom - top >= MIN_VISIBLE) {
                parts.add(new int[] {left - wx, top - wy, right - left, bottom - top});
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        int[][] out = new int[4][];
        int count = parts.size();
        int player = 0;
        for (int k = 0; k < count && player < players; k++) {
            int share = Math.max(1, players / count + (k < players % count ? 1 : 0));
            for (int s = 0; s < share && player < 4; s++, player++) {
                out[player] = part(parts.get(k), share, s);
            }
        }
        return out;
    }

    private static int[] part(int[] m, int share, int s) {
        if (share == 1) {
            return m.clone();
        }
        int halfW = m[2] / 2, halfH = m[3] / 2;
        int col = s % 2, row = share == 2 ? 0 : s / 2;
        int h = share == 2 ? m[3] : halfH;
        return new int[] {m[0] + col * halfW, m[1] + row * halfH, halfW, h};
    }

    private static String describe(int[][] l) {
        if (l == null) {
            return "none (the window covers no monitor)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.length; i++) {
            if (l[i] != null) {
                sb.append(" p").append(i).append('=').append(l[i][2]).append('x').append(l[i][3])
                    .append('@').append(l[i][0]).append(',').append(l[i][1]);
            }
        }
        return sb.toString().trim();
    }
}
