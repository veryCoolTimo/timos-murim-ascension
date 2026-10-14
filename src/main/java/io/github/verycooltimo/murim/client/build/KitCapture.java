package io.github.verycooltimo.murim.client.build;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModBlocks;
import io.github.verycooltimo.murim.world.build.PillarBridgeBlockEntity;
import io.github.verycooltimo.murim.world.build.RedPillarBlock;
import io.github.verycooltimo.murim.world.build.TimberOffset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Stand of the timber kit ({@code MURIM_CAPTURE_FRAMES=12 ./capture.sh 400 back kit}, only with
 * {@code -Dmurim.capture=true}): proves the client bakes the column models (the 07.10 out-of-memory at model baking)
 * and films bridged columns, a span column between two columns and white columns. Frames
 * {@code screenshots/murim_kit_<view>_<n>.png}; the pattern after SealCapture.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class KitCapture {

    private static final boolean ENABLED = Boolean.getBoolean("murim.capture")
            && "kit".equals(System.getProperty("murim.capture.technique"));

    private static final float[][] VIEWS = {{0.5F, -1.5F, 180.0F, 10.0F}, {-3.5F, 1.0F, 225.0F, 12.0F}, {4.5F, 1.0F, 135.0F, 12.0F}};

    private static boolean setup;
    private static int t;
    private static int frame;
    private static BlockPos base;

    private KitCapture() {
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (mc.player == null || mc.level == null || server == null) {
            return;
        }
        if (!setup) {
            setup = true;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.fov().set(70);
            server.execute(() -> build(server));
            return;
        }
        t++;
        // let chunks and block entities arrive, then 4 frames from each view
        int start = 200;
        if (t < start) {
            return;
        }
        int k = (t - start) / 20;
        if (k >= VIEWS.length) {
            if (t == start + VIEWS.length * 20 + 10) {
                MurimMod.LOGGER.info("Стенд набора: снято");
                mc.stop();
            }
            return;
        }
        if ((t - start) % 20 == 0) {
            float[] v = VIEWS[k];
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                p.teleportTo(p.serverLevel(), base.getX() + v[0], base.getY(), base.getZ() + v[1], v[2], v[3]);
                p.setYRot(v[2]);
                p.setYHeadRot(v[2]);
                p.setXRot(v[3]);
            });
        }
        if ((t - start) % 20 >= 12 && (t - start) % 2 == 0) {
            Screenshot.grab(mc.gameDirectory, String.format("murim_kit_%d_%03d.png", k, frame++), mc.getMainRenderTarget(), m -> {
            });
        }
    }

    private static BlockState column(Block b, int up, int down) {
        return b.defaultBlockState().setValue(RedPillarBlock.UP, up > 0 ? RedPillarBlock.Joint.RUN : RedPillarBlock.Joint.NONE)
                .setValue(RedPillarBlock.DOWN, down > 0 ? RedPillarBlock.Joint.RUN : RedPillarBlock.Joint.NONE);
    }

    private static void build(IntegratedServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.setDayTime(6000L);
        p.setGameMode(GameType.SPECTATOR);
        base = p.blockPosition().offset(0, 0, 4);
        for (int x = -6; x <= 8; x++) {
            for (int z = -3; z <= 6; z++) {
                level.setBlock(base.offset(x, -1, z), ModBlocks.PLUM_PLANKS.get().defaultBlockState(), 2);
                for (int y = 0; y <= 6; y++) {
                    level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        // 1. two red columns side by side with bridges at two heights
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x <= 1; x++) {
                level.setBlock(base.offset(x, y, 2), column(ModBlocks.RED_PILLAR.get(), y < 2 ? 1 : 0, y), 3);
            }
        }
        for (int y : new int[] {0, 2}) {
            BlockPos at = base.offset(0, y, 2);
            level.setBlock(at, level.getBlockState(at).setValue(RedPillarBlock.BRIDGED, true), 3);
            if (level.getBlockEntity(at) instanceof PillarBridgeBlockEntity be) {
                be.set(Direction.Axis.X, y == 0 ? RedPillarBlock.Bridge.LOW_MID : RedPillarBlock.Bridge.HIGH_MID);
            }
        }
        // 2. white columns a block apart with a lying column spanning the gap and red beams
        for (int y = 0; y < 4; y++) {
            for (int x : new int[] {-4, -2}) {
                level.setBlock(base.offset(x, y, 2), column(ModBlocks.WHITE_PILLAR.get(), y < 3 ? 1 : 0, y), 3);
            }
        }
        level.setBlock(base.offset(-3, 1, 2), RedPillarBlock.connect(ModBlocks.RED_PILLAR.get().defaultBlockState()
                .setValue(RedPillarBlock.AXIS, Direction.Axis.X).setValue(RedPillarBlock.OFFSET, TimberOffset.CENTER), level, base.offset(-3, 1, 2)), 3);
        level.setBlock(base.offset(-3, 3, 2), io.github.verycooltimo.murim.world.build.RedBeamBlock.connect(ModBlocks.RED_BEAM.get()
                .defaultBlockState(), level, base.offset(-3, 3, 2)), 3);
        // 3. a white column bridged to a red one
        for (int y = 0; y < 3; y++) {
            level.setBlock(base.offset(4, y, 2), column(ModBlocks.WHITE_PILLAR.get(), y < 2 ? 1 : 0, y), 3);
            level.setBlock(base.offset(5, y, 2), column(ModBlocks.RED_PILLAR.get(), y < 2 ? 1 : 0, y), 3);
        }
        BlockPos at = base.offset(4, 1, 2);
        level.setBlock(at, level.getBlockState(at).setValue(RedPillarBlock.BRIDGED, true), 3);
        if (level.getBlockEntity(at) instanceof PillarBridgeBlockEntity be) {
            be.set(Direction.Axis.X, RedPillarBlock.Bridge.MID_MID);
        }
        MurimMod.LOGGER.info("Стенд набора: сцена у {}", base.toShortString());
    }
}
