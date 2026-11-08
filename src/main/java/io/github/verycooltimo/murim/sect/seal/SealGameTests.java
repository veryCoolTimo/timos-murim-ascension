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
 * iron come back whole from {@code /murim dev loc capture}.
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
        return player(helper, x, z, new java.util.ArrayList<>());
    }

    private static ServerPlayer player(GameTestHelper helper, double x, double z,
            java.util.List<net.minecraft.network.chat.Component> bars) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "seal-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public void displayClientMessage(net.minecraft.network.chat.Component message, boolean actionBar) {
                bars.add(message);
                super.displayClientMessage(message, actionBar);
            }
        };
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

    @GameTest(template = YARD, timeoutTicks = 600, batch = "seals")
    public static void doorTrialIsAutomaticAndSilent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos door = helper.absolutePos(new BlockPos(6, 1, 6));
        level.setBlock(door, io.github.verycooltimo.murim.sect.vault.VaultRegistry.VAULT_DOOR.get().defaultBlockState(), 3);
        var bars = new java.util.ArrayList<net.minecraft.network.chat.Component>();
        ServerPlayer p = player(helper, 6, 9, bars);
        var forms = Styles.TWENTY_FOUR_PLUM.forms();
        VaultTrial.onTechnique(p, VaultLadder.PETAL);
        helper.assertTrue(!io.github.verycooltimo.murim.sect.vault.VaultDoorBlock.isOpen(level, door),
                "a non-24-Plum technique opened the door");
        p.moveTo(door.getX() + 20, door.getY(), door.getZ(), 0, 0);
        VaultTrial.tick(p);
        helper.assertTrue(!VaultTrial.active(p), "leaving did not end the trial");
        p.moveTo(door.getX() + 0.5, door.getY(), door.getZ() + 3.5, 0, 0);
        VaultTrial.onTechnique(p, VaultLadder.PETAL);
        p.getPersistentData().getCompound("murim_vault_trial").putLong("last", level.getGameTime() - VaultTrial.IDLE_TICKS - 1);
        VaultTrial.tick(p);
        helper.assertTrue(!VaultTrial.active(p), "idle trial did not end");
        var form = forms.get(0);
        var definition = io.github.verycooltimo.murim.technique.TechniqueLoader.get(form);
        int duration = definition == null ? 50 : definition.totalTicks();
        long start = level.getGameTime();
        p.setData(ModAttachments.TECHNIQUE_STATE, io.github.verycooltimo.murim.combat.TechniqueState.started(form, start));
        VaultTrial.onTechnique(p, form);
        helper.runAfterDelay(duration / 2, () -> {
            p.setData(ModAttachments.TECHNIQUE_STATE, new io.github.verycooltimo.murim.combat.TechniqueState(form, duration / 2, true, start));
            VaultTrial.tick(p);
            helper.assertTrue(!io.github.verycooltimo.murim.sect.vault.VaultDoorBlock.isOpen(level, door), "door opened mid-form");
            io.github.verycooltimo.murim.combat.TechniqueService.cancel(p);
            helper.assertTrue(!io.github.verycooltimo.murim.sect.vault.VaultDoorBlock.isOpen(level, door), "cancel opened door");
            long retry = level.getGameTime();
            p.setData(ModAttachments.TECHNIQUE_STATE, io.github.verycooltimo.murim.combat.TechniqueState.started(form, retry));
            VaultTrial.onTechnique(p, form);
            helper.runAfterDelay(duration, () -> {
                io.github.verycooltimo.murim.combat.TechniqueService.finish(p);
                helper.assertTrue(!io.github.verycooltimo.murim.sect.vault.VaultDoorBlock.isOpen(level, door), "missing finish delay");
                helper.runAfterDelay(8, () -> {
                    VaultTrial.tick(p);
                    helper.assertTrue(io.github.verycooltimo.murim.sect.vault.VaultDoorBlock.isOpen(level, door), "finished form did not open door");
                    helper.assertTrue(bars.isEmpty(), "trial emitted text feedback");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = YARD, timeoutTicks = 60, batch = "seals")
    public static void oldSealPaletteBecomesWall(GameTestHelper helper) {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("Name", "murim:vault_seal");
        var properties = new net.minecraft.nbt.CompoundTag();
        properties.putString("facing", "north");
        properties.putString("petals", "3");
        tag.put("Properties", properties);
        var state = net.minecraft.nbt.NbtUtils.readBlockState(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(), tag);
        helper.assertTrue(state.is(SealRegistry.SEALED_POLISHED_HUA_GRANITE.get()), "legacy palette did not resolve to wall");
        helper.assertTrue(!net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet().contains(
                ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "vault_seal")), "obsolete block still registered");
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

    /** Holes review npc-1: a released (free) member can open the cave door again, and the door waits for him in the doorway. */
    @GameTest(template = YARD, timeoutTicks = 100, batch = "seals_penance_release")
    public static void releasedPrisonerIsNeverWalledIn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(6, 0, 6));
        BlockPos doorPos = helper.absolutePos(new BlockPos(9, 1, 6));
        level.setBlock(cell, SealRegistry.PENANCE_SEAL.get().defaultBlockState(), 3);
        door(level, doorPos);
        ServerPlayer p = player(helper, 8, 6);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().contribute(20));
        helper.assertTrue(PenanceService.sentence(p, cell), "not sentenced");
        helper.assertTrue(!SealAccess.mayOpen(p, doorPos), "the sentenced opens the door");
        level.setDayTime(PenanceRules.until(level.getDayTime()) + 1L);
        PenanceService.tick(p);
        helper.assertTrue(!PenanceService.sentenced(p), "not released");
        helper.assertTrue(SealAccess.mayOpen(p, doorPos), "the released cannot open the door again");
        // The shutting tick finds him in the doorway: the door stays open and waits.
        p.teleportTo(level, doorPos.getX() + 0.5D, doorPos.getY(), doorPos.getZ() + 0.5D, 0.0F, 0.0F);
        level.addNewPlayer(p);
        level.setBlock(doorPos, level.getBlockState(doorPos).setValue(DoorBlock.OPEN, true), 10);
        var st = level.getBlockState(doorPos);
        ((ColdIronDoorBlock) st.getBlock()).tick(st, level, doorPos, level.random);
        helper.assertTrue(level.getBlockState(doorPos).getValue(DoorBlock.OPEN), "the door shut on the man in the doorway");
        level.removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        // A stranger in the doorway does not hold it.
        ServerPlayer q = player(helper, 20, 20);
        q.setData(ModAttachments.SECT, SectState.NONE);
        helper.assertTrue(!SealAccess.mayOpen(q, doorPos), "an outsider far from the cave opens the door");
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
        level.setBlock(seal, SealRegistry.PENANCE_SEAL.get().defaultBlockState(), 3);
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
        helper.assertTrue(s2.is(SealRegistry.PENANCE_SEAL.get()),
                "penance seal lost: " + s2);
        BlockPos d2 = CapturedLocation.world(origin, rot, doorPos.subtract(min));
        helper.assertTrue(level.getBlockState(d2).is(SealRegistry.COLD_IRON_DOOR.get())
                && level.getBlockState(d2.above()).is(SealRegistry.COLD_IRON_DOOR.get()), "cold iron door lost in capture");
        helper.succeed();
    }
}
