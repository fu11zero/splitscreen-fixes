package dev.splitscreenfixes;

import java.lang.reflect.Method;
import zombie.CombatManager;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;

// Vanilla CombatManager.update(boolean) runs updateReticle and highlightTargets for
// IsoPlayer.players[0] only. For the other local players the aiming hit list is never computed
// while they aim: hasTarget stays false (AimingMode never becomes *_TARGET_FOUND), the
// reticle gets no hit chance and stays wide open, no target colour, no melee outline, and the
// ballistics camera targets used by setAngleFromAim are not refreshed. Run the same per-player
// work for players 1..3.
public final class CombatReticle {
    private static final Method HIGHLIGHT_TARGETS = findHighlightTargets();
    private static boolean failed;

    private CombatReticle() {}

    private static Method findHighlightTargets() {
        try {
            Method m = CombatManager.class.getDeclaredMethod("highlightTargets", IsoPlayer.class);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            Log.error("CombatManager.highlightTargets not found, melee outline stays player 1 only", t);
            return null;
        }
    }

    public static void afterUpdate(CombatManager manager) {
        if (failed) {
            return;
        }
        IsoPlayer savedInstance = IsoPlayer.getInstance();
        IsoGameCharacter savedCamera = IsoCamera.getCameraCharacter();
        try {
            for (int i = 1; i < IsoPlayer.numPlayers && i < IsoPlayer.players.length; i++) {
                IsoPlayer player = IsoPlayer.players[i];
                if (player == null || !player.isLocalPlayer() || player.isDead() || !player.isWeaponReady()) {
                    continue;
                }
                // Same context as IsoPlayer.update gives each local player.
                IsoPlayer.setInstance(player);
                IsoCamera.setCameraCharacter(player);
                manager.updateReticle(player);
                if (HIGHLIGHT_TARGETS != null) {
                    HIGHLIGHT_TARGETS.invoke(manager, player);
                }
            }
        } catch (Throwable t) {
            failed = true;
            Log.error("reticle update for splitscreen players failed, disabled", t);
        } finally {
            IsoPlayer.setInstance(savedInstance);
            IsoCamera.setCameraCharacter(savedCamera);
        }
    }
}
