package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.MethodLoader;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.boss.BossRegistry;
import io.github.verycooltimo.murim.item.MethodScrollItem;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.SectGoalPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.technique.Styles;
import io.github.verycooltimo.murim.training.BodyRules;
import io.github.verycooltimo.murim.training.TrainingService;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.WeakHashMap;

/** One personal invitation, derived from existing progress. No deadlines or server-wide newcomer gates. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectJourney {
    public static final String SCROLL_GIVEN = "journey.method_given";
    public static final ResourceLocation PETAL = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "falling_petal_sword");
    // The awarded style manual teaches its basic first; the Cut is still locked by its own requires.
    public static final ResourceLocation SEVEN = Styles.SEVEN_PLUM.basic().orElseThrow();
    private record Sent(Component text, ResourceLocation dimension) { }
    private static final Map<ServerPlayer, Sent> SENT = new WeakHashMap<>();

    private SectJourney() { }

    public record Goal(String id, String person, Component blocker) {
        public Component text() {
            return Component.translatable("murim.sect.journey.next",
                    Component.translatable("murim.sect.journey.goal." + id), blocker,
                    Component.translatable("npc.murim." + person));
        }
    }

    private static Goal goal(String id, String person, String blocker, Object... args) {
        return new Goal(id, person, Component.translatable("murim.sect.journey.blocker." + blocker, args));
    }

    private static boolean needsMethod(ServerPlayer p) {
        var cultivation = p.getData(ModAttachments.CULTIVATION);
        return cultivation.method().isEmpty() && !cultivation.seeded()
                && !p.getData(ModAttachments.PROFILE).isAwakened();
    }

    private static boolean hasScroll(ServerPlayer p) {
        return java.util.stream.Stream.concat(p.getInventory().items.stream(), p.getInventory().offhand.stream())
                .anyMatch(s -> s.is(ModItems.METHOD_SCROLL.get()) && SectService.SIX.equals(s.get(ModDataComponents.METHOD.get())));
    }

    public static boolean canHelp(ServerPlayer p, SectDisciple npc) {
        return npc != null && npc.role() == SectRole.MENTOR && npc.isAlive() && npc.level() == p.level()
                && npc.distanceToSqr(p) <= 64 && p.isAlive() && !p.isSpectator()
                && SectService.state(p).member() && needsMethod(p);
    }

    /** First issue is saved only after inventory delivery. Recovery teaches from the mentor's copy, never pays items. */
    public static boolean help(ServerPlayer p, SectDisciple npc) {
        if (!canHelp(p, npc)) return false;
        var method = MethodLoader.get(SectService.SIX);
        if (method == null) return false;
        SectState state = SectService.state(p);
        if (hasScroll(p)) {
            p.setData(ModAttachments.SECT, state.with(SCROLL_GIVEN));
            return true;
        }
        if (state.has(SCROLL_GIVEN)) {
            MethodScrollItem.learn(p, method);
            return true;
        }
        ItemStack scroll = new ItemStack(ModItems.METHOD_SCROLL.get());
        scroll.set(ModDataComponents.METHOD.get(), SectService.SIX);
        if (!p.getInventory().add(scroll)) {
            p.displayClientMessage(Component.translatable("murim.sect.journey.inventory_full"), false);
            return false;
        }
        p.setData(ModAttachments.SECT, state.with(SCROLL_GIVEN));
        p.displayClientMessage(Component.translatable("murim.sect.journey.scroll_given",
                Component.translatable("method.murim.six_harmonies")), false);
        return true;
    }

    /** Choosing another method, or arriving with a centre, never replaces that choice or grants a duplicate scroll. */
    public static Goal next(ServerPlayer p) {
        SectState s = SectService.state(p);
        var profile = p.getData(ModAttachments.PROFILE);
        var cultivation = p.getData(ModAttachments.CULTIVATION);
        SectStanding standing = SectStanding.of(s, profile.rank());
        if (standing == SectStanding.OUTSIDER) return goal("join", "tae_hwi", "join");
        if (SectPositions.disciplined(s)) return goal("sentence", "tae_hwi", "sentence");
        if (s.has(SectProgression.REPAIR)) {
            String person = s.flags().stream().filter(f -> f.startsWith("discipline.victim."))
                    .map(f -> f.substring("discipline.victim.".length())).sorted().findFirst().orElse(SectProgression.MENTOR);
            return s.has("discipline.apologised") ? goal("repair", SectProgression.MENTOR, "repair")
                    : goal("apology", person, "apology");
        }
        if (needsMethod(p)) {
            return hasScroll(p) ? goal("read_method", "gyeong_pil", "read_method") : goal("method", "gyeong_pil", "method");
        }
        if (!profile.isAwakened()) {
            if (cultivation.seeded()) return goal("centre", "gyeong_pil", "centre");
            return goal(switch (cultivation.beats()) {
                case 0 -> "seed_feel";
                case 1 -> "seed_hold";
                default -> "seed_compress";
            }, "gyeong_pil", "seed");
        }
        if (!MasteryService.knows(p, SectService.SIX)) return goal("learn_six", "gyeong_pil", "book");
        if (!s.has(SectStanding.LESSON_ONE)) {
            if (!s.has("lesson.six")) return goal("take_six", "gyeong_pil", "ready");
            if (MasteryService.stars(p, SectService.SIX) < 2) return goal("practice_six", "gyeong_pil", "practice");
            if (profile.rank() < 1) return rankGoal(p);
            return goal("submit_six", "gyeong_pil", "ready");
        }
        if (!s.has(SectStanding.LESSON_TWO)) {
            if (!MasteryService.knows(p, PETAL)) return goal("learn_petal", "gyeong_pil", "book");
            if (!s.has(SectService.LESSON_SPAR)) return goal("take_spar", "gyeong_pil", "ready");
            if (!s.has(SectService.SPAR_CLEAN)) return goal("clean_hits", "seo_rang", "clean_hits");
            if (profile.rank() < 2) return rankGoal(p);
            return goal("submit_spar", "gyeong_pil", "ready");
        }
        if (!MasteryService.knows(p, SEVEN)) return goal("learn_seven", "gyeong_pil", "book");
        if (!s.has(SectProgression.DIRECT)) {
            if (!SectRequests.completedAny(s)) {
                if (s.has(SectRequests.flag("pills", "active"))) return goal("deliver_pills", "gyeong_cho", "pills");
                // A local, one-shot favour is enough; no bandit trip or review week is required.
                return goal("request", "tae_gyun", "request");
            }
            if (s.flags().stream().filter(f -> f.startsWith("mentor.partner.")).count() < 3)
                return goal("partners", SectProgression.MENTOR, "partners");
            if (s.has("mentor.ceremony")) return goal("ceremony", SectProgression.MENTOR, "ceremony");
            if (SectProgression.directEligible(s)) return goal("direct", SectProgression.MENTOR, "ready");
        }
        if (standing != SectStanding.TRUSTED) {
            if (s.contribution() < SectStanding.TRUSTED_CONTRIBUTION) return goal("standing", "tae_gyun", "standing",
                    SectStanding.TRUSTED_CONTRIBUTION - s.contribution());
            if (!s.has(SectStanding.APPROVAL)) return goal("approval", "tae_hwi", "ready");
        }
        if (SectPositions.eligible(s, profile.rank(), "elder") && !s.has("position.declined.elder"))
            return goal("elder", "tae_hwi", "ready");
        if (SectPositions.eligible(s, profile.rank(), "first") && !s.has("position.declined.first"))
            return goal("first", "tae_hwi", "ready");
        var practice = p.getData(ModAttachments.MASTERY).techniques().get(SectProgression.lessonTechnique(p));
        if (practice != null && practice.atCap()) return goal("free_practice", SectProgression.MENTOR, "cap");
        return goal("personal_lesson", SectProgression.MENTOR,
                s.entry("mentor.lesson.day", -1) >= SectSchedule.day(p.level().getDayTime()) ? "rest"
                        : s.entry("spar.count", 0) <= s.entry("mentor.lesson.spar", 0) ? "new_spar" : "ready");
    }

    private static Goal rankGoal(ServerPlayer p) {
        var profile = p.getData(ModAttachments.PROFILE);
        Realm.Blocker blocker = Realm.check(profile, p.getData(ModAttachments.MASTERY).executionTiers(), p.getData(BossRegistry.BOSS_DEFEATED));
        String reason = switch (blocker) {
            case NO_DANTIAN -> "centre";
            case NOT_AT_WALL -> "wall";
            case NO_TECHNIQUE -> "technique";
            case NO_BOSS -> "boss";
            case MAX_RANK -> "max";
            case NONE -> BodyRules.breakthroughReady(profile.rank() + 1, TrainingService.level(p)) ? "breakthrough" : "body";
        };
        return goal(profile.rank() == 0 ? "rank_one" : "rank_two", "gyeong_pil", reason);
    }

    public static Component line(ServerPlayer p) { return next(p).text(); }

    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 20 != 11) return;
        Component text = SectService.state(p).member() ? line(p) : Component.empty();
        Sent snapshot = new Sent(text, p.level().dimension().location());
        if (snapshot.equals(SENT.get(p))) return;
        SectGoalPayload payload = new SectGoalPayload(text);
        if (p.connection != null && p.connection.hasChannel(payload)) {
            PacketDistributor.sendToPlayer(p, payload);
            SENT.put(p, snapshot);
        }
    }
}
