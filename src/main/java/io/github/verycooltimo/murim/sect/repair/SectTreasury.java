package io.github.verycooltimo.murim.sect.repair;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * The sect treasury as the rest of the mod sees it (docs/design/39-player-path-v2.md, «Ремонт секты и казна»): a number the tablet
 * shows, a chronicle of the big repairs, and the fees the sect keeps. It is the sect's money, not the player's: nothing the player
 * does costs it except what he breaks, and his purse is never touched. The numbers live in {@link RepairData}; the prices, the start
 * sum and the daily income in {@code data/murim/sect/treasury.json} ({@link RepairCosts}).
 */
public final class SectTreasury {
    private SectTreasury() {
    }

    /** The treasury in liang, rounded down. */
    public static int liang(MinecraftServer server) {
        return RepairCosts.liang(RepairData.get(server).treasury());
    }

    /** Fees a finished contract of this kind ({@code camp}, {@code escort}, {@code herbs}) brings the sect. */
    public static void contractDone(MinecraftServer server, String kind) {
        int wen = RepairCosts.shipped().contractWen(kind);
        if (wen > 0) {
            RepairData.get(server).credit(wen);
        }
    }

    /** The last {@code max} big repairs, newest first, as lines for the tablet. */
    public static List<Component> chronicle(MinecraftServer server, int max) {
        List<RepairData.Entry> all = RepairData.get(server).chronicle();
        List<Component> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < max; i--) {
            RepairData.Entry e = all.get(i);
            Component place = Component.translatable("murim.sect.place." + (e.place().isEmpty() ? "unknown" : e.place()));
            out.add(Component.translatable("murim.tablet.chronicle.repair", e.day(), place, e.blocks(), RepairCosts.liang(e.wen())));
        }
        return out;
    }
}
