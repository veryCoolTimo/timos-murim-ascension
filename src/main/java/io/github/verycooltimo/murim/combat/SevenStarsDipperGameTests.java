package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.*;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.Styles;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import java.util.Optional;

/** API verified in /tmp/mr-ref-1121/minecraft-src: GameTestHelper#spawn/setBlock/absoluteVec/assertTrue/succeed;
 * Entity#moveTo/setOnGround/setDeltaMovement/discard/getUUID, LivingEntity#getAttribute/setHealth/hurt,
 * AttributeInstance#setBaseValue/getValue; ServerPlayer#tick. NeoForge gametest annotations and IAttachmentHolder#getData/setData.
 * Synchronous fixture ticks exercise the authoritative service without depending on live AI. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SevenStarsDipperGameTests {
    private static final ResourceLocation SIX = ResourceLocation.fromNamespaceAndPath("murim", "six_harmonies");
    private static void floor(GameTestHelper h) {
        for (int x = 0; x <= 10; x++) for (int z = 0; z <= 10; z++) {
            h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            for (int y = 2; y <= 5; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }
    private static ServerPlayer player(GameTestHelper h, int stars, ResourceLocation basic) {
        ServerPlayer p = FootworkMasteryGameTests.player(h, stars);
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withAxes(100, .9, .5).withCirculating(50));
        var mastery = MasteryState.EMPTY.with(basic, TechniqueProgress.learned(12, 12));
        for (var form : Styles.SEVEN_STARS.forms()) mastery = mastery.with(form, TechniqueProgress.learned(stars, 12));
        p.setData(ModAttachments.MASTERY, mastery);
        p.setData(ModAttachments.LOADOUT, Loadout.EMPTY.with(0, Optional.of(DipperService.FORM)).withFoundation(Optional.of(basic)));
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        Vec3 at = h.absoluteVec(new Vec3(5, 2, 3)); p.moveTo(at.x, at.y, at.z, 0, 0); p.setOnGround(true);
        return p;
    }
    private static net.minecraft.world.entity.monster.Zombie foe(GameTestHelper h, ServerPlayer p) {
        var target = h.spawn(EntityType.ZOMBIE, new Vec3(5, 2, 5)); target.setNoAi(true); target.setOnGround(true);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200); target.setHealth(200);
        TargetLock.set(p, target.getId()); return target;
    }
    private static void cast(ServerPlayer p) { FootworkService.request(p, DipperService.FORM, 0); }

    @GameTest(template = "sect_yard", timeoutTicks = 40)
    public static void survivalDipperWithFullQiHitsBanditSevenTimes(GameTestHelper h) {
        floor(h);
        var p = player(h, 8, SIX);
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        var profile = p.getData(ModAttachments.PROFILE);
        p.setData(ModAttachments.PROFILE, profile.withCirculating(profile.maxCirculating()));
        // Reproduce the stand's bad learning order: a subform exists, but the basic does not.
        var subform = ResourceLocation.fromNamespaceAndPath("murim", "six_harmonies_strike");
        var incomplete = MasteryState.EMPTY.with(subform, TechniqueProgress.learned(12, 12));
        h.assertTrue(!incomplete.with(SIX, TechniqueProgress.learned(12, 12)).knows(SIX),
                "fixture must exercise the art-update versus form-grant distinction");
        var prepared = io.github.verycooltimo.murim.dev.FootworkDuelCapture.prepareMastery(
                incomplete, "seven_stars_dipper@20");
        prepared = prepared.with(DipperService.FORM, TechniqueProgress.learned(8, 12));
        p.setData(ModAttachments.MASTERY, prepared);
        LoadoutService.sync(p);
        h.assertTrue(!p.isCreative() && !p.isSpectator() && FoundationService.active(p).isPresent(),
                "survival stand did not grant its equipped foundation");
        var target = h.spawn(io.github.verycooltimo.murim.registry.ModEntities.BANDIT_SWORDSMAN.get(), new Vec3(5, 2, 5));
        target.setNoAi(true); target.setOnGround(true);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200); target.setHealth(200);
        TargetLock.set(p, target.getId());
        h.assertTrue(Math.abs(p.distanceTo(target) - 2) < .01, "bandit must start two blocks away");
        p.setData(ModAttachments.MASTERY, incomplete.with(DipperService.FORM, TechniqueProgress.learned(8, 12)));
        String missingBasic = FootworkService.request(p, DipperService.FORM, FootworkService.LEFT);
        h.assertTrue("sword_foundation_not_learned".equals(missingBasic) && !DipperService.active(p),
                "missing foundation must report its actual refusal gate: " + missingBasic);
        p.setData(ModAttachments.MASTERY, prepared);
        double qi = p.getData(ModAttachments.PROFILE).circulating();
        String reason = FootworkService.request(p, DipperService.FORM, FootworkService.LEFT);
        h.assertTrue(reason == null, "survival Dipper refused: " + reason);
        var state = p.getData(ModAttachments.WIND_GOD).dipper;
        h.assertTrue(state != null && p.getData(ModAttachments.PROFILE).circulating() < qi,
                "survival cast did not start and pay qi");
        float hp = target.getHealth();
        for (int tick = 0; tick < DipperService.DURATION; tick++) FootworkService.tick(p);
        h.assertTrue(state.visited == 7 && state.strikes == 7 && target.getHealth() < hp,
                "survival Dipper did not land seven bandit strikes");
        h.assertTrue(!DipperService.active(p) && target.getData(ModAttachments.WIND_GOD).rootOwner == null
                && target.isNoAi(), "finish failed to restore the stand bandit's original AI flag");
        target.discard(); FootworkService.cancel(p); h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40)
    public static void dipperHoldsBodyAndHeadYawAndRestoresAi(GameTestHelper h) {
        floor(h); var p = player(h, 8, SIX); var target = foe(h, p);
        target.setNoAi(false);
        target.setYRot(37); target.setYBodyRot(37); target.setYHeadRot(37);
        cast(p);
        h.assertTrue(DipperService.active(p) && target.isNoAi(), "form did not suppress AI rotation");
        // The fake player is not registered for autonomous ticking: explicitly advance its form.
        h.runAtTickTime(1, () -> advanceHeldForm(h, p, target, 0));
    }
    private static void advanceHeldForm(GameTestHelper h, ServerPlayer p,
            net.minecraft.world.entity.monster.Zombie target, int tick) {
        // Assert after a real world/AI tick before touching the target in this callback.
        h.assertTrue(target.getYRot() == 37 && target.yBodyRot == 37 && target.yHeadRot == 37,
                "live entity tick changed held yaw");
        var anchor = target.getData(ModAttachments.WIND_GOD).rootAnchor;
        h.assertTrue(target.position().distanceTo(anchor) < .01, "live entity tick moved held foe");
        target.setYRot(123); target.setYBodyRot(123); target.setYHeadRot(123);
        FootworkService.tick(p);
        h.assertTrue(target.getYRot() == 37 && target.yBodyRot == 37 && target.yHeadRot == 37,
                "pressure failed to correct yaw drift");
        if (tick + 1 < DipperService.DURATION) {
            h.runAfterDelay(1, () -> advanceHeldForm(h, p, target, tick + 1));
        } else {
            h.assertTrue(target.tickCount > 0, "held-foe fixture never received a world tick");
            h.assertTrue(!DipperService.active(p) && !target.isNoAi(), "finish did not restore AI");
            target.discard(); FootworkService.cancel(p); h.succeed();
        }
    }

    @GameTest(template = "sect_yard", timeoutTicks = 30)
    public static void sevenPointsSevenBasicHitsBothStylesAndRootRelease(GameTestHelper h) {
        floor(h);
        for (var basic : new ResourceLocation[] {SIX, Styles.SEVEN_PLUM.basic().orElseThrow()}) {
            var p = player(h, 8, basic); var target = foe(h, p); Vec3 anchor = target.position();
            cast(p); var state = p.getData(ModAttachments.WIND_GOD).dipper;
            h.assertTrue(state != null, "signature refused at art star eight");
            h.assertTrue(target.getAttribute(Attributes.MOVEMENT_SPEED).getValue() == 0 && !Stun.isFrozen(target), "root became a stun");
            var visited = new java.util.HashSet<Vec3>();
            float hp = target.getHealth();
            for (int tick = 0; tick < 28; tick++) {
                int before = state.visited;
                Vec3 beforePosition = p.position();
                target.setDeltaMovement(.2, 0, .2);
                FootworkService.tick(p);
                if (state.visited > before) visited.add(p.position());
                if (tick % 4 >= DipperService.TRAVEL) h.assertTrue(p.position().distanceTo(beforePosition) < .01, "the cut tick moved the body");
                h.assertTrue(target.position().distanceTo(anchor) < .01, "pressure allowed retreat");
                h.assertTrue(p.level().noCollision(p, p.getBoundingBox().deflate(.001)), "point entered a body or block");
            }
            h.assertTrue(state.visited == 7 && visited.size() == 7 && state.strikes == 7 && target.getHealth() < hp, "missing points or basic contacts");
            h.assertTrue(!FootworkService.active(p) && target.getData(ModAttachments.WIND_GOD).rootOwner == null
                    && target.getAttribute(Attributes.MOVEMENT_SPEED).getValue() > 0, "finish retained pressure");
            cast(p); h.assertTrue(!FootworkService.active(p), "twenty-second cooldown bypassed");
            h.assertTrue(p.getData(ModAttachments.COOLDOWNS).containsKey(DipperService.FORM), "signature cooldown absent");
            target.discard(); FootworkService.cancel(p);
        }
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 30)
    public static void blockedPointIsSkippedAndCooldownExpiresAtFourHundredTicks(GameTestHelper h) {
        floor(h); var p = player(h, 12, SIX); var target = foe(h, p);
        // The first authored point is local (7,2,3.5); later points remain accessible.
        h.setBlock(new BlockPos(7, 2, 3), Blocks.STONE); h.setBlock(new BlockPos(7, 3, 3), Blocks.STONE);
        cast(p); var state = p.getData(ModAttachments.WIND_GOD).dipper;
        h.assertTrue(state != null, "single obstacle refused the entire form");
        for (int tick = 0; tick < 28; tick++) FootworkService.tick(p);
        h.assertTrue(state.visited < 7 && state.visited > 0 && state.strikes == state.visited, "blocked point struck or all free points lost");
        var cooldowns = new java.util.HashMap<>(p.getData(ModAttachments.COOLDOWNS));
        cooldowns.put(DipperService.FORM, p.level().getGameTime() - 399); p.setData(ModAttachments.COOLDOWNS, cooldowns);
        cast(p); h.assertTrue(!FootworkService.active(p), "cooldown ended early");
        cooldowns.put(DipperService.FORM, p.level().getGameTime() - 400); p.setData(ModAttachments.COOLDOWNS, cooldowns);
        cast(p); h.assertTrue(FootworkService.active(p), "cooldown did not end at twenty seconds");
        FootworkService.cancel(p); target.discard(); h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 30)
    public static void sevenStarsLockedEightUnlockedAndOutsideHitBreaksRoot(GameTestHelper h) {
        floor(h); var p = player(h, 7, SIX); var target = foe(h, p);
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SIX, TechniqueProgress.learned(12, 12))
                .with(Styles.SEVEN_STARS.forms().getFirst(), TechniqueProgress.learned(7, 12)));
        MasteryService.unlockStarForms(p);
        double qi = p.getData(ModAttachments.PROFILE).circulating(); cast(p);
        h.assertTrue(!FootworkService.active(p) && qi == p.getData(ModAttachments.PROFILE).circulating(), "signature unlocked before star eight");
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY).with(Styles.SEVEN_STARS.forms().getFirst(), TechniqueProgress.learned(8, 12)));
        MasteryService.unlockStarForms(p);
        h.assertTrue(MasteryService.knows(p, DipperService.FORM), "art breakthrough did not grant signature");
        for (int i = 0; i < 61; i++) p.tick();
        cast(p); h.assertTrue(DipperService.active(p), "signature refused at star eight");
        h.assertTrue(p.hurt(p.damageSources().mobAttack(target), 1) && DipperService.active(p), "own foe broke pressure or could not attack");
        p.invulnerableTime = 0;
        var outsider = h.spawn(EntityType.ZOMBIE, new Vec3(8, 2, 8)); outsider.setNoAi(true);
        h.assertTrue(p.hurt(p.damageSources().mobAttack(outsider), 2), "outside hit fixture did not land");
        h.assertTrue(!DipperService.active(p) && target.getData(ModAttachments.WIND_GOD).rootOwner == null, "outside hit retained form or root");
        h.assertTrue(p.getData(ModAttachments.COOLDOWNS).containsKey(DipperService.FORM), "interrupt refunded cooldown");
        FootworkService.cancel(p); target.discard(); outsider.discard(); h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 30)
    public static void chasingRequiresYourRecentHitAndOutwardMotion(GameTestHelper h) {
        floor(h); var p = player(h, 12, SIX); var target = foe(h, p);
        Vec3 at = h.absoluteVec(new Vec3(5, 2, 8)); target.moveTo(at.x, at.y, at.z, 180, 0);
        var seven = p.getData(ModAttachments.WIND_GOD);
        target.setDeltaMovement(0, 0, .1);
        h.assertTrue(FootworkService.chasingTarget(p) == null, "chased an unhit foe");
        seven.chasedFoe = target.getUUID(); seven.chasedHit = p.level().getGameTime();
        h.assertTrue(FootworkService.chasingTarget(p) == target, "missed outward motion after hit");
        target.setDeltaMovement(Vec3.ZERO); h.assertTrue(FootworkService.chasingTarget(p) == null, "chased stationary target");
        target.setDeltaMovement(0, 0, -.1); h.assertTrue(FootworkService.chasingTarget(p) == null, "chased approaching target");
        target.setDeltaMovement(0, 0, .1);
        p.setData(ModAttachments.LOADOUT, p.getData(ModAttachments.LOADOUT).with(0, Optional.of(Styles.SEVEN_STARS.forms().get(1))));
        FootworkService.request(p, Styles.SEVEN_STARS.forms().get(1), 0);
        for (int tick = 0; tick < 12; tick++) FootworkService.tick(p);
        h.assertTrue(p.distanceTo(target) < 2, "retreating foe did not return to sword reach");
        FootworkService.cancel(p); target.discard(); h.succeed();
    }
    @GameTest(template = "sect_yard", timeoutTicks = 30)
    public static void stationaryFoeGetsOnlyTheShortFallbackStep(GameTestHelper h) {
        floor(h);
        for (boolean retreat : new boolean[] {false, true}) {
            var p = player(h, 1, SIX); var target = foe(h, p);
            Vec3 at = h.absoluteVec(new Vec3(5, 2, 8)); target.moveTo(at.x, at.y, at.z, 180, 0);
            var seven = p.getData(ModAttachments.WIND_GOD);
            seven.chasedFoe = target.getUUID(); seven.chasedHit = p.level().getGameTime();
            target.setDeltaMovement(0, 0, retreat ? .1 : 0);
            p.setData(ModAttachments.LOADOUT, p.getData(ModAttachments.LOADOUT).with(0, Optional.of(Styles.SEVEN_STARS.forms().get(1))));
            FootworkService.request(p, Styles.SEVEN_STARS.forms().get(1), 0);
            for (int tick = 0; tick < 12; tick++) FootworkService.tick(p);
            h.assertTrue(retreat ? p.distanceTo(target) < 2 : p.distanceTo(target) >= 3, "pursuit ignored retreat condition");
            FootworkService.cancel(p); target.discard();
        }
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 30)
    public static void chasingLinkStartsNextHuaFormWithoutWindup(GameTestHelper h) {
        floor(h); var p = player(h, 12, SIX);
        var next = Styles.SIX_HARMONIES.forms().getFirst();
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY).with(next, TechniqueProgress.learned(12, 12)));
        p.setData(ModAttachments.LOADOUT, p.getData(ModAttachments.LOADOUT).with(0, Optional.of(Styles.SEVEN_STARS.forms().get(1))));
        p.getData(ModAttachments.WIND_GOD).huaHit = p.level().getGameTime();
        FootworkService.request(p, Styles.SEVEN_STARS.forms().get(1), 0);
        h.assertTrue(FootworkService.active(p), "Chasing Step link did not start");
        double qi = p.getData(ModAttachments.PROFILE).circulating();
        long linkStart = p.getData(ModAttachments.WIND_GOD).sevenStart;
        var refusals = new java.util.ArrayList<String>();
        h.assertTrue(TechniqueService.tryStart(p, next, 1, refusals::add), "linked Hua sword refused");
        h.assertTrue(refusals.isEmpty(), "accepted buffer reported a refusal: " + refusals);
        h.assertTrue(p.getData(ModAttachments.PROFILE).circulating() == qi
                && !p.getData(ModAttachments.COOLDOWNS).containsKey(next)
                && p.getData(ModAttachments.WIND_GOD).sevenStart == linkStart,
                "buffer paid qi, started cooldown or consumed the link before arrival");
        h.assertTrue(!p.getData(ModAttachments.TECHNIQUE_STATE).isActive() && FootworkService.active(p), "sword cut the lunge short");
        for (int tick = 0; tick < 6; tick++) FootworkService.tick(p);
        h.assertTrue(p.getData(ModAttachments.TECHNIQUE_STATE).tick() == io.github.verycooltimo.murim.technique.TechniqueLoader.get(next)
                .startTickOf(TechniquePhase.IMPACT), "Chasing Step retained sword windup");
        h.assertTrue(!FootworkService.active(p), "sword link left the step running");
        double paidQi = p.getData(ModAttachments.PROFILE).circulating();
        h.assertTrue(paidQi < qi && p.getData(ModAttachments.COOLDOWNS).containsKey(next)
                && p.getData(ModAttachments.WIND_GOD).sevenStart == Long.MIN_VALUE,
                "arrival failed to pay qi, start cooldown or consume the link");
        FootworkService.tick(p);
        h.assertTrue(p.getData(ModAttachments.PROFILE).circulating() == paidQi, "buffer charged qi twice");
        TechniqueService.cancel(p); FootworkService.cancel(p); h.succeed();
    }

    private SevenStarsDipperGameTests() { }
}
