package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.HallDecor;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real placement/undo logic on a flat floor, independent of the world's actual mountain. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectLayoutGameTests {
    private SectLayoutGameTests() {
    }

    private static MountHuaSite site(SectLayoutPlan.Box box, BlockPos corner, int rotation) {
        MountHuaSite origin = MountHuaSite.placement(0, 0, 70, rotation);
        int[] offset = origin.toWorld(box.minU(), box.minV());
        return MountHuaSite.placement(corner.getX() - offset[0], corner.getZ() - offset[1], 70, rotation);
    }

    private static BlockPos cell(MountHuaSite site, SectLayoutPlan.Box box, BlockPos corner, int x, int y, int z) {
        int[] w = site.toWorld(box.minU() + x, box.minV() + z);
        return new BlockPos(w[0], corner.getY() + y, w[1]);
    }

    @GameTest(template = "sect_yard", batch = "sect_layout")
    public static void boxesUndoPersistAndProtectAuthorBlocks(GameTestHelper helper) {
        var level = helper.getLevel();
        var box = new SectLayoutPlan.Box("scriptures", "scriptures", 0, 0, 7, 6,
                SectLayoutPlan.Kind.HALL, Direction.NORTH);
        BlockPos corner = helper.absolutePos(new BlockPos(5, 2, 5));
        var site = site(box, corner, 0);
        BlockPos protectedBlock = cell(site, box, corner, 0, 2, 0);
        BlockPos originalConcrete = cell(site, box, corner, 0, 0, 1);
        BlockPos restore = cell(site, box, corner, 6, 2, 5);
        BlockPos edited = cell(site, box, corner, 6, 2, 0);
        BlockPos outside = helper.absolutePos(new BlockPos(18, 2, 18));
        level.setBlock(protectedBlock, HallDecor.PLASTER.get().defaultBlockState(), 2 | 16);
        level.setBlock(originalConcrete, Blocks.WHITE_CONCRETE.defaultBlockState(), 2 | 16);
        level.setBlock(restore, Blocks.COBBLESTONE.defaultBlockState(), 2 | 16);
        level.setBlock(outside, Blocks.WHITE_CONCRETE.defaultBlockState(), 2 | 16);
        var unrelated = EntityType.TEXT_DISPLAY.create(level);
        helper.assertTrue(unrelated != null, "text display creation failed");
        unrelated.setPos(outside.getX() + 0.5, outside.getY() + 2, outside.getZ() + 0.5);
        level.addFreshEntity(unrelated);

        var data = new SectLayoutData();
        helper.assertTrue(SectLayoutBoxes.place(level, site, List.of(box), data, corner.getY() + 6) == 1, "box not placed");
        helper.assertTrue(SectLayoutBoxes.place(level, site, List.of(box), data, corner.getY() + 6) == 0, "repeat duplicated sketch");
        helper.assertTrue(!data.blocks.containsKey(protectedBlock) && !data.blocks.containsKey(originalConcrete),
                "author's existing blocks entered the undo journal");
        helper.assertTrue(level.getBlockState(protectedBlock).is(HallDecor.PLASTER.get()), "murim block replaced");
        for (int x = 0; x < box.width(); x++) {
            for (int z = 0; z < box.depth(); z++) {
                for (int y = 0; y < box.kind().height(); y++) {
                    BlockPos pos = cell(site, box, corner, x, y, z);
                    boolean border = x == 0 || x == box.width() - 1 || z == 0 || z == box.depth() - 1;
                    if (pos.equals(protectedBlock)) {
                        continue;
                    }
                    helper.assertTrue(border && !box.door(x, z, y)
                                    ? level.getBlockState(pos).is(Blocks.WHITE_CONCRETE) : level.getBlockState(pos).isAir(),
                            "wrong wall, door or interior at " + pos);
                }
            }
        }
        helper.assertTrue(level.getBlockState(corner.above(5)).isAir(), "box has a roof or excess height");
        helper.assertTrue(data.labels.size() == 1, "missing/duplicate label");
        var labelId = data.labels.keySet().iterator().next();
        var display = level.getEntity(labelId);
        helper.assertTrue(display != null, "label not in level");
        String label = display.saveWithoutId(new CompoundTag()).getString("text");
        helper.assertTrue(label.contains("zone.scriptures.ru") && label.contains("zone.scriptures.en"), "label is not bilingual");
        helper.assertTrue(display.getY() == corner.getY() + 6, "label not above box");

        level.setBlock(edited, HallDecor.PLASTER.get().defaultBlockState(), 2 | 16);
        var loaded = SectLayoutData.load(data.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        helper.assertTrue(loaded.blocks.size() == data.blocks.size() && loaded.labels.containsKey(labelId), "journal did not persist");
        helper.assertTrue(SectLayoutBoxes.clear(level, loaded) > 0, "nothing cleared");
        helper.assertTrue(level.getBlockState(restore).is(Blocks.COBBLESTONE), "old block not restored");
        helper.assertTrue(level.getBlockState(corner).isAir(), "marker not removed");
        helper.assertTrue(level.getBlockState(edited).is(HallDecor.PLASTER.get()), "later author edit removed");
        helper.assertTrue(level.getBlockState(protectedBlock).is(HallDecor.PLASTER.get()), "protected block removed");
        helper.assertTrue(level.getBlockState(originalConcrete).is(Blocks.WHITE_CONCRETE), "old same-colour concrete removed");
        helper.assertTrue(level.getBlockState(outside).is(Blocks.WHITE_CONCRETE) && !unrelated.isRemoved(), "unrelated block/label removed");
        helper.assertTrue(display.isRemoved() && !loaded.active(), "label survived clear");
        helper.assertTrue(SectLayoutBoxes.clear(level, loaded) == 0, "second clear changed anything");
        unrelated.discard();
        helper.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_layout")
    public static void rotatedBoxesHaveDoorsOnTheFacingSide(GameTestHelper helper) {
        var box = new SectLayoutPlan.Box("dorm_2nd", "dorm_2nd", 0, 0, 5, 5,
                SectLayoutPlan.Kind.HOUSE, Direction.EAST);
        BlockPos corner = helper.absolutePos(new BlockPos(12, 2, 12));
        for (int rotation = 0; rotation < 4; rotation++) {
            var site = site(box, corner, rotation);
            var data = new SectLayoutData();
            SectLayoutBoxes.place(helper.getLevel(), site, List.of(box), data, corner.getY() + 6);
            helper.assertTrue(data.blocks.size() == 16 * 4 - 6, "wrong wall count after rotation " + rotation);
            for (int z = 1; z <= 2; z++) {
                helper.assertTrue(helper.getLevel().getBlockState(cell(site, box, corner, 4, 1, z)).isAir(),
                        "door missing after rotation " + rotation);
                helper.assertTrue(helper.getLevel().getBlockState(cell(site, box, corner, 4, 0, z)).is(Blocks.LIGHT_GRAY_CONCRETE),
                        "ground outline missing at entrance");
            }
            SectLayoutBoxes.clear(helper.getLevel(), data);
        }
        helper.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_layout")
    public static void outlineFindsSolidGroundUnderWaterAndLeaves(GameTestHelper helper) {
        var level = helper.getLevel();
        var box = new SectLayoutPlan.Box("mentor", "mentor", 0, 0, 5, 5,
                SectLayoutPlan.Kind.OPEN, Direction.NORTH);
        BlockPos corner = helper.absolutePos(new BlockPos(5, 2, 5));
        var site = site(box, corner, 0);
        BlockPos centre = cell(site, box, corner, 2, 0, 2);
        level.setBlock(centre, Blocks.WATER.defaultBlockState(), 2 | 16);
        level.setBlock(centre.above(3), Blocks.OAK_LEAVES.defaultBlockState(), 2 | 16);
        var data = new SectLayoutData();
        SectLayoutBoxes.place(level, site, List.of(box), data, corner.getY() + 6);
        helper.assertTrue(data.blocks.size() == 16, "outline is not one block high");
        helper.assertTrue(level.getBlockState(corner).is(Blocks.LIME_CONCRETE), "water/leaves raised outline above ground");
        helper.assertTrue(level.getBlockState(corner.above()).isAir(), "open area has walls");
        SectLayoutBoxes.clear(level, data);

        level.setBlock(centre, Blocks.AIR.defaultBlockState(), 2 | 16);
        level.setBlock(centre.above(2), Blocks.STONE.defaultBlockState(), 2 | 16);
        data = new SectLayoutData();
        SectLayoutBoxes.place(level, site, List.of(box), data, corner.getY() + 6);
        helper.assertTrue(level.getBlockState(corner.above(3)).is(Blocks.LIME_CONCRETE), "highest solid centre not used");
        SectLayoutBoxes.clear(level, data);
        helper.succeed();
    }
}
