package io.github.verycooltimo.murim.client.vfx;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/** Client-only pilot seam; no optional-library types cross this boundary. */
public interface VfxBackend {
    String id();
    /** Translation key, or null when usable. Rechecked before every emission. */
    default String unavailableReason() { return null; }
    /** False means no effect was emitted and the caller should use ours. */
    boolean shockwave(ClientLevel level, Vec3 ground);
    boolean rainfall(ClientLevel level, Vec3 ground);
}
