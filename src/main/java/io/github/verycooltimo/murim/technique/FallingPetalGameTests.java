package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Falling Petal v3 blinks: the master reappears BEHIND the target on the side of each cut, never
 * inside a wall or behind it, and the target turns toward him by a limited step per tick.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class FallingPetalGameTests {

    private FallingPetalGameTests() {
    }

    private static ServerPlayer fakePlayer(GameTestHelper helper, Vec3 rel) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "petal-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        Vec3 at = helper.absoluteVec(rel);
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        return p;
    }

    /** Three sides: rear, rear-left, rear-right of the target's facing, on the floor, within reach. */
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void blinkPointsAreBehindTheTarget(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper, new Vec3(8.5D, 2.0D, 4.5D));
        Zombie z = helper.spawn(EntityType.ZOMBIE, new Vec3(8.5D, 2.0D, 8.5D));
        z.setNoAi(true);
        helper.runAfterDelay(2, () -> {
            Vec3 facing = new Vec3(0.0D, 0.0D, -1.0D);   // the zombie looks at the player (north)
            double[] lefts = new double[3];
            for (int k = 0; k < 3; k++) {
                Vec3 at = FallingPetalExecutor.safeBehind(p, z, facing, k);
                helper.assertTrue(at != null, "no blink spot for cut " + k);
                Vec3 rel = at.subtract(z.position());
                helper.assertTrue(rel.dot(facing) < -0.3D, "cut " + k + " is not behind the target: " + rel);
                helper.assertTrue(Math.abs(at.y - z.getY()) <= 1.0D + 1.0E-3D, "cut " + k + " off the target's level: " + at.y + " vs " + z.getY());
                helper.assertFalse(p.level().noCollision(p, p.getDimensions(p.getPose()).makeBoundingBox(at).move(0.0D, -0.6D, 0.0D)),
                        "cut " + k + " floats: " + at);
                double flat = Math.hypot(rel.x, rel.z);
                helper.assertTrue(flat >= 2.2D && flat <= 3.4D, "cut " + k + " not 2.2-3.4 blocks away: " + flat);
                lefts[k] = rel.dot(FallingPetalRules.left(facing));
            }
            helper.assertTrue(Math.abs(lefts[0]) < 0.2D && lefts[1] > 1.0D && lefts[2] < -1.0D,
                    "sides are not rear / rear-left / rear-right: " + java.util.Arrays.toString(lefts));
            helper.succeed();
        });
    }

    /** A wall right behind the target: the spot moves around it or is refused, never inside or beyond. */
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void blinkNeverLandsInOrBehindAWall(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper, new Vec3(8.5D, 2.0D, 4.5D));
        Zombie z = helper.spawn(EntityType.ZOMBIE, new Vec3(8.5D, 2.0D, 8.5D));
        z.setNoAi(true);
        for (int x = 5; x <= 12; x++) {
            for (int y = 2; y <= 4; y++) {
                helper.setBlock(new BlockPos(x, y, 10), Blocks.STONE.defaultBlockState());
            }
        }
        helper.runAfterDelay(2, () -> {
            Vec3 at = FallingPetalExecutor.safeBehind(p, z, new Vec3(0.0D, 0.0D, -1.0D), 0);
            if (at != null) {
                helper.assertTrue(at.z < helper.absoluteVec(new Vec3(0.0D, 0.0D, 10.0D)).z - 0.29D,
                        "blink spot inside or beyond the wall: " + at);
                helper.assertTrue(p.level().noCollision(p, p.getDimensions(p.getPose()).makeBoundingBox(at)), "spot collides");
            }
            helper.succeed();
        });
    }

    /** The target turns toward the master at most TURN_SPEED degrees per call. */
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void targetTurnsByLimitedSteps(GameTestHelper helper) {
        Zombie z = helper.spawn(EntityType.ZOMBIE, new Vec3(8.5D, 2.0D, 8.5D));
        z.setNoAi(true);
        z.setYRot(0.0F);
        Vec3 behind = z.position().add(0.0D, 0.0D, -2.0D);   // yaw 180
        FallingPetalExecutor.turnToward(z, behind, FallingPetalRules.TURN_SPEED);
        helper.assertTrue(Math.abs(Math.abs(z.getYRot()) - FallingPetalRules.TURN_SPEED) < 0.01F, "first step " + z.getYRot());
        for (int i = 0; i < 8; i++) {
            FallingPetalExecutor.turnToward(z, behind, FallingPetalRules.TURN_SPEED);
        }
        helper.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(z.getYRot() - 180.0F)) < 0.01F, "did not face the master: " + z.getYRot());
        helper.succeed();
    }
}
