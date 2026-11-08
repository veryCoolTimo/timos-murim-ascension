package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.sect.SectSchedule;
import io.github.verycooltimo.murim.sect.SectStanding;

import java.util.ArrayList;
import java.util.List;

/**
 * What the tablet may show of the road ahead, and the tables the screen draws from: the rungs a player holds and the next
 * one (nothing higher), the ladder itself, the icon of each part of the day and the tabs. The ladder and the day are built
 * from {@link SectStanding} and {@link SectSchedule.Period}, so a rung or a period added there reaches the tablet without a
 * second list to keep in step (docs/adr/0016). Plain arithmetic with no client types, so it is tested headless.
 */
public final class SectTabletView {
    /** The offices above the standings, from the lowest: first disciple, elder. */
    private static final List<String> OFFICES = List.of("first", "elder");
    /** Rungs of the ladder from the bottom: the standings, then the offices. */
    public static final int RUNGS = SectStanding.values().length + OFFICES.size();

    /** The tabs of the slab, left to right: the id of the text {@code murim.tablet.tab.<id>} and of the icon. */
    public enum Tab {
        TODAY("sunrise"), GOAL("flag"), CONTRACTS("scroll"), STANDING("hall"), MAP("mountain");

        private final String icon;

        Tab(String icon) {
            this.icon = icon;
        }

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public String icon() {
            return icon;
        }
    }

    private SectTabletView() { }

    /** The rung a player stands on: his office when he holds one, otherwise his standing (0..4). */
    public static int held(int standing, int position) {
        int top = SectStanding.values().length - 1;
        return position > 0 ? top + Math.min(position, OFFICES.size()) : Math.max(0, Math.min(top, standing));
    }

    /** The highest rung that is drawn: the held one and the next above it. */
    public static int top(int standing, int position) {
        return Math.min(RUNGS - 1, held(standing, position) + 1);
    }

    /**
     * The ladder from the bottom: the id of each rung, the standings first. The id names {@code murim.tablet.rank.<id>}, and
     * for a standing also its emblem sprite {@code emblem_<id>}.
     */
    public static List<String> ladder() {
        List<String> out = new ArrayList<>();
        for (SectStanding s : SectStanding.values()) out.add(s.id());
        out.addAll(OFFICES);
        return out;
    }

    /** True for the rungs that are standings (they have an emblem); the offices above them have an icon instead. */
    public static boolean standing(int rung) {
        return rung < SectStanding.values().length;
    }

    /** The icon of a part of the day. An exhaustive switch: a new period does not compile until it has an icon. */
    public static String periodIcon(SectSchedule.Period period) {
        return switch (period) {
            case FORMATION -> "sunrise";
            case BREAKFAST -> "bowl";
            case TRAINING -> "post";
            case DINNER -> "steam_bowl";
            case EVENING -> "moon";
            case NIGHT -> "moon_stars";
        };
    }
}
