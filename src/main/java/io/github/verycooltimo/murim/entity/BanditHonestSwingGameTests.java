package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The ordinary bandit swing is honest (author 14.11): damage only on the contact frame, only inside the swing's reach and arc. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class BanditHonestSwingGameTests {

    private BanditHonestSwingGameTests() {
    }

    private static Cow target(GameTestHelper h, Vec3 at) {
        Cow c = h.spawn(EntityType.COW, at);
        c.setNoAi(true);
        c.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        c.setHealth(200.0F);
        return c;
    }

    private static BanditSwordsman bandit(GameTestHelper h) {
        BanditSwordsman b = h.spawn(ModEntities.BANDIT_SWORDSMAN.get(), new Vec3(2.5D, 2.0D, 1.5D));
        b.setNoAi(true);
        b.setYRot(0.0F);
        b.yBodyRot = 0.0F;
        return b;
    }

    /** The contact frame: behind him, out of reach or beside the arc is a miss; in front inside the reach is a hit. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void contactFrameChecksReachAndArc(GameTestHelper h) {
        BanditSwordsman b = bandit(h);
        BanditMove m = BanditMove.CHOP;
        Cow front = target(h, new Vec3(2.5D, 2.0D, 3.5D));
        double dz = front.getZ() - b.getZ();
        boolean reach = m.reaches(front.getX() - b.getX(), front.getY() - b.getY(), dz, b.yBodyRot);
        boolean los = b.getSensing().hasLineOfSight(front);
        boolean hit = b.contact(front, m);
        h.assertTrue(hit && front.getHealth() < 200.0F, "no hit in front: reach=" + reach + " los=" + los + " hit=" + hit + " dz=" + dz + " yaw=" + b.yBodyRot + " hp=" + front.getHealth());
        Cow behind = target(h, new Vec3(2.5D, 2.0D, 0.5D));
        h.assertTrue(!b.contact(behind, m) && behind.getHealth() == 200.0F, "hit a target behind him");
        Cow far = target(h, new Vec3(2.5D, 2.0D, 1.5D + m.reach() + 1.0D));
        h.assertTrue(!b.contact(far, m) && far.getHealth() == 200.0F, "hit a target out of reach");
        Cow side = target(h, new Vec3(4.5D, 2.0D, 1.6D));
        h.assertTrue(!b.contact(side, m) && side.getHealth() == 200.0F, "hit a target at his side (out of the chop arc)");
        h.succeed();
    }

    /** The whole swing: the target steps away during the swing and takes nothing; one who stays is hit, and not before the contact frame. */
    @GameTest(template = "small_floor", timeoutTicks = 120)
    public static void swingMissesWhoStepsAway(GameTestHelper h) {
        BanditSwordsman b = h.spawn(ModEntities.BANDIT_SWORDSMAN.get(), new Vec3(2.5D, 2.0D, 2.5D));
        Cow away = target(h, new Vec3(2.5D, 2.0D, 4.3D));
        b.setTarget(away);
        boolean[] stepped = {false};
        float[] seenAtWindup = {200.0F};
        h.onEachTick(() -> {
            if (b.state() == Bandit.WINDUP) {
                seenAtWindup[0] = Math.min(seenAtWindup[0], away.getHealth());
            }
            if (!stepped[0] && b.state() == Bandit.STRIKE) {
                stepped[0] = true;
                away.setPos(2.5D, 2.0D, 2.5D + 9.0D);
            }
            if (stepped[0] && b.state() == Bandit.RECOVER) {
                h.assertTrue(seenAtWindup[0] == 200.0F, "damage before the contact frame");
                h.assertTrue(away.getHealth() == 200.0F, "the swing hurt a target that stepped out of reach");
                h.succeed();
            }
        });
    }

    @GameTest(template = "small_floor", timeoutTicks = 120)
    public static void swingHitsWhoStays(GameTestHelper h) {
        BanditSwordsman b = h.spawn(ModEntities.BANDIT_SWORDSMAN.get(), new Vec3(2.5D, 2.0D, 2.5D));
        Cow stays = target(h, new Vec3(2.5D, 2.0D, 4.3D));
        b.setTarget(stays);
        h.onEachTick(() -> {
            if (b.state() == Bandit.WINDUP || b.state() == Bandit.STRIKE) {
                // Keep him in front at a hitting distance (the cow has no AI and the bandit may shuffle).
                stays.setPos(b.getX() - Math.sin(Math.toRadians(b.yBodyRot)) * 1.9D, b.getY(), b.getZ() + Math.cos(Math.toRadians(b.yBodyRot)) * 1.9D);
            }
            if (stays.getHealth() < 200.0F) {
                h.succeed();
            }
        });
    }
}
