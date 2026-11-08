package io.github.verycooltimo.murim.sect;

import net.minecraft.network.chat.Component;

import java.util.Set;

/**
 * The short role of a sect person in words a newcomer can use ("Teacher", "Healer"): what the name plate and the guidance
 * arrow say after the name. The long titles of the dialogue screen stay where they are. Lang keys {@code murim.role.<role>}
 * and, for the few people everybody asks for by their post, {@code murim.role.person.<key>}.
 */
public final class SectRoleNames {
    /** The people whose post is told by name, not by the generation (the leader, the elders, the teacher, the senior). */
    public static final Set<String> PERSONAL = Set.of("tae_hwi", "tae_gyun", "tae_rok", "tae_seong", "gyeong_cho", "gyeong_tak",
            "gyeong_pil", "seo_rang", "gatekeeper");

    private SectRoleNames() { }

    /** The lang key of the short role of the person {@code key} (empty: by role) with that role. */
    public static String key(String key, SectRole role) {
        return PERSONAL.contains(key) ? "murim.role.person." + key : "murim.role." + role.id();
    }

    public static Component of(String key, SectRole role) {
        return Component.translatable(key(key, role));
    }

    /** The roster person {@code key}, whether or not he is standing on the mountain now. */
    public static Component of(String key) {
        SectRole role = SectRoster.of(key).map(SectRoster::role).orElse(SectRole.DISCIPLE);
        return of(key, role);
    }
}
