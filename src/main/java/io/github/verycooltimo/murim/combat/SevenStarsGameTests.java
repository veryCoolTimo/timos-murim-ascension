package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.*;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.Optional;

/** API: MC 1.21.1 GameTestHelper (spawn/setBlock/absoluteVec/assertTrue/succeed),
 * Entity (moveTo/setOnGround/position), Mob#setNoAi; NeoForge gametest annotations.
 * Fixtures intentionally advance the same authoritative tick used by PlayerTickEvent.Post.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SevenStarsGameTests {
    private static void lane(GameTestHelper h) {
        for (int x = 0; x <= 4; x++) for (int z = 0; z <= 12; z++) {
            h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            for (int y = 2; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }
    private static ServerPlayer player(GameTestHelper h, int stars, boolean follow) {
        ServerPlayer p = FootworkMasteryGameTests.player(h, stars);
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withAxes(100, .9, .5).withCirculating(50));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY
                .with(Styles.SEVEN_STARS.forms().get(0), TechniqueProgress.learned(stars, 12))
                .with(Styles.SEVEN_STARS.forms().get(1), TechniqueProgress.learned(stars, 12)));
        p.setData(ModAttachments.LOADOUT, Loadout.EMPTY.with(0, Optional.of(Styles.SEVEN_STARS.forms().get(follow ? 1 : 0))));
        Vec3 at = h.absoluteVec(new Vec3(2.5, 2, 1));
        p.moveTo(at.x, at.y, at.z, 0, 0); p.setOnGround(true);
        return p;
    }
    private static void cast(ServerPlayer p, boolean follow) {
        FootworkService.request(p, Styles.SEVEN_STARS.forms().get(follow ? 1 : 0), FootworkService.FORWARD);
    }

    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void behindFollowsItsApprovedArcWithoutRoutineTeleports(GameTestHelper h) {
        lane(h);
        ServerPlayer p = player(h, 12, false);
        var form = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "dark_fragrance_behind");
        p.setData(ModAttachments.LOADOUT, Loadout.EMPTY.with(0, Optional.of(form)));
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY).with(form, TechniqueProgress.learned(12, 12)));
        var target = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 3));
        target.setNoAi(true);
        p.setData(ModAttachments.FOOTWORK_DIR, new float[]{0, 1, 1, 4});
        FootworkService.windEvade(p, form);
        var approved = FootworkService.approvedRoute(p);
        h.assertTrue(approved.size() > 4, "Behind did not build an arc");
        h.assertTrue(approved.stream().anyMatch(at -> Math.abs(at.x - approved.getFirst().x) > .5), "Behind used a chord");
        for (int tick = 1; tick <= 6; tick++) {
            FootworkService.tick(p);
            Vec3 expected = SevenStarsRules.routePoint(approved, tick / 6.0);
            h.assertTrue(p.position().distanceTo(expected) < .1, "body left approved ribbon route at tick " + tick);
        }
        h.assertTrue(!FootworkService.active(p), "Behind changed its six-tick movement budget");
        h.assertTrue(FootworkMasteryGameTests.teleports(p) == 0, "routine Behind teleported");
        p.discard(); target.discard();
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void distancesTimingAndPaymentAtEveryPlateau(GameTestHelper h) {
        lane(h);
        for (int stars : new int[] {0, 1, 3, 5, 8, 10, 12}) for (boolean follow : new boolean[] {false, true}) {
            ServerPlayer p = player(h, stars, follow);
            Vec3 start = p.position();
            double qi = p.getData(ModAttachments.PROFILE).circulating();
            cast(p, follow);
            h.assertTrue(FootworkService.active(p), "selected form refused at " + stars);
            h.assertTrue(Math.abs(qi - p.getData(ModAttachments.PROFILE).circulating() - SevenStarsRules.qi(stars)) < 1e-6, "server qi payment");
            int duration = SevenStarsRules.PREPARATION + SevenStarsRules.movement(follow)
                    + SevenStarsRules.recovery(follow);
            // Observe the endpoint and a later tick so a completed form cannot resume moving.
            for (int age = 1; age <= duration + 1; age++) {
                FootworkService.tick(p);
                double moved = p.position().distanceTo(start);
                double expected = (follow ? Math.min(2, SevenStarsRules.distance(stars, true)) : SevenStarsRules.distance(stars, false))
                        * Math.max(0, Math.min(1, (age - SevenStarsRules.PREPARATION) / (double) SevenStarsRules.movement(follow)));
                h.assertTrue(Math.abs(moved - expected) <= .2, "distance/timing at star " + stars + " age " + age);
                // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/gametest/framework/GameTestHelper.java#assertTrue.
                h.assertTrue(FootworkService.active(p) == (age < duration),
                        "preparation/movement/recovery duration at star " + stars
                                + " follow=" + follow + " age=" + age + " duration=" + duration);
            }
            h.assertTrue(FootworkMasteryGameTests.teleports(p) == 0, "routine Shift/Follow sent a correction teleport");
            double balance = p.getData(ModAttachments.PROFILE).circulating();
            cast(p, follow);
            h.assertTrue(!FootworkService.active(p) && balance == p.getData(ModAttachments.PROFILE).circulating(), "cooldown not enforced");
            FootworkService.cancel(p);
        }
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void onlySelectionPaysAndChangingFormCancels(GameTestHelper h) {
        lane(h);
        for (boolean follow : new boolean[] {false, true}) {
            ServerPlayer p = player(h, 12, follow);
            double qi = p.getData(ModAttachments.PROFILE).circulating();
            cast(p, !follow);
            h.assertTrue(!TechniqueService.tryStart(p, Styles.SEVEN_STARS.forms().get(follow ? 0 : 1)), "direct cast bypassed selection");
            h.assertTrue(!FootworkService.active(p) && qi == p.getData(ModAttachments.PROFILE).circulating()
                    && p.getData(ModAttachments.COOLDOWNS).isEmpty(), "unselected form changed server state");
            FootworkService.request(p, Styles.SEVEN_STARS.forms().get(follow ? 1 : 0), 0);
            h.assertTrue(FootworkService.active(p) == follow, "plain R contract");
            FootworkService.cancel(p);
            p.setData(ModAttachments.COOLDOWNS, java.util.Map.of());
            cast(p, follow);
            FootworkService.tick(p); FootworkService.tick(p); FootworkService.tick(p);
            Vec3 before = p.position();
            LoadoutService.selectForm(p, 0, Styles.SEVEN_STARS.forms().get(follow ? 0 : 1), false);
            for (int i = 0; i < 12; i++) FootworkService.tick(p);
            h.assertTrue(!FootworkService.active(p) && p.position().equals(before), "changed form kept moving");
        }
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void wallsStopBothForms(GameTestHelper h) {
        lane(h);
        for (int x = 0; x <= 4; x++) for (int y = 2; y <= 4; y++) h.setBlock(new BlockPos(x, y, 3), Blocks.STONE);
        for (boolean follow : new boolean[] {false, true}) {
            ServerPlayer p = player(h, 12, follow); Vec3 start = p.position();
            cast(p, follow);
            for (int i = 0; i < 12; i++) FootworkService.tick(p);
            // API: MC 1.21.1 GameTestHelper#absoluteVec; Entity#getBbWidth/getZ.
            // The wall starts at local z=3 and the player centre starts at z=1.
            double wallFace = h.absoluteVec(new Vec3(2.5, 2, 3)).z;
            double stoppedAt = wallFace - p.getBbWidth() / 2;
            h.assertTrue(Math.abs(p.getZ() - stoppedAt) < .01 && p.getZ() > start.z,
                    "wall did not clip " + (follow ? "follow" : "shift") + ": " + p.getZ() + " expected " + stoppedAt);
            h.assertTrue(!FootworkService.active(p), "wall left motion running");
        }
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void chasingStopsAtSwordReachWithoutMovingTarget(GameTestHelper h) {
        lane(h);
        ServerPlayer p = player(h, 12, true);
        var target = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 5)); target.setNoAi(true);
        Vec3 body = target.position(); TargetLock.set(p, target.getId());
        p.getData(ModAttachments.WIND_GOD).chasedFoe = target.getUUID();
        p.getData(ModAttachments.WIND_GOD).chasedHit = p.level().getGameTime();
        target.setDeltaMovement(0, 0, .1);
        cast(p, true);
        for (int i = 0; i < 12; i++) {
            FootworkService.tick(p);
            h.assertTrue(p.getZ() < target.getZ(), "passed target body");
        }
        double gap = p.position().distanceTo(target.position());
        h.assertTrue(gap >= 1.65 && gap <= 2 && target.position().equals(body), "wrong sword reach or target pushed");
        h.assertTrue(p.getLookAngle().dot(target.position().subtract(p.position()).normalize()) > .99, "did not face target");
        target.discard(); FootworkService.cancel(p); h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void bodyEnteringThePathDuringPreparationStopsFollowing(GameTestHelper h) {
        lane(h);
        ServerPlayer p = player(h, 12, true);
        cast(p, true); // No target: a straight approach.
        FootworkService.tick(p); FootworkService.tick(p);
        var body = h.spawn(EntityType.COW, new Vec3(2.5, 2, 2.5)); body.setNoAi(true);
        Vec3 at = body.position();
        for (int i = 0; i < 12; i++) FootworkService.tick(p);
        h.assertTrue(p.getZ() + p.getBbWidth() / 2 < body.getZ() - body.getBbWidth() / 2,
                "impulse passed a newly arrived body");
        h.assertTrue(body.position().equals(at), "approach pushed a bystander");
        body.discard(); FootworkService.cancel(p); h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 20)
    public static void noQiCannotStartEitherForm(GameTestHelper h) {
        lane(h);
        for (boolean follow : new boolean[] {false, true}) {
            ServerPlayer p = player(h, 12, follow);
            p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withCirculating(0));
            cast(p, follow);
            h.assertTrue(!FootworkService.active(p) && p.getData(ModAttachments.COOLDOWNS).isEmpty(), "unpaid form started");
        }
        h.succeed();
    }
    private static net.minecraft.resources.ResourceLocation sword(String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", path);
    }
    private static void learnSword(ServerPlayer p, net.minecraft.resources.ResourceLocation id) {
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY).with(id, TechniqueProgress.learned(12, 12)));
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
    }

    @GameTest(template = "sect_yard", timeoutTicks = 30)
    public static void confirmedSwordHitCancelsRecoveryAndSpeedsNextSword(GameTestHelper h) {
        lane(h);
        ServerPlayer p = player(h, 12, false);
        var id = sword("falling_petal_sword"); learnSword(p, id);
        var def = TechniqueLoader.get(id);
        h.assertTrue(TechniqueService.tryStart(p, id), "initial Falling Petal refused");
        long ownStart = p.getData(ModAttachments.COOLDOWNS).get(id);
        // A real health hit records the link while the sword is in recovery.
        p.setData(ModAttachments.TECHNIQUE_STATE, new TechniqueState(id, def.startTickOf(TechniquePhase.RECOVERY), true, p.level().getGameTime()));
        var enemy = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 3)); enemy.setNoAi(true);
        float before = enemy.getHealth();
        FallingPetalExecutor.start(p, id);
        FallingPetalExecutor.tick(p, id, FallingPetalRules.STRIKES[0]);
        h.assertTrue(enemy.getHealth() < before, "Falling Petal fixture did not hit");
        h.assertTrue(TechniqueService.canLinkStep(p), "confirmed sword hit did not open step link");
        cast(p, false);
        h.assertTrue(FootworkService.active(p) && !p.getData(ModAttachments.TECHNIQUE_STATE).isActive(), "recovery not cancelled");
        long linkStart = p.getData(ModAttachments.WIND_GOD).sevenStart;
        double qi = p.getData(ModAttachments.PROFILE).circulating();
        var refusals = new java.util.ArrayList<String>();
        h.assertTrue(!TechniqueService.tryStart(p, id, 1, refusals::add), "step bypassed Falling Petal's own cooldown");
        h.assertTrue(refusals.equals(java.util.List.of("cooldown")), "wrong sword refusal gate: " + refusals);
        h.assertTrue(p.getData(ModAttachments.WIND_GOD).sevenStart == linkStart
                && FootworkService.active(p) && !p.getData(ModAttachments.TECHNIQUE_STATE).isActive(),
                "refused recast consumed the link or interrupted the step");
        h.assertTrue(p.getData(ModAttachments.COOLDOWNS).get(id) == ownStart
                && p.getData(ModAttachments.PROFILE).circulating() == qi, "refused recast changed cooldown or qi");
        var next = Styles.SIX_HARMONIES.forms().get(0); learnSword(p, next);
        h.assertTrue(TechniqueService.tryStart(p, next), "different Hua sword could not use preserved link");
        h.assertTrue(p.getData(ModAttachments.TECHNIQUE_STATE).tick() == SevenStarsRules.linkedOpener(
                TechniqueLoader.get(next).ticksOf(TechniquePhase.WINDUP)), "Weight Shift link lost its original windup reduction");
        h.assertTrue(p.getData(ModAttachments.WIND_GOD).sevenStart == Long.MIN_VALUE, "successful cast kept link");
        h.assertTrue(!FootworkService.active(p), "step recovery still owns movement");
        TechniqueService.cancel(p); enemy.discard(); h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 30)
    public static void dodgeWindowRequiresExistingSwingAndOpensInstantSix(GameTestHelper h) {
        lane(h);
        for (int age : new int[] {0, 6, 7}) for (boolean swinging : new boolean[] {false, true}) {
            ServerPlayer p = player(h, 12, false);
            // API: MC 1.21.1 ServerPlayer#tick decrements the private 60-tick
            // spawn immunity; ServerPlayer#hurt otherwise returns before damage events.
            for (int tick = 0; tick < 61; tick++) p.tick();
            var enemy = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 3)); enemy.setNoAi(true);
            if (swinging) enemy.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            cast(p, false);
            h.assertTrue(FootworkService.sevenStarsActive(p), "dodge fixture step did not start");
            h.assertTrue(p.getData(ModAttachments.WIND_GOD).pendingSwings.contains(enemy.getUUID()) == swinging,
                    "dodge fixture did not snapshot the existing swing");
            p.getData(ModAttachments.WIND_GOD).shiftStart -= age;
            float hp = p.getHealth();
            boolean hit = p.hurt(p.damageSources().mobAttack(enemy), 2);
            boolean dodge = swinging && age <= 6;
            h.assertTrue(dodge ? !hit && p.getHealth() == hp : hit && p.getHealth() < hp, "wrong dodge window/swing requirement: age=" + age + ", swinging=" + swinging + ", hit=" + hit);
            h.assertTrue((p.getData(ModAttachments.WIND_GOD).opening != Long.MIN_VALUE) == dodge, "wrong opening");
            if (dodge) {
                var counter = Styles.SIX_HARMONIES.forms().get(4); learnSword(p, counter);
                // Model Counter's recent use; the actual timed dodge must not bypass it.
                // API: NeoForge IAttachmentHolder#getData/setData; MC Level#getGameTime.
                long now = p.level().getGameTime();
                var cooldowns = new java.util.HashMap<>(p.getData(ModAttachments.COOLDOWNS));
                cooldowns.put(counter, now);
                p.setData(ModAttachments.COOLDOWNS, cooldowns);
                long opening = p.getData(ModAttachments.WIND_GOD).opening;
                long link = p.getData(ModAttachments.WIND_GOD).sevenStart;
                double qi = p.getData(ModAttachments.PROFILE).circulating();
                h.assertTrue(!TechniqueService.tryStart(p, counter), "dodge opening bypassed Counter's own cooldown");
                h.assertTrue(p.getData(ModAttachments.WIND_GOD).opening == opening
                        && p.getData(ModAttachments.WIND_GOD).sevenStart == link
                        && FootworkService.sevenStarsActive(p)
                        && !p.getData(ModAttachments.TECHNIQUE_STATE).isActive(), "refused Counter consumed bonus windows or step");
                h.assertTrue(p.getData(ModAttachments.COOLDOWNS).get(counter) == now
                        && p.getData(ModAttachments.PROFILE).circulating() == qi, "refused Counter changed cooldown or qi");
                var id = Styles.SIX_HARMONIES.forms().get(0); learnSword(p, id);
                h.assertTrue(TechniqueService.tryStart(p, id), "opening refused Six Harmonies");
                h.assertTrue(p.getData(ModAttachments.TECHNIQUE_STATE).tick() == TechniqueLoader.get(id).startTickOf(TechniquePhase.IMPACT), "opening was not instant");
                double base = TechniqueDamage.base(p, id);
                h.assertTrue(Math.abs(p.getData(ModAttachments.SIX_FORM).base - base * 1.2) < 1e-6, "opening damage not +20%");
                h.assertTrue(p.getData(ModAttachments.WIND_GOD).opening == Long.MIN_VALUE, "opening reusable");
                TechniqueService.cancel(p);
            }
            FootworkService.cancel(p); enemy.discard();
        }
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 30)
    public static void expiredAndForeignSwordLinksDoNothing(GameTestHelper h) {
        lane(h);
        for (String name : new String[] {"falling_petal_sword", "twenty_four_plum_river", "demon_palm"}) {
            ServerPlayer p = player(h, 12, false);
            var id = sword(name); learnSword(p, id);
            p.setData(ModAttachments.TECHNIQUE_STATE, new TechniqueState(id, 40, true, p.level().getGameTime()));
            var enemy = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 4)); enemy.setNoAi(true);
            enemy.hurt(p.damageSources().playerAttack(p), 1);
            Casters.onHit(p, id, enemy);
            if (name.equals("falling_petal_sword")) p.getData(ModAttachments.WIND_GOD).huaHit -= 9;
            cast(p, false);
            h.assertTrue(!FootworkService.active(p) && p.getData(ModAttachments.TECHNIQUE_STATE).isActive(), "expired/foreign recovery cancelled");
            enemy.discard(); TechniqueService.cancel(p);
        }
        h.succeed();
    }
    @GameTest(template = "sect_yard", timeoutTicks = 30)
    public static void expiredStepAndOpeningLeaveNormalWindupAndDamage(GameTestHelper h) {
        lane(h);
        ServerPlayer p = player(h, 12, false);
        var id = Styles.SIX_HARMONIES.forms().get(0); learnSword(p, id);
        var seven = p.getData(ModAttachments.WIND_GOD);
        seven.sevenStart = p.level().getGameTime() - 11;
        seven.opening = p.level().getGameTime() - 11;
        h.assertTrue(TechniqueService.tryStart(p, id), "normal sword refused");
        h.assertTrue(p.getData(ModAttachments.TECHNIQUE_STATE).tick() == 0, "expired bonus skipped windup");
        h.assertTrue(Math.abs(p.getData(ModAttachments.SIX_FORM).base - TechniqueDamage.base(p, id)) < 1e-6, "expired opening raised damage");
        TechniqueService.cancel(p); h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 30)
    public static void openingCounterHitsImmediatelyAndNonHuaCannotFollowStep(GameTestHelper h) {
        lane(h);
        ServerPlayer p = player(h, 12, false);
        var foreign = sword("demon_palm"); learnSword(p, foreign);
        cast(p, false);
        h.assertTrue(!TechniqueService.tryStart(p, foreign), "foreign art interrupted Seven Stars");
        var enemy = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 3)); enemy.setNoAi(true);
        var id = Styles.SIX_HARMONIES.forms().get(4); learnSword(p, id);
        p.getData(ModAttachments.WIND_GOD).opening = p.level().getGameTime();
        float hp = enemy.getHealth();
        h.assertTrue(TechniqueService.tryStart(p, id), "opening counter refused");
        TechniqueService.tick(p);
        h.assertTrue(enemy.getHealth() < hp, "opening counter waited for another incoming hit");
        TechniqueService.cancel(p); enemy.discard(); h.succeed();
    }
    /** API checked in /tmp/mr-ref-1121/minecraft-src: Entity#discard, LivingEntity#hurt/getHealth,
     * GameTestHelper#spawn/setBlock/assertTrue; NeoForge IAttachmentHolder#getData. */
    @GameTest(template = "sect_yard", timeoutTicks = 30)
    public static void timedShiftLandsAtPreferredSideAndStunsAtEveryStar(GameTestHelper h) {
        lane(h);
        for (int stars : new int[] {1, 3, 5, 8, 10, 12}) {
            ServerPlayer p = player(h, stars, false);
            for (int i = 0; i < 61; i++) p.tick();
            var enemy = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 3)); enemy.setNoAi(true);
            enemy.setYRot(180);
            enemy.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            FootworkService.request(p, Styles.SEVEN_STARS.forms().getFirst(), FootworkService.LEFT);
            float hp = p.getHealth();
            h.assertTrue(!p.hurt(p.damageSources().mobAttack(enemy), 2) && p.getHealth() == hp, "timed hit landed");
            h.assertTrue(enemy.getData(ModAttachments.STUN_END) - p.level().getGameTime() == SevenStarsRules.stunTicks(stars), "wrong star stun");
            for (int i = 0; i < 10; i++) FootworkService.tick(p);
            h.assertTrue(Math.abs(p.position().distanceTo(enemy.position()) - 1.2) < .05, "wrong side distance");
            h.assertTrue(p.getX() > enemy.getX(), "ignored left input");
            h.assertTrue(p.getLookAngle().dot(enemy.position().subtract(p.position()).normalize()) > .99, "did not face attacker");
            FootworkService.cancel(p); enemy.discard();
        }
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 30)
    public static void dodgeRoutesUseOtherSideThenBehindAndRejectUnsafePaths(GameTestHelper h) {
        for (int x = -2; x <= 7; x++) for (int z = 0; z <= 7; z++) {
            h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            for (int y = 2; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        ServerPlayer p = player(h, 12, false);
        var enemy = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 3)); enemy.setNoAi(true); enemy.setYRot(180);
        Vec3 step = new Vec3(1, 0, 0);
        for (int i = 0; i < 61; i++) p.tick();
        var first = FootworkService.dodgeRoute(p, enemy, step);
        h.assertTrue(!first.isEmpty() && first.getLast().x > enemy.getX(), "preferred side missing");
        for (int y = 2; y <= 3; y++) h.setBlock(new BlockPos(3, y, 3), Blocks.STONE);
        var other = FootworkService.dodgeRoute(p, enemy, step);
        h.assertTrue(!other.isEmpty() && other.getLast().x < enemy.getX(), "blocked preferred side not replaced");
        enemy.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        FootworkService.request(p, Styles.SEVEN_STARS.forms().getFirst(), FootworkService.LEFT);
        h.assertTrue(!p.hurt(p.damageSources().mobAttack(enemy), 2), "opposite-side dodge hit");
        for (int i = 0; i < 10; i++) FootworkService.tick(p);
        h.assertTrue(p.position().distanceTo(other.getLast()) < .05, "motion did not land at opposite side");
        FootworkService.cancel(p);
        enemy.removeData(ModAttachments.STUN_END);
        p = player(h, 12, false);
        for (int i = 0; i < 61; i++) p.tick();
        for (int y = 2; y <= 3; y++) h.setBlock(new BlockPos(1, y, 3), Blocks.STONE);
        var behind = FootworkService.dodgeRoute(p, enemy, step);
        h.assertTrue(!behind.isEmpty() && behind.getLast().z > enemy.getZ(), "both sides blocked: no route behind");
        h.assertTrue(behind.get(1).x > enemy.getX(), "behind route ignored preferred flank");
        // Block that flank's approach; the other rear route must still clear both side-wall corners.
        for (int y = 2; y <= 3; y++) h.setBlock(new BlockPos(4, y, 2), Blocks.STONE);
        behind = FootworkService.dodgeRoute(p, enemy, step);
        h.assertTrue(!behind.isEmpty() && behind.getLast().z > enemy.getZ()
                && behind.get(1).x < enemy.getX(), "blocked rear flank not replaced");
        enemy.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        FootworkService.request(p, Styles.SEVEN_STARS.forms().getFirst(), FootworkService.LEFT);
        h.assertTrue(!p.hurt(p.damageSources().mobAttack(enemy), 2), "behind dodge hit");
        for (int i = 0; i < 10; i++) FootworkService.tick(p);
        h.assertTrue(p.position().distanceTo(behind.getLast()) < .05, "motion did not follow route behind");
        FootworkService.cancel(p);
        Vec3 reset = h.absoluteVec(new Vec3(2.5, 2, 1));
        p.moveTo(reset.x, reset.y, reset.z, 0, 0); p.setOnGround(true);
        // A complete wall cuts every route, even if the far endpoint has support.
        for (int x = -2; x <= 7; x++) for (int y = 2; y <= 4; y++) h.setBlock(new BlockPos(x, y, 2), Blocks.STONE);
        h.assertTrue(FootworkService.dodgeRoute(p, enemy, step).isEmpty(), "crossed wall");
        for (int x = -2; x <= 7; x++) for (int y = 2; y <= 4; y++) h.setBlock(new BlockPos(x, y, 2), Blocks.AIR);
        // The only remaining destination is behind, with a two-block drop.
        for (int x = 0; x <= 4; x++) for (int z = 4; z <= 6; z++) {
            h.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
            h.setBlock(new BlockPos(x, 0, z), Blocks.AIR);
        }
        h.assertTrue(FootworkService.dodgeRoute(p, enemy, step).isEmpty(), "accepted two-block drop");
        // A one-block descent remains legal; filling it with water does not.
        for (int x = 0; x <= 4; x++) for (int z = 4; z <= 6; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        h.assertTrue(!FootworkService.dodgeRoute(p, enemy, step).isEmpty(), "rejected one-block descent");
        for (int x = 0; x <= 4; x++) for (int z = 4; z <= 6; z++) h.setBlock(new BlockPos(x, 1, z), Blocks.WATER);
        h.assertTrue(FootworkService.dodgeRoute(p, enemy, step).isEmpty(), "route entered water");
        enemy.discard(); FootworkService.cancel(p); h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 30)
    public static void mistimedShiftStopsAndTakesFullDamageWithoutRewards(GameTestHelper h) {
        lane(h);
        ServerPlayer p = player(h, 12, false);
        for (int i = 0; i < 61; i++) p.tick();
        var enemy = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 3)); enemy.setNoAi(true);
        enemy.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        cast(p, false);
        FootworkService.tick(p); FootworkService.tick(p); FootworkService.tick(p);
        p.getData(ModAttachments.WIND_GOD).shiftStart -= 7;
        Vec3 before = p.position();
        float hp = p.getHealth();
        h.assertTrue(p.hurt(p.damageSources().mobAttack(enemy), 2), "mistimed hit refused");
        h.assertTrue(Math.abs(hp - p.getHealth() - 2) < .001, "mistimed damage reduced");
        for (int i = 0; i < 12; i++) FootworkService.tick(p);
        h.assertTrue(p.position().equals(before) && !FootworkService.active(p), "failed shift kept moving");
        h.assertTrue(!Stun.isStunned(enemy) && p.getData(ModAttachments.WIND_GOD).opening == Long.MIN_VALUE, "failed shift granted reward");
        enemy.discard(); FootworkService.cancel(p); h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 30)
    public static void dodgeStunUsesPlayerAndBossCaps(GameTestHelper h) {
        lane(h);
        var boss = h.spawn(EntityType.WITHER, new Vec3(2.5, 2, 6)); boss.setNoAi(true);
        var opponent = player(h, 12, false);
        for (var attacker : new net.minecraft.world.entity.LivingEntity[] {opponent, boss}) {
            ServerPlayer p = player(h, 12, false);
            attacker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            cast(p, false);
            // The fake player is intentionally unregistered: supply its swing snapshot explicitly.
            p.getData(ModAttachments.WIND_GOD).pendingSwings.add(attacker.getUUID());
            // Exercise the actual incoming handler without vanilla PvP/spawn-immunity gates.
            // API: /tmp/mr-ref-1121/neoforge-src/common/damagesource/DamageContainer.java
            // #DamageContainer(DamageSource,float); event/entity/living/LivingIncomingDamageEvent.java constructor.
            var event = new net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent(p,
                    new net.neoforged.neoforge.common.damagesource.DamageContainer(p.damageSources().mobAttack(attacker), 2));
            h.assertTrue(FootworkService.timedDodge(event) && event.isCanceled(), "capped-target dodge refused");
            long expected = attacker == boss ? 10 : 12;
            h.assertTrue(attacker.getData(ModAttachments.STUN_END) - p.level().getGameTime() == expected, "wrong dodge stun cap");
            FootworkService.cancel(p);
        }
        h.assertTrue(Stun.apply(boss, SevenStarsRules.stunTicks(12)) == 0, "boss immunity bypassed");
        boss.discard(); FootworkService.cancel(opponent); h.succeed();
    }
    private SevenStarsGameTests() { }
}
