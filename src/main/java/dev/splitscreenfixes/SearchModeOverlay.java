package dev.splitscreenfixes;

import zombie.characters.IsoPlayer;
import zombie.core.textures.TextureDraw;

// The search mode (foraging) overlay is drawn by media/shaders/screen.frag when
// MultiTextureFBO2.render puts each player's view on the screen with the weather shader. The
// shader finds the visible circle from gl_FragCoord minus SearchMode.zw, which
// WeatherShader.startMainThread sets to IsoCamera.getOffscreenLeft/Top(player). In splitscreen
// only player 0 (offset 0, 0) gets a circle around the character; for the others the circle
// lands outside their screen and the whole screen goes dark, so the shader's coordinates are
// relative to the player's own screen. The same offsets place the drunk
// and glasses blur circles.
//
// Patch: in splitscreen, after vanilla WeatherShader.startMainThread, use offset 0, 0 for every
// player, as for player 0 (checked in game: the circle is around each player's character).
public final class SearchModeOverlay {

    private SearchModeOverlay() {}

    public static void afterStartMainThread(TextureDraw texd, int playerIndex) {
        if (IsoPlayer.numPlayers < 2 || playerIndex <= 0 || playerIndex >= 4) {
            return;
        }
        if (texd == null || texd.vars == null || texd.vars.length < 10) {
            return;
        }
        texd.vars[8] = 0.0F;
        texd.vars[9] = 0.0F;
    }
}
