package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.sect.SectBreakthrough;
import io.github.verycooltimo.murim.sect.SectRoster;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * The world side of the rank breakthrough scene (hua-main-1810 section 4, author 18.10): the shockwave that knocks mobs back
 * without breaking a block, the ten seconds of running, the disciples who walk up and bow, the free guardian who takes the
 * FIRST disruption (the scene goes on) while the second interrupts it without costing the reserve, and a rank-up with no
 * weakness and no sitting after it.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class BreakthroughWorldGameTests {

    private static final String FLOOR = "small_floor";
    private static final String YARD = "sect_yard";

    private BreakthroughWorldGameTests() {
    }

    /** A seeded, seated player with a full reserve, standing in the middle of the small floor. */
    private static ServerPlayer player(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "bt-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(),
                cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(
                net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        Vec3 at = helper.absoluteVec(new Vec3(2.5D, 3.0D, 2.5D));
        p.setPos(at.x, at.y, at.z);
        p.setOnGround(true);
        p.setOldPosAndRot();
        DantianProfile profile = DantianProfile.INITIAL.withTags("clear", "debug").withAxes(60.0D, 0.8D, 0.4D).withPool(40.0D);
        p.setData(ModAttachments.PROFILE, profile);
        p.setData(ModAttachments.CULTIVATION, new CultivationState(Optional.of(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "six_harmonies")), CultivationState.SEEDED));
        return p;
    }

    /** Sits the player and puts the breakthrough at tick {@code t} (the scene starts at {@link Realm#WARNING_TICKS}). */
    private static void inBreakthrough(GameTestHelper h, ServerPlayer p, int t) {
        h.assertTrue(MeditationService.start(p, true), "could not sit down");
        p.setData(ModAttachments.MEDITATION, p.getData(ModAttachments.MEDITATION).withBreakthrough(t));
    }

    /** One server tick of the sitting; the fake player never moves. */
    private static void tick(ServerPlayer p) {
        p.setOnGround(true);
        p.setOldPosAndRot();
        MeditationService.tick(p);
    }

    private static SectDisciple npc(GameTestHelper h, String key, Vec3 absolute) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setKeepAwake(true);
        d.setNoAi(true);
        d.moveTo(absolute.x, absolute.y, absolute.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static Vec3 near(ServerPlayer p, double dx, double dz) {
        return p.position().add(dx, 0.0D, dz);
    }

    private static LivingIncomingDamageEvent blow(GameTestHelper h, ServerPlayer p, Zombie foe) {
        return new LivingIncomingDamageEvent(p, new DamageContainer(h.getLevel().damageSources().mobAttack(foe), 3.0F));
    }

    private static double pool(ServerPlayer p) {
        return p.getData(ModAttachments.PROFILE).pool();
    }

    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void switchingMethodsKeepsBothQiStores(GameTestHelper h) {
        ServerPlayer p = player(h);
        DantianProfile before = p.getData(ModAttachments.PROFILE).withCirculating(12.0D);
        CultivationMethod current = MethodLoader.get(net.minecraft.resources.ResourceLocation
                .fromNamespaceAndPath(MurimMod.MODID, "six_harmonies"));
        for (String name : java.util.List.of("mountain_root", "tang_poison_breath")) {
            CultivationMethod target = MethodLoader.get(net.minecraft.resources.ResourceLocation
                    .fromNamespaceAndPath(MurimMod.MODID, name));
            h.assertTrue(current != null && target != null, "method fixtures did not load");
            var changed = SeedLogic.learn(p.getData(ModAttachments.CULTIVATION), before, target, current);
            h.assertTrue(changed.profile().pool() == before.pool()
                    && changed.profile().circulating() == before.circulating(), "method switch lost qi: " + name);
            h.assertTrue(changed.state().method().orElseThrow().equals(target.id()), "method did not switch");
        }
        h.succeed();
    }

    /** Exercises the actual server gate with every other requirement satisfied. */
    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void allSixBodyGatesBlockThenAllowAtTheExactThreshold(GameTestHelper h) {
        for (int gate = 0; gate < 6; gate++) {
            ServerPlayer p = player(h);
            int rank = Math.min(gate, Realm.PEAK);
            int stage = Math.max(0, gate - Realm.PEAK);
            int need = gate < 4
                    ? io.github.verycooltimo.murim.training.BodyRules.breakthroughNeed(rank + 1)
                    : io.github.verycooltimo.murim.training.BodyRules.stageNeed(stage + 1);
            DantianProfile ready = p.getData(ModAttachments.PROFILE).withRank(rank).withStage(stage);
            ready = ready.withPool(Realm.wall(ready));
            p.setData(ModAttachments.PROFILE, ready);
            p.setData(ModAttachments.MASTERY, io.github.verycooltimo.murim.mastery.MasteryState.EMPTY.with(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_basic"),
                    io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(12, 12))
                    .with(io.github.verycooltimo.murim.sect.SectService.SIX,
                            io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(6, 12))
                    .with(io.github.verycooltimo.murim.sect.SectJourney.PETAL,
                            io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(3, 12)));
            // Rank two needs sect skills, not the optional fortress boss (rank-two-skills ADR).
            p.setData(ModAttachments.SECT, io.github.verycooltimo.murim.sect.SectState.NONE.joined()
                    .with(io.github.verycooltimo.murim.sect.SectProgression.SECOND_TEST)
                    .with("rank2.win.seo_rang").with("rank2.win.seo_gyu").with("rank2.win.seo_ho"));
            var sitting = MeditationState.started(true, CultivationState.SEEDED);
            p.setData(ModAttachments.MEDITATION, sitting);
            io.github.verycooltimo.murim.training.TrainingService.setPoints(p,
                    io.github.verycooltimo.murim.training.BodyRules.threshold(need) - 0.001D);
            h.assertFalse(MeditationService.checkWall(p, ready, sitting), "gate allowed an unready body: " + gate);
            h.assertFalse(p.getData(ModAttachments.MEDITATION).breakingThrough(), "warning started below body gate");
            h.assertTrue(p.getData(ModAttachments.PROFILE).equals(ready), "blocked gate changed qi or rank");
            io.github.verycooltimo.murim.training.TrainingService.setPoints(p,
                    io.github.verycooltimo.murim.training.BodyRules.threshold(need));
            h.assertTrue(MeditationService.checkWall(p, ready, sitting), "gate refused the exact body threshold: " + gate);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ the free guardian

    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void guardianTakesTheFirstBlowAndTheSecondCostsNothing(GameTestHelper h) {
        ServerPlayer p = player(h);
        inBreakthrough(h, p, Realm.WARNING_TICKS + 40);
        double before = pool(p);
        SectDisciple guard = npc(h, "gyeong_pil", near(p, 3.0D, 0.0D));
        Zombie foe = h.spawn(EntityType.ZOMBIE, new Vec3(4.5D, 3.0D, 2.5D));

        LivingIncomingDamageEvent first = blow(h, p, foe);
        NeoForge.EVENT_BUS.post(first);
        h.assertTrue(first.isCanceled(), "the guardian must take the first blow");
        MeditationState state = p.getData(ModAttachments.MEDITATION);
        h.assertTrue(state.breakingThrough() && state.guarded(), "the scene goes on and remembers the blow: " + state);
        h.assertTrue(guard.bubbleKey() != null && guard.bubbleKey().startsWith("murim.bubble.breakthrough.guard."),
                "the guardian answers with a line, got " + guard.bubbleKey());
        tick(p);
        h.assertTrue(p.getData(ModAttachments.MEDITATION).breakingThrough(), "a taken blow must not stop the scene");

        LivingIncomingDamageEvent second = blow(h, p, foe);
        NeoForge.EVENT_BUS.post(second);
        h.assertFalse(second.isCanceled(), "only the FIRST disruption is taken");
        p.hurtTime = 10;
        tick(p);
        h.assertFalse(p.getData(ModAttachments.MEDITATION).active(), "the second blow must interrupt the scene");
        h.assertTrue(pool(p) >= before - 1.0E-9D, "the guardian's help keeps the reserve: " + before + " -> " + pool(p));
        h.assertTrue(p.getActiveEffects().isEmpty(), "no weakness after a guarded interruption: " + p.getActiveEffects());
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void withoutAGuardianTheFirstBlowInterruptsAndHurts(GameTestHelper h) {
        ServerPlayer p = player(h);
        inBreakthrough(h, p, Realm.WARNING_TICKS + 40);
        double before = pool(p);
        // Nobody within six blocks; a disciple eight blocks away is not a guardian.
        npc(h, "gyeong_pil", near(p, 8.0D, 0.0D));
        Zombie foe = h.spawn(EntityType.ZOMBIE, new Vec3(4.5D, 3.0D, 2.5D));
        LivingIncomingDamageEvent hit = blow(h, p, foe);
        NeoForge.EVENT_BUS.post(hit);
        h.assertFalse(hit.isCanceled(), "no guardian, no help");
        p.hurtTime = 10;
        tick(p);
        h.assertFalse(p.getData(ModAttachments.MEDITATION).active(), "the blow must interrupt the scene");
        h.assertTrue(pool(p) < before - 1.0E-6D, "an unguarded interruption still costs a third of the reserve");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void servantsAndTheSleepingDoNotGuard(GameTestHelper h) {
        ServerPlayer p = player(h);
        inBreakthrough(h, p, Realm.WARNING_TICKS + 40);
        npc(h, "cook_kim", near(p, 2.0D, 0.0D));
        h.assertTrue(BreakthroughWorld.guardian(p) == null, "a servant is not a guardian");
        SectDisciple pupil = npc(h, "yul_jin", near(p, 2.0D, 1.0D));
        h.assertTrue(BreakthroughWorld.guardian(p) == pupil, "a disciple within six blocks is");
        pupil.setPos(near(p, 7.0D, 0.0D));
        h.assertTrue(BreakthroughWorld.guardian(p) == null, "seven blocks is too far");
        h.succeed();
    }

    // ------------------------------------------------------------------ the shockwave and the flight

    @GameTest(template = YARD, timeoutTicks = 330)
    public static void shockwaveKnocksMobsBackAndTheyRunForTenSeconds(GameTestHelper h) {
        // The small floor is five blocks wide: a mob cannot run anywhere on it. The sect yard is 24 by 24.
        h.getLevel().setDayTime(18000L);
        ServerPlayer p = player(h);
        Vec3 at = h.absoluteVec(new Vec3(11.5D, 2.0D, 11.5D));
        p.setPos(at.x, at.y, at.z);
        p.setOldPosAndRot();
        Zombie close = h.spawn(EntityType.ZOMBIE, new Vec3(13.5D, 2.0D, 11.5D));
        Zombie mid = h.spawn(EntityType.ZOMBIE, new Vec3(11.5D, 2.0D, 17.5D));
        Zombie far = h.spawn(EntityType.ZOMBIE, new Vec3(11.5D, 2.0D, 0.5D));
        BlockPos glass = h.absolutePos(new BlockPos(12, 2, 11));
        h.getLevel().setBlockAndUpdate(glass, Blocks.GLASS.defaultBlockState());
        double startDistance = close.position().distanceTo(p.position());

        BreakthroughWorld.impact(p, 1);

        Vec3 push = close.getDeltaMovement();
        Vec3 away = close.position().subtract(p.position()).multiply(1.0D, 0.0D, 1.0D).normalize();
        h.assertTrue(push.dot(away) > 0.5D, "a mob inside the radius is thrown away from the player, got " + push);
        h.assertTrue(BreakthroughWorld.fleeing(close), "a mob within eight blocks runs");
        h.assertTrue(BreakthroughWorld.fleeing(mid), "a mob at six blocks (outside the third rank's 3 blocks) still runs");
        h.assertTrue(mid.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4D, "outside the radius nothing pushes it");
        h.assertFalse(BreakthroughWorld.fleeing(far), "a mob beyond eight blocks does not care");
        h.assertTrue(h.getLevel().getBlockState(glass).is(Blocks.GLASS), "the shockwave must not touch a block");

        h.runAfterDelay(70, () -> {
            h.assertTrue(close.position().distanceTo(p.position()) > startDistance + 2.0D,
                    "the mob must have run away, from " + startDistance + " to " + close.position().distanceTo(p.position()));
            h.assertTrue(BreakthroughWorld.fleeing(close), "ten seconds are not over yet");
        });
        h.runAfterDelay(BreakthroughWorld.FLEE_TICKS + 40, () -> {
            h.assertFalse(BreakthroughWorld.fleeing(close), "after ten seconds the mob is itself again");
            h.succeed();
        });
    }

    @GameTest(template = FLOOR, timeoutTicks = 40)
    public static void theSectsPeopleAreNotThrownAndTamedPetsAreSpared(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectDisciple pupil = npc(h, "yul_jin", near(p, 2.0D, 0.0D));
        net.minecraft.world.entity.animal.Wolf wolf = h.spawn(EntityType.WOLF, new Vec3(1.5D, 3.0D, 2.5D));
        wolf.tame(p);
        BreakthroughWorld.impact(p, 1);
        h.assertTrue(pupil.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4D, "a disciple is not thrown");
        h.assertTrue(wolf.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4D && !BreakthroughWorld.fleeing(wolf),
                "a tamed wolf is neither thrown nor frightened");
        h.succeed();
    }

    // ------------------------------------------------------------------ the people come and bow

    @GameTest(template = YARD, timeoutTicks = 60)
    public static void freeDisciplesWalkUpAndBowButElderServantsAndGuardsStay(GameTestHelper h) {
        ServerPlayer p = player(h);
        // The five-block floor puts the called disciples beyond its barrier shell, hiding the impact.
        // API: Minecraft 1.21.1 GameTestHelper#absoluteVec, Entity#setPos(Vec3),
        // LivingEntity#hasLineOfSight and Sensing#hasLineOfSight (read-only /tmp/mr-ref-1121/minecraft-src).
        p.setPos(h.absoluteVec(new Vec3(11.5D, 2.0D, 11.5D)));
        p.setOldPosAndRot();
        SectDisciple pupil = npc(h, "yul_jin", near(p, 7.0D, 0.0D));
        SectDisciple senior = npc(h, "mok_hayeon", near(p, -6.0D, 1.0D));
        SectDisciple elder = npc(h, "tae_rok", near(p, 0.0D, 6.0D));
        SectDisciple cook = npc(h, "cook_kim", near(p, 0.0D, -6.0D));
        SectDisciple sleeper = npc(h, "yul_seok", near(p, 5.0D, 5.0D));
        sleeper.setWounded(true);
        SectDisciple far = npc(h, "yul_pyo", near(p, 30.0D, 0.0D));

        h.assertTrue(pupil.getSensing().hasLineOfSight(p) && senior.getSensing().hasLineOfSight(p),
                "both free disciples must see the breakthrough before they can acknowledge it");
        BreakthroughWorld.impact(p, 1);

        h.assertTrue(SectBreakthrough.waitingToBow(pupil) && SectBreakthrough.waitingToBow(senior), "the free disciples are called");
        h.assertFalse(SectBreakthrough.waitingToBow(elder), "an elder keeps his place");
        h.assertFalse(SectBreakthrough.waitingToBow(cook), "a servant keeps working");
        h.assertFalse(SectBreakthrough.waitingToBow(sleeper), "a wounded person stays where he is");
        h.assertFalse(SectBreakthrough.waitingToBow(far), "nobody beyond sixteen blocks hears it");
        CompoundTag tag = pupil.getPersistentData().getCompound("murim_sect_yield");
        Vec3 spot = new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
        h.assertTrue(spot.distanceTo(p.position()) < SectBreakthrough.STAND_OFF + 0.5D && tag.getLong("until") > h.getLevel().getGameTime(),
                "he is sent to stand round the player, got " + spot);

        // He arrives; forty ticks after the impact the follow-up window makes him bow and say one of the acknowledge lines.
        pupil.moveTo(spot.x, spot.y, spot.z, 0, 0);
        p.getPersistentData().putLong(BreakthroughWorld.IMPACT_AT, h.getLevel().getGameTime() - 40L);
        NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(p));
        h.assertTrue(SectBreakthrough.bowed(pupil) && pupil.poseHeld(), "the person who arrived bows");
        h.assertTrue(pupil.bubbleKey() != null && pupil.bubbleKey().startsWith("murim.bubble.social.acknowledge."),
                "and says an acknowledge line, got " + pupil.bubbleKey());
        h.assertFalse(SectBreakthrough.bowed(senior), "the one still on the way has not bowed");
        h.succeed();
    }

    // ------------------------------------------------------------------ the afterglow costs nothing

    @GameTest(template = FLOOR, timeoutTicks = 60)
    public static void aRankUpLeavesNoWeaknessAndNoSittingAndOpensTheFollowUp(GameTestHelper h) {
        for (int target = Realm.THIRD; target <= Realm.PEAK; target++) {
            ServerPlayer p = player(h);
            p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(target - 1));
            int blow = Realm.WARNING_TICKS + Realm.breakthroughTicks(target);
            inBreakthrough(h, p, blow - 2);
            tick(p);
            h.assertTrue(p.getData(ModAttachments.PROFILE).rank() == target - 1,
                    "rank must not rise before blow for target " + target);
            p.setHealth(p.getMaxHealth() * 0.5F);
            tick(p);
            h.assertTrue(p.getData(ModAttachments.PROFILE).rank() == target,
                    "rank must rise at warning + scene ticks for target " + target);
            h.assertFalse(p.getData(ModAttachments.MEDITATION).active(), "the session ends");
            h.assertTrue(p.getActiveEffects().isEmpty(), "no weakness after the breakthrough");
            h.assertTrue(p.getHealth() >= p.getMaxHealth() - 1.0E-3F, "full health at once");
            h.assertTrue(p.getPersistentData().contains(BreakthroughWorld.IMPACT_AT), "world follow-up opens");
        }
        h.succeed();
    }
}
