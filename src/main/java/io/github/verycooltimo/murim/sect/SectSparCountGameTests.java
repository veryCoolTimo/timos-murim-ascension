package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Playtest 19.10: «не засчитываются спарринги». The whole chain, as it runs in play: the bout is opened by the senior's
 * dialogue ({@code start_spar}), fought with real blows through the damage events, and ends by the health floor
 * ({@code SparEvents}); then the lesson mark, the partner, the clean-hit lesson and the bout journal must all show it. The
 * old tests called {@code SectBouts.ended} directly and never proved the links between.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectSparCountGameTests {
    /**
     * One batch per test: the tests of a batch run side by side, both spawn the same senior (seo_rang), and a second person of one key within
     * 256 blocks is folded into the first (SectLife#duplicate, checked every 200 ticks of his life). Whenever that check fell inside the
     * bow, one bout lost its senior and never reached the fight ("fight did not start: BOW_IN", "didn't succeed within 400 ticks"): a
     * race with the load of the machine, not with the clock.
     */
    private static final String BATCH = "sect_spar_count";
    private static final String BATCH_CLEAN = "sect_spar_count_clean";

    private SectSparCountGameTests() { }

    private record Flat(Vec3 origin) implements SectLayout {
        @Override public Vec3 at(String zone, double u, double v) { return origin.add(u, 0, v); }
        @Override public float yaw(double u, double v) { return SectLayout.yawOf(u, v); }
        @Override public double[] half(String zone) { return new double[] {30, 30}; }
        @Override public double[] local(String zone, Vec3 p) { return new double[] {p.x - origin.x, p.z - origin.z}; }
    }

    private static Flat flat(GameTestHelper h) {
        return new Flat(h.absoluteVec(new Vec3(11.5, 2, 10.5)));
    }

    private static Vec3 ring(Flat f) {
        return f.at("training", SectSchedule.RINGS[0][0], SectSchedule.RINGS[0][1]);
    }

    private static ServerPlayer player(GameTestHelper h, Vec3 at) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setOnGround(true);
        try {
            java.lang.reflect.Field field = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
            field.setAccessible(true);
            field.setInt(p, 0);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SectService.SIX, TechniqueProgress.learned(1, 6)));
        return p;
    }

    private static SectDisciple npc(GameTestHelper h, Flat f, String key, Vec3 at) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setKeepAwake(true);
        d.neverGuards = true;
        d.setNoAi(true);
        d.setLayout(f);
        d.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    /** Opens the swing window the way the pupil's own swing does, then lets the player's blow land inside it. */
    private static void clean(SectDisciple d) {
        try {
            java.lang.reflect.Field f = SectDisciple.class.getDeclaredField("lastSwing");
            f.setAccessible(true);
            f.setLong(d, d.level().getGameTime());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void blow(GameTestHelper h, ServerPlayer p, SectDisciple d, boolean clean) {
        if (clean) clean(d);
        d.invulnerableTime = 0;
        d.hurt(h.getLevel().damageSources().playerAttack(p), 3.0F);
    }

    /** Fights until the bout ends (the pupil's health floor), the way the tests above never did. */
    private static void fightOut(GameTestHelper h, ServerPlayer p, SectDisciple d, int cleanBlows) {
        for (int i = 0; i < 60 && d.spar() == SectDisciple.Spar.FIGHT; i++) {
            blow(h, p, d, i < cleanBlows);
        }
    }

    /**
     * Runs {@code body} on the first tick the senior is in the fight. A fixed delay (20 + bow + 6) was a race with the first tick of a newly
     * added entity: when the batch is loaded, the senior starts ticking a few ticks late and the delay ran out in the bow (flaky 19.10).
     */
    private static void whenFighting(GameTestHelper h, SectDisciple senior, Runnable body) {
        boolean[] done = {false};
        h.onEachTick(() -> {
            if (!done[0] && senior.spar() == SectDisciple.Spar.FIGHT) {
                done[0] = true;
                body.run();
            }
        });
    }

    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = BATCH, timeoutTicks = 400)
    public static void aBoutOpenedByDialogueAndFoughtWithBlowsCountsEverywhere(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        h.getLevel().addNewPlayer(p);
        p.setData(ModAttachments.SECT, SectService.state(p).with(SectService.LESSON_SPAR));
        SectDisciple senior = npc(h, f, "seo_rang", ring(f).add(1.5, 0, 0));
        long before = SectService.state(p).entry("spar.count", 0);
        // The senior's dialogue starts the bout exactly like this (DialogueService, action start_spar).
        h.assertTrue(senior.spars() && senior.spar() == SectDisciple.Spar.NONE, "the senior does not spar");
        senior.startSpar(p, 20);
        whenFighting(h, senior, () -> {
            h.assertTrue(SectBouts.active(p) != null, "the bout is not tracked");
            fightOut(h, p, senior, 3);
            h.assertTrue(senior.spar() != SectDisciple.Spar.FIGHT, "the bout never ended through the health floor");
            SectState s = SectService.state(p);
            h.assertTrue(senior.cleanHits() >= SectService.CLEAN_NEEDED, "clean hits not counted: " + senior.cleanHits());
            h.assertTrue(s.has(SectService.SPAR_CLEAN), "three clean hits did not give the lesson mark");
            h.assertTrue(s.entry("spar.count", 0) == before + 1, "the spar was not counted: " + s.entry("spar.count", 0));
            h.assertTrue(SectAttendance.current(p).today().did(SectAttendance.Activity.LESSON), "the bout is no lesson of the day");
            h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            h.succeed();
        });
    }

    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = BATCH_CLEAN, timeoutTicks = 400)
    public static void threeCleanHitsGiveTheLessonMarkAtOnceNotOnlyAtTheEnd(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        h.getLevel().addNewPlayer(p);
        p.setData(ModAttachments.SECT, SectService.state(p).with(SectService.LESSON_SPAR));
        SectDisciple senior = npc(h, f, "seo_rang", ring(f).add(1.5, 0, 0));
        senior.startSpar(p, 20);
        whenFighting(h, senior, () -> {
            for (int i = 0; i < 3; i++) blow(h, p, senior, true);
            h.assertTrue(senior.spar() == SectDisciple.Spar.FIGHT, "the bout ended too early for this check");
            h.assertTrue(SectService.state(p).has(SectService.SPAR_CLEAN), "the third clean hit gave nothing until the bout ended");
            h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            h.succeed();
        });
    }

    private static final String BATCH_B = "sect_spar_playtest_b";

    /** A won bout with no clean touch still counts one (author 22.10: «Вы победили» with 0/3), and adds to the body. */
    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = BATCH_B, timeoutTicks = 400)
    public static void aWonLessonBoutCountsATouchAndTemperBody(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        h.getLevel().addNewPlayer(p);
        p.setData(ModAttachments.SECT, SectService.state(p).with(SectService.LESSON_SPAR));
        double body0 = p.getData(io.github.verycooltimo.murim.training.TrainingRegistry.BODY).points();
        SectDisciple senior = npc(h, f, "seo_rang", ring(f).add(1.5, 0, 0));
        senior.startSpar(p, 20);
        whenFighting(h, senior, () -> {
            fightOut(h, p, senior, 0);
            h.assertTrue(senior.spar() != SectDisciple.Spar.FIGHT, "the bout never ended");
            h.assertTrue(SectService.state(p).entry(SectService.CLEAN_TOTAL, 0) >= 1, "a won bout counted no touch");
            h.assertTrue(p.getData(io.github.verycooltimo.murim.training.TrainingRegistry.BODY).points() > body0,
                    "martial practice gave no body points");
            h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            h.succeed();
        });
    }

    /** A senior in his wind-up shrugs an ordinary blow off; the area of a technique does not touch a bystander. */
    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = BATCH_B + "_armor", timeoutTicks = 400)
    public static void windupHasHyperArmorAndBystandersAreSpared(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        h.getLevel().addNewPlayer(p);
        SectDisciple senior = npc(h, f, "seo_rang", ring(f).add(1.5, 0, 0));
        SectDisciple bystander = npc(h, f, "gyeong_pil", ring(f).add(-6.0, 0, 0));
        senior.startSpar(p, 20);
        whenFighting(h, senior, () -> {
            try {
                java.lang.reflect.Field tick = SectDisciple.class.getDeclaredField("formTick");
                java.lang.reflect.Field wind = SectDisciple.class.getDeclaredField("formWindup");
                tick.setAccessible(true);
                wind.setAccessible(true);
                wind.setInt(senior, 100);
                tick.setInt(senior, 1);
                float h0 = senior.getHealth();
                senior.invulnerableTime = 0;
                senior.hurt(h.getLevel().damageSources().playerAttack(p), 4.0F);
                float armored = h0 - senior.getHealth();
                tick.setInt(senior, -1);
                float h1 = senior.getHealth();
                senior.invulnerableTime = 0;
                senior.hurt(h.getLevel().damageSources().playerAttack(p), 4.0F);
                float open = h1 - senior.getHealth();
                h.assertTrue(armored < open, "the wind-up took as much as an open blow: " + armored + " vs " + open);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            float b0 = bystander.getHealth();
            bystander.hurt(h.getLevel().damageSources().playerAttack(p), 4.0F);
            h.assertTrue(bystander.getHealth() == b0 && bystander.spar() == SectDisciple.Spar.NONE, "a bystander was hit in a spar");
            h.assertTrue(SectBouts.MAX_PAIRS > 1, "the square holds one pair only");
            h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            h.succeed();
        });
    }

    private static void setInt(SectDisciple d, String field, int v) {
        try {
            java.lang.reflect.Field f = SectDisciple.class.getDeclaredField(field);
            f.setAccessible(true);
            f.setInt(d, v);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int getInt(SectDisciple d, String field) {
        try {
            java.lang.reflect.Field f = SectDisciple.class.getDeclaredField(field);
            f.setAccessible(true);
            return f.getInt(d);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The client effects of a senior's Seven Plum Rush start {@code animSkip} ticks into the full definition (the STARTED payload
     * carries it); the real release must land exactly where the effects expect it: skip + wait == the definition's impact tick.
     */
    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = BATCH_B + "_rushsync", timeoutTicks = 400)
    public static void seniorRushReleaseMatchesTheEffectTiming(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        h.getLevel().addNewPlayer(p);
        SectDisciple senior = npc(h, f, "seo_rang", ring(f).add(1.5, 0, 0));
        senior.startSpar(p, 20);
        whenFighting(h, senior, () -> {
            h.assertTrue(senior.hardBout(), "the bout is not a hard one");
            var def = io.github.verycooltimo.murim.technique.TechniqueLoader.get(SectDisciple.RUSH);
            h.assertTrue(def != null, "no rush definition");
            try {
                java.lang.reflect.Method start = SectDisciple.class.getDeclaredMethod("startTechnique", net.minecraft.resources.ResourceLocation.class);
                start.setAccessible(true);
                h.assertTrue((boolean) start.invoke(senior, SectDisciple.RUSH), "the senior did not start the rush");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            int skip = getInt(senior, "animSkip");
            int impact = def.startTickOf(io.github.verycooltimo.murim.combat.TechniquePhase.IMPACT);
            h.assertTrue(skip == def.ticksOf(io.github.verycooltimo.murim.combat.TechniquePhase.WINDUP) - SectDisciple.HARD_WINDUP_MAX,
                    "the skipped ticks are not the cut wind-up: " + skip);
            long t0 = h.getLevel().getGameTime();
            h.onEachTick(() -> {
                try {
                    java.lang.reflect.Field done = SectDisciple.class.getDeclaredField("impactDone");
                    done.setAccessible(true);
                    if (done.getBoolean(senior)) {
                        long waited = h.getLevel().getGameTime() - t0;
                        h.assertTrue(Math.abs(skip + waited - impact) <= 2,
                                "release at " + (skip + waited) + " of the definition, the effects expect " + impact + " (waited " + waited + ", skip " + skip + ")");
                        h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                        h.succeed();
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
        });
    }

    /** A senior's release on a player who does not block: a stun and a shove. */
    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = BATCH_B + "_stun", timeoutTicks = 400)
    public static void unblockedReleaseStunsThePlayer(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        h.getLevel().addNewPlayer(p);
        SectDisciple senior = npc(h, f, "seo_rang", ring(f).add(1.5, 0, 0));
        senior.startSpar(p, 20);
        whenFighting(h, senior, () -> {
            h.assertTrue(senior.hardBout(), "the bout is not a hard one");
            setInt(senior, "formWindup", 0);
            setInt(senior, "formTick", 1);
            h.assertTrue(senior.releasing(), "the senior is not releasing");
            p.setHealth(p.getMaxHealth());
            p.invulnerableTime = 0;
            p.hurt(h.getLevel().damageSources().mobAttack(senior), 2.0F);
            h.assertTrue(io.github.verycooltimo.murim.combat.Stun.isStunned(p), "an unblocked release did not stun");
            h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            h.succeed();
        });
    }

    /** An exact parry of the release staggers the senior and spares the player. */
    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = BATCH_B + "_parry", timeoutTicks = 400)
    public static void parriedReleaseStaggersTheSenior(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        h.getLevel().addNewPlayer(p);
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(io.github.verycooltimo.murim.registry.ModItems.HUASHAN_SWORD.get()));
        SectDisciple senior = npc(h, f, "seo_rang", ring(f).add(1.5, 0, 0));
        senior.startSpar(p, 20);
        whenFighting(h, senior, () -> {
            setInt(senior, "formWindup", 0);
            setInt(senior, "formTick", 1);
            // The senior stands east of the player, who faces him.
            p.moveTo(p.getX(), p.getY(), p.getZ(), -90.0F, 0.0F);
            // MVP R4: vanilla swords leave right-click to vanilla by default.
            io.github.verycooltimo.murim.combat.SwordGuard.hold(p, true);
            h.assertTrue(io.github.verycooltimo.murim.combat.SwordGuard.blocking(p), "parry fixture did not engage guard");
            float hp = p.getHealth();
            p.invulnerableTime = 0;
            p.hurt(h.getLevel().damageSources().mobAttack(senior), 2.0F);
            h.assertTrue(p.getHealth() == hp, "the parried blow still hurt");
            h.assertTrue(!io.github.verycooltimo.murim.combat.Stun.isStunned(p), "the parrier was stunned");
            h.assertTrue(io.github.verycooltimo.murim.combat.Stun.isStunned(senior), "the parried senior did not reel");
            h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            h.succeed();
        });
    }

    /** Blows into a wind-up do not cancel it: they shorten it and make the release stronger. */
    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = BATCH_B + "_greed", timeoutTicks = 400)
    public static void blowsInWindupQuickenItAndDoNotCancelIt(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        h.getLevel().addNewPlayer(p);
        SectDisciple senior = npc(h, f, "seo_rang", ring(f).add(1.5, 0, 0));
        senior.startSpar(p, 20);
        whenFighting(h, senior, () -> {
            setInt(senior, "formWindup", 20);
            setInt(senior, "formTick", 1);
            float f0 = senior.greedFactor();
            for (int i = 0; i < 3; i++) {
                senior.invulnerableTime = 0;
                senior.hurt(h.getLevel().damageSources().playerAttack(p), 6.0F);
            }
            int tick = getInt(senior, "formTick");
            h.assertTrue(tick >= 6 && tick < 20, "the wind-up was cancelled or not quickened: " + tick);
            h.assertTrue(senior.greedFactor() > f0, "greedy blows did not strengthen the release");
            h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            h.succeed();
        });
    }
}
