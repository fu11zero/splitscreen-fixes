package dev.splitscreenfixes;

import zombie.IndieGL;
import zombie.characters.IsoPlayer;

// Client: the see-through circle in trees for every splitscreen player.
//
// Vanilla IsoCell.drawStencilMask clears depth and stencil (glClear) and stamps
// media/mask_transparency_player.png into stencil bit 128 around the player (and a cursor
// mask while aiming); trees are drawn faded where the bit is set. It ends with
// glStencilMask(127), and glClear only clears the bits the stencil write mask allows. Player
// 0's clear runs after the UI frame (Core.StartFrameUI sets glStencilMask(255)), but player
// 1's runs right after player 0's stamp with the mask still at 127: bit 128 is never cleared
// on player 1's part of the offscreen buffer (the UI clears its own FBO). Every stamp adds to
// the previous ones; the circle grows into a stencil hole over the whole screen, wherever the
// player walked, and the dithered see-through dots are lost (seen in a stencil readback:
// player 0 ~234k px, player 1 700k-900k px of 993k).
//
// Patch: before the stamp in splitscreen, set the stencil write mask to 255 so the clear
// covers bit 128 for every player.
public final class TreeStencilMask {
    private TreeStencilMask() {}

    public static void beforeDrawStencilMask() {
        if (IsoPlayer.numPlayers < 2) {
            return;
        }
        IndieGL.glStencilMask(255);
    }
}
