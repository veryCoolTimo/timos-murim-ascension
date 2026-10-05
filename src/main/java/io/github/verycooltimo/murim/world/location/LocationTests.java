package io.github.verycooltimo.murim.world.location;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.camp.BanditCampData;
import io.github.verycooltimo.murim.world.camp.BanditCampPiece;
import io.github.verycooltimo.murim.world.camp.BanditCamps;
import io.github.verycooltimo.murim.world.camp.CampTemplate;
import io.github.verycooltimo.murim.world.hua.HuaBiomes;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the location round trip (docs/design/28-location-capture.md): capture with marker signs, place back
 * turned, camp posts from spawn signs, the Mount Hua biome painted into a chunk, {@code /locate biome} finding it.
 * Captures here use test ids and never write into the source tree.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/gametest/GameTestHolder.java,
 * reference/minecraft-src/net/minecraft/gametest/framework/GameTestHelper.java (relative y=1 is the floor of
 * the template, agent-log 04.10), reference/minecraft-src/net/minecraft/commands/CommandSourceStack.java.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class LocationTests {

    private LocationTests() {
    }

    private static void sign(ServerLevel level, BlockPos at, String text) {
        level.setBlock(at, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8), 3);
        if (level.getBlockEntity(at) instanceof SignBlockEntity s) {
            s.setText(new SignText().setMessage(0, Component.literal(text)), true);
        }
    }

    /**
     * A small scene — a stone pillar, a chest with a {@code loot:} sign on it, a {@code spawn:chief} sign — is
     * captured, wiped and placed back turned a quarter: the pillar and the chest stand where the turn sends them,
     * the chest carries the loot table, the signs are gone, the manifest has both markers; the camp built on that
     * template puts its chief on the spawn sign.
     */
    @GameTest(template = "camp_floor", timeoutTicks = 100)
    public static void captureAndPlaceTurned(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos min = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos max = helper.absolutePos(new BlockPos(14, 7, 14));
        for (int y = 2; y <= 4; y++) {
            level.setBlock(helper.absolutePos(new BlockPos(5, y, 5)), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        }
        BlockPos chest = helper.absolutePos(new BlockPos(8, 2, 8));
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        if (level.getBlockEntity(chest) instanceof ChestBlockEntity c) {
            c.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT));
        }
        sign(level, chest.above(), "loot:chest_tier1");
        BlockPos chiefSign = helper.absolutePos(new BlockPos(11, 2, 10));
        sign(level, chiefSign, "spawn:chief");
        String id = "test_capture";
        CapturedLocation loc;
        try {
            loc = LocationCapture.capture(level, id, BoundingBox.fromCorners(min, max), null, null, false).location();
        } catch (Exception e) {
            helper.fail("capture: " + e);
            return;
        }
        helper.assertTrue(loc.markers().size() == 2, "markers " + loc.markers());
        LocationMarker loot = loc.markers("loot").get(0);
        helper.assertTrue(loot.pos().equals(chest.subtract(min)), "loot marker at " + loot.pos() + ", chest at " + chest.subtract(min));
        LocationMarker chief = loc.markers("spawn").get(0);
        helper.assertTrue(chief.name().equals("chief") && chief.pos().equals(chiefSign.subtract(min)), "chief " + chief);
        helper.assertTrue(LocationTemplates.get(level.getServer(), id) != null, "manifest not readable back");

        // Wipe the scene, then place it back turned 90° with the origin at the far x edge (stays inside the floor).
        for (BlockPos p : BlockPos.betweenClosed(min.above(), max)) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        }
        BlockPos origin = min.offset(12, 0, 0);
        Rotation rot = Rotation.CLOCKWISE_90;
        boolean ok = LocationPlacer.place(level.getServer(), level, loc, origin, rot, null, level.getRandom(), 3);
        helper.assertTrue(ok, "templates missing");
        BlockPos pillar = CapturedLocation.world(origin, rot, helper.absolutePos(new BlockPos(5, 3, 5)).subtract(min));
        helper.assertTrue(level.getBlockState(pillar).is(Blocks.STONE_BRICKS), "pillar not at " + pillar + ": " + level.getBlockState(pillar));
        BlockPos chest2 = loot.world(origin, rot);
        helper.assertTrue(level.getBlockEntity(chest2) instanceof ChestBlockEntity c2 && c2.getLootTable() != null
                && c2.getLootTable().location().toString().equals("murim:chests/bandit_camp/crate"),
                "chest at " + chest2 + " has no fresh loot table");
        helper.assertTrue(level.getBlockState(chest2.above()).isAir(), "loot sign was not cut out");
        BlockPos chief2 = chief.world(origin, rot);
        helper.assertTrue(level.getBlockState(chief2).isAir(), "spawn sign was not cut out");

        // Camp life on this template: one bandit, the chief, standing on the sign.
        LocationTemplatePiece tpl = new LocationTemplatePiece(loc, origin, rot);
        BlockPos centre = CapturedLocation.world(origin, rot, loc.anchor());
        BanditCampData.Camp camp = BanditCamps.data(level).getOrCreate(centre.asLong() ^ 0x7E57L, centre, 42L);
        CampTemplate.attach(camp, BanditCampPiece.shell(42L, centre, rot, tpl.getBoundingBox()), tpl);
        helper.assertTrue(CampTemplate.posts(camp).size() == 1 && CampTemplate.roster(camp).get(0).chief(),
                "posts " + CampTemplate.posts(camp));
        helper.assertTrue(camp.templateFeet.get(0).equals(chief2), "chief stands at " + camp.templateFeet.get(0) + " not " + chief2);
        try {
            LocationCapture.deleteWorldCopy(level.getServer(), id);
        } catch (Exception e) {
            helper.fail("cleanup: " + e);
        }
        helper.assertTrue(LocationTemplates.get(level.getServer(), id) == null, "capture still visible after forget");
        helper.succeed();
    }

    /**
     * The cleanup pass paints the chunk's stored biome: a mountain placed with its sect over the test chunk turns that
     * chunk into murim:mount_hua above y 48 and leaves the deep underground alone.
     */
    @GameTest(template = "small_floor", timeoutTicks = 40)
    public static void biomeIsPaintedIntoTheChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        // Mountain centred so that local (6, 4) — the training ground — is this column.
        MountHuaSite site = new MountHuaSite(at.getX() - 6, at.getZ() - 4, 70, 0, level.getSeed());
        LevelChunk chunk = level.getChunkAt(at);
        HuaBiomes.paint(level, chunk, site);
        // Exact stored biome of the quart (no fuzzy zoom into the unpainted neighbour chunk).
        int qx = net.minecraft.core.QuartPos.fromBlock(at.getX());
        int qz = net.minecraft.core.QuartPos.fromBlock(at.getZ());
        var high = chunk.getNoiseBiome(qx, net.minecraft.core.QuartPos.fromBlock(Math.max(at.getY(), 64)), qz);
        helper.assertTrue(high.is(HuaBiomes.MOUNT_HUA), "biome " + high.getRegisteredName());
        var deep = chunk.getNoiseBiome(qx, net.minecraft.core.QuartPos.fromBlock(level.getMinBuildHeight() + 2), qz);
        helper.assertTrue(!deep.is(HuaBiomes.MOUNT_HUA), "deep underground painted too");
        helper.succeed();
    }

    /** {@code /locate biome murim:mount_hua} answers with a position (the vanilla search alone never finds it). */
    @GameTest(template = "small_floor", timeoutTicks = 40)
    public static void locateFindsMountHua(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        if (io.github.verycooltimo.murim.world.hua.MountHuaSites.get(level.getServer()) == null) {
            helper.fail("no Mount Hua site in the test world");
            return;
        }
        List<String> out = new ArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                out.add(component.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack stack = new CommandSourceStack(sink, Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO)), Vec2.ZERO, level, 4,
                "test", Component.literal("test"), level.getServer(), null);
        level.getServer().getCommands().performPrefixedCommand(stack, "locate biome murim:mount_hua");
        helper.assertTrue(out.stream().anyMatch(s -> s.contains("murim:mount_hua") && s.contains("[")), "locate said " + out);
        helper.succeed();
    }

    /** Sign facing survives as a marker direction (standing sign rotation 8 = south). */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void standingSignFacing(GameTestHelper helper) {
        helper.assertTrue(LocationCapture.facing(Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8)) == Direction.SOUTH,
                "rotation 8 is not south");
        helper.succeed();
    }
}
