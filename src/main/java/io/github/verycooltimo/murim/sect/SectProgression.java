package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.MasteryRules;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.Styles;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;

/** Optional sect activities. All proof comes from server events; fixed journal keys bound save growth. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectProgression {
    public static final ResourceLocation DIALOGUE = id("sect_progression");
    public static final String MENTOR = "tae_seong", RIVAL = "seo_ho";
    public static final String DIRECT = "mentor.direct_disciple", REPAIR = "discipline.repair";
    private static final List<String> STOPS = List.of("sect_gate", "training", "dining");
    private static final List<ResourceLocation> MANUALS = List.of(SectService.SIX,
            Styles.SEVEN_PLUM.basic().orElseThrow(), Styles.TWENTY_FOUR_PLUM.forms().getFirst());
    private SectProgression() { }
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, name); }
    private static long day(ServerPlayer p) { return SectSchedule.day(p.serverLevel().getDayTime()); }
    private static SectState state(ServerPlayer p) { return p.getData(ModAttachments.SECT); }
    private static void save(ServerPlayer p, SectState s) { p.setData(ModAttachments.SECT, s); }
    private static boolean calm(ServerPlayer p) {
        return p.isAlive() && !p.isSpectator() && p.level().dimension() == Level.OVERWORLD && state(p).member()
                && !SectPositions.disciplined(state(p));
    }
    private static boolean ready(ServerPlayer p) { return calm(p) && !state(p).has(REPAIR); }
    private static boolean speaker(SectDisciple npc, String key) { return npc != null && key.equals(npc.memberKey()); }
    private static boolean available(ServerPlayer p, SectDisciple npc) {
        return npc != null && npc.isAlive() && npc.level() == p.level() && npc.distanceToSqr(p) <= 144
                && npc.spar() == SectDisciple.Spar.NONE && !npc.defending()
                && (npc.talkingTo() < 0 || npc.talkingTo() == p.getId()) && !npc.wounded() && !SectLife.onWatch(npc);
    }
    private static void say(SectDisciple npc, String key) { SectChatter.send(npc, key, Component.translatable(key)); }

    public static boolean directEligible(SectState s) {
        return s.member() && s.has(SectStanding.LESSON_TWO) && SectRequests.completedAny(s)
                && s.flags().stream().filter(f -> f.startsWith("mentor.partner.")).count() >= 3
                && !s.has(DIRECT) && !s.has(REPAIR) && !SectPositions.disciplined(s);
    }
    public static boolean beginDiscipleship(ServerPlayer p, SectDisciple npc) {
        if (!ready(p) || !available(p, npc) || !speaker(npc, MENTOR) || !directEligible(state(p))) return false;
        if (!state(p).has("mentor.ceremony")) save(p, state(p).with("mentor.ceremony")
                .record("mentor.ceremony.at", p.level().getGameTime()));
        return true;
    }
    public static boolean finishDiscipleship(ServerPlayer p, SectDisciple npc) {
        if (!ready(p) || !available(p, npc) || !speaker(npc, MENTOR) || !directEligible(state(p))
                || !state(p).has("mentor.ceremony")
                || p.level().getGameTime() - state(p).entry("mentor.ceremony.at", p.level().getGameTime()) < 100) return false;
        save(p, state(p).with(DIRECT).without("mentor.ceremony"));
        say(npc, "murim.bubble.progression.direct");
        return true;
    }
    static ResourceLocation lessonTechnique(ServerPlayer p) {
        ResourceLocation selected = p.getData(ModAttachments.LOADOUT).foundation().orElse(SectService.SIX);
        return MANUALS.contains(selected) ? selected : SectService.SIX;
    }
    public static boolean canLesson(ServerPlayer p, SectDisciple npc) {
        ResourceLocation technique = lessonTechnique(p);
        return ready(p) && available(p, npc) && speaker(npc, MENTOR) && state(p).has(DIRECT)
                && state(p).entry("mentor.lesson.day", -1) < day(p)
                && state(p).entry("spar.count", 0) > state(p).entry("mentor.lesson.spar", 0)
                && MasteryService.knows(p, technique) && !p.getData(ModAttachments.MASTERY).techniques().get(technique).atCap();
    }
    public static boolean lesson(ServerPlayer p, SectDisciple npc) {
        if (!canLesson(p, npc)) return false;
        ResourceLocation technique = lessonTechnique(p);
        save(p, state(p).record("mentor.lesson.day", day(p)).record("mentor.lesson.spar", state(p).entry("spar.count", 0)));
        MasteryService.study(p, technique, MasteryRules.need(MasteryService.stars(p, technique)) * 0.1D, true);
        say(npc, "murim.bubble.progression.lesson");
        return true;
    }

    /** Tiers concern reading in this archive, never grant vault permission or unknown high arts. */
    public static boolean archiveAllowed(SectState s, int rank, int tier) {
        if (tier < 0 || tier >= MANUALS.size() || !s.member() || s.has(REPAIR) || SectPositions.disciplined(s)) return false;
        SectStanding required = tier == 0 ? SectStanding.DISCIPLE : tier == 1 ? SectStanding.GRADUATE : SectStanding.TRUSTED;
        return SectStanding.of(s, rank).atLeast(required);
    }
    public static boolean canRead(ServerPlayer p, SectDisciple npc, int tier) {
        if (!ready(p) || !available(p, npc) || !speaker(npc, "tae_rok")
                || !archiveAllowed(state(p), p.getData(ModAttachments.PROFILE).rank(), tier)
                || state(p).entry("archive.day", -1) >= day(p)) return false;
        ResourceLocation technique = MANUALS.get(tier);
        return npc.layout() != null && npc.layout().inside("scriptures", p.position(), 4)
                && MasteryService.knows(p, technique) && !p.getData(ModAttachments.MASTERY).techniques().get(technique).atCap();
    }
    public static boolean read(ServerPlayer p, SectDisciple npc, int tier) {
        if (!canRead(p, npc, tier)) return false;
        ResourceLocation technique = MANUALS.get(tier);
        save(p, state(p).record("archive.day", day(p)));
        MasteryService.study(p, technique, MasteryRules.need(MasteryService.stars(p, technique)) * 0.05D);
        say(npc, "murim.bubble.progression.archive");
        return true;
    }

    public static boolean canMission(ServerPlayer p, SectDisciple npc, String mission) {
        if (!List.of("supply", "patrol").contains(mission) || !ready(p) || !available(p, npc) || npc.layout() == null
                || state(p).has("duty.active") || state(p).entry("duty.day", -1) >= day(p) || state(p).entry("duty.completed.day", -1) >= day(p)) return false;
        return STOPS.stream().allMatch(zone -> npc.layout().at(zone, 0, 0) != null);
    }
    public static boolean startMission(ServerPlayer p, SectDisciple npc, String mission) {
        if (!canMission(p, npc, mission)) return false;
        SectState s = state(p).with("duty.active").without("duty.supply").without("duty.patrol").with("duty." + mission)
                .record("duty.day", day(p)).record("duty.step", 0).record("duty.origin", npc.blockPosition().asLong());
        for (int i = 0; i < STOPS.size(); i++) {
            s = s.record("duty.stop." + i, BlockPos.containing(npc.layout().at(STOPS.get(i), 0, 0)).asLong());
        }
        save(p, s);
        reportStop(p);
        return true;
    }
    private static boolean currentMission(ServerPlayer p) {
        return ready(p) && state(p).has("duty.active");
    }
    public static boolean canFinishMission(ServerPlayer p, SectDisciple npc) {
        if (!currentMission(p) || !available(p, npc)
                || p.blockPosition().distSqr(BlockPos.of(state(p).entry("duty.origin", 0))) > 144) return false;
        return state(p).has("duty.supply") ? new SectService.ItemNeed(Items.OAK_PLANKS, 16).count(p) >= 16
                && new SectService.ItemNeed(Items.BREAD, 8).count(p) >= 8 : state(p).entry("duty.step", 0) >= STOPS.size();
    }
    public static boolean finishMission(ServerPlayer p, SectDisciple npc) {
        if (!canFinishMission(p, npc)) return false;
        boolean supply = state(p).has("duty.supply");
        if (supply) {
            new SectService.ItemNeed(Items.OAK_PLANKS, 16).take(p);
            new SectService.ItemNeed(Items.BREAD, 8).take(p);
            SectLife.data(p.serverLevel()).supply(day(p) + 1);
        }
        save(p, state(p).without("duty.active").with("duty.completed").record("duty.completed.day", day(p)));
        SectService.contribute(p, supply ? 4 : 5);
        say(npc, "murim.bubble.progression.duty");
        return true;
    }
    public static boolean abandonMission(ServerPlayer p) {
        if (!state(p).has("duty.active")) return false;
        save(p, state(p).without("duty.active")); // Today's slot stays used; declining is never an offence.
        return true;
    }
    private static void reportStop(ServerPlayer p) {
        long step = state(p).entry("duty.step", 0);
        if (!state(p).has("duty.patrol") || step >= STOPS.size()) return;
        BlockPos target = BlockPos.of(state(p).entry("duty.stop." + step, 0));
        p.displayClientMessage(Component.translatable("murim.sect.progression.stop", step + 1,
                target.getX(), target.getY(), target.getZ()), false);
    }
    public static void patrolTick(ServerPlayer p) {
        if (!currentMission(p) || !state(p).has("duty.patrol") || p.isPassenger() || p.getAbilities().flying) return;
        long step = state(p).entry("duty.step", 0);
        if (step >= STOPS.size()) return;
        BlockPos target = BlockPos.of(state(p).entry("duty.stop." + step, 0));
        if (p.blockPosition().distSqr(target) <= 25 && p.onGround()) {
            save(p, state(p).record("duty.step", step + 1));
            reportStop(p);
            if (step + 1 == STOPS.size()) p.displayClientMessage(Component.translatable("murim.sect.progression.return"), false);
        }
    }

    public static boolean canRival(ServerPlayer p, SectDisciple npc) {
        return ready(p) && available(p, npc) && speaker(npc, RIVAL) && !npc.defending()
                && state(p).entry("rival.day", -2) + 2 <= day(p) && p.getHealth() > p.getMaxHealth() * 0.5F;
    }
    public static boolean rival(ServerPlayer p, SectDisciple npc) {
        if (!canRival(p, npc)) return false;
        save(p, state(p).record("rival.day", day(p)).record("rival.at", p.level().getGameTime()).with("rival.active"));
        DialogueService.close(p, npc);
        npc.startSpar(p, 20);
        say(npc, "murim.bubble.progression.rival");
        return true;
    }
    public static int rivalStage(SectState s) { return (int) Math.min(2, s.entry("rival.bouts", 0)); }
    /** Existing safe spar, no damage/rank multiplier. Later bouts have longer, clearly telegraphed chains. */
    public static int rivalChain(SectDisciple npc, ServerPlayer p) {
        return speaker(npc, RIVAL) && state(p).has("rival.active") ? 1 + rivalStage(state(p)) : 0;
    }
    public static void sparStarted(ServerPlayer p, SectDisciple npc) {
        if (state(p).has("rival.active") && (!speaker(npc, RIVAL)
                || state(p).entry("rival.at", -1) != p.level().getGameTime())) save(p, state(p).without("rival.active"));
    }
    public static Component rivalLine(ServerPlayer p) {
        return Component.translatable("dialogue.murim.progression.rival.stage." + rivalStage(state(p)))
                .append(" ").append(Component.translatable(state(p).has("rival.won")
                        ? "dialogue.murim.progression.rival.won" : state(p).has("rival.lost")
                        ? "dialogue.murim.progression.rival.lost" : "dialogue.murim.progression.rival.new"));
    }
    public static void sparEnded(ServerPlayer p, SectDisciple npc, boolean won) {
        if (!state(p).member() || !npc.member().isPresent() || SectLife.onWatch(npc) || npc.defending()) return;
        SectState s = state(p).record("spar.count", state(p).entry("spar.count", 0) + 1);
        if (s.has(SectStanding.LESSON_TWO) && !s.has(DIRECT)
                && s.flags().stream().filter(f -> f.startsWith("mentor.partner.")).count() < 3) s = s.with("mentor.partner." + npc.memberKey());
        if (speaker(npc, RIVAL) && s.has("rival.active")) {
            s = s.without("rival.active").record("rival.bouts", s.entry("rival.bouts", 0) + 1)
                    .record(won ? "rival.wins" : "rival.losses", s.entry(won ? "rival.wins" : "rival.losses", 0) + 1)
                    .without("rival.won").without("rival.lost").with(won ? "rival.won" : "rival.lost");
        }
        save(p, s);
    }

    /** Accepted offences only: preserve penance thresholds and never penalise a spar loss. */
    public static void offence(ServerPlayer p, String kind, SectDisciple witness) {
        if (!List.of("assault", "trespass").contains(kind)) return;
        SectState s = state(p).with(REPAIR).without("discipline.apologised").without("discipline.assault").without("discipline.trespass").with("discipline." + kind);
        // At most one victim: a later offence replaces the current apology target.
        for (String flag : List.copyOf(s.flags())) if (flag.startsWith("discipline.victim.")) s = s.without(flag);
        String victim = witness != null && witness.member().isPresent() ? witness.memberKey() : MENTOR;
        s = s.with("discipline.victim." + victim).without("favour." + victim).spend(Math.min(3, s.merit()));
        save(p, s);
        p.displayClientMessage(Component.translatable("murim.sect.progression.offence." + kind), false);
    }
    public static boolean canApologise(ServerPlayer p, SectDisciple npc) {
        return calm(p) && available(p, npc) && state(p).has(REPAIR) && !state(p).has("discipline.apologised")
                && state(p).has("discipline.victim." + npc.memberKey());
    }
    public static boolean apologise(ServerPlayer p, SectDisciple npc) {
        if (!canApologise(p, npc)) return false;
        save(p, state(p).with("discipline.apologised"));
        return true;
    }
    public static boolean canRepair(ServerPlayer p, SectDisciple npc) {
        return calm(p) && available(p, npc) && speaker(npc, MENTOR) && state(p).has(REPAIR)
                && state(p).has("discipline.apologised") && new SectService.ItemNeed(Items.BREAD, 8).count(p) >= 8;
    }
    public static boolean repair(ServerPlayer p, SectDisciple npc) {
        if (!canRepair(p, npc)) return false;
        new SectService.ItemNeed(Items.BREAD, 8).take(p);
        SectState s = state(p).without(REPAIR).without("discipline.apologised");
        for (String flag : List.copyOf(s.flags())) if (flag.startsWith("discipline.victim.")) {
            s = s.with("favour." + flag.substring("discipline.victim.".length())).without(flag);
        }
        save(p, s);
        say(npc, "murim.bubble.progression.repair"); // No merit reward for creating your own offence.
        return true;
    }

    /** One saved news snapshot per world week; no catch-up rewards or forced attendance. */
    public static Component councilNews(ServerPlayer p) {
        SectSiteData site = SectLife.data(p.serverLevel());
        long week = day(p) / 7;
        if (site.councilWeek() < week) {
            boolean danger = !p.level().getEntitiesOfClass(Bandit.class, p.getBoundingBox().inflate(48), Bandit::isAlive).isEmpty();
            site.council(week, danger ? "danger" : p.level().isRaining() ? "rain"
                    : site.suppliedUntil() > day(p) ? "supply" : "peace", site.lastChampion());
        }
        Component news = Component.translatable("dialogue.murim.progression.council." + site.councilTopic());
        if (!site.councilChampion().isEmpty()) {
            String champion = site.councilChampion();
            news = news.copy().append(" ").append(Component.translatable("dialogue.murim.progression.council.champion",
                    champion.startsWith("@") ? Component.literal(champion.substring(1)) : Component.translatable("npc.murim." + champion)));
        }
        return news;
    }
    public static boolean canCouncil(ServerPlayer p, SectDisciple npc) {
        return calm(p) && available(p, npc) && npc.role() == SectRole.LEADER && state(p).entry("council.week", -1) < day(p) / 7;
    }
    public static boolean council(ServerPlayer p, SectDisciple npc) {
        if (!canCouncil(p, npc)) return false;
        Component news = councilNews(p);
        save(p, state(p).record("council.week", day(p) / 7));
        p.displayClientMessage(news, false);
        say(npc, "murim.bubble.progression.council");
        return true;
    }
    public static boolean matches(ServerPlayer p, SectDisciple npc, String value) {
        return switch (value) {
            case "menu" -> calm(p) && available(p, npc);
            case "direct" -> ready(p) && available(p, npc) && speaker(npc, MENTOR) && directEligible(state(p));
            case "ceremony" -> ready(p) && speaker(npc, MENTOR) && state(p).has("mentor.ceremony") && !state(p).has(DIRECT)
                    && p.level().getGameTime() - state(p).entry("mentor.ceremony.at", p.level().getGameTime()) >= 100;
            case "lesson" -> canLesson(p, npc);
            case "archive0", "archive1", "archive2" -> canRead(p, npc, value.charAt(7) - '0');
            case "supply", "patrol" -> canMission(p, npc, value);
            case "complete" -> canFinishMission(p, npc);
            case "abandon" -> state(p).has("duty.active");
            case "rival" -> canRival(p, npc);
            case "council_view" -> calm(p) && available(p, npc) && npc.role() == SectRole.LEADER;
            case "council" -> canCouncil(p, npc);
            case "apology" -> canApologise(p, npc);
            case "repair" -> canRepair(p, npc);
            default -> false;
        };
    }
    public static boolean act(ServerPlayer p, SectDisciple npc, String value) {
        return switch (value) {
            case "direct" -> beginDiscipleship(p, npc);
            case "ceremony" -> finishDiscipleship(p, npc);
            case "lesson" -> lesson(p, npc);
            case "archive0", "archive1", "archive2" -> read(p, npc, value.charAt(7) - '0');
            case "supply", "patrol" -> startMission(p, npc, value);
            case "complete" -> finishMission(p, npc);
            case "abandon" -> abandonMission(p);
            case "rival" -> rival(p, npc);
            case "council" -> council(p, npc);
            case "apology" -> apologise(p, npc);
            case "repair" -> repair(p, npc);
            default -> false;
        };
    }
    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 20 != 0) return;
        patrolTick(p);
        if (!calm(p) || p.getData(ModAttachments.DIALOGUE).active()) return;
        p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(12), d -> d.role() == SectRole.LEADER)
                .stream().filter(d -> canCouncil(p, d)).findFirst().ifPresent(d -> council(p, d));
    }
}
