package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.SwordGuard;
import io.github.verycooltimo.murim.combat.SwordGuardState;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The parry probe against thrown Tang daggers (author 24.10: «парирование против кинжалов/игл»). No sect person throws Tang daggers in
 * this tree, so the thrower throws the dagger of the hand throw (the real flight, 2 blocks a tick, {@link TangExecutor#throwByHand}
 * built the same way) from 8 blocks. The defender is a cow with a sword: the guard state machine is the same for every living thing,
 * and a mock player with no mod channel would crash the server on the dagger's spawn packet. The defender presses the right mouse
 * button {@code k} ticks after the throw (negative: before it), with an own swing four ticks before the press ("swing": the way a
 * window opens, ADR 0020) or without ("plain": only the exact tick parries). One line per variant: for each {@code k} P = parried,
 * - = the dagger arrived unparried; and the tick the dagger arrived. Namespace {@code murimbench}: not in the ordinary suite.
 */
@GameTestHolder("murimbench")
@PrefixGameTestTemplate(false)
public final class TangParryProbeGameTests {
    private TangParryProbeGameTests() { }

    private static void probe(GameTestHelper h, String variant, double distance) {
        // GameTestHelper#spawn takes a position relative to the test structure
        Vec3 rel = new Vec3(11.5, 2, 6.5);
        Cow defender = h.spawn(EntityType.COW, rel);
        Cow thrower = h.spawn(EntityType.COW, rel.add(0, 0, distance));
        Vec3 at = defender.position();
        for (Cow c : new Cow[] {defender, thrower}) {
            c.setNoAi(true);
            c.setOnGround(true);
        }
        defender.setYRot(0);
        defender.setYHeadRot(0);
        defender.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        thrower.setYRot(180);
        int[] ks = new int[24];
        for (int i = 0; i < ks.length; i++) ks[i] = i - 6;
        int[] st = {0, 0};
        TangDagger[] dagger = {null};
        StringBuilder row = new StringBuilder();
        long[] arrived = {-1};
        h.onEachTick(() -> {
            if (st[0] >= ks.length) return;
            int k = ks[st[0]];
            int t = st[1]++;
            if (t == 0) {
                defender.setData(ModAttachments.SWORD_GUARD, new SwordGuardState());
                defender.moveTo(at.x, at.y, at.z, 0, 0);
                defender.invulnerableTime = 0;
                defender.setHealth(defender.getMaxHealth());
                dagger[0] = null;
                arrived[0] = -1;
            }
            // k >= 0: the throw is at t == 6 and the press k ticks after it; k < 0: the press precedes the throw by -k ticks
            int throwAt = 6;
            int pressAt = throwAt + k;
            if (variant.equals("swing") && t == pressAt - 4) SwordGuard.swing(defender);
            if (t == pressAt) SwordGuard.hold(defender, true);
            if (t == throwAt) {
                TangDagger d = new TangDagger(h.getLevel(), thrower, TangRules.FIVE, 0, 0);
                d.thrownItem = true;
                d.freeThrow = true;
                d.setPos(thrower.getX(), thrower.getY() + 1.0D, thrower.getZ() - 0.4D);
                d.setMode(TangDagger.STRAIGHT);
                d.speed = TangRules.HAND_SPEED;
                d.setDeltaMovement(0, 0, -TangRules.HAND_SPEED);
                d.damage = TangRules.HAND_DMG;
                d.life = 60;
                h.getLevel().addFreshEntity(d);
                dagger[0] = d;
            }
            if (dagger[0] != null && arrived[0] < 0 && dagger[0].isRemoved()) {
                arrived[0] = t - throwAt;
            }
            if (t >= 22) {
                boolean parried = SwordGuard.state(defender).consumed;
                row.append(String.format("%+d:%s ", k, parried ? "P" : "-"));
                if (st[0] == 0) row.append("(arrival ").append(arrived[0]).append(" ticks after the throw) ");
                SwordGuard.hold(defender, false);
                if (dagger[0] != null && !dagger[0].isRemoved()) dagger[0].discard();
                st[0]++;
                st[1] = 0;
                if (st[0] >= ks.length) {
                    MurimMod.LOGGER.info("PARRYTANG variant={} distance={} press at k ticks after the throw: {}", variant, distance, row);
                    h.succeed();
                }
            }
        });
    }

    @GameTest(template = "sect_yard", templateNamespace = "murimbench", batch = "tang_parry_probe", timeoutTicks = 900)
    public static void swingThenPress(GameTestHelper h) {
        probe(h, "swing", 8.0D);
    }

    @GameTest(template = "sect_yard", templateNamespace = "murimbench", batch = "tang_parry_probe2", timeoutTicks = 900)
    public static void pressOnly(GameTestHelper h) {
        probe(h, "plain", 8.0D);
    }
}
