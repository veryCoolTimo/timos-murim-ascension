package io.github.verycooltimo.murim.library;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * A crude model of a first visit to the archive, to measure the roadmap's question (docs/design/22-roadmap-pipeline.md
 * M3: "98/2 is not a probability of progress — measure time to the useful find"). Pure: the plan says where every
 * book is; the record barrels' chance of a genuine page is passed in (the GameTest measures it on the real loot table).
 *
 * <p>Visitor model (docs/design/25-ruined-library.md §5): down the tunnel (12 s); the record barrel by the doorway is
 * opened with p = 0.85 (5 s) and the keeper's note read (12 s). Not knowing where to look, a visitor checks 4..16
 * random shelves per tier (2 s each + 0.8 s per volume pulled, the first three junk volumes read in full, 10 s each);
 * knowing, 0..4. Each tier's barrel is opened with p = 0.6 (0.3 when informed). Stairs to the next tier: 15 s.
 * At the bottom an informed visitor counts the shelves and pulls the book (25 s); an uninformed one keeps checking
 * bottom shelves, and when a check lands on the propped unit notices the wedge with p = 0.6.
 */
public final class LibrarySim {

    public enum Source { SHELF, BARREL, PROPPED, NONE }

    /** One visit: seconds until the first genuine manual, where it came from, volumes pulled on the way. */
    public record Visit(double seconds, Source source, int pulled, boolean informed) {
    }

    /** Distribution over many archives. */
    public record Summary(int runs, double p50, double p90, double max, double[] sourceShare, double shelfGenuineMean,
                          double shelvesWithGenuine, double booksMean, double pulledMean, double informedShare) {
        public String describe() {
            return String.format(java.util.Locale.ROOT,
                    "runs=%d p50=%.0fs p90=%.0fs max=%.0fs | first find: propped %.1f%% shelf %.1f%% barrel %.1f%% none %.1f%%"
                            + " | volumes per archive %.0f, genuine on shelves %.2f (archives with any %.1f%%)"
                            + " | pulled before the find %.1f, read the keeper's note %.1f%%",
                    runs, p50, p90, max, sourceShare[Source.PROPPED.ordinal()] * 100, sourceShare[Source.SHELF.ordinal()] * 100,
                    sourceShare[Source.BARREL.ordinal()] * 100, sourceShare[Source.NONE.ordinal()] * 100,
                    booksMean, shelfGenuineMean, shelvesWithGenuine * 100, pulledMean, informedShare * 100);
        }
    }

    private static final double CAP = 1800.0D;

    public static Visit visit(LibraryPlan plan, Random r, double barrelGenuine) {
        double t = 12.0D;
        int pulled = 0;
        int readFull = 0;
        boolean informed = false;
        // Arrival on the top tier; the record barrel with the keeper's note is beside the doorway.
        if (r.nextDouble() < 0.85D) {
            t += 5.0D;
            informed = true;
            t += 12.0D;
            if (r.nextDouble() < barrelGenuine) {
                return new Visit(t, Source.BARREL, pulled, true);
            }
        }
        for (int tier = LibraryPlan.TIERS.length - 1; tier >= 0; tier--) {
            List<LibraryPlan.Shelf> here = new ArrayList<>();
            for (LibraryPlan.Shelf s : plan.shelves()) {
                if (s.tier() == tier) {
                    here.add(s);
                }
            }
            int checks = informed ? r.nextInt(5) : 4 + r.nextInt(13);
            boolean bottom = tier == 0;
            if (bottom && !informed) {
                checks = Integer.MAX_VALUE;
            }
            int[] propped = plan.proppedCell();
            for (int c = 0; c < checks && !here.isEmpty() && t < CAP; c++) {
                LibraryPlan.Shelf s = here.remove(r.nextInt(here.size()));
                t += 2.0D;
                for (LibraryPlan.Slot slot : s.slots()) {
                    if (slot == null) {
                        continue;
                    }
                    pulled++;
                    t += 0.8D;
                    if (slot.useful()) {
                        return new Visit(t, Source.SHELF, pulled, informed);
                    }
                    if (readFull < 3) {
                        readFull++;
                        t += 10.0D;
                    }
                }
                // Checking a shelf of the propped unit: the wedged book shows under it.
                if (bottom && Math.abs(s.ax() - propped[0]) + Math.abs(s.az() - propped[1]) <= 1 && r.nextDouble() < 0.6D) {
                    return new Visit(t + 6.0D, Source.PROPPED, pulled, informed);
                }
            }
            if (r.nextDouble() < (informed ? 0.3D : 0.6D) && tier != LibraryPlan.TIERS.length - 1) {
                t += 4.0D;
                if (r.nextDouble() < barrelGenuine) {
                    return new Visit(t, Source.BARREL, pulled, informed);
                }
            }
            if (!bottom) {
                t += 15.0D;
            }
        }
        if (informed) {
            return new Visit(t + 25.0D, Source.PROPPED, pulled, true);
        }
        return new Visit(Math.min(t, CAP), t < CAP ? Source.PROPPED : Source.NONE, pulled, false);
    }

    /** {@code runs} archives with seeds from {@code seed}, one visit each. */
    public static Summary run(long seed, int runs, double barrelGenuine) {
        Random seeds = new Random(seed);
        double[] times = new double[runs];
        double[] share = new double[Source.values().length];
        double shelfGenuine = 0;
        int withGenuine = 0;
        double books = 0;
        double pulled = 0;
        int informed = 0;
        for (int i = 0; i < runs; i++) {
            LibraryPlan plan = LibraryPlan.plan(seeds.nextLong());
            Visit v = visit(plan, new Random(seeds.nextLong()), barrelGenuine);
            times[i] = v.seconds();
            share[v.source().ordinal()]++;
            int g = plan.genuineOnShelves();
            shelfGenuine += g;
            withGenuine += g > 0 ? 1 : 0;
            books += plan.books();
            pulled += v.pulled();
            informed += v.informed() ? 1 : 0;
        }
        Arrays.sort(times);
        for (int i = 0; i < share.length; i++) {
            share[i] /= runs;
        }
        return new Summary(runs, times[runs / 2], times[(int) (runs * 0.9)], times[runs - 1], share, shelfGenuine / runs,
                (double) withGenuine / runs, books / runs, pulled / runs, (double) informed / runs);
    }

    private LibrarySim() {
    }
}
