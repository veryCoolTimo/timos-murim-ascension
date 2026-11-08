package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.SectService;
import io.github.verycooltimo.murim.sect.SectStanding;
import net.minecraft.server.level.ServerPlayer;

/** Scroll possession permits reading; standing gates only the elder's voluntary gift (author 26.10). */
public final class BreathingAccess {

    /** Elders who may pass the method on (docs 43: Tae Rok, Tae Gyun, Tae Seong). */
    public static final java.util.Set<String> ELDERS = java.util.Set.of("tae_rok", "tae_gyun", "tae_seong");

    public static boolean allowed(ServerPlayer player, CultivationMethod method) {
        return switch (method.access()) {
            case CultivationMethod.ACCESS_OPEN, CultivationMethod.ACCESS_ELDER, CultivationMethod.ACCESS_HEAD -> true;
            default -> false;
        };
    }

    /** The gate for the elder's offer only; never for reading a held scroll. */
    public static boolean trustedFirstRank(ServerPlayer player) {
        return player.getData(ModAttachments.PROFILE).rank() >= Realm.FIRST
                && SectService.state(player).member()
                && SectService.standing(player).atLeast(SectStanding.TRUSTED);
    }

    private BreathingAccess() {
    }
}
