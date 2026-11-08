package io.github.verycooltimo.murim.sect;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * The name of a roster person as the world knows him now. After a sect war a dead person's slot is taken by a successor with a
 * new name ({@link SectWar#nameOf}); every text that points at a person by his roster key goes through here, so no line spells
 * the dead man's name.
 */
public final class SectNames {

    /** The current holder's name of this roster key (the original name when nobody replaced him, or off the server). */
    public static Component of(String key) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? original(key) : of(server.overworld(), key);
    }

    public static Component of(ServerLevel level, String key) {
        if (level != null && !key.isEmpty()) {
            Component heir = SectRoster.of(key).map(m -> SectWar.nameOf(level, m)).orElse(null);
            if (heir != null) {
                return heir;
            }
        }
        return original(key);
    }

    private static Component original(String key) {
        return Component.translatable("npc.murim." + key);
    }

    private SectNames() {
    }
}
