package io.github.verycooltimo.murim.mastery;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.SectProgression;
import io.github.verycooltimo.murim.sect.SectState;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec2;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /murim progress}: the command runs for an ordinary player (permission 0) through the real dispatcher, lists a learned
 * art with its stars and the cost of the next one, shows a cross for a missing breakthrough condition and a tick when it is met,
 * and the ring of sources records a training gain.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class ProgressCommandGameTests {

    private static final String FLOOR = "small_floor";
    private static final ResourceLocation SIX = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "six_harmonies");
    private static final String K = "command.murim.progress.";

    private ProgressCommandGameTests() {
    }

    private static ServerPlayer player(GameTestHelper h) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "progress-cmd"), false);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(
                net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie);
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("pure", "debug"));
        return p;
    }

    /** Runs a command line for the player with permission 0 and returns what it said. */
    private static List<Component> run(GameTestHelper h, ServerPlayer p, String line) {
        return run(h, p, line, 0);
    }

    private static List<Component> run(GameTestHelper h, ServerPlayer p, String line, int permission) {
        List<Component> said = new ArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override public void sendSystemMessage(Component message) { said.add(message); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        CommandSourceStack source = new CommandSourceStack(sink, p.position(), Vec2.ZERO, h.getLevel(), permission, "t",
                Component.literal("t"), h.getLevel().getServer(), p);
        try {
            h.getLevel().getServer().getCommands().getDispatcher().execute(line, source);
        } catch (CommandSyntaxException e) {
            throw new IllegalStateException("command refused: " + line + ": " + e.getMessage(), e);
        }
        return said;
    }

    private static TranslatableContents find(List<Component> lines, String key) {
        for (Component line : lines) {
            if (line.getContents() instanceof TranslatableContents t && t.getKey().equals(K + key)) return t;
            for (Component s : line.getSiblings()) {
                if (s.getContents() instanceof TranslatableContents t && t.getKey().equals(K + key)) return t;
            }
        }
        return null;
    }

    private static Component row(List<Component> lines, String key) {
        for (Component line : lines) {
            for (Component s : line.getSiblings()) {
                if (s.getContents() instanceof TranslatableContents t && (t.getKey().equals(K + key) || t.getKey().equals(key))) return line;
            }
        }
        return null;
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void commandListsLearnedArtWithStarsAndCost(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SIX, new TechniqueProgress(3, 60.0D, 12.0D, 0L, 12)));
        List<Component> said = run(h, p, "murim progress");
        TranslatableContents art = find(said, "art");
        h.assertTrue(art != null, "no art line");
        h.assertTrue(art.getArgs()[1].equals(3) && art.getArgs()[2].equals(12), "stars shown: " + java.util.Arrays.toString(art.getArgs()));
        TranslatableContents xp = find(said, "xp");
        // The table: 280 -> 520 is the cost of star 4, so 240 for the transition 3 -> 4; 60 of it is 25 %.
        h.assertTrue(xp != null && "60.0".equals(xp.getArgs()[0]) && "240.0".equals(xp.getArgs()[1]) && xp.getArgs()[2].equals(25L),
                "xp line: " + (xp == null ? "missing" : java.util.Arrays.toString(xp.getArgs())));
        TranslatableContents reserve = find(said, "reserve");
        h.assertTrue(reserve != null && "12.0".equals(reserve.getArgs()[0]), "reserve line missing");
        TranslatableContents help = find(said, "help");
        h.assertTrue(help != null && "48.0".equals(help.getArgs()[1]), "help ceiling should be a fifth of 240");
        // An art filter that matches nothing says so; one that matches shows the same art.
        h.assertTrue(find(run(h, p, "murim progress nonsense"), "unknown") != null, "unknown filter not reported");
        h.assertTrue(find(run(h, p, "murim progress six"), "art") != null, "filter by part of the name failed");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void breakthroughShowsCrossThenTick(GameTestHelper h) {
        ServerPlayer p = player(h);
        DantianProfile profile = p.getData(ModAttachments.PROFILE);
        // Nothing yet: pool empty, no art.
        List<Component> bad = run(h, p, "murim progress breakthrough");
        Component wall = row(bad, "bt.wall");
        Component art = row(bad, "bt.art.none");
        h.assertTrue(wall != null && wall.getString().startsWith("✘"), "empty pool must be a cross");
        h.assertTrue(art != null && art.getString().startsWith("✘"), "missing art must be a cross");
        h.assertTrue(find(bad, "bt.notready") != null && find(bad, "bt.ready") == null, "must not be ready");
        // Pool at the wall and the art at the star the first breakthrough asks for (★1).
        p.setData(ModAttachments.PROFILE, profile.withPool(io.github.verycooltimo.murim.cultivation.Realm.wall(profile)));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SIX, TechniqueProgress.learned(1, 12)));
        Component body = row(run(h, p, "murim progress breakthrough"), "bt.body");
        h.assertTrue(body != null && body.getString().startsWith("✘"), "untrained body must be a cross");
        io.github.verycooltimo.murim.training.TrainingService.setPoints(p,
                io.github.verycooltimo.murim.training.BodyRules.threshold(
                        io.github.verycooltimo.murim.training.BodyRules.breakthroughNeed(1)));
        List<Component> good = run(h, p, "murim progress breakthrough");
        for (String key : List.of("bt.dantian", "bt.wall", "bt.art", "bt.body")) {
            Component r = row(good, key);
            h.assertTrue(r != null && r.getString().startsWith("✔"), key + " must be a tick: " + (r == null ? "missing" : r.getString()));
        }
        h.assertTrue(find(good, "bt.ready") != null, "ready line missing");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void peakProgressRequiresTheExactBodyThreshold(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(
                ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_basic"), TechniqueProgress.learned(12, 12)));
        for (int stage = 0; stage < 2; stage++) {
            DantianProfile profile = p.getData(ModAttachments.PROFILE)
                    .withRank(io.github.verycooltimo.murim.cultivation.Realm.PEAK).withStage(stage);
            p.setData(ModAttachments.PROFILE, profile.withPool(io.github.verycooltimo.murim.cultivation.Realm.wall(profile)));
            int need = io.github.verycooltimo.murim.training.BodyRules.stageNeed(stage + 1);
            double threshold = io.github.verycooltimo.murim.training.BodyRules.threshold(need);
            io.github.verycooltimo.murim.training.TrainingService.setPoints(p, threshold - 0.001D);
            List<Component> blocked = run(h, p, "murim progress breakthrough");
            Component body = row(blocked, "bt.body");
            h.assertTrue(body != null && body.getString().startsWith("✘"), "Peak body gate must be crossed");
            h.assertTrue(find(blocked, "bt.notready") != null, "Peak must wait for body training");
            io.github.verycooltimo.murim.training.TrainingService.setPoints(p, threshold);
            List<Component> ready = run(h, p, "murim progress breakthrough");
            body = row(ready, "bt.body");
            h.assertTrue(body != null && body.getString().startsWith("✔"), "Peak body threshold must be checked");
            h.assertTrue(find(ready, "bt.ready") != null, "Peak must be ready at the body threshold");
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void sourcesRingRecordsTrainingGain(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SIX, TechniqueProgress.learned(0, 12)));
        ProgressLog.clear(p);
        ArmorStand stand = h.spawn(EntityType.ARMOR_STAND, 1, 2, 1);
        MasteryService.onHit(p, SIX, stand);
        MasteryService.onMiss(p, SIX);
        List<ProgressLog.Entry> ring = ProgressLog.entries(p);
        h.assertTrue(ring.size() == 2, "ring should hold two gains, has " + ring.size());
        h.assertTrue(ring.get(0).origin() == ProgressLog.Origin.TRAINING && ring.get(0).amount() > 0.0D
                && ring.get(0).art().equals(SIX), "newest gain is not a training gain of the art");
        List<Component> said = run(h, p, "murim progress");
        long lines = said.stream().filter(c -> c.getContents() instanceof TranslatableContents t
                && t.getKey().equals(K + "recent.line")).count();
        h.assertTrue(lines == 2, "command should list both gains, listed " + lines);
        // The ring is capped.
        for (int i = 0; i < 30; i++) {
            MasteryService.onMiss(p, SIX);
        }
        h.assertTrue(ProgressLog.entries(p).size() == ProgressLog.SIZE, "ring is not capped at " + ProgressLog.SIZE);
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void helpCeilingAndFatigueAreExplained(GameTestHelper h) {
        ServerPlayer p = player(h);
        // The help pool of star 2 -> 3 is full: a fifth of 160 is 32.
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SIX,
                new TechniqueProgress(2, 10.0D, 0.0D, 0L, 12, TechniqueProgress.VERSION, 32.0D)));
        List<Component> said = run(h, p, "murim progress");
        h.assertTrue(find(said, "blocked.help") != null, "help ceiling not explained");
        // Dummy fatigue: many hits on a stand today.
        ArmorStand stand = h.spawn(EntityType.ARMOR_STAND, 1, 2, 1);
        for (int i = 0; i < 200; i++) {
            MasteryService.onHit(p, SIX, stand);
        }
        said = run(h, p, "murim progress");
        h.assertTrue(find(said, "blocked.dummy.monotony") != null, "dummy fatigue not explained");
        h.succeed();
    }
    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void reportsAreReadOnlyAndHistoryShowsOnlyFive(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SIX, TechniqueProgress.learned(2, 12)));
        for (int i = 0; i < 24; i++) {
            ProgressLog.record(p, SIX, ProgressLog.Origin.FIGHT, i, ProgressLog.Why.NONE);
        }
        MasteryState before = p.getData(ModAttachments.MASTERY);
        var profile = p.getData(ModAttachments.PROFILE);
        var persistent = p.getPersistentData().copy();
        List<ProgressLog.Entry> history = ProgressLog.entries(p);
        h.assertTrue(history.size() == 20 && history.get(0).amount() == 23 && history.get(19).amount() == 4,
                "ring must retain the newest 20 entries in reverse order");
        List<Component> said = run(h, p, "murim progress six");
        h.assertTrue(said.stream().filter(c -> c.getContents() instanceof TranslatableContents t
                && t.getKey().equals(K + "recent.line")).count() == 5, "report must show only five entries");
        run(h, p, "murim progress breakthrough");
        h.assertTrue(before.equals(p.getData(ModAttachments.MASTERY))
                && profile.equals(p.getData(ModAttachments.PROFILE))
                && persistent.equals(p.getPersistentData()) && history.equals(ProgressLog.entries(p)),
                "read-only commands changed player state");
        ProgressLog.clear(p);
        run(h, p, "murim progress");
        h.assertTrue(!p.hasData(ModAttachments.PROGRESS_LOG), "reading an empty history must not create a ring");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void permissionChecksKeepOtherPlayersPrivate(GameTestHelper h) {
        ServerPlayer p = player(h);
        boolean denied = false;
        try {
            run(h, p, "murim progress someone_else breakthrough");
        } catch (IllegalStateException e) {
            denied = e.getCause() instanceof CommandSyntaxException c
                    && c.getRawMessage() instanceof Component text
                    && text.getContents() instanceof TranslatableContents t && t.getKey().equals(K + "denied");
        }
        h.assertTrue(denied, "permission zero must not inspect another player's breakthrough");
        boolean offline = false;
        try {
            run(h, p, "murim progress someone_else breakthrough", 2);
        } catch (IllegalStateException e) {
            offline = e.getCause() instanceof CommandSyntaxException c
                    && c.getRawMessage() instanceof Component text
                    && text.getContents() instanceof TranslatableContents t && t.getKey().equals(K + "offline");
        }
        h.assertTrue(offline, "permission two should pass authorization and check online status");
        h.assertTrue(find(run(h, p, "murim progress progress-cmd breakthrough"), "bt.rank") != null,
                "explicit self target must remain available at permission zero");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void cappedManuscriptKeepsReserveAndCannotGain(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SIX,
                new TechniqueProgress(3, 0, 12, 0, 3)));
        MasteryService.onMiss(p, SIX);
        h.assertTrue(ProgressLog.entries(p).getFirst().amount() == 0
                && ProgressLog.entries(p).getFirst().why() == ProgressLog.Why.COMPLETE,
                "a capped manuscript must not claim a positive gain");
        List<Component> said = run(h, p, "murim progress");
        h.assertTrue(find(said, "reserve") != null && find(said, "help") != null
                && find(said, "complete.manuscript") != null, "capped manuscript lost its diagnostics");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void dummyFatigueIsSharedAndResetsNextDay(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SIX, TechniqueProgress.learned(0, 12)));
        var first = h.spawn(io.github.verycooltimo.murim.registry.ModEntities.DUMMY.get(), 1, 2, 1);
        var second = h.spawn(io.github.verycooltimo.murim.registry.ModEntities.DUMMY.get(), 3, 2, 1);
        long time = h.getLevel().getDayTime();
        try {
            for (int i = 0; i < 100; i++) MasteryService.onHit(p, SIX, first);
            MasteryService.onHit(p, SIX, second);
            h.assertTrue(MasteryService.hitsToday(p, "training") == 101, "all dummies must share one counter");
            TranslatableContents line = find(run(h, p, "murim progress"), "dummy");
            h.assertTrue(line != null && line.getArgs()[1].equals(101)
                    && line.getArgs()[0].equals(Math.round(100 * YardRules.fatigue(101, false))),
                    "report disagrees with the live shared fatigue curve");
            h.getLevel().setDayTime(time + 24000);
            h.assertTrue(MasteryService.hitsToday(p, "training") == 0, "daily fatigue did not recover");
            line = find(run(h, p, "murim progress"), "dummy");
            h.assertTrue(line != null && line.getArgs()[0].equals(100L), "next day must report full return");
        } finally {
            h.getLevel().setDayTime(time);
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void breakthroughUsesSectSkillsAndFootworkCap(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(1));
        String key = "murim.rank.second.checklist";
        Component skills = row(run(h, p, "murim progress breakthrough"), key);
        h.assertTrue(skills != null && skills.getString().startsWith("✘"), "missing sect skills must be crossed");
        p.setData(io.github.verycooltimo.murim.entity.boss.BossRegistry.BOSS_DEFEATED, true);
        skills = row(run(h, p, "murim progress breakthrough"), key);
        h.assertTrue(skills != null && skills.getString().startsWith("✘"), "boss must not replace sect skills");
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY
                .with(SIX, TechniqueProgress.learned(6, 12))
                .with(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "falling_petal_sword"),
                        TechniqueProgress.learned(3, 12)));
        p.setData(ModAttachments.SECT, SectState.NONE.joined()
                .with("rank2.win.seo_rang").with("rank2.win.seo_gyu").with("rank2.win.seo_ho"));
        skills = row(run(h, p, "murim progress breakthrough"), key);
        h.assertTrue(skills != null && skills.getString().startsWith("✘"), "mentor test must still be required");
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with(SectProgression.SECOND_TEST));
        p.setData(io.github.verycooltimo.murim.entity.boss.BossRegistry.BOSS_DEFEATED, false);
        skills = row(run(h, p, "murim progress breakthrough"), key);
        h.assertTrue(skills != null && skills.getString().startsWith("✔"), "completed sect skills must be checked without boss");
        ResourceLocation step = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "shadow_step");
        ResourceLocation plum = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_basic");
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(2));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(step, TechniqueProgress.learned(10, 10)));
        Component art = row(run(h, p, "murim progress breakthrough"), "bt.art.main");
        h.assertTrue(art != null && art.getString().startsWith("✘"), "footwork must not satisfy FIRST");
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(plum, TechniqueProgress.learned(6, 12)));
        art = row(run(h, p, "murim progress breakthrough"), "bt.art");
        h.assertTrue(art != null && art.getString().startsWith("✔"), "Seven Plum six must satisfy FIRST");
        TranslatableContents contents = find(List.of(art), "bt.art");
        h.assertTrue(contents != null && contents.getArgs()[2].equals(6), "the requirement must be six for Seven Plum");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void weakTargetIsExplainedByRealAward(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(3));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SIX, TechniqueProgress.learned(0, 12)));
        MasteryService.onHit(p, SIX, h.spawn(EntityType.ZOMBIE, 1, 2, 1));
        h.assertTrue(ProgressLog.entries(p).getFirst().why() == ProgressLog.Why.WEAK_TARGET,
                "award did not record a weak target");
        h.assertTrue(find(run(h, p, "murim progress"), "blocked.fight.weak_target") != null,
                "report did not explain the weak target");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void methodPracticeUsesItsOwnHistoryTrack(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setData(ModAttachments.CULTIVATION,
                io.github.verycooltimo.murim.cultivation.CultivationState.NONE.withMethod(SIX));
        MasteryService.practiceMethod(p);
        List<ProgressLog.Entry> history = ProgressLog.entries(p);
        h.assertTrue(history.size() == 1 && history.getFirst().origin() == ProgressLog.Origin.METHOD
                && history.getFirst().art().equals(Arts.methodArt(SIX)) && history.getFirst().amount() > 0,
                "method practice must award to the method art, not the sword art sharing its id");
        h.assertTrue(find(run(h, p, "murim progress method_six_harmonies"), "recent.line") != null,
                "method history is missing from report");
        h.succeed();
    }

}
