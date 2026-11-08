package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.BanditSwordsman;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.technique.TangDagger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real hurt/knockback hooks plus boundary tests. Server receipt ticks, never client timestamps. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SwordGuardGameTests {
    private static Cow defender(GameTestHelper h) {
        Cow e = h.spawn(EntityType.COW, new Vec3(2.5D, 2.0D, 2.5D));
        e.setNoAi(true);
        e.setYRot(0);
        e.setOnGround(true);
        e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        e.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(0);
        e.setHealth(200);
        e.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(io.github.verycooltimo.murim.registry.ModItems.HUASHAN_SWORD.get()));
        return e;
    }
    private static Cow attacker(GameTestHelper h, Cow e) {
        Cow a = h.spawn(EntityType.COW, new Vec3(2.5D, 2.0D, 4.5D));
        a.setNoAi(true);
        return a;
    }
    private static void close(double expected, double actual, GameTestHelper h, String message) {
        h.assertTrue(Math.abs(expected - actual) < 0.001D, message + ": " + actual);
    }
    @GameTest(template = "small_floor", batch = "sword_guard", timeoutTicks = 20)
    public static void heldBlockHalvesDamageAndKeepsThreeQuarterKnockback(GameTestHelper h) {
        Cow e = defender(h), a = attacker(h, e);
        SwordGuard.hold(e, true);
        // four ticks: a press one or two ticks old is still the exact parry (see pressTheTickBeforeTheContactIsStillExact)
        h.runAfterDelay(4, () -> {
            e.setDeltaMovement(Vec3.ZERO);
            e.hurt(e.damageSources().mobAttack(a), 8);
            close(196, e.getHealth(), h, "block damage");
            close(-0.3D, e.getDeltaMovement().z, h, "block impulse");
            // NPC/vanilla sprint follow-up must share this contact's reduction.
            var extra = new net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent(e, 0.5F, 0, 1);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(extra);
            close(0.375D, extra.getStrength(), h, "follow-up impulse");
            h.succeed();
        });
    }
    @GameTest(template = "small_floor", batch = "sword_guard", timeoutTicks = 20)
    public static void freshPressAfterSwingDeflectsOnlyOneContact(GameTestHelper h) {
        Cow e = defender(h), a = attacker(h, e);
        SwordGuard.swing(e);
        h.runAfterDelay(3, () -> SwordGuard.hold(e, true));
        h.runAfterDelay(4, () -> {
            e.setDeltaMovement(Vec3.ZERO);
            e.hurt(e.damageSources().mobAttack(a), 8);
            close(200, e.getHealth(), h, "deflect damage");
            close(-0.1D, e.getDeltaMovement().z, h, "deflect impulse");
            SwordGuard.hold(e, false);
            SwordGuard.hold(e, true);
            h.assertTrue(!SwordGuard.state(e).deflecting(e.level().getGameTime()), "mashing refreshed window");
            e.invulnerableTime = 0;
            e.hurt(e.damageSources().mobAttack(a), 8);
            close(196, e.getHealth(), h, "second contact should only block");
            h.succeed();
        });
    }
    @GameTest(template = "small_floor", batch = "sword_guard")
    public static void exactPressNullifiesHitWithoutGrantingAnotherAttack(GameTestHelper h) {
        Cow e = defender(h), a = attacker(h, e);
        SwordGuard.hold(e, true);
        e.hurt(e.damageSources().mobAttack(a), 8);
        close(200, e.getHealth(), h, "same-tick parry damage");
        h.assertTrue(SwordGuard.state(e).consumed, "exact parry did not consume opportunity");
        h.succeed();
    }
    /**
     * Probe 24.10: the server stamps a press with the game time BEFORE the entities of the next tick move, so a press "in the tick
     * of the contact" arrives one tick early. A press one or two ticks before the contact is still the exact parry; three ticks
     * before it is only a block.
     */
    @GameTest(template = "small_floor", batch = "sword_guard", timeoutTicks = 40)
    public static void pressTheTickBeforeTheContactIsStillExact(GameTestHelper h) {
        Cow e = defender(h), a = attacker(h, e);
        SwordGuard.hold(e, true);
        h.runAfterDelay(1, () -> {
            h.assertTrue(SwordGuard.state(e).exact(e.level().getGameTime()), "a press one tick old is not exact");
            e.setDeltaMovement(Vec3.ZERO);
            e.hurt(e.damageSources().mobAttack(a), 8);
            close(200, e.getHealth(), h, "one tick early parry damage");
            h.assertTrue(SwordGuard.state(e).consumed, "the early exact parry did not consume the opportunity");
            h.succeed();
        });
    }
    @GameTest(template = "small_floor", batch = "sword_guard", timeoutTicks = 40)
    public static void pressThreeTicksBeforeTheContactIsOnlyABlock(GameTestHelper h) {
        Cow e = defender(h), a = attacker(h, e);
        SwordGuard.hold(e, true);
        h.runAfterDelay(3, () -> {
            h.assertTrue(!SwordGuard.state(e).exact(e.level().getGameTime()), "a press three ticks old is exact");
            e.setDeltaMovement(Vec3.ZERO);
            e.hurt(e.damageSources().mobAttack(a), 8);
            close(196, e.getHealth(), h, "held block damage");
            h.succeed();
        });
    }
    @GameTest(template = "small_floor", batch = "sword_guard")
    public static void boundariesHeldInputAndMashing(GameTestHelper h) {
        var s = new SwordGuardState();
        s.hold(true, 98);
        s.swing(100, 13);
        h.assertTrue(!s.blocking(102) && s.blocking(103), "three open attack ticks");
        s.hold(true, 103);
        h.assertTrue(!s.deflecting(103), "holding both buttons opened a window");
        s.hold(false, 103);
        s.hold(true, 104);
        h.assertTrue(s.deflecting(104) && s.deflecting(108) && !s.deflecting(109), "not five ticks");
        s.hold(false, 105);
        s.hold(true, 106);
        h.assertTrue(!s.deflecting(106) && !s.exact(106), "release/repress refreshed opportunity");
        s.swing(120, 13);
        s.hold(false, 120);
        s.hold(true, 121);
        h.assertTrue(!s.deflecting(122) && s.deflecting(123) && !s.deflecting(128), "buffer widened window");
        s.swing(124, 13);
        h.assertTrue(!s.deflecting(124), "next swing left window active");
        s.hold(false, 136);
        s.hold(true, 137);
        h.assertTrue(!s.deflecting(137), "window started after next attack was ready");
        h.succeed();
    }
    @GameTest(template = "small_floor", batch = "sword_guard")
    public static void exactParryClearsSeveralIncomingDaggersButLeavesRearWeapon(GameTestHelper h) {
        Cow e = defender(h), a = attacker(h, e);
        TangDagger[] volley = new TangDagger[3];
        for (int i = 0; i < volley.length; i++) {
            volley[i] = new TangDagger(h.getLevel(), a, 0, 0, i);
            volley[i].setPos(e.getX() + (i - 1) * 0.25D, e.getY() + 0.8D, e.getZ() + 1);
            volley[i].setDeltaMovement(0, 0, -0.5D);
            h.getLevel().addFreshEntity(volley[i]);
        }
        TangDagger rear = new TangDagger(h.getLevel(), a, 0, 0, 3);
        rear.setPos(e.getX(), e.getY() + 0.8D, e.getZ() - 1);
        rear.setDeltaMovement(0, 0, 0.5D);
        h.getLevel().addFreshEntity(rear);
        SwordGuard.hold(e, true);
        e.hurt(e.damageSources().mobProjectile(volley[0], a), 8);
        close(200, e.getHealth(), h, "salvo parry damage");
        for (TangDagger d : volley) h.assertTrue(d.isRemoved(), "a frontal dagger survived the sweep");
        h.assertTrue(!rear.isRemoved(), "parry reached behind the defender");
        h.succeed();
    }
    @GameTest(template = "small_floor", batch = "sword_guard", timeoutTicks = 40)
    public static void swordsmanBlocksBetweenHisSwings(GameTestHelper h) {
        Cow t = defender(h);
        BanditSwordsman npc = h.spawn(ModEntities.BANDIT_SWORDSMAN.get(), new Vec3(2.5D, 2.0D, 4.5D));
        npc.delayFirstAttack(6);
        npc.setTarget(t);
        boolean[] seen = {false, false};
        h.onEachTick(() -> {
            seen[0] |= SwordGuard.blocking(npc);
            seen[1] |= npc.state() == io.github.verycooltimo.murim.entity.Bandit.WINDUP;
            if (seen[0] && seen[1]) h.succeed();
        });
    }
    @GameTest(template = "small_floor", batch = "sword_guard", timeoutTicks = 20)
    public static void rearAndMagicHitsBypassGuard(GameTestHelper h) {
        Cow e = defender(h), a = attacker(h, e);
        SwordGuard.hold(e, true);
        a.setPos(e.getX(), e.getY(), e.getZ() - 2);
        e.hurt(e.damageSources().mobAttack(a), 8);
        close(192, e.getHealth(), h, "rear damage");
        e.invulnerableTime = 0;
        e.hurt(e.damageSources().magic(), 4);
        close(188, e.getHealth(), h, "magic damage");
        h.succeed();
    }
    @GameTest(template = "small_floor", batch = "sword_guard")
    public static void arrowsParryBeforeImpactAndAreaDamageRetainsAttribution(GameTestHelper h) {
        Cow e = defender(h), a = attacker(h, e);
        var arrow = new net.minecraft.world.entity.projectile.Arrow(h.getLevel(), e.getX(), e.getY() + 1,
                e.getZ() + 1, new ItemStack(Items.ARROW), null);
        arrow.setOwner(a);
        arrow.setDeltaMovement(0, 0, -1);
        h.getLevel().addFreshEntity(arrow);
        SwordGuard.hold(e, true);
        var impact = new net.neoforged.neoforge.event.entity.ProjectileImpactEvent(arrow,
                new net.minecraft.world.phys.EntityHitResult(e));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(impact);
        h.assertTrue(impact.isCanceled() && arrow.isRemoved(), "arrow impact was not intercepted");
        SwordGuard.unguarded(e, () -> e.hurt(e.damageSources().mobAttack(a), 8));
        close(192, e.getHealth(), h, "melee-attributed area damage was guarded");
        h.assertTrue(SwordGuard.state(e).areaDepth == 0, "area context leaked");
        h.succeed();
    }
    private SwordGuardGameTests() {}
}
