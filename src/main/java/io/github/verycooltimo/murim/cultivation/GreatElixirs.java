package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Author 31.10. Reuses the saved sect journal; no new save fields or client attachment are needed.
 * Requirements are sent by ProgressReport, assistance by FlowStart. Canon: Mount Hua 248; original game addition.
 */
public final class GreatElixirs {
    public static final int ASSIST_MERIT = 20;
    private static final String NEXT = "pill.breakthrough.target";
    private static final String HELP = "pill.assistance.first";

    private GreatElixirs() { }

    public static boolean ready(ServerPlayer p) {
        int rank = p.getData(ModAttachments.PROFILE).rank();
        return rank < Realm.MAX && p.getData(ModAttachments.SECT).entry(NEXT, -1) == rank + 1;
    }

    static void absorbed(ServerPlayer p) {
        int rank = p.getData(ModAttachments.PROFILE).rank();
        if (rank < Realm.MAX) p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).record(NEXT, rank + 1));
    }

    /** Called only on a successful breakthrough; an interrupted attempt keeps its waiver. */
    public static void consume(ServerPlayer p) {
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).record(NEXT, -1));
    }

    static boolean assisted(ServerPlayer p, PillState pills) {
        return pills.pending().size() == 1 && pills.pending().get(0).greatElixir()
                && p.getData(ModAttachments.SECT).entry(HELP, -1) == pills.first();
    }

    /** Dialogue action: the elder guides the elixir already eaten, before the player sits. */
    public static boolean assist(ServerPlayer p) {
        PillState pills = p.getData(ModAttachments.PILLS);
        var sect = p.getData(ModAttachments.SECT);
        if (!sect.member() || !pills.windowOpen(p.level().getGameTime()) || pills.pending().size() != 1
                || !pills.pending().get(0).greatElixir() || !p.getData(ModAttachments.CULTIVATION).seeded()
                || p.getData(ModAttachments.MEDITATION).active() || sect.merit() < ASSIST_MERIT) {
            PlayerJournal.refuse(p, Component.translatable("murim.pill.assist.need", ASSIST_MERIT));
            return false;
        }
        if (!assisted(p, pills)) {
            p.setData(ModAttachments.SECT, sect.spend(ASSIST_MERIT).record(HELP, pills.first()));
        }
        PlayerJournal.record(p, Component.translatable("murim.pill.assist.ready"));
        return true;
    }
}
