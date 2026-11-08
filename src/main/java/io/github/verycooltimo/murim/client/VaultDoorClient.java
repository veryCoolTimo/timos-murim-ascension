package io.github.verycooltimo.murim.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Client side of the vault door: the leaves part, the camera shakes once, harder the nearer you stand. */
public final class VaultDoorClient {

    private VaultDoorClient() {
    }

    public static void shake(BlockPos door) {
        var p = Minecraft.getInstance().player;
        if (p == null) {
            return;
        }
        double d = Math.sqrt(p.distanceToSqr(door.getX() + 0.5D, door.getY() + 0.5D, door.getZ() + 0.5D));
        if (d < 36.0D) {
            CameraShakeHandler.quake((float) (0.75D * (1.0D - d / 36.0D) + 0.1D), 22);
        }
    }
}
