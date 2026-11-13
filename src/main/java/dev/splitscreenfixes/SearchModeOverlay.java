package dev.splitscreenfixes;

import java.lang.reflect.Field;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.weather.WeatherShader;

// The search mode (foraging) overlay is drawn by media/shaders/screen.frag when
// MultiTextureFBO2.render puts each player's view on the screen with the weather shader. The
// shader finds the visible circle from gl_FragCoord minus SearchMode.zw, which
// WeatherShader.startMainThread sets to IsoCamera.getOffscreenLeft/Top(player). The drunk and
// glasses blur circles use the same offsets.
//
// That pass runs with Core.glDoStartFrame(width, height, 1, -1): player -1 sets an ortho
// projection over the window but leaves the viewport as the previous pass left it, and
// gl_FragCoord counts from that viewport's framebuffer. So the player's screen does not start at
// getOffscreenLeft/Top in gl_FragCoord (Top is also measured from the top while gl_FragCoord.y
// grows upwards), and only player 0 reliably gets the circle; for the others it lands off their
// screen and the whole screen goes dark. A constant offset (the old patch used 0, 0) is right
// only for one viewport state.
//
// Patch: in splitscreen, startMainThread stores the player's screen in window units measured from
// the bottom-left (vars 8, 9); after startRenderThread uploads the uniforms, the render thread
// reads the actual viewport, maps that point through the ortho projection to gl_FragCoord and
// uploads SearchMode.zw again.
public final class SearchModeOverlay {
    private static final Field SEARCH_MODE_ID = field("searchModeId");
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
    private static String lastLogged = "";
    private static int logsLeft = 20;

    private SearchModeOverlay() {}

    private static Field field(String name) {
        try {
            Field f = WeatherShader.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            Log.warn("search mode: WeatherShader." + name + " not found, circle left vanilla: " + t);
            return null;
        }
    }

    // Main thread, after WeatherShader.startMainThread.
    public static void afterStartMainThread(TextureDraw texd, int playerIndex) {
        if (IsoPlayer.numPlayers < 2 || playerIndex < 0 || playerIndex >= 4 || SEARCH_MODE_ID == null) {
            return;
        }
        if (texd == null || texd.vars == null || texd.vars.length < 10) {
            return;
        }
        int screenH = Core.getInstance().getScreenHeight();
        texd.vars[8] = IsoCamera.getScreenLeft(playerIndex);
        texd.vars[9] = screenH - IsoCamera.getScreenTop(playerIndex) - IsoCamera.getScreenHeight(playerIndex);
    }

    // Render thread, after WeatherShader.startRenderThread (the weather shader is bound).
    public static void afterStartRenderThread(WeatherShader shader, TextureDraw texd) {
        Field id = SEARCH_MODE_ID;
        if (IsoPlayer.numPlayers < 2 || id == null || shader == null || texd == null
            || texd.vars == null || texd.vars.length < 10) {
            return;
        }
        try {
            int screenW = Core.getInstance().getScreenWidth();
            int screenH = Core.getInstance().getScreenHeight();
            if (screenW <= 0 || screenH <= 0) {
                return;
            }
            VIEWPORT.clear();
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
            float x = VIEWPORT.get(0) + texd.vars[8] * VIEWPORT.get(2) / screenW;
            float y = VIEWPORT.get(1) + texd.vars[9] * VIEWPORT.get(3) / screenH;
            GL20.glUniform4f(id.getInt(shader), texd.vars[6], texd.vars[7], x, y);
            if (texd.vars.length > 18 && texd.vars[18] > 0 && logsLeft > 0) {
                String line = "viewport " + VIEWPORT.get(0) + "," + VIEWPORT.get(1) + " " + VIEWPORT.get(2)
                    + "x" + VIEWPORT.get(3) + ", window " + screenW + "x" + screenH + ", screen from "
                    + (int) texd.vars[8] + "," + (int) texd.vars[9] + " -> offset " + (int) x + "," + (int) y;
                if (!line.equals(lastLogged)) {
                    lastLogged = line;
                    logsLeft--;
                    Log.info("search mode: " + line);
                }
            }
        } catch (Throwable t) {
            Log.warn("search mode: offset left vanilla: " + t);
        }
    }
}
