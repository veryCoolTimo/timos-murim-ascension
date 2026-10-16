package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.HallDecor;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaPlan;
import io.github.verycooltimo.murim.world.hua.MountHuaShape;
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
    public static void newRoofsKeepFullClearanceAndAuthoredGateAndArchKeepTheirPads(GameTestHelper helper) {
        for (var box : SectLayoutPlan.BOXES) {
            if (box.kind().outlineOnly()) continue;
            var z = box.zone();
            int x = (int) Math.floor(z.u() - z.width() / 2.0);
            int v = (int) Math.floor(z.v() - z.depth() / 2.0);
            int[] required = box.requiredPad();
            int expectedMargin = box.id().equals("sect_gate") ? 2 : box.id().equals("arch") ? 1 : 5;
            helper.assertTrue(box.padMargin() == expectedMargin, "unexpected roof clearance exception: " + box.id());
            helper.assertTrue(required[0] >= x && required[1] >= v
                    && required[2] <= x + z.width() && required[3] <= v + z.depth(),
                    "roof/walking margin outside " + box.id());
        }
        for (int i = 0; i < MountHuaPlan.ZONES.size(); i++) {
            var a = MountHuaPlan.ZONES.get(i);
            if (a.cave()) continue;
            for (var b : MountHuaPlan.ZONES.subList(i + 1, MountHuaPlan.ZONES.size())) {
                if (b.cave()) continue;
                boolean overlaps = Math.abs(a.u() - b.u()) * 2 < a.width() + b.width()
                        && Math.abs(a.v() - b.v()) * 2 < a.depth() + b.depth();
                boolean nested = (a.id().equals("camp") && b.id().startsWith("dorm_"))
                        || a.id().equals("training") && (b.id().equals("mentor") || b.id().equals("poles"));
                helper.assertTrue(!overlaps || nested && a.y() == b.y(), "overlapping neighbouring pads: " + a.id() + "/" + b.id());
            }
        }
        helper.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_layout")
    public static void housingAndAlchemyRespectTheirPadsAndTrainingStaysOnSquare(GameTestHelper helper) {
        var camp = SectLayoutPlan.BOXES.stream().filter(b -> b.id().equals("camp")).findFirst().orElseThrow();
        for (String id : List.of("dorm_2nd", "dorm_3rd")) {
            var dorm = SectLayoutPlan.BOXES.stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
            helper.assertTrue(dorm.width() == 20 && dorm.depth() == (id.equals("dorm_2nd") ? 20 : 22)
                    && dorm.facing() == Direction.EAST, "dorm size/facing wrong");
            helper.assertTrue(dorm.minU() >= camp.minU() && dorm.minU() + dorm.width() <= camp.minU() + camp.width()
                    && dorm.minV() >= camp.minV() && dorm.minV() + dorm.depth() <= camp.minV() + camp.depth(),
                    "dorm outside housing pad");
            helper.assertTrue(camp.minU() + camp.width() - dorm.minU() - dorm.width() >= 8, "east courtyard missing");
        }
        var alchemy = SectLayoutPlan.BOXES.stream().filter(b -> b.id().equals("alchemy")).findFirst().orElseThrow();
        helper.assertTrue(alchemy.width() == 16 && alchemy.depth() == 12 && alchemy.kind() == SectLayoutPlan.Kind.HALL
                && alchemy.facing() == Direction.NORTH && alchemy.zone().width() >= alchemy.width() + 2 * SectLayoutPlan.PAD_MARGIN
                && alchemy.zone().depth() >= alchemy.depth() + 2 * SectLayoutPlan.PAD_MARGIN,
                "alchemy is not a larger building");
        for (var neighbour : MountHuaPlan.ZONES) {
            if (neighbour.cave() || neighbour.id().equals("alchemy")) continue;
            var a = alchemy.zone();
            helper.assertTrue(Math.abs(a.u() - neighbour.u()) >= (a.width() + neighbour.width()) / 2.0
                    || Math.abs(a.v() - neighbour.v()) >= (a.depth() + neighbour.depth()) / 2.0,
                    "alchemy too close to " + neighbour.id());
        }
        helper.assertTrue(alchemy.marker(9, 8, 2) && alchemy.marker(10, 6, 2)
                && !alchemy.marker(11, 6, 2) && !alchemy.marker(12, 6, 2), "furnace room/door missing");
        MountHuaShape shape = new MountHuaShape(helper.getLevel().getSeed());
        double[] approach = java.util.Arrays.stream(MountHuaPlan.PATHS)
                .filter(p -> p[0] == p[3] && p[3] >= alchemy.minU() && p[3] < alchemy.minU() + alchemy.width()
                        && p[4] == alchemy.minV() + 0.5 && p[2] == alchemy.zone().y() && p[5] == p[2])
                .findFirst().orElseThrow();
        for (double v = approach[1]; v <= approach[4]; v += 1) {
            helper.assertTrue(shape.height(approach[0], v) == alchemy.zone().y(), "alchemy approach is not level with camp");
        }
        var training = MountHuaPlan.ZONES.stream().filter(z -> z.id().equals("training")).findFirst().orElseThrow();
        var poles = MountHuaPlan.ZONES.stream().filter(z -> z.id().equals("poles")).findFirst().orElseThrow();
        helper.assertTrue(poles.width() == 12 && poles.depth() == 10 && poles.y() == training.y()
                && Math.abs(poles.u() - training.u()) + poles.width() / 2.0 <= training.width() / 2.0
                && Math.abs(poles.v() - training.v()) + poles.depth() / 2.0 <= training.depth() / 2.0,
                "poles outside training");
        for (int ring = 0; ring < SectSchedule.DAILY_RINGS.length; ring++) for (int side = 0; side < 2; side++) {
            var spot = SectSchedule.ring(ring, side, "");
            helper.assertTrue(spot.zone().equals("training") && Math.abs(spot.du()) + 1 < training.width() / 2.0
                    && Math.abs(spot.dv()) + 1 < training.depth() / 2.0 && spot.du() > 8,
                    "daily spar outside training side lane");
        }
        helper.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_layout")
    public static void archLeavesThreeWidePassageAndUndoInEveryRotation(GameTestHelper helper) {
        var box = SectLayoutPlan.BOXES.stream().filter(b -> b.id().equals("arch")).findFirst().orElseThrow();
        BlockPos corner = helper.absolutePos(new BlockPos(12, 2, 12));
        for (int rotation = 0; rotation < 4; rotation++) {
            var site = site(box, corner, rotation);
            var data = new SectLayoutData();
            helper.assertTrue(SectLayoutBoxes.place(helper.getLevel(), site, List.of(box), data, corner.getY() + 6) == 1,
                    "arch missing in rotation " + rotation);
            helper.assertTrue(data.blocks.size() == 26, "arch must be two posts and one lintel");
            for (int x = 0; x < box.width(); x++) {
                for (int z = 0; z < box.depth(); z++) {
                    for (int y = 0; y < box.kind().height(); y++) {
                        var state = helper.getLevel().getBlockState(cell(site, box, corner, x, y, z));
                        helper.assertTrue(box.marker(x, z, y) ? state.is(Blocks.RED_CONCRETE) : state.isAir(),
                                "blocked arch passage or missing post/lintel");
                    }
                }
            }
            helper.assertTrue(SectLayoutBoxes.clear(helper.getLevel(), data) == 26, "arch undo incomplete");
        }
        helper.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_layout")
    public static void planHasArchLargerHallAndWalkableAncestorsPath(GameTestHelper helper) {
        helper.assertTrue(MountHuaPlan.ZONES.stream().noneMatch(z -> z.id().equals("sparring"))
                && SectLayoutPlan.BOXES.stream().noneMatch(b -> b.id().equals("sparring"))
                && !SectLayout.SECT_ZONES.contains("sparring"), "retired sparring zone remains");
        var arch = SectLayoutPlan.BOXES.stream().filter(b -> b.id().equals("arch")).findFirst().orElseThrow();
        var hall = SectLayoutPlan.BOXES.stream().filter(b -> b.id().equals("main_hall")).findFirst().orElseThrow();
        var gate = MountHuaPlan.ZONES.stream().filter(z -> z.id().equals("sect_gate")).findFirst().orElseThrow();
        double hallU = hall.zone().u() + hall.du();
        double hallV = hall.zone().v() + hall.dv();
        // Author 15.10: the arch stands exactly opposite the main hall, on the hall's centre line.
        double axisU = hallU;
        helper.assertTrue(arch.kind() == SectLayoutPlan.Kind.ARCH && arch.width() >= 3 && arch.width() <= 5
                && arch.depth() <= 2 && Math.abs(arch.zone().u() - axisU) <= 0.5, "arch off the main hall axis");
        helper.assertTrue(hall.width() == 50 && hall.depth() >= 20, "hall did not get freed depth");
        int exitX = (hall.width() - 4) / 2;
        for (int x = exitX; x < exitX + 4; x++) {
            helper.assertTrue(!hall.marker(x, hall.depth() - 1, 1) && !hall.marker(x, hall.depth() - 1, 3), "rear exit blocked");
        }
        helper.assertTrue(hall.marker(exitX - 1, hall.depth() - 1, 1) && hall.marker(exitX + 4, hall.depth() - 1, 1),
                "rear wall missing beside exit");
        helper.assertTrue(Math.abs(hall.minU() + exitX + 2 - hallU) <= 1, "rear exit off the hall axis");
        helper.assertTrue(SectLayoutPlan.BOXES.stream().anyMatch(b -> b.id().equals("hall_court") && b.depth() >= SectLayoutPlan.WALK_MARGIN),
                "front court missing");
        var corridor = SectLayoutPlan.CORRIDORS.getFirst();
        helper.assertTrue(corridor.width(0) >= 3, "ancestor corridor too narrow");
        double[] first = corridor.segment(0);
        helper.assertTrue(first[0] == hallU && first[1] == hall.minV() + hall.depth(), "path misses hall rear exit");
        for (int s = 0; s < corridor.pathCount(); s++) {
            double[] p = corridor.segment(s);
            if (s > 0) {
                double[] prev = corridor.segment(s - 1);
                helper.assertTrue(prev[3] == p[0] && prev[4] == p[1] && prev[5] == p[2], "path landing disconnected");
            }
            int steps = (int) Math.round(Math.hypot(p[3] - p[0], p[4] - p[1]));
            long previous = Math.round(p[2]);
            for (int i = 1; i <= steps; i++) {
                double t = (double) i / steps;
                long y = Math.round(MountHuaShape.pathAt(p, p[0] + (p[3] - p[0]) * t,
                        p[1] + (p[4] - p[1]) * t)[1]);
                helper.assertTrue(Math.abs(y - previous) <= 1, "unwalkable stair rise");
                previous = y;
            }
        }
        var ancestors = MountHuaPlan.ZONES.stream().filter(z -> z.id().equals("ancestors")).findFirst().orElseThrow();
        double[] last = corridor.segment(corridor.pathCount() - 1);
        helper.assertTrue(last[3] == ancestors.u() && last[4] >= ancestors.v() - ancestors.depth() / 2.0
                && last[5] == ancestors.y(), "path misses ancestors' porch");
        helper.assertTrue(SectReview.ringSpot(1, 0).zone().equals("training")
                && SectReview.ringSpot(4, 1).zone().equals("training"), "review still uses retired square");
        helper.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_layout")
    public static void mentorDaisIsInsideWestTrainingEdgeAndFacesEast(GameTestHelper helper) {
        var mentor = SectLayoutPlan.BOXES.stream().filter(b -> b.id().equals("mentor")).findFirst().orElseThrow();
        var training = MountHuaPlan.ZONES.stream().filter(z -> z.id().equals("training")).findFirst().orElseThrow();
        var zone = mentor.zone();
        helper.assertTrue(zone.u() == -12 && zone.v() == training.v() + 0.5 && zone.y() == training.y(), "mentor moved off the training pad");
        helper.assertTrue(Math.abs(zone.u() - training.u()) + zone.width() / 2.0 <= training.width() / 2.0
                && Math.abs(zone.v() - training.v()) + zone.depth() / 2.0 <= training.depth() / 2.0,
                "mentor footprint is not fully inside training");
        helper.assertTrue(mentor.facing() == Direction.EAST && mentor.width() == 6 && mentor.depth() == 8,
                "north-frame 8x6 dais must be rotated towards the square");
        var npc = SectRoster.of("gyeong_pil").orElseThrow();
        var watch = SectSchedule.task(npc, SectSchedule.Period.TRAINING, 0);
        helper.assertTrue(watch.zone().equals("mentor") && watch.faceU() == 1 && watch.faceV() == 0,
                "mentor still watches north");
        for (String key : List.of("gyeong_pil", "tae_hwi")) {
            var spectator = SectReview.spectator(SectRoster.of(key).orElseThrow());
            helper.assertTrue(spectator.zone().equals("mentor") && spectator.faceU() == 1 && spectator.faceV() == 0,
                    "review spectator still assumes the old dais");
        }
        helper.assertTrue(SectReview.ringSpot(1, 0).zone().equals("training"), "review left the training square");
        helper.succeed();
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
