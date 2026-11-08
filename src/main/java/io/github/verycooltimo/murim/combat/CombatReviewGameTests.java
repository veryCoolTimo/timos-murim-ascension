package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.*;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.Casters;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Batch A: synchronous production entry points. Not executed on the corporate editing machine.
 * API checked in the supplied 1.21.1 sources: DamageContainer/Post, ServerPlayerGameMode,
 * EntityType, GameTestHelper, Entity/LivingEntity/Mob, and CommonHooks/BlockEvent.BreakEvent. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class CombatReviewGameTests {
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("murim", path); }

    private static ServerPlayer player(GameTestHelper h) {
        var p = FootworkMasteryGameTests.player(h, 0);
        p.moveTo(h.absoluteVec(new Vec3(5, 3, 3)));
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        return p;
    }

    private static LivingDamageEvent.Post post(ServerPlayer p, net.minecraft.world.entity.LivingEntity target,
                                               float original, float remaining) {
        var damage = new DamageContainer(p.damageSources().playerAttack(p), original);
        damage.setNewDamage(remaining);
        return new LivingDamageEvent.Post(target, damage);
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void meditationUsesRealDamageAndKeepsBothExceptions(GameTestHelper h) {
        var p = player(h);
        for (int mode = 0; mode < 4; mode++) {
            p.setData(ModAttachments.MEDITATION,
                    io.github.verycooltimo.murim.cultivation.MeditationState.started(true, 0));
            boolean wave = mode == 2;
            io.github.verycooltimo.murim.cultivation.PillService.setWaveHit(p, wave);
            try {
                var source = mode == 1 ? io.github.verycooltimo.murim.registry.ModDamageTypes.qiDeviation(p)
                        : p.damageSources().playerAttack(p);
                var damage = new DamageContainer(source, 10);
                damage.setNewDamage(mode == 0 ? 0 : 10);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new LivingDamageEvent.Post(p, damage));
                h.assertTrue(p.getData(ModAttachments.MEDITATION).active() == (mode != 3),
                        "meditation damage/exception changed for mode " + mode);
            } finally { io.github.verycooltimo.murim.cultivation.PillService.setWaveHit(p, false); }
        }
        h.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void rainfallInterruptionUsesFinalDamage(GameTestHelper h) {
        var p = player(h);
        var rain = id("twenty_four_plum_rainfall");
        float threshold = TechniqueLoader.get(rain).interruption().damageThreshold();
        for (float remaining : new float[] {0, threshold / 2, threshold + 1}) {
            p.setData(ModAttachments.TECHNIQUE_STATE, TechniqueState.started(rain, p.level().getGameTime()));
            CombatEvents.onDamage(post(p, p, threshold + 10, remaining));
            h.assertTrue(p.getData(ModAttachments.TECHNIQUE_STATE).isActive() == (remaining < threshold),
                    "shield/Resistance final damage did not control interruption: " + remaining);
        }
        h.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void sixEffectsRequireDamageAndConsumeSwing(GameTestHelper h) {
        var p = player(h);
        var six = id("six_harmonies");
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(six, TechniqueProgress.learned(0, 12)));
        p.setData(ModAttachments.LOADOUT, Loadout.EMPTY.withFoundation(java.util.Optional.of(six)));
        var foe = h.spawn(EntityType.ZOMBIE, new Vec3(5, 3, 5));
        foe.setNoAi(true);
        p.setData(ModAttachments.FOUNDATION_SWING, new int[] {FoundationForms.Form.RISING.ordinal(), p.tickCount});
        var before = p.getData(ModAttachments.MASTERY);
        SchoolStyle.onAttack(new net.neoforged.neoforge.event.entity.player.AttackEntityEvent(p, foe));
        FoundationService.onDamage(post(p, foe, 10, 0), false);
        h.assertTrue(before.equals(p.getData(ModAttachments.MASTERY)) && foe.getDeltaMovement().lengthSqr() == 0,
                "parried/zero-damage cut granted hit credit or push");
        FoundationService.onDamage(post(p, foe, 10, 10), false);
        h.assertTrue(!before.equals(p.getData(ModAttachments.MASTERY)) && foe.getDeltaMovement().y == .25,
                "confirmed Six hit lost its mastery or rising effect");
        var credited = p.getData(ModAttachments.MASTERY);
        FoundationService.onDamage(post(p, foe, 10, 10), false);
        h.assertTrue(credited.equals(p.getData(ModAttachments.MASTERY)) && foe.getDeltaMovement().y == .25,
                "one swing credited twice");
        foe.discard(); h.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void betterCombatBridgeCreditsBothFoundationsOnce(GameTestHelper h) {
        for (String name : new String[] {"six_harmonies", "seven_plum_basic"}) {
            var p = player(h);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(io.github.verycooltimo.murim.registry.ModItems.HUASHAN_SWORD.get()));
            var basic = id(name);
            p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(basic, TechniqueProgress.learned(0, 12)));
            p.setData(ModAttachments.LOADOUT, Loadout.EMPTY.withFoundation(java.util.Optional.of(basic)));
            var foe = h.spawn(EntityType.ZOMBIE, new Vec3(5, 3, 5));
            var before = p.getData(ModAttachments.MASTERY);
            FoundationService.onDamage(post(p, foe, 4, 0), true);
            h.assertTrue(before.equals(p.getData(ModAttachments.MASTERY)), "bridge credited blocked damage");
            FoundationService.onDamage(post(p, foe, 4, 4), true);
            var credited = p.getData(ModAttachments.MASTERY);
            h.assertTrue(!before.equals(credited), "bridge did not grow " + name);
            FoundationService.onDamage(post(p, foe, 4, 4), true);
            h.assertTrue(credited.equals(p.getData(ModAttachments.MASTERY)), "bridge repeated sweep credit");
            p.tickCount++;
            FoundationService.onDamage(post(p, foe, 4, 4), true);
            h.assertTrue(!credited.equals(p.getData(ModAttachments.MASTERY)), "next attack lost credit");
            foe.discard();
        }
        h.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void techniqueMiningHonoursAdventureAndCancellation(GameTestHelper h) {
        var p = player(h);
        var pos = h.absolutePos(new BlockPos(5, 2, 5));
        h.getLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        p.setGameMode(GameType.ADVENTURE);
        h.assertTrue(!Casters.mayBreak(p, pos), "Adventure sword could break terrain");
        p.setGameMode(GameType.SURVIVAL);
        h.assertTrue(Casters.mayBreak(p, pos), "fixture is protected before listener");
        // UUID-scoped to this disposable player: cannot affect another fixture or ordinary play.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                net.neoforged.bus.api.EventPriority.LOWEST,
                (net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) -> {
                    if (event.getPlayer() == p && event.getPos().equals(pos)) event.setCanceled(true);
                });
        h.assertTrue(!Casters.mayBreak(p, pos), "cancelled break event allowed technique destruction");
        h.assertTrue(h.getLevel().getBlockState(pos).is(Blocks.STONE), "permission probe destroyed a block");
        h.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void holdsExcludeAnimalsAndOccludedLockedOpponents(GameTestHelper h) {
        var p = player(h);
        var cow = h.spawn(EntityType.COW, new Vec3(5, 3, 5));
        var bandit = h.spawn(io.github.verycooltimo.murim.registry.ModEntities.BANDIT_SWORDSMAN.get(), new Vec3(6, 3, 5));
        var technique = TechniqueLoader.get(id("twenty_four_plum_rainfall"));
        Stun.holdStart(p, technique);
        h.assertTrue(!Stun.isFrozen(cow) && Stun.isFrozen(bandit), "hold did not distinguish animal and opponent");
        var disciple = h.spawn(io.github.verycooltimo.murim.registry.ModEntities.SECT_DISCIPLE.get(), new Vec3(5, 3, 7));
        h.assertTrue(!Stun.opponent(p, disciple), "uninvolved sect disciple counted as hostile");
        TargetLock.set(p, disciple.getId());
        for (int x = 3; x <= 7; x++) for (int y = 3; y <= 6; y++) h.setBlock(new BlockPos(x, y, 6), Blocks.STONE);
        h.assertTrue(!p.hasLineOfSight(disciple), "wall fixture did not obstruct sight");
        Stun.holdStart(p, technique);
        h.assertTrue(!Stun.isFrozen(disciple) && TargetLock.locked(p, 10) == null, "locked disciple held through wall");
        cow.discard(); bandit.discard(); disciple.discard(); h.succeed();
    }
}
