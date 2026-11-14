package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.Stun;
import io.github.verycooltimo.murim.combat.TargetLock;
import io.github.verycooltimo.murim.combat.TechniqueService;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Synchronous production service ticks keep fixtures independent of an untracked mock player's entity ticks.
 * No fabricated contacts or effect packets. Real blocks, hitboxes, hurt, qi and recoil deadlines are used.
 * API: verified /tmp/mr-ref-1121/minecraft-src/net/minecraft/gametest/framework/GameTestHelper.java
 * (setBlock, spawn, absoluteVec, assertTrue, runAfterDelay, succeed), world/entity/{Entity,LivingEntity,Mob}.java
 * (moveTo, health, attributes, movement, noAI), world/entity/projectile/Arrow.java constructors,
 * server/level/ServerPlayer.java#setGameMode, world/entity/ai/attributes/AttributeInstance.java#setBaseValue, Attributes.java#MAX_HEALTH
 * (1024 cap), RangedAttribute.java#sanitizeValue, LivingEntity.java#setHealth (clamps to max),
 * Entity.java#setOnGround(boolean).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class CrushGameTests {
    // Attributes.MAX_HEALTH is capped at 1024 in Minecraft 1.21.1.
    private static final float FOE_HEALTH = 1000;
    private static final ResourceLocation ID = CrushExecutor.ID;
    private static final ResourceLocation SEA = ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum_sea");
    private static ServerPlayer player(GameTestHelper h) {
        for (int x = 5; x <= 12; x++) for (int z = 3; z <= 13; z++) {
            h.setBlock(x, 2, z, Blocks.STONE);
            for (int y = 3; y <= 6; y++) h.setBlock(x, y, z, Blocks.AIR);
        }
        ServerPlayer p = io.github.verycooltimo.murim.sect.SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(8.5, 3, 4.5));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setOnGround(true);
        p.setGameMode(GameType.SURVIVAL);
        var profile = DantianProfile.INITIAL.withTags("clear", "plum").withRank(Realm.PEAK).withAxes(100, 1, 1);
        // Capacity first, reserve above maxCirculating second, circulating balance last (rules/05).
        profile = profile.withPool(profile.maxCirculating() * 10).withCirculating(profile.maxCirculating());
        p.setData(ModAttachments.PROFILE, profile);
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(ID, TechniqueProgress.learned(12, 12)));
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.HUASHAN_SWORD.get()));
        h.assertTrue(profile.circulating() > TechniqueService.techniqueCost(TechniqueLoader.get(ID)) * 2, "fixture qi unfunded");
        return p;
    }
    private static Zombie foe(GameTestHelper h, double x, double z) {
        Zombie e = h.spawn(EntityType.ZOMBIE, new Vec3(x, 3, z));
        e.setNoAi(true);
        e.setOnGround(true);
        e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(FOE_HEALTH);
        e.setHealth(FOE_HEALTH); e.invulnerableTime = 0;
        h.assertTrue(e.getHealth() == FOE_HEALTH, "fixture health clamped");
        return e;
    }
    private static void start(GameTestHelper h, ServerPlayer p) {
        h.assertTrue(TechniqueService.tryStart(p, ID), "production cast refused");
    }
    private static void through(ServerPlayer p, int tick) {
        while (p.getData(ModAttachments.TECHNIQUE_STATE).isActive()
                && p.getData(ModAttachments.TECHNIQUE_STATE).tick() <= tick) TechniqueService.tick(p);
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void crushRequiresKnownSeaAtFiveStars(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SEA, TechniqueProgress.learned(4, 12)));
        h.assertTrue(MasteryService.learn(p, ID, 12) == MasteryService.Learn.MISSING_BASICS, "Sea four unlocked Crush");
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SEA, TechniqueProgress.learned(5, 12)));
        h.assertTrue(MasteryService.learn(p, ID, 12) == MasteryService.Learn.LEARNED && MasteryService.knows(p, ID), "Sea five did not unlock Crush");
        h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void crushLungesThenContactsOnceAtFiftyFour(GameTestHelper h) {
        ServerPlayer p = player(h); Zombie target = foe(h, 8.5, 8);
        TargetLock.set(p, target.getId()); Vec3 before = p.position(), targetBefore = target.position();
        p.getData(ModAttachments.WIND_GOD).comboUntil = h.getLevel().getGameTime() + 20;
        start(h, p); through(p, 49);
        h.assertTrue(p.position().distanceTo(before) < .01 && target.position().distanceTo(targetBefore) < .01
                && target.getHealth() == FOE_HEALTH && !Stun.isHeld(target), "charge moved or held target");
        through(p, 53);
        h.assertTrue(p.position().distanceTo(before) > 2 && p.position().distanceTo(before) <= CrushRules.LUNGE + .01, "lunge distance");
        h.assertTrue(target.getHealth() == FOE_HEALTH, "early strike");
        through(p, 54);
        h.assertTrue(target.getHealth() < FOE_HEALTH && Stun.isStunned(target), "contact did not damage/stun");
        h.assertTrue(p.getData(ModAttachments.PROFILE).circulating() == 0, "remaining qi not spent at aim");
        float health = target.getHealth(); through(p, 60);
        h.assertTrue(target.getHealth() == health, "strike repeated");
        TechniqueService.cancel(p); h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void crushLungeStopsAtWallAndDoesNotDamageThroughIt(GameTestHelper h) {
        ServerPlayer p = player(h); Zombie target = foe(h, 8.5, 8);
        TargetLock.set(p, target.getId()); start(h, p); through(p, 50);
        // Wall arrives after T50 aim, so the remaining lunge and strike must respect the new obstruction.
        for (int x = 7; x <= 9; x++) for (int y = 3; y <= 5; y++) h.setBlock(x, y, 6, Blocks.STONE);
        through(p, 54);
        double wallZ = h.absoluteVec(new Vec3(8.5, 3, 6)).z;
        h.assertTrue(p.getBoundingBox().maxZ <= wallZ + .001, "lunge crossed wall");
        h.assertTrue(target.getHealth() == FOE_HEALTH && !Stun.isStunned(target), "wall did not stop damage");
        TechniqueService.cancel(p); h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void crushProjectileWindowReflectsUpAndBack(GameTestHelper h) {
        ServerPlayer p = player(h); start(h, p); through(p, 48);
        Vec3 at = p.position().add(0, 1, 3);
        Arrow arrow = new Arrow(h.getLevel(), at.x, at.y, at.z, new ItemStack(Items.ARROW), null);
        arrow.setDeltaMovement(0, 0, -1); h.getLevel().addFreshEntity(arrow);
        through(p, 49);
        h.assertTrue(arrow.getDeltaMovement().y == 0 && arrow.getOwner() != p, "reflection before window");
        through(p, 50);
        h.assertTrue(arrow.getOwner() == p && arrow.getDeltaMovement().y > .5 && arrow.getDeltaMovement().z < 0, "projectile not sent above/behind caster");
        Vec3 reflected = arrow.getDeltaMovement(); through(p, 58);
        h.assertTrue(arrow.getDeltaMovement().equals(reflected), "projectile reflected repeatedly");
        TechniqueService.cancel(p); arrow.discard(); h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 30)
    public static void crushAppliesFourteenTickRecoilEvenOnMiss(GameTestHelper h) {
        ServerPlayer p = player(h); p.setSprinting(true); start(h, p); through(p, 54);
        h.assertTrue(CrushExecutor.recoiling(p) && !p.isSprinting(), "no caster recoil on miss");
        h.assertTrue(p.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN), "no recoil slowness");
        p.setDeltaMovement(0, .42, 0);
        CrushExecutor.jump(new net.neoforged.neoforge.event.entity.living.LivingEvent.LivingJumpEvent(p));
        h.assertTrue(p.getDeltaMovement().y <= 0, "jump escaped recoil");
        h.runAfterDelay(14, () -> {
            h.assertTrue(!CrushExecutor.recoiling(p), "recoil outlived 14 ticks");
            TechniqueService.cancel(p); h.succeed();
        });
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void crushMissStillDamagesConeWithoutPrimaryStun(GameTestHelper h) {
        ServerPlayer p = player(h); Zombie aimed = foe(h, 8.5, 8);
        TargetLock.set(p, aimed.getId()); start(h, p); through(p, 50);
        aimed.moveTo(h.absoluteVec(new Vec3(12, 3, 8))); // dodge after the axis freezes.
        Zombie second = foe(h, 8.9, 10.5);
        through(p, 54);
        h.assertTrue(aimed.getHealth() == FOE_HEALTH && !Stun.isStunned(aimed) && !Stun.isHeld(aimed),
                "strike followed dodging target");
        h.assertTrue(second.getHealth() < FOE_HEALTH && !Stun.isStunned(second) && !Stun.isHeld(second), "miss did not apply area-only damage");
        TechniqueService.cancel(p); h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void crushDamageThresholdOnlyInterruptsBeforeAim(GameTestHelper h) {
        ServerPlayer p = player(h); start(h, p); through(p, 48);
        TechniqueService.onDamaged(p, 7.99F);
        h.assertTrue(p.getData(ModAttachments.TECHNIQUE_STATE).isActive(), "low damage interrupted");
        TechniqueService.onDamaged(p, 8);
        h.assertTrue(!p.getData(ModAttachments.TECHNIQUE_STATE).isActive() && p.getData(ModAttachments.CRUSH).length == 0, "eight damage did not cancel/clean charge");
        ServerPlayer q = player(h); start(h, q); through(q, 49); // next state tick is exactly 50.
        TechniqueService.onDamaged(q, 8);
        h.assertTrue(q.getData(ModAttachments.TECHNIQUE_STATE).isActive(), "damage interrupted at T50");
        TechniqueService.cancel(q); h.succeed();
    }
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void crushCreativeCastKeepsBothQiBalances(GameTestHelper h) {
        ServerPlayer p = player(h); p.setGameMode(GameType.CREATIVE);
        var before = p.getData(ModAttachments.PROFILE);
        start(h, p); through(p, 54);
        var after = p.getData(ModAttachments.PROFILE);
        h.assertTrue(before.circulating() == after.circulating() && before.pool() == after.pool(), "creative paid normal or extra qi");
        TechniqueService.cancel(p); h.succeed();
    }
    private CrushGameTests() {}
}
