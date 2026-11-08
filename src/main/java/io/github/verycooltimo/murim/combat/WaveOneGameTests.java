package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.sect.SectGameTests;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** MVP R3/R4/R12 regressions. API: existing StunTests and SwordGuardGameTests fixtures. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class WaveOneGameTests {
    @GameTest(template = "small_floor", timeoutTicks = 40)
    public static void turtleMasterIsNotStunAndModStunExpires(GameTestHelper h) {
        var p = SectGameTests.fakePlayer(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.HUASHAN_SWORD.get()));
        for (int amplifier : new int[] {3, 5}) {
            p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, amplifier));
            h.assertFalse(Stun.isStunned(p), "Turtle Master marked as stun: " + amplifier);
            h.assertTrue(SwordGuard.eligible(p), "Turtle Master prevented guard");
            SwordGuard.hold(p, true);
            h.assertTrue(SwordGuard.blocking(p), "guard did not engage");
            SwordGuard.hold(p, false);
        }
        h.assertTrue(Stun.apply(p, 8) == 8 && Stun.isStunned(p), "mod stun absent");
        h.assertFalse(SwordGuard.eligible(p), "stunned player can guard");
        h.runAfterDelay(9, () -> {
            h.assertFalse(Stun.isStunned(p), "mod stun did not expire");
            h.assertTrue(SwordGuard.eligible(p), "guard did not recover");
            h.succeed();
        });
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void handsPreserveVanillaUse(GameTestHelper h) {
        ItemStack sword = new ItemStack(ModItems.HUASHAN_SWORD.get());
        h.assertTrue(SwordGuard.handsAllowGuard(sword, ItemStack.EMPTY, false), "mod sword refused");
        h.assertTrue(SwordGuard.handsAllowGuard(sword, new ItemStack(Items.STICK), false), "inert offhand refused");
        for (var item : new net.minecraft.world.item.Item[] {Items.SHIELD, Items.BOW, Items.CROSSBOW,
                Items.TRIDENT, Items.APPLE, Items.POTION, Items.BUCKET, Items.WATER_BUCKET}) {
            ItemStack use = new ItemStack(item);
            for (boolean vanilla : new boolean[] {false, true}) {
                h.assertFalse(SwordGuard.handsAllowGuard(sword, use, vanilla), "offhand use intercepted: " + item);
                h.assertFalse(SwordGuard.handsAllowGuard(use, ItemStack.EMPTY, vanilla), "main use intercepted: " + item);
            }
        }
        h.assertFalse(SwordGuard.handsAllowGuard(new ItemStack(Items.IRON_SWORD), ItemStack.EMPTY, false), "vanilla default guards");
        h.assertTrue(SwordGuard.handsAllowGuard(new ItemStack(Items.IRON_SWORD), ItemStack.EMPTY, true), "vanilla opt-in refused");
        var p = SectGameTests.fakePlayer(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, sword);
        p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        SwordGuard.input(p, 1);
        h.assertFalse(SwordGuard.blocking(p), "server accepted shield plus guard");
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void auraDoesNotAttachToForeignMobs(GameTestHelper h) {
        var p = SectGameTests.fakePlayer(h);
        var zombie = h.spawn(EntityType.ZOMBIE, new Vec3(2, 2, 2));
        var cow = h.spawn(EntityType.COW, new Vec3(3, 2, 2));
        p.moveTo(cow.getX(), cow.getY(), cow.getZ(), 0, 0);
        AuraService.measure(p);
        for (var mob : java.util.List.of(zombie, cow)) {
            h.assertFalse(mob.hasData(ModAttachments.AURA), "aura scan created attachment");
            h.assertFalse(FootworkService.exposed(mob), "fresh mob exposed");
            h.assertFalse(mob.hasData(ModAttachments.WIND_GOD), "exposure read created attachment");
        }
        cow.setData(ModAttachments.AURA, new AuraState(6, false));
        h.assertTrue(AuraService.measure(p) > 0, "explicit aura stopped exerting pressure");
        AuraService.set(cow, AuraState.NONE);
        h.assertFalse(cow.hasData(ModAttachments.AURA), "empty aura was retained");
        h.succeed();
    }
    private WaveOneGameTests() { }
}
