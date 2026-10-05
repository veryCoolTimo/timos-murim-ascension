package io.github.verycooltimo.murim.sect.seal;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.SectState;
import io.github.verycooltimo.murim.technique.Styles;
import io.github.verycooltimo.murim.world.location.CapturedLocation;
import io.github.verycooltimo.murim.world.location.LocationCapture;
import io.github.verycooltimo.murim.world.location.LocationPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the sect seals: a sealed block survives survival, blasts and pistons but not creative; cold iron
 * refuses a Peak cultivator and gives to one above Peak (and grows back); the vault trial opens the cold iron door
 * for the one who passed it; the penance cave takes the sentenced in, keeps him and lets him out; seals and cold
 * iron come back whole from {@code /murim capture}.
 * API: reference/minecraft-src/net/minecraft/server/level/ServerPlayerGameMode.java#handleBlockBreakAction/tick/destroyBlock,
 * PistonBaseBlock#isPushable, Level#explode.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SealGameTests {

    private static final String YARD = "sect_yard";

    private SealGameTests() {
    }

    private static ServerPlayer player(GameTestHelper helper, double x, double z) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "seal-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        BlockPos at = helper.absolutePos(new BlockPos((int) x, 1, (int) z));
        p.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, 0.0F, 0.0F);
        p.setGameMode(GameType.SURVIVAL);
        return p;
    }

    private static DantianProfile cultivator(int rank) {
        DantianProfile p = DantianProfile.INITIAL.withTags("clear", "plum").withRank(rank);
        return p.withCirculating(p.maxCirculating());
    }

    private static void door(ServerLevel level, BlockPos lower) {
        BlockState d = SealRegistry.COLD_IRON_DOOR.get().defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        level.setBlock(lower, d.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(lower.above(), d.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
    }

    // ------------------------------------------------------------------ sealed blocks

    @GameTest(template = YARD, timeoutTicks = 60, batch = "seals")
    public static void sealedBlockUnbreakableInSurvival(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(6, 1, 6));
        BlockPos wallPos = helper.absolutePos(new BlockPos(14, 1, 14));
        level.setBlock(pos, SealRegistry.SEALED_STONE_BRICKS.get().defaultBlockState(), 3);
        level.setBlock(wallPos, SealRegistry.SEALED_HUA_GRANITE_WALL.get().defaultBlockState(), 3);
        ServerPlayer p = player(helper, 4, 6);
        BlockState s = level.getBlockState(pos);
        helper.assertTrue(s.getDestroyProgress(p, level, pos) == 0.0F, "survival digs sealed stone: " + s.getDestroyProgress(p, level, pos));
        helper.assertTrue(!PistonBaseBlock.isPushable(s, level, pos, Direction.EAST, true, Direction.EAST), "a piston pushes sealed stone");
        level.explode(null, pos.getX() + 1.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 4.0F, Level.ExplosionInteraction.TNT);
        helper.assertTrue(level.getBlockState(pos).is(SealRegistry.SEALED_STONE_BRICKS.get()), "a blast took sealed stone");
        // Survival: the break action does nothing.
        p.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, Direction.UP, level.getMaxBuildHeight(), 0);
        p.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, Direction.UP, level.getMaxBuildHeight(), 1);
        helper.assertTrue(level.getBlockState(pos).is(SealRegistry.SEALED_STONE_BRICKS.get()), "survival broke sealed stone");
        // Creative: the author builds and tears down freely.
        p.setGameMode(GameType.CREATIVE);
        helper.assertTrue(p.gameMode.destroyBlock(wallPos) && level.getBlockState(wallPos).isAir(), "creative cannot break sealed wall");
        helper.succeed();
    }

    // ------------------------------------------------------------------ cold iron

    /** Holds attack on the block for {@code ticks} ticks, the way the server sees a client dig. */
    private static void dig(GameTestHelper helper, ServerPlayer p, BlockPos pos, int ticks, Runnable then) {
        int max = helper.getLevel().getMaxBuildHeight();
        p.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, Direction.WEST, max, 0);
        for (int t = 1; t <= ticks; t++) {
            int tick = t;
            helper.runAfterDelay(t, () -> {
                p.gameMode.tick();
                if (tick == ticks) {
                    p.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, Direction.WEST, max, 1);
                    then.run();
                }
            });
        }
    }

    @GameTest(template = YARD, timeoutTicks = 200, batch = "seals")
    public static void coldIronRefusesPeak(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(6, 1, 6));
        level.setBlock(pos, SealRegistry.COLD_IRON_BLOCK.get().defaultBlockState(), 3);
        ServerPlayer p = player(helper, 4, 6);
        p.setData(ModAttachments.PROFILE, cultivator(io.github.verycooltimo.murim.cultivation.Realm.PEAK));
        dig(helper, p, pos, ColdIronRules.BREAK_TICKS + 10, () -> {
            helper.assertTrue(level.getBlockState(pos).is(SealRegistry.COLD_IRON_BLOCK.get()), "cold iron gave way at Peak");
            helper.assertTrue(p.getData(ModAttachments.PROFILE).circulating() >= p.getData(ModAttachments.PROFILE).maxCirculating() - 1.0E-6,
                    "qi drained without a crack");
            helper.succeed();
        });
    }

    @GameTest(template = YARD, timeoutTicks = 200, batch = "seals")
    public static void coldIronBreaksAbovePeakAndGrowsBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(6, 1, 6));
        level.setBlock(pos, SealRegistry.COLD_IRON_BLOCK.get().defaultBlockState(), 3);
        ServerPlayer p = player(helper, 4, 6);
        p.setData(ModAttachments.PROFILE, cultivator(ColdIronRules.MIN_RANK));
        double full = p.getData(ModAttachments.PROFILE).circulating();
        dig(helper, p, pos, ColdIronRules.BREAK_TICKS + 4, () -> {
            helper.assertTrue(level.getBlockState(pos).isAir(), "cold iron held above Peak: " + level.getBlockState(pos));
            helper.assertTrue(p.getData(ModAttachments.PROFILE).circulating() < full * (1.0D - ColdIronRules.COST * 0.8D),
                    "the strike cost no qi: " + p.getData(ModAttachments.PROFILE).circulating());
            ColdIronRegrowth.get(level).regrow(level, level.getGameTime() + ColdIronRules.REGROW_TICKS + 1);
            helper.assertTrue(level.getBlockState(pos).is(SealRegistry.COLD_IRON_BLOCK.get()), "cold iron did not grow back");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ vault trial

    @GameTest(template = YARD, timeoutTicks = 60, batch = "seals")
    public static void vaultTrialOpensDoor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos seal = helper.absolutePos(new BlockPos(6, 1, 6));
        BlockPos doorPos = helper.absolutePos(new BlockPos(9, 1, 6));
        level.setBlock(seal, SealRegistry.VAULT_SEAL.get().defaultBlockState(), 3);
        door(level, doorPos);
        ServerPlayer p = player(helper, 5, 8);
        ServerPlayer stranger = player(helper, 5, 9);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        helper.assertTrue(!SealAccess.mayOpen(p, doorPos), "the door opens before the trial");

        VaultTrial.begin(p, seal);
        VaultTrial.onFoundation(p, VaultLadder.SIX, 0);
        VaultTrial.onFoundation(p, VaultLadder.SIX, 1);
        // A wrong move breaks the ladder; footwork does not count.
        VaultTrial.onTechnique(p, ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "dark_fragrance_step"));
        helper.assertTrue(VaultTrial.step(p) == 2, "footwork broke the ladder: " + VaultTrial.step(p));
        VaultTrial.onTechnique(p, Styles.SEVEN_PLUM.forms().get(0));
        helper.assertTrue(VaultTrial.step(p) == 0, "a wrong move did not reset: " + VaultTrial.step(p));
        for (VaultLadder.Step s : VaultLadder.LADDER) {
            if (s.foundation()) {
                VaultTrial.onFoundation(p, s.technique(), s.form());
            } else {
                VaultTrial.onTechnique(p, s.technique());
            }
        }
        helper.assertTrue(!VaultTrial.active(p), "the trial still runs after the whole ladder");
        helper.assertTrue(p.getData(ModAttachments.SECT).has(SealAccess.VAULT_FLAG), "no vault flag after the trial");
        helper.assertTrue(level.getBlockState(doorPos).getValue(DoorBlock.OPEN), "the door did not open on success");
        helper.assertTrue(SealAccess.mayOpen(p, doorPos), "the door does not know the one who passed");
        helper.assertTrue(!SealAccess.mayOpen(stranger, doorPos), "the door opens for a stranger");
        helper.succeed();
    }

    // ------------------------------------------------------------------ penance

    @GameTest(template = YARD, timeoutTicks = 60, batch = "seals_penance")
    public static void penanceLocksAndReleases(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(6, 0, 6));
        BlockPos doorPos = helper.absolutePos(new BlockPos(9, 1, 6));
        level.setBlock(cell, SealRegistry.PENANCE_SEAL.get().defaultBlockState(), 3);
        door(level, doorPos);
        level.setBlock(doorPos, level.getBlockState(doorPos).setValue(DoorBlock.OPEN, true), 10);
        ServerPlayer p = player(helper, 12, 12);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().contribute(20));

        PenanceService.offence(p, "trespass", null);
        helper.assertTrue(!p.getData(ModAttachments.SECT).has(PenanceService.SUMMONED), "the first offence already sentenced");
        PenanceService.offence(p, "trespass", null);
        helper.assertTrue(p.getData(ModAttachments.SECT).has(PenanceService.SUMMONED), "the second offence was not called to account");

        PenanceService.accept(p);
        helper.assertTrue(PenanceService.sentenced(p), "not sentenced after accepting");
        helper.assertTrue(p.blockPosition().equals(cell.above()), "not in the cell: " + p.blockPosition() + " cell " + cell);
        helper.assertTrue(!level.getBlockState(doorPos).getValue(DoorBlock.OPEN), "the cave door stayed open");
        helper.assertTrue(PenanceService.meditationFactor(p) == PenanceRules.MEDITATION_FACTOR, "meditation is not faster in the cell");
        helper.assertTrue(!SealAccess.mayOpen(p, doorPos), "the sentenced opens the cave door himself");

        // Walks off — brought back.
        p.teleportTo(level, cell.getX() + 15.5D, cell.getY() + 1.0D, cell.getZ() + 0.5D, 0.0F, 0.0F);
        PenanceService.tick(p);
        helper.assertTrue(p.blockPosition().equals(cell.above()), "left the cave freely: " + p.blockPosition());

        // Served: released, the door opens.
        long before = level.getDayTime();
        level.setDayTime(PenanceRules.until(before) + 1L);
        PenanceService.tick(p);
        level.setDayTime(before);
        helper.assertTrue(!PenanceService.sentenced(p) && !p.getData(ModAttachments.SECT).has(PenanceService.SENTENCED), "not released");
        helper.assertTrue(level.getBlockState(doorPos).getValue(DoorBlock.OPEN), "the elders did not open the door");

        // Refusing costs merit and keeps him free.
        ServerPlayer q = player(helper, 14, 14);
        q.setData(ModAttachments.SECT, SectState.NONE.joined().contribute(20).with(PenanceService.SUMMONED));
        PenanceService.refuse(q);
        helper.assertTrue(q.getData(ModAttachments.SECT).contribution() == 20 - PenanceRules.REFUSE_COST && !PenanceService.sentenced(q),
                "refusal: contribution " + q.getData(ModAttachments.SECT).contribution());
        helper.succeed();
    }

    // ------------------------------------------------------------------ capture

    /** Seals and cold iron are plain blocks: a captured and re-placed template brings them back, turned with it. */
    @GameTest(template = "camp_floor", timeoutTicks = 100, batch = "seals_capture")
    public static void sealsSurviveCapture(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos min = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos max = helper.absolutePos(new BlockPos(12, 6, 12));
        BlockPos sealed = helper.absolutePos(new BlockPos(4, 2, 4));
        BlockPos seal = helper.absolutePos(new BlockPos(6, 2, 4));
        BlockPos doorPos = helper.absolutePos(new BlockPos(8, 2, 4));
        level.setBlock(sealed, SealRegistry.SEALED_HUA_GRANITE.get().defaultBlockState(), 3);
        level.setBlock(seal, SealRegistry.VAULT_SEAL.get().defaultBlockState(), 3);
        door(level, doorPos);
        CapturedLocation loc;
        try {
            loc = LocationCapture.capture(level, "test_seals", BoundingBox.fromCorners(min, max), null, null, false).location();
        } catch (Exception e) {
            helper.fail("capture: " + e);
            return;
        }
        for (BlockPos q : BlockPos.betweenClosed(min.above(), max)) {
            level.setBlock(q, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        BlockPos origin = min.offset(10, 0, 0);
        Rotation rot = Rotation.CLOCKWISE_90;
        helper.assertTrue(LocationPlacer.place(level.getServer(), level, loc, origin, rot, null, level.getRandom(), 3), "templates missing");
        helper.assertTrue(level.getBlockState(CapturedLocation.world(origin, rot, sealed.subtract(min))).is(SealRegistry.SEALED_HUA_GRANITE.get()),
                "sealed granite lost in capture");
        BlockState s2 = level.getBlockState(CapturedLocation.world(origin, rot, seal.subtract(min)));
        helper.assertTrue(s2.is(SealRegistry.VAULT_SEAL.get()) && s2.getValue(SealBlocks.VaultSeal.FACING) == rot.rotate(Direction.NORTH),
                "vault seal lost or not turned: " + s2);
        BlockPos d2 = CapturedLocation.world(origin, rot, doorPos.subtract(min));
        helper.assertTrue(level.getBlockState(d2).is(SealRegistry.COLD_IRON_DOOR.get())
                && level.getBlockState(d2.above()).is(SealRegistry.COLD_IRON_DOOR.get()), "cold iron door lost in capture");
        helper.succeed();
    }
}
