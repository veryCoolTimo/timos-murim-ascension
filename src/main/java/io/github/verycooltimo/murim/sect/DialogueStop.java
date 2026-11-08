package io.github.verycooltimo.murim.sect;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The body stops when a talk opens (author 22.10: a player who did not stop before the talk kept running through the cutscene):
 * no sprint, no horizontal momentum; the vertical speed stays, so a fall goes on. Common code, so the GameTest can reach it.
 */
public final class DialogueStop {

    private DialogueStop() {
    }

    public static void stop(Player player) {
        if (player == null) {
            return;
        }
        player.setSprinting(false);
        Vec3 v = player.getDeltaMovement();
        player.setDeltaMovement(0.0D, v.y, 0.0D);
        player.hurtMarked = true;
    }
}
