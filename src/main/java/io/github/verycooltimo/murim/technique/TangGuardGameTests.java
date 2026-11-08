package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.SwordGuard;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercise Tang's real contact entry before form-specific poison, burst or freeze processing. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class TangGuardGameTests {
    @GameTest(template = "small_floor", batch = "sword_guard")
    public static void parriedVolleyStopsBeforeTechniqueContactEffects(GameTestHelper h) {
        var defender = h.spawn(EntityType.COW, new Vec3(2.5D, 2, 2.5D));
        defender.setNoAi(true);
        defender.setYRot(0);
        defender.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.HUASHAN_SWORD.get()));
        var owner = h.spawn(EntityType.COW, new Vec3(2.5D, 2, 5));
        owner.setNoAi(true);
        TangDagger[] volley = new TangDagger[3];
        for (int i = 0; i < volley.length; i++) {
            volley[i] = new TangDagger(h.getLevel(), owner, TangRules.BURST, 0, i);
            volley[i].setPos(defender.getX() + i * 0.15D, defender.getY() + 0.8D, defender.getZ() + 0.8D);
            volley[i].setDeltaMovement(0, 0, -1);
            h.getLevel().addFreshEntity(volley[i]);
        }
        float health = defender.getHealth();
        // MVP R4: use a mod sword so the fixture respects the default hand policy.
        SwordGuard.hold(defender, true);
        h.assertTrue(SwordGuard.blocking(defender), "volley fixture did not engage guard");
        for (TangDagger d : volley) {
            h.assertTrue(TangExecutor.onHit(d, defender, defender.position()), "parried contact continued");
            h.assertTrue(d.isRemoved(), "parried dagger survived");
        }
        h.assertTrue(defender.getHealth() == health, "parried burst still dealt damage");
        h.succeed();
    }
    private TangGuardGameTests() {}
}
