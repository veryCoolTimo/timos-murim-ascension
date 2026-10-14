package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Placement, old inventory IDs, old chunk palettes, template conversion and single-item loot. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class PlumShelfGameTests {
    private PlumShelfGameTests() { }

    private static List<LegacyPlumShelfBlock> aliases() {
        return List.of(HallFurniture.SCROLL_SHELF_BOOKS.get(), HallFurniture.SCROLL_SHELF_MIXED.get(),
                HallFurniture.SCROLL_SHELF_SPARSE.get(), HallFurniture.BOOKS_SHELF.get(),
                HallFurniture.MIXED_SHELF.get(), HallFurniture.SPARSE_SHELF.get());
    }

    private static boolean place(GameTestHelper h, BlockItem item, BlockPos lower) {
        // Explicit support and headroom: the yard's surface height is not part of these checks.
        h.setBlock(lower.below(), net.minecraft.world.level.block.Blocks.STONE);
        BlockPos support = h.absolutePos(lower.below());
        return item.place(new BlockPlaceContext(h.getLevel(), null, InteractionHand.MAIN_HAND, new ItemStack(item),
                new BlockHitResult(Vec3.atCenterOf(support).add(0, .5, 0), Direction.UP, support, false)))
                .consumesAction();
    }

    private static void checkRack(GameTestHelper h, BlockPos lower, int variant, Direction facing) {
        for (int y = 0; y < 2; y++) {
            BlockState state = h.getBlockState(lower.above(y));
            h.assertTrue(state.is(HallFurniture.SCROLL_SHELF.get()), "alias survived: " + state);
            h.assertTrue(state.getValue(PlumShelfBlock.VARIANT) == variant, "wrong fill: " + state);
            h.assertTrue(state.getValue(ScrollShelfBlock.FACING) == facing, "wrong facing: " + state);
            h.assertTrue(state.getValue(ScrollShelfBlock.HALF) == (y == 0 ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER),
                    "wrong half: " + state);
        }
    }

    @GameTest(template = "sect_yard", batch = "decor")
    public static void plumShelfPositionVariantAndReplant(GameTestHelper h) {
        BlockPos lower = new BlockPos(2, 3, 2);
        BlockItem item = HallFurniture.item(HallFurniture.SCROLL_SHELF);
        int variant = PlumShelfBlock.variantAt(h.absolutePos(lower));
        h.assertTrue(place(h, item, lower), "placement refused");
        checkRack(h, lower, variant, Direction.SOUTH);
        h.setBlock(lower, Blocks.AIR);
        h.assertBlockPresent(Blocks.AIR, lower.above());
        h.assertTrue(place(h, item, lower), "replant refused");
        checkRack(h, lower, variant, Direction.SOUTH);
        h.assertTrue(HallFurniture.SCROLL_SHELF.get().getStateDefinition().getPossibleStates().size() == 32,
                "rack exceeds 32 states");
        boolean[] seen = new boolean[4];
        for (int x = 0; x < 64; x++) seen[PlumShelfBlock.variantAt(new BlockPos(x, 70, -17))] = true;
        for (boolean found : seen) h.assertTrue(found, "position selection cannot reach every fill");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "decor")
    public static void plumShelfAllLegacyItemsKeepFill(GameTestHelper h) {
        for (int i = 0; i < aliases().size(); i++) {
            LegacyPlumShelfBlock alias = aliases().get(i);
            BlockPos lower = new BlockPos(i + 1, 3, 2);
            h.assertTrue(place(h, (BlockItem) alias.asItem(), lower), "legacy item refused: " + alias);
            checkRack(h, lower, i % 3 + 1, Direction.SOUTH);
        }
        h.assertTrue(HallFurniture.SCROLL_SHELF.get().asItem() == HallFurniture.item(HallFurniture.SCROLL_SHELF),
                "legacy item overwrote canonical item mapping");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "decor")
    public static void plumShelfOldChunkPaletteKeepsEveryHalfAndFacing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int z = 1;
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (int i = 0; i < aliases().size(); i++) {
                LegacyPlumShelfBlock alias = aliases().get(i);
                BlockPos lower = new BlockPos(i + 1, 3, z);
                for (int y = 0; y < 2; y++) {
                    BlockPos pos = h.absolutePos(lower.above(y));
                    // Direct chunk writes simulate decoded saved palettes, before neighbour notifications.
                    level.getChunkAt(pos).setBlockState(pos, alias.defaultBlockState()
                            .setValue(ScrollShelfBlock.FACING, facing)
                            .setValue(ScrollShelfBlock.HALF, y == 0 ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER), false);
                }
            }
            z++;
        }
        for (int x = 1; x <= aliases().size(); x++) {
            for (int zz = 1; zz <= 4; zz++) {
                LevelChunk chunk = level.getChunkAt(h.absolutePos(new BlockPos(x, 3, zz)));
                PlumShelfMigration.migrate(chunk);
                PlumShelfMigration.migrate(chunk); // Idempotent after the first save/load.
            }
        }
        z = 1;
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (int i = 0; i < aliases().size(); i++) {
                BlockPos lower = new BlockPos(i + 1, 3, z);
                checkRack(h, lower, i % 3 + 1, facing);
                h.assertItemEntityCountIs(HallFurniture.item(HallFurniture.SCROLL_SHELF), lower, .75, 0);
            }
            z++;
        }
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "decor")
    public static void plumShelfTemplateAliasMigratesBeforeBreaking(GameTestHelper h) {
        BlockPos lower = new BlockPos(2, 3, 2);
        ServerLevel level = h.getLevel();
        BlockState legacy = HallFurniture.SCROLL_SHELF_MIXED.get().defaultBlockState()
                .setValue(ScrollShelfBlock.FACING, Direction.WEST);
        h.setBlock(lower.below(), Blocks.STONE);
        // Structure templates install both halves before updating neighbours.
        level.setBlock(h.absolutePos(lower), legacy, Block.UPDATE_CLIENTS);
        level.setBlock(h.absolutePos(lower.above()), legacy.setValue(ScrollShelfBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS);
        h.runAfterDelay(3, () -> {
            checkRack(h, lower, 2, Direction.WEST);
            level.destroyBlock(h.absolutePos(lower.above()), true);
            h.assertBlockPresent(Blocks.AIR, lower);
            h.succeedWhen(() -> h.assertItemEntityCountIs(HallFurniture.item(HallFurniture.SCROLL_SHELF), lower, 3, 1));
        });
    }

    @GameTest(template = "sect_yard", batch = "decor")
    public static void plumShelfBreakLowerDropsOne(GameTestHelper h) {
        BlockPos lower = new BlockPos(2, 3, 2);
        h.assertTrue(place(h, HallFurniture.item(HallFurniture.SCROLL_SHELF), lower), "placement refused");
        h.getLevel().destroyBlock(h.absolutePos(lower), true);
        h.assertBlockPresent(Blocks.AIR, lower.above());
        h.succeedWhen(() -> h.assertItemEntityCountIs(HallFurniture.item(HallFurniture.SCROLL_SHELF), lower, 3, 1));
    }

    @GameTest(template = "sect_yard", batch = "decor")
    public static void plumShelfNeedsRoomAbove(GameTestHelper h) {
        BlockPos lower = new BlockPos(2, 3, 2);
        h.setBlock(lower.above(), Blocks.OAK_PLANKS);
        h.assertFalse(place(h, HallFurniture.item(HallFurniture.SCROLL_SHELF), lower), "placed through occupied upper block");
        h.assertBlockPresent(Blocks.AIR, lower);
        h.succeed();
    }
}
