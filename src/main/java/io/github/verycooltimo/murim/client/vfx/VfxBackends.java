package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/** Single-backend seam kept for the shockwave/rainfall showcase commands. */
public final class VfxBackends {
    private static final VfxBackend OURS = new QiShockwaveVfx();
    private static VfxBackend active = OURS;

    public static String activeId() { return active.id(); }

    /** Only our own layer remains: the AAA Particles pilot was dropped (author 13.10: no visible gain). */
    public static boolean select(String id) {
        return "ours".equals(id);
    }

    public static void shockwave(ClientLevel level, Vec3 ground) {
        if (active == OURS) { OURS.shockwave(level, ground); return; }
        try {
            String reason = active.unavailableReason();
            if (reason == null && active.shockwave(level, ground)) { return; }
            fallback(reason == null ? "command.murim.vfx.asset_failed" : reason);
        } catch (LinkageError | RuntimeException ex) {
            MurimMod.LOGGER.warn("Murim AAA shockwave failed; using ours", ex);
            fallback("command.murim.vfx.failed");
        }
        OURS.shockwave(level, ground);
    }

    /** Command showcase; emits an honest approximation if AAA is unavailable. */
    public static void rainfall(ClientLevel level, Vec3 ground) {
        if (!rainfallAaa(level, ground)) { OURS.rainfall(level, ground); }
    }

    /** Technique hook: preserve its existing VFX when AAA was not selected or failed. */
    static boolean rainfallAaa(ClientLevel level, Vec3 ground) {
        return false;
    }

    static void reset() { active = OURS; }

    private static void fallback(String reason) {
        active = OURS;
        var player = Minecraft.getInstance().player;
        if (player != null) { player.displayClientMessage(Component.translatable(reason), false); }
    }

    private VfxBackends() { }
}
