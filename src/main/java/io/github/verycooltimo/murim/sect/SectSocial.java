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
    public static final List<String> SITUATIONS = List.of("outsider", "punished", "review", "secret", "loot", "weak",
            "strong", "equal", "lower", "friendly", "morning", "evening", "night", "ordinary", "title");
    public static final int VARIANTS = 4;
    public static final List<String> BUBBLE_GROUPS = List.of("respect", "mock", "advice", "challenge", "test",
            "acknowledge", "art_junior", "art_elder", "rumour", "title");

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
        CompoundTag tag = p.getPersistentData().getCompound(MEMORY);
        String slot = "line." + (npc.memberKey().isEmpty() ? npc.role().id() : npc.memberKey());
        String previous = tag.getString(slot);
        List<String> choices = keys.stream().filter(k -> !k.equals(previous) && !k.equals(npc.bubbleKey())).toList();
        if (choices.isEmpty()) choices = keys.stream().filter(k -> !k.equals(previous)).toList();
        if (choices.isEmpty()) choices = keys;
        String key = choices.get(p.getRandom().nextInt(choices.size()));
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
        if (SectPositions.disciplined(s) || recent(p, "punished")) return "punished";
        CompoundTag rumour = npc.getPersistentData().getCompound(RUMOUR);
        if (rumour.getLong("until") > p.level().getGameTime()
                && rumour.getString("player").equals(p.getName().getString())) return "secret";
        if (recent(p, "review")) return "review";
        if (recent(p, "loot")) return "loot";
        Power power = power(p, npc);
        if (power == Power.WEAK) return "weak";
        if (npc.role().third() && (s.has(SectPositions.FIRST) || s.has(SectPositions.ELDER))) return "title";
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
        String stem = "dialogue.murim.social." + voice(npc) + "." + situation(p, npc) + ".";
        String key = pick(p, npc, java.util.stream.IntStream.rangeClosed(1, VARIANTS).mapToObj(i -> stem + i).toList());
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
        if (npc == null) return false;
        int colon = condition.indexOf(':');
        String kind = colon < 0 ? condition : condition.substring(0, colon);
        String value = colon < 0 ? "" : condition.substring(colon + 1);
        return switch (kind) {
            case "escort" -> SectEscorts.available(p, npc, value);
            case "journey_help" -> SectJourney.canHelp(p, npc);
            case "progression" -> SectProgression.matches(p, npc, value);
            case "request" -> SectService.state(p).member() && !SectPositions.disciplined(SectService.state(p))
                    && (SectRequests.issuer(npc, value) || SectRequests.receiver(npc, value));
            case "request_start" -> SectRequests.canStart(p, npc, value);
            case "request_complete" -> SectRequests.canComplete(p, npc, value);
            case "promotion" -> npc.role() == SectRole.LEADER
                    && SectPositions.eligible(SectService.state(p), p.getData(ModAttachments.PROFILE).rank(), value);
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
                String group = npc.rank() >= 3 ? "art_elder" : "art_junior";
                String key = pick(p, npc, bubbleKeys(group));
                SectChatter.send(npc, key, Component.translatable(key));
            }
        }
    }

    static List<String> bubbleKeys(String group) {
        return java.util.stream.IntStream.rangeClosed(1, VARIANTS).mapToObj(i -> "murim.bubble.social." + group + "." + i).toList();
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
        String group;
        if (power == Power.STRONG && npc.rank() <= 2) {
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
        if (npc.role().third() && power != Power.WEAK && (SectService.state(p).has(SectPositions.FIRST) || SectService.state(p).has(SectPositions.ELDER))) {
            String key = pick(p, npc, bubbleKeys("title"));
            SectChatter.send(npc, key, Component.translatable(key, Component.translatable(SectPositions.titleKey(SectService.state(p)))));
        } else {
            String key = pick(p, npc, bubbleKeys(group));
            SectChatter.send(npc, key, Component.translatable(key));
        }
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
