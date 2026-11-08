package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.cultivation.RankRequirements;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.sect.SectProgression;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.training.BodyRules;
import io.github.verycooltimo.murim.training.TrainingService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The text of {@code /murim progress}: what the real systems say about a player's arts and about the next breakthrough.
 * Architecture: docs/adr/progress-command.md. Read only: nothing here changes a save. Every line is a translatable component (en_us and ru_ru).
 */
public final class ProgressReport {

    private static final String K = "command.murim.progress.";
    /** A source whose last gain is older than this (one Minecraft day) is not "currently" giving nothing. */
    private static final long STALE_TICKS = 24000L;

    private ProgressReport() {
    }

    private static MutableComponent t(String key, Object... args) {
        return Component.translatable(K + key, args);
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, v > 0 && v < 0.1D ? "%.3f" : "%.1f", v);
    }

    private static Component ago(long ticks) {
        long s = Math.max(0, ticks) / 20;
        if (s < 90) return t("ago.s", s);
        if (s < 5400) return t("ago.min", s / 60);
        return t("ago.h", String.format(Locale.ROOT, "%.1f", s / 3600.0D));
    }

    // ------------------------------------------------------------------ arts

    /** Does the art match the user's filter (a part of its id or of its name)? */
    static boolean matches(ResourceLocation art, String filter) {
        if (filter == null || filter.isBlank()) return true;
        String f = filter.toLowerCase(Locale.ROOT);
        return art.toString().toLowerCase(Locale.ROOT).contains(f)
                || art.getPath().replace('/', '_').toLowerCase(Locale.ROOT).contains(f)
                || MasteryService.artName(art).getString().toLowerCase(Locale.ROOT).contains(f);
    }

    /** Lines for every learned art matching {@code filter} (all when null). */
    public static List<Component> arts(ServerPlayer player, String filter) {
        MasteryState state = player.getData(ModAttachments.MASTERY);
        List<Component> out = new ArrayList<>();
        List<Map.Entry<ResourceLocation, TechniqueProgress>> list = state.arts().entrySet().stream()
                .filter(e -> matches(e.getKey(), filter))
                .sorted(Comparator.comparing(e -> e.getKey().toString())).toList();
        if (list.isEmpty()) {
            out.add(t(filter == null || filter.isBlank() ? "none" : "unknown", filter == null ? "" : filter)
                    .withStyle(ChatFormatting.GRAY));
            out.addAll(formation(player));
            return out;
        }
        for (Map.Entry<ResourceLocation, TechniqueProgress> e : list) {
            art(player, e.getKey(), e.getValue(), out);
        }
        if (filter == null || filter.isBlank()) {
            out.addAll(formation(player));
        }
        return out;
    }

    private static void art(ServerPlayer player, ResourceLocation art, TechniqueProgress p,
                            List<Component> out) {
        int artCap = Arts.cap(art);
        int limit = Math.min(p.cap(), artCap);
        boolean done = p.stars() >= limit;
        long now = player.level().getGameTime();
        out.add(t("art", MasteryService.artName(art).copy().withStyle(ChatFormatting.GOLD), p.stars(), artCap,
                limit < artCap ? t("manuscript", limit) : Component.empty()).withStyle(ChatFormatting.YELLOW));
        double need = MethodMastery.need(art, p.stars());
        if (done) {
            out.add(t(p.stars() >= artCap ? "complete" : "complete.manuscript").withStyle(ChatFormatting.GREEN));
        } else {
            out.add(t("xp", num(p.progress()), num(need), Math.round(100.0D * p.progress() / need)));
        }
        out.add(t(p.unprocessed() > 0.0D ? "reserve" : "reserve.empty", num(p.unprocessed())).withStyle(ChatFormatting.AQUA));
        double cap = done ? 0.0D : MasteryRules.HELP_CAP * need;
        out.add(t("help", num(p.help()), num(cap), Math.round(MasteryRules.HELP_CAP * 100)).withStyle(ChatFormatting.GRAY));
        List<ProgressLog.Entry> mine = ProgressLog.entries(player).stream().filter(x -> x.art().equals(art)).toList();
        if (mine.isEmpty()) {
            out.add(t("recent.none").withStyle(ChatFormatting.GRAY));
        } else {
            out.add(t("recent").withStyle(ChatFormatting.GRAY));
            for (ProgressLog.Entry x : mine.stream().limit(5).toList()) {
                out.add(t("recent.line", t("src." + x.origin().key()), num(x.amount()), ago(now - x.tick()),
                        x.why() == ProgressLog.Why.NONE ? Component.empty()
                                : Component.literal(" — ").append(t("why." + x.why().key()))));
            }
        }
        if (!Arts.isMethod(art)) {
            int hits = MasteryService.hitsToday(player, "training");
            out.add(t("dummy", Math.round(100 * YardRules.fatigue(hits, YardRules.varied(YardPractice.recent(player)))), hits,
                    recovery(player)).withStyle(ChatFormatting.GRAY));
        }
        List<Component> blocked = blocked(player, art, p, artCap, done, mine);
        if (!blocked.isEmpty()) {
            out.add(t("blocked").withStyle(ChatFormatting.RED));
            blocked.forEach(b -> out.add(Component.literal("   ").append(b).withStyle(ChatFormatting.RED)));
        }
    }

    /** When the daily fatigue of the dummies resets (the dawn; it is shared by ALL dummies and drills), as a time-ago style span. */
    private static Component recovery(ServerPlayer player) {
        long left = 24000L - Math.floorMod(player.level().getDayTime(), 24000L);
        return ago(left);
    }

    /** The morning drill line, only at the formation hour: how many forms count and whether the player stands in the rows. */
    public static List<Component> formation(ServerPlayer player) {
        int[] st = io.github.verycooltimo.murim.sect.SectLife.morningStatus(player);
        List<Component> out = new ArrayList<>();
        if (st == null) return out;
        if (st[1] == 1) {
            out.add(t("morning.done").withStyle(ChatFormatting.GREEN));
        } else if (st[2] == 0) {
            out.add(t("morning.outside", st[0], io.github.verycooltimo.murim.sect.SectLife.MORNING_FORMS).withStyle(ChatFormatting.RED));
        } else {
            out.add(t("morning.in", st[0], io.github.verycooltimo.murim.sect.SectLife.MORNING_FORMS).withStyle(ChatFormatting.GREEN));
        }
        return out;
    }

    /** The sources that give this art nothing (or next to nothing) right now, and why. */
    static List<Component> blocked(ServerPlayer player, ResourceLocation art, TechniqueProgress p, int artCap,
                                   boolean done, List<ProgressLog.Entry> mine) {
        List<Component> out = new ArrayList<>();
        if (done) {
            out.add(t(p.stars() >= artCap ? "blocked.complete" : "complete.manuscript"));
            return out;
        }
        double need = MethodMastery.need(art, p.stars());
        if (!Arts.isMethod(art) && p.stars() >= MasteryRules.insightStar(artCap) && p.progress() >= need) {
            out.add(t("blocked.wall"));
        }
        if (MasteryService.helpRoom(player, art) <= 0.0D) {
            out.add(t("blocked.help"));
        }
        if (!Arts.isMethod(art)) {
            int hits = MasteryService.hitsToday(player, "training");
            boolean varied = YardRules.varied(YardPractice.recent(player));
            double fat = YardRules.fatigue(hits, varied);
            if (fat < 0.5D) {
                out.add(t(varied ? "blocked.dummy.fatigue" : "blocked.dummy.monotony", Math.round(fat * 100), hits, recovery(player)));
            }
            long now = player.level().getGameTime();
            for (ProgressLog.Origin o : new ProgressLog.Origin[]{ProgressLog.Origin.FIGHT, ProgressLog.Origin.SPARRING}) {
                mine.stream().filter(x -> x.origin() == o).findFirst()
                        .filter(x -> now - x.tick() < STALE_TICKS)
                        .filter(x -> x.why() == ProgressLog.Why.WEAK_TARGET)
                        .ifPresent(x -> out.add(t("blocked." + o.key() + "." + x.why().key())));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ breakthrough

    private static MutableComponent row(boolean ok, Component what, Component todo) {
        MutableComponent line = Component.literal(ok ? "✔ " : "✘ ").withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED)
                .append(what.copy().withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.WHITE));
        if (!ok && todo != null) {
            line.append(Component.literal("  → ").withStyle(ChatFormatting.GRAY))
                    .append(todo.copy().withStyle(ChatFormatting.GRAY));
        }
        return line;
    }

    /** The rows of the next breakthrough (or of the next Peak stage): each with a tick or a cross and what to do. */
    public static List<Component> breakthrough(ServerPlayer player) {
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        MasteryState state = player.getData(ModAttachments.MASTERY);
        List<Component> out = new ArrayList<>();
        int rank = profile.rank();
        boolean peak = rank == Realm.PEAK;
        out.add(t("bt.rank", Component.translatable(Realm.nameKey(rank)),
                peak ? Component.translatable(Realm.stageKey(profile.stage())) : Component.empty())
                .withStyle(ChatFormatting.YELLOW));
        if (rank > Realm.PEAK || (peak && profile.stage() >= Realm.STAGES - 1)) {
            out.add(t("bt.end").withStyle(ChatFormatting.GRAY));
            return out;
        }
        out.add(t(peak ? "bt.next.stage" : "bt.next", Component.translatable(peak ? Realm.stageKey(profile.stage() + 1)
                : Realm.nameKey(rank + 1))).withStyle(ChatFormatting.YELLOW));
        boolean allOk = true;

        boolean awake = profile.isAwakened();
        allOk &= awake;
        out.add(row(awake, t("bt.dantian"), t("bt.dantian.todo")));

        double wall = Realm.wall(profile) * Realm.WALL_FRACTION;
        boolean atWall = awake && profile.pool() >= wall;
        allOk &= atWall;
        out.add(row(atWall, t("bt.wall", num(profile.pool()), num(wall), Math.round(100.0D * profile.pool() / wall)),
                t("bt.wall.todo")));

        Map<ResourceLocation, Integer> stars = state.combatStars();
        boolean elixir = !peak && io.github.verycooltimo.murim.cultivation.GreatElixirs.ready(player);
        if (elixir) out.add(Component.translatable("murim.pill.breakthrough.ready").withStyle(ChatFormatting.GOLD));
        if (!peak && Realm.condition(rank + 1) == Realm.Condition.SECT_SKILLS) {
            // Same gate as Realm.check; reuse the tablet/mentor checklist from SectProgression.
            boolean skills = (elixir || Realm.secondArtsMet(stars)) && SectProgression.secondSparProof(player);
            allOk &= skills;
            out.add(row(skills, SectProgression.secondChecklist(player), null));
        } else {
            RankRequirements.Gate gate = peak ? RankRequirements.stageGate(profile.stage() + 1) : Realm.gate(rank + 1);
            ResourceLocation best = stars.keySet().stream()
                    .filter(id -> gate.art().isEmpty() || Arts.masteryId(id).equals(gate.art().get()))
                    .max(Comparator.comparingDouble(id -> stars.get(id) / (double) Arts.cap(id))).orElse(null);
            boolean met = elixir || RankRequirements.met(gate, stars);
            allOk &= met;
            if (best == null) {
                out.add(row(met, gate.art().isPresent()
                        ? t("bt.art.main", MasteryService.artName(gate.art().get()), RankRequirements.stars(gate, Arts.MAX_STARS))
                        : t("bt.art.none", RankRequirements.stars(gate, Arts.MAX_STARS)), t("bt.art.todo")));
            } else {
                out.add(row(met, t("bt.art", MasteryService.artName(best), stars.get(best),
                        RankRequirements.stars(gate, Arts.cap(best))), t("bt.art.todo")));
            }
        }

        // Match the body gates in MeditationService.checkWall and checkStage.
        int bodyNeed = peak ? BodyRules.stageNeed(profile.stage() + 1) : BodyRules.breakthroughNeed(rank + 1);
        int level = TrainingService.level(player);
        boolean body = level >= bodyNeed;
        allOk &= body;
        out.add(row(body, bodyNeed <= 0 ? t("bt.body.free", level) : t("bt.body", level, bodyNeed), t("bt.body.todo")));

        out.add((allOk ? t("bt.ready") : t("bt.notready")).withStyle(allOk ? ChatFormatting.GREEN : ChatFormatting.GOLD));
        return out;
    }
}
