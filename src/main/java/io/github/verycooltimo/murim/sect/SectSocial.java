package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.mastery.TechniqueTier;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;
import java.util.Locale;

/** Contextual speech and power etiquette. Memories are bounded by speaker and expire; offices/quests live in SectState. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectSocial {
    public static final List<String> VOICES = List.of("junior", "second", "first", "coach", "elder", "head");
    /**
     * What the speaker has in mind. {@code office_elder} and {@code office_first} are for an office holder and replace
     * every other situation (docs/design/39 «Обращение по положению»): nobody instructs or sizes up a holder.
     */
    public static final List<String> SITUATIONS = List.of("outsider", "punished", "review", "secret", "loot", "weak",
            "strong", "equal", "lower", "friendly", "morning", "evening", "night", "ordinary", "office_elder", "office_first");
    /** Variants of every voice x situation and every bubble group (data test: at least {@link SectLines#MIN_VARIANTS}). */
    public static final int VARIANTS = 6;
    /** {@code *_up}: juniors speaking up to a holder; {@code *_peer}: the first generation and the council. */
    public static final List<String> BUBBLE_GROUPS = List.of("respect", "mock", "advice", "challenge", "test",
            "acknowledge", "art_junior", "art_elder", "art_peer", "rumour", "elder_up", "elder_peer", "first_up", "first_peer");

    public static List<String> allDialogueKeys() {
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        for (String voice : VOICES) for (String situation : SITUATIONS) for (int i = 1; i <= VARIANTS; i++) {
            keys.add("dialogue.murim.social." + voice + "." + situation + "." + i);
        }
        for (String period : List.of("morning", "day", "evening", "night")) for (int i = 1; i <= VARIANTS; i++) {
            keys.add("dialogue.murim.social.time." + period + "." + i);
        }
        return List.copyOf(keys);
    }
    private static final String MEMORY = "murim_sect_social";
    public static final String RUMOUR = "murim_sect_art_rumour";
    private SectSocial() { }

    public enum Power { WEAK, STRONG, EQUAL, LOWER }

    /** Stage affects power on the same scale as combat. Below-rank advice is separate from relative strength. */
    public static Power compare(SectState s, int rank, int stage, int npcRank) {
        int expected = s.has(SectPositions.ELDER) ? 4 : s.has(SectPositions.FIRST) ? 3
                : SectStanding.of(s, rank).atLeast(SectStanding.GRADUATE) ? 2 : s.has(SectStanding.LESSON_ONE) ? 1 : 0;
        if (s.member() && rank < expected) return Power.WEAK;
        double delta = Realm.power(rank, stage) - Realm.power(npcRank);
        return delta > 0.05D ? Power.STRONG : delta < -0.05D ? Power.LOWER : Power.EQUAL;
    }

    public static Power power(ServerPlayer p, SectDisciple npc) {
        var profile = p.getData(ModAttachments.PROFILE);
        return compare(SectService.state(p), profile.rank(), profile.stage(), npc.rank());
    }

    /** Sect seniority, independent of cultivation power. Strangers are never owed a bow. */
    static int seniority(SectDisciple npc) {
        if (npc.role() == SectRole.LEADER) return SectRank.ELDER.ordinal() + 1;
        if (npc.role() == SectRole.ELDER) return SectRank.ELDER.ordinal();
        // docs/design/39-player-path-v2.md: first-generation teachers address elders as colleagues.
        if (npc.role() == SectRole.MENTOR) return SectRank.ELDER.ordinal();
        return npc.member().map(m -> m.generation() == 1 ? SectRank.ELDER.ordinal()
                : m.generation() == 2 ? SectRank.GRADUATE.ordinal() : SectRank.DISCIPLE.ordinal())
                .orElse(SectRank.DISCIPLE.ordinal());
    }

    static boolean mayBow(SectDisciple npc, ServerPlayer p, boolean deed) {
        SectRank rank = SectRank.of(p);
        return rank != SectRank.OUTSIDER && (seniority(npc) < rank.ordinal()
                || deed && seniority(npc) == rank.ordinal());
    }

    static void gesture(SectDisciple npc, ServerPlayer p, String gesture) {
        npc.gesture("bow".equals(gesture) && !mayBow(npc, p, false) ? "nod" : gesture);
    }

    public static String voice(SectDisciple npc) {
        if (npc.role() == SectRole.LEADER) return "head";
        if (npc.role() == SectRole.MENTOR || "gyeong_tak".equals(npc.memberKey()) || "gyeong_cho".equals(npc.memberKey())) return "coach";
        if (npc.role() == SectRole.ELDER) return "elder";
        return npc.member().map(m -> m.generation() == 1 ? "first" : m.generation() == 2 ? "second" : "junior").orElse("junior");
    }

    public static void remember(ServerPlayer p, String deed) {
        CompoundTag tag = p.getPersistentData().getCompound(MEMORY);
        tag.putLong("deed." + deed, p.level().getGameTime() + 24000);
        p.getPersistentData().put(MEMORY, tag);
    }

    static boolean recent(ServerPlayer p, String deed) {
        return p.getPersistentData().getCompound(MEMORY).getLong("deed." + deed) > p.level().getGameTime();
    }

    /** Keep the actual key, not a token such as @context, so no line repeats on reopening a conversation. */
    public static String pick(ServerPlayer p, SectDisciple npc, List<String> keys) {
        keys = SectPersonalLines.choices(p, npc, keys);
        CompoundTag tag = p.getPersistentData().getCompound(MEMORY);
        String slot = "line." + (npc.memberKey().isEmpty() ? npc.role().id() : npc.memberKey());
        String previous = tag.getString(slot);
        List<String> choices = keys.stream().filter(k -> !k.equals(previous) && !k.equals(npc.bubbleKey())).toList();
        if (choices.isEmpty()) choices = keys.stream().filter(k -> !k.equals(previous)).toList();
        if (choices.isEmpty()) choices = keys;
        // The person never repeats himself (slot), and the player is not told a line he heard today (SectLines).
        String key = SectLines.pick(p, SectLines.commonPool(keys), choices, p.getRandom().nextInt(1 << 16));
        tag.putString(slot, key);
        p.getPersistentData().put(MEMORY, tag);
        return key;
    }

    public static String pickToken(ServerPlayer p, SectDisciple npc, List<String> tokens) {
        CompoundTag tag = p.getPersistentData().getCompound(MEMORY);
        String slot = "token." + (npc.memberKey().isEmpty() ? npc.role().id() : npc.memberKey());
        String previous = tag.getString(slot);
        List<String> choices = tokens.stream().filter(k -> !k.equals(previous)).toList();
        if (choices.isEmpty()) choices = tokens;
        String token = choices.get(p.getRandom().nextInt(choices.size()));
        tag.putString(slot, token);
        p.getPersistentData().put(MEMORY, tag);
        return token;
    }

    public static String situation(ServerPlayer p, SectDisciple npc) {
        SectState s = SectService.state(p);
        if (!s.member()) {
            Power stranger = power(p, npc);
            return stranger == Power.STRONG ? "strong" : stranger == Power.EQUAL ? "equal" : "outsider";
        }
        // An office holder is addressed by his office whatever else happened (rank before standing, power or the clock).
        SectRank rank = SectRank.of(p);
        if (rank.office()) return rank == SectRank.ELDER ? "office_elder" : "office_first";
        if (SectPositions.disciplined(s) || recent(p, "punished")) return "punished";
        CompoundTag rumour = npc.getPersistentData().getCompound(RUMOUR);
        if (rumour.getLong("until") > p.level().getGameTime()
                && rumour.getString("player").equals(p.getName().getString())) return "secret";
        if (recent(p, "review")) return "review";
        if (recent(p, "loot")) return "loot";
        Power power = power(p, npc);
        if (power == Power.WEAK) return "weak";
        if (s.has("favour." + npc.memberKey())) return "friendly";
        if (power == Power.STRONG || power == Power.EQUAL) return power.name().toLowerCase(Locale.ROOT);
        return switch (SectSchedule.at(p.level().getDayTime())) {
            case FORMATION, BREAKFAST -> "morning";
            case EVENING, DINNER -> "evening";
            case NIGHT -> "night";
            default -> SectService.standing(p).atLeast(SectStanding.DISCIPLE) ? "ordinary" : "lower";
        };
    }

    public static Component context(ServerPlayer p, SectDisciple npc) {
        String situation = situation(p, npc);
        String stem = "dialogue.murim.social." + voice(npc) + "." + situation + ".";
        String key = pick(p, npc, java.util.stream.IntStream.rangeClosed(1, VARIANTS).mapToObj(i -> stem + i).toList());
        if (situation.startsWith("office_")) {
            // Office lines stand alone: the clock tails («Start dawn with breathing») are drills for juniors.
            return Component.translatable(key, Component.translatable(SectPositions.titleKey(SectService.state(p))),
                    Component.translatable(SectService.standing(p).nameKey()), Component.translatable(Realm.nameKey(npc.rank())));
        }
        String period = switch (SectSchedule.at(p.level().getDayTime())) {
            case FORMATION, BREAKFAST -> "morning";
            case DINNER, EVENING -> "evening";
            case NIGHT -> "night";
            default -> "day";
        };
        String timeKey = "dialogue.murim.social.time." + period + "." + key.substring(key.lastIndexOf('.') + 1);
        return Component.translatable(key, Component.translatable(SectPositions.titleKey(SectService.state(p))),
                Component.translatable(SectService.standing(p).nameKey()), Component.translatable(Realm.nameKey(npc.rank())))
                .append(" ").append(Component.translatable(timeKey));
    }

    /** Optional 'social' condition extends existing dialogue objects without changing their structure. */
    static boolean matches(ServerPlayer p, SectDisciple npc, String condition) {
        if (condition.startsWith("quest:available:")) {
            return io.github.verycooltimo.murim.quest.QuestLoader.available(p, condition.substring("quest:available:".length()));
        }
        if (npc == null) return false;
        int colon = condition.indexOf(':');
        String kind = colon < 0 ? condition : condition.substring(0, colon);
        String value = colon < 0 ? "" : condition.substring(colon + 1);
        return switch (kind) {
            // ADR 0020: explicit wanderer questions work nearby too; shared offers keep their distance policy.
            case "road_guidance" -> npc.wanderer() ? !SectService.state(p).member() : Wanderer.needsDirections(p);
            case "head_intro" -> SectHeadIntro.canOffer(p, npc);
            case "escort" -> SectEscorts.available(p, npc, value);
            case "first_exam_missing" -> SectService.state(p).member()
                    && SectService.state(p).has("lesson.six") && !SectService.state(p).has(SectStanding.LESSON_ONE)
                    && !SectProgression.firstExamMissing(p).isEmpty();
            case "lost_book" -> npc.role() == SectRole.MENTOR && SectService.lostBook(p) != null;
            case "bamboo_hand_lesson" -> SectService.canTeachBamboo(p, npc);
            case "seven_stars_lesson" -> SectService.canTeachSevenStars(p, npc);
            case "blade_exam" -> SectBladeExam.invited(p, npc);
            case "first_exam" -> SectProgression.firstExamReady(p);
            case "first_exam_asked" -> io.github.verycooltimo.murim.quest.MentorStrikeDrill.asked(p);
            case "first_exam_alone" -> !io.github.verycooltimo.murim.quest.MentorStrikeDrill.asked(p);
            case "journey_help" -> SectJourney.canHelp(p, npc);
            case "quest" -> io.github.verycooltimo.murim.quest.Quests.matches(p, npc, value);
            case "lost_tablet" -> npc.role() == SectRole.MENTOR && SectTablet.lost(p);
            case "bowl" -> SectMeals.canServe(p);
            case "approaching" -> npc.approachKind() != null && npc.blockTarget() == p.getId();
            case "progression" -> SectProgression.matches(p, npc, value);
            case "request" -> SectService.state(p).member() && !SectPositions.disciplined(SectService.state(p))
                    && (SectRequests.issuer(npc, value) || SectRequests.receiver(npc, value));
            case "request_start" -> SectRequests.canStart(p, npc, value);
            case "request_complete" -> SectRequests.canComplete(p, npc, value);
            case "promotion" -> npc.role() == SectRole.LEADER
                    && SectPositions.eligible(p, value);
            case "business" -> SectService.state(p).member() && !SectPositions.disciplined(SectService.state(p))
                    && (npc.role() == SectRole.LEADER || npc.role() == SectRole.MENTOR || npc.role() == SectRole.ELDER);
            default -> false;
        };
    }

    /** A high art is remembered only by people who actually saw it; they pass the rumour at meals. */
    public static void highArt(ServerPlayer p, TechniqueTier tier) {
        if (tier.ordinal() < TechniqueTier.ADVANCED.ordinal()) return;
        List<SectDisciple> witnesses = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(16),
                d -> d.isAlive() && d.member().isPresent() && SectReactions.watches(d) && d.distanceToSqr(p) <= 256
                        && d.getSensing().hasLineOfSight(p));
        if (witnesses.isEmpty()) return;
        remember(p, "secret");
        long now = p.level().getGameTime();
        CompoundTag memory = p.getPersistentData().getCompound(MEMORY);
        boolean say = !memory.contains("artAt") || now - memory.getLong("artAt") >= 200;
        memory.putLong("artAt", now);
        p.getPersistentData().put(MEMORY, memory);
        witnesses.sort(java.util.Comparator.comparingDouble(d -> d.distanceToSqr(p)));
        int spoken = 0;
        for (SectDisciple npc : witnesses) {
            npc.watch(p, 120);
            CompoundTag rumour = new CompoundTag();
            rumour.putString("player", p.getName().getString());
            rumour.putLong("until", now + 48000);
            npc.getPersistentData().put(RUMOUR, rumour);
            if (say && spoken++ < 2) {
                // The first generation and the council advise a pupil about a higher art; to an office holder they only note it.
                String group = npc.rank() >= 3 ? (SectRank.of(p).office() ? "art_peer" : "art_elder") : "art_junior";
                String key = pick(p, npc, bubbleKeys(group));
                SectChatter.send(npc, key, Component.translatable(key));
            }
        }
    }

    static List<String> bubbleKeys(String group) {
        return SectLines.keys("murim.bubble.social." + group, VARIANTS);
    }

    /** Send a person to stand at {@code spot} for {@code ticks} (the same tag {@link #yielding} walks him to: the schedule waits). */
    public static void yieldTo(SectDisciple npc, Vec3 spot, long ticks) {
        CompoundTag yield = new CompoundTag();
        yield.putDouble("x", spot.x); yield.putDouble("y", spot.y); yield.putDouble("z", spot.z);
        yield.putLong("until", npc.level().getGameTime() + ticks);
        npc.getPersistentData().put("murim_sect_yield", yield);
    }

    public static boolean yielding(SectDisciple npc) {
        CompoundTag tag = npc.getPersistentData().getCompound("murim_sect_yield");
        if (tag.getLong("until") <= npc.level().getGameTime()) return false;
        Vec3 spot = new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
        if (npc.position().distanceToSqr(spot) > 0.25D) npc.walking().walk(spot, 0.7D);
        else npc.getNavigation().stop();
        return true;
    }

    /** No forced spar: an equal invites the player, who may choose the ordinary spar dialogue. */
    public static void react(ServerPlayer p, SectDisciple npc) {
        // Nobody gives advice («Спину прямее») to a player the council or its door guard is dealing with.
        if (npc.doingKind() == SectSchedule.Kind.COUNCIL || SectCouncilIntrusion.count(p) > 0 && SectCouncil.phase(p.level().getDayTime()).seated()) return;
        if (!SectReactions.watches(npc) || SectLife.onWatch(npc)
                || npc.blocking() || npc.sitting() || npc.doingKind() == SectSchedule.Kind.FORM_ROW
                || npc.doingKind() == SectSchedule.Kind.INSPECT || npc.doingKind() == SectSchedule.Kind.GUARD
                || npc.doingKind() == SectSchedule.Kind.SPAR || npc.doingKind() == SectSchedule.Kind.POLES
                || npc.doingKind() == SectSchedule.Kind.DRILL || npc.watching() != null) return;
        long day = SectSchedule.day(p.level().getDayTime());
        if (npc.bowedToday(p, day)) return;
        Power power = power(p, npc);
        npc.markBowed(p, day);
        npc.faceEntity(p, 60.0F);
        SectRank rank = SectRank.of(p);
        if (rank.office()) {
            office(p, npc, rank);
            return;
        }
        String group;
        if (power == Power.STRONG && mayBow(npc, p, false)) {
            npc.holdPose(SectPose.BOW, 30);
            Vec3 delta = npc.position().subtract(p.position());
            Vec3 side = new Vec3(-delta.z, 0, delta.x).normalize().scale(1.2D);
            if (side.lengthSqr() < 0.01D) side = new Vec3(1.2D, 0, 0);
            Vec3 at = npc.position().add(side);
            CompoundTag yield = new CompoundTag();
            yield.putDouble("x", at.x); yield.putDouble("y", at.y); yield.putDouble("z", at.z);
            yield.putLong("until", p.level().getGameTime() + 90);
            npc.getPersistentData().put("murim_sect_yield", yield);
            group = "respect";
        } else {
            npc.attend(p, 50);
            group = power == Power.WEAK ? npc.rank() >= 3 ? "advice" : "mock"
                    : power == Power.EQUAL ? npc.rank() >= 3 ? "test" : "challenge"
                    : npc.rank() >= 3 && power == Power.STRONG ? "acknowledge" : "advice";
        }
        String key = pick(p, npc, bubbleKeys(group));
        SectChatter.send(npc, key, Component.translatable(key));
    }

    /** Younger than an office holder in the sect: the third and second generation and the servants. */
    public static boolean junior(SectDisciple npc) {
        return npc.role().lay() || npc.member().map(m -> m.generation() >= 2).orElse(npc.rank() <= 2);
    }

    /** The bubble group of a person speaking to an office holder: {@code elder_up}, {@code elder_peer}, {@code first_up}, {@code first_peer}. */
    public static String officeGroup(SectRank rank, SectDisciple npc) {
        return (rank == SectRank.ELDER ? "elder" : "first") + (junior(npc) ? "_up" : "_peer");
    }

    /**
     * The meeting with an elder or a first disciple. Juniors bow and make room; the first generation and the council
     * nod to him as to a colleague. Never a mock, a drill or a challenge.
     */
    static void office(ServerPlayer p, SectDisciple npc, SectRank rank) {
        if (mayBow(npc, p, false)) {
            npc.holdPose(SectPose.BOW, 30);
            Vec3 delta = npc.position().subtract(p.position());
            Vec3 side = new Vec3(-delta.z, 0, delta.x).normalize().scale(1.2D);
            if (side.lengthSqr() < 0.01D) side = new Vec3(1.2D, 0, 0);
            Vec3 at = npc.position().add(side);
            CompoundTag yield = new CompoundTag();
            yield.putDouble("x", at.x); yield.putDouble("y", at.y); yield.putDouble("z", at.z);
            yield.putLong("until", p.level().getGameTime() + 90);
            npc.getPersistentData().put("murim_sect_yield", yield);
        } else {
            npc.attend(p, 50);
            npc.gesture("fist");
        }
        String key = pick(p, npc, bubbleKeys(officeGroup(rank, npc)));
        SectChatter.send(npc, key, Component.translatable(key));
    }

    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 40 != 19 || p.isSpectator()
                || SectPositions.disciplined(SectService.state(p))) return;
        p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(4),
                d -> d.isAlive() && !d.role().lay() && d.member().isPresent() && d.distanceToSqr(p) <= 16
                        && d.getSensing().hasLineOfSight(p)).stream()
                .filter(d -> !d.bowedToday(p, SectSchedule.day(p.level().getDayTime())))
                .min(java.util.Comparator.comparingDouble(d -> d.distanceToSqr(p))).ifPresent(d -> react(p, d));
    }
}
