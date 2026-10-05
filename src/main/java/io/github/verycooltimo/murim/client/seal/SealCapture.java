package io.github.verycooltimo.murim.client.seal;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.seal.ColdIronRules;
import io.github.verycooltimo.murim.sect.seal.SealRegistry;
import io.github.verycooltimo.murim.sect.seal.VaultLadder;
import io.github.verycooltimo.murim.sect.seal.VaultTrial;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Stand of the sect seals ({@code ./capture.sh 600 back seals}, only {@code -Dmurim.capture=true}): a wall of sealed
 * stone with a cold iron block in it; a Peak cultivator holds attack on the iron (real input — the attack key is
 * pressed by the stand) and it does not yield; the same player above Peak breaks it with qi; then the vault door
 * with its seal: the ladder is fed rung by rung through the trial hooks (the moves themselves are not animated
 * here) and the cold iron door opens. Frames: {@code screenshots/murim_seal_<step>_<n>.png}; waits in client ticks.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class SealCapture {

    private static final boolean ENABLED = Boolean.getBoolean("murim.capture")
            && "seals".equals(System.getProperty("murim.capture.technique"));

    private record Step(String name, int ticks, int every, Runnable start) {
    }

    private static final java.util.List<Step> STEPS = new java.util.ArrayList<>();
    private static boolean setup;
    private static int index = -1;
    private static int t;
    private static int frame;
    private static BlockPos base;
    private static boolean attack;

    private SealCapture() {
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
            STEPS.add(new Step("wait", 260, 0, () -> { }));
            STEPS.add(new Step("refuse", 75, 3, () -> server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                rank(p, Realm.PEAK);
                stand(p, 0.5D, 1.6D, 0.0F, 5.0F);
                p.sendSystemMessage(Component.literal("Rank: Peak"));
                attack = true;
            })));
            STEPS.add(new Step("rank", 20, 4, () -> {
                attack = false;
                server.execute(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    rank(p, ColdIronRules.MIN_RANK);
                    p.sendSystemMessage(Component.literal("Rank: above Peak, full qi"));
                });
            }));
            STEPS.add(new Step("break", ColdIronRules.BREAK_TICKS + 30, 3, () -> attack = true));
            STEPS.add(new Step("trial", VaultLadder.LADDER.size() * 8 + 60, 4, () -> {
                attack = false;
                server.execute(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    stand(p, 0.5D, 5.6D, -12.0F, 8.0F);
                    VaultTrial.begin(p, base.offset(2, 1, 8));
                });
            }));
            STEPS.add(new Step("done", 30, 0, () -> MurimMod.LOGGER.info("Стенд печатей: снято")));
            return;
        }
        if (index >= STEPS.size()) {
            return;
        }
        if (index < 0 || t >= STEPS.get(index).ticks()) {
            index++;
            t = 0;
            frame = 0;
            if (index >= STEPS.size()) {
                mc.stop();
                return;
            }
            MurimMod.LOGGER.info("Стенд печатей: шаг {}", STEPS.get(index).name());
            STEPS.get(index).start().run();
        }
        Step s = STEPS.get(index);
        mc.options.keyAttack.setDown(attack);
        if ("trial".equals(s.name()) && t >= 20 && (t - 20) % 8 == 0) {
            int rung = (t - 20) / 8;
            if (rung < VaultLadder.LADDER.size()) {
                VaultLadder.Step r = VaultLadder.LADDER.get(rung);
                server.execute(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    if (r.foundation()) {
                        VaultTrial.onFoundation(p, r.technique(), r.form());
                    } else {
                        VaultTrial.onTechnique(p, r.technique());
                    }
                });
            }
        }
        if (s.every() > 0 && t % s.every() == 0) {
            Screenshot.grab(mc.gameDirectory, String.format("murim_seal_%s_%03d.png", s.name(), frame++), mc.getMainRenderTarget(), m -> {
            });
        }
        t++;
    }

    private static void rank(ServerPlayer p, int rank) {
        DantianProfile prof = p.getData(ModAttachments.PROFILE);
        if (!prof.isAwakened()) {
            prof = prof.withTags("clear", "plum");
        }
        prof = prof.withRank(rank);
        p.setData(ModAttachments.PROFILE, prof.withCirculating(prof.maxCirculating()));
        ProfileNetwork.sync(p);
    }

    /** Puts the player at {@code base + (x, 0, z)} looking south with the given turn and pitch. */
    private static void stand(ServerPlayer p, double x, double z, float yaw, float pitch) {
        p.teleportTo(p.serverLevel(), base.getX() + x, base.getY(), base.getZ() + z, yaw, pitch);
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
        p.setXRot(pitch);
    }

    /**
     * Wall of sealed bricks with a cold iron block at eye height and cold iron bars beside it; behind it the vault
     * front of sealed granite with a cold iron door and the vault seal; lanterns for the night.
     */
    private static void build(IntegratedServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setWeatherParameters(12000, 0, false, false);
        if (!"day".equals(System.getProperty("murim.time", "night"))) {
            level.setDayTime(18000L);
        }
        p.setGameMode(GameType.SURVIVAL);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        base = p.blockPosition().offset(0, 0, 2);
        for (int x = -6; x <= 6; x++) {
            for (int z = -2; z <= 14; z++) {
                level.setBlock(base.offset(x, -1, z), SealRegistry.SEALED_POLISHED_HUA_GRANITE.get().defaultBlockState(), 2);
                for (int y = 0; y <= 6; y++) {
                    level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        // First wall: sealed bricks, cold iron block at eye height, bars at the sides.
        for (int x = -4; x <= 4; x++) {
            for (int y = 0; y <= 3; y++) {
                BlockState s = Math.abs(x) == 2 && y <= 2 ? SealRegistry.COLD_IRON_BARS.get().defaultBlockState()
                        : SealRegistry.SEALED_STONE_BRICKS.get().defaultBlockState();
                level.setBlock(base.offset(x, y, 4), s, 3);
            }
        }
        level.setBlock(base.offset(0, 1, 4), SealRegistry.COLD_IRON_BLOCK.get().defaultBlockState(), 3);
        level.setBlock(base.offset(0, 0, 4), SealRegistry.COLD_IRON_BLOCK.get().defaultBlockState(), 3);
        // The vault front: sealed granite, the cold iron door, the seal beside it.
        for (int x = -4; x <= 4; x++) {
            for (int y = 0; y <= 3; y++) {
                level.setBlock(base.offset(x, y, 8), SealRegistry.SEALED_HUA_GRANITE.get().defaultBlockState(), 3);
            }
        }
        for (int x = -4; x <= 4; x++) {
            for (int z = 8; z <= 12; z++) {
                level.setBlock(base.offset(x, 4, z), SealRegistry.SEALED_PLANKS.get().defaultBlockState(), 3);
            }
        }
        BlockState door = SealRegistry.COLD_IRON_DOOR.get().defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        level.setBlock(base.offset(0, 0, 8), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(base.offset(0, 1, 8), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
        level.setBlock(base.offset(2, 1, 8), SealRegistry.VAULT_SEAL.get().defaultBlockState()
                .setValue(io.github.verycooltimo.murim.sect.seal.SealBlocks.VaultSeal.FACING, Direction.NORTH), 3);
        level.setBlock(base.offset(0, 0, 11), Blocks.CHEST.defaultBlockState(), 3);
        for (int[] l : new int[][] {{-3, 1}, {3, 1}, {-3, 6}, {3, 6}, {-1, 10}, {1, 10}}) {
            level.setBlock(base.offset(l[0], 0, l[1]), Blocks.LANTERN.defaultBlockState(), 3);
        }
        MurimMod.LOGGER.info("Стенд печатей: сцена у {}", base.toShortString());
    }
}
