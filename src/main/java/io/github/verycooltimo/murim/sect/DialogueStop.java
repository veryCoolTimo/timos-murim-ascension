package io.github.verycooltimo.murim.sect;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The body stops when a talk opens (author 22.10: a player who did not stop before the talk kept running through the cutscene):
 * no sprint, no horizontal momentum, no remembered walk input; the vertical speed stays, so a fall goes on. Common code, so the GameTest can reach it.
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
        // The talk camera is another camera entity, and while the player is not the camera vanilla stops copying the
        // keyboard into xxa/zza (LocalPlayer#serverAiStep, isControlledCamera): the last «forward» only fades by 2 % a tick
        // and walks the player on for 4-5 blocks (measured 11.11: 0.046 blocks a tick with the talk open).
        // API: reference/minecraft-src/net/minecraft/client/player/LocalPlayer.java#serverAiStep
        player.xxa = 0.0F;
        player.zza = 0.0F;
        player.setJumping(false);
        player.hurtMarked = true;
    }
}
