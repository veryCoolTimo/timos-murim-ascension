package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.library.ModLibrary;
import io.github.verycooltimo.murim.library.ProppedShelfBlock;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Generated manual lifecycle, including stale clicks, real drops and serialized states/components. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class BookcaseManualGameTests {
    private static final BlockPos POS = new BlockPos(1, 2, 1);
    private BookcaseManualGameTests() {}

    private static ItemStack manual() {
        ItemStack stack = new ItemStack(ModItems.TECHNIQUE_MANUAL.get());
        stack.set(ModDataComponents.TECHNIQUE.get(), ResourceLocation.parse("murim:six_harmonies"));
        stack.set(ModDataComponents.MANUAL_DEPTH.get(), 1);
        return stack;
    }

    private static void place(GameTestHelper h, boolean tall, int variant, Direction facing) {
        Block block = tall ? HallFurniture.LIBRARY_BOOKCASE_TALL.get() : HallFurniture.LIBRARY_BOOKCASE.get();
        BlockState state = block.defaultBlockState().setValue(LibraryBookcaseBlock.VARIANT, variant)
                .setValue(HorizontalDirectionalBlock.FACING, facing);
        h.setBlock(POS, state);
        block.setPlacedBy(h.getLevel(), h.absolutePos(POS), state, null, ItemStack.EMPTY);
    }

    private static InteractionResult click(GameTestHelper h, BlockPos pos, BlockState state) {
        BlockPos absolute = h.absolutePos(pos);
        return state.useWithoutItem(h.getLevel(), h.makeMockPlayer(GameType.SURVIVAL),
                new BlockHitResult(Vec3.atCenterOf(absolute), state.getValue(HorizontalDirectionalBlock.FACING), absolute, false));
    }

    private static BlockState roundTripState(GameTestHelper h, BlockState state) {
        return NbtUtils.readBlockState(h.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK), NbtUtils.writeBlockState(state));
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void manualParticleUsesThreeToFiveSecondCooldown(GameTestHelper h) {
        place(h, false, 0, Direction.SOUTH);
        var be = (BookcaseManualBlockEntity) h.getBlockEntity(POS);
        var random = net.minecraft.util.RandomSource.create(42L);
        h.assertTrue(!be.particleDue(0L, random), "first sample must schedule a faint delayed mote");
        long last = 0L;
        int count = 0;
        for (long tick = 1; tick <= 2000; tick++) {
            if (!be.particleDue(tick, random)) continue;
            h.assertTrue(tick - last >= 60 && tick - last <= 100, "mote cooldown outside 3-5 seconds");
            h.assertTrue(!be.particleDue(tick, random), "duplicate mote in one tick");
            last = tick;
            count++;
        }
        h.assertTrue(count >= 20 && count <= 33, "motes too frequent/sparse");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void manualSlotsCoverEveryShelfAtNegativePositions(GameTestHelper h) {
        for (boolean tall : new boolean[]{false, true}) {
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            Block block = tall ? HallFurniture.LIBRARY_BOOKCASE_TALL.get() : HallFurniture.LIBRARY_BOOKCASE.get();
            for (int x = -32; x < 32; x++) {
                for (int z = -16; z < 16; z++) {
                    BlockPos pos = new BlockPos(x, -11, z);
                    int slot = LibraryBookcaseBlock.bookSlotAt(pos, tall);
                    seen.add(slot);
                    BlockState state = block.defaultBlockState();
                    var property = LibraryBookcaseBlock.bookSlotProperty(state);
                    state = state.setValue(property, slot);
                    h.assertTrue(roundTripState(h, state).getValue(property) == slot,
                            "negative-position slot changed after serialization");
                }
            }
            h.assertTrue(seen.size() == (tall ? 6 : 4), "position hash never selects some shelves: " + seen);
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void generatedManualClicksOnceInBothHeights(GameTestHelper h) {
        ItemStack expected = manual();
        int clicks = 0;
        for (boolean tall : new boolean[]{false, true}) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (int variant = 0; variant < 7; variant++) {
                    for (int bookSlot = 0; bookSlot < (tall ? 6 : 4); bookSlot++) {
                        // Clear the previous empty rack before placing another layout/height.
                        for (int dy = 2; dy >= 0; dy--) h.setBlock(POS.above(dy), Blocks.AIR);
                        place(h, tall, variant, facing);
                        int height = tall ? 3 : 2;
                        for (int dy = 0; dy < height; dy++) {
                            h.assertTrue(!h.getBlockState(POS.above(dy)).getValue(LibraryBookcaseBlock.HAS_BOOK),
                                    "placement generated an infinite manual");
                        }
                        // Copy the stored slot to all parts, just as placement/world generation do.
                        for (int dy = 0; dy < height; dy++) {
                            BlockState part = h.getBlockState(POS.above(dy));
                            h.setBlock(POS.above(dy), part.setValue(LibraryBookcaseBlock.bookSlotProperty(part), bookSlot));
                        }
                        // Put the manual in the part selected by the whole-rack shelf index.
                        BlockPos slot = POS.above(bookSlot / 2);
                        BookcaseManualBlockEntity be = (BookcaseManualBlockEntity) h.getBlockEntity(slot);
                        be.setManual(expected);
                        h.assertTrue(h.getBlockState(slot).getValue(LibraryBookcaseBlock.HAS_BOOK), "marked book is invisible");
                        BlockState stale = h.getBlockState(POS);
                        h.assertTrue(click(h, POS, stale).consumesAction(), "occupied rack click was ignored");
                        h.assertTrue(be.manual().isEmpty() && !h.getBlockState(slot).getValue(LibraryBookcaseBlock.HAS_BOOK),
                                "taken book stayed in the model or storage");
                        h.assertTrue(click(h, POS, stale) == InteractionResult.PASS, "empty rack click did something");
                        clicks++;
                    }
                }
            }
        }
        var drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(POS)).inflate(4));
        int count = drops.stream().filter(e -> ItemStack.isSameItemSameComponents(expected, e.getItem()))
                .mapToInt(e -> e.getItem().getCount()).sum();
        h.assertTrue(count == clicks, "manual drops " + count + " vs occupied clicks " + clicks);
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void manualAndSpentSlotSurviveSaveLoad(GameTestHelper h) {
        for (boolean tall : new boolean[]{false, true}) {
            for (int bookSlot = 0; bookSlot < (tall ? 6 : 4); bookSlot++) {
                for (int dy = 2; dy >= 0; dy--) h.setBlock(POS.above(dy), Blocks.AIR);
                place(h, tall, 6, Direction.WEST);
                BlockState lower = h.getBlockState(POS);
                h.setBlock(POS, lower.setValue(LibraryBookcaseBlock.bookSlotProperty(lower), bookSlot));
                BookcaseManualBlockEntity original = (BookcaseManualBlockEntity) h.getBlockEntity(POS);
                original.setManual(manual());
                BlockState occupied = roundTripState(h, h.getBlockState(POS));
                CompoundTag tag = original.saveWithFullMetadata(h.getLevel().registryAccess());
                BlockEntity restored = BlockEntity.loadStatic(h.absolutePos(POS), occupied, tag, h.getLevel().registryAccess());
                h.assertTrue(restored instanceof BookcaseManualBlockEntity, "manual entity did not reload");
                BookcaseManualBlockEntity loaded = (BookcaseManualBlockEntity) restored;
                h.assertTrue(occupied.getValue(LibraryBookcaseBlock.HAS_BOOK)
                        && ItemStack.isSameItemSameComponents(manual(), loaded.manual()), "save/load lost manual components");
                h.assertTrue(occupied.getValue(LibraryBookcaseBlock.bookSlotProperty(occupied)) == bookSlot, "save/load lost book slot");
                // Install the deserialized entity and use the real block interaction.
                h.getLevel().setBlockEntity(loaded);
                click(h, POS.above(tall ? 2 : 1), h.getBlockState(POS.above(tall ? 2 : 1)));
                BlockState spent = roundTripState(h, h.getBlockState(POS));
                tag = loaded.saveWithFullMetadata(h.getLevel().registryAccess());
                BookcaseManualBlockEntity spentEntity = (BookcaseManualBlockEntity) BlockEntity.loadStatic(
                        h.absolutePos(POS), spent, tag, h.getLevel().registryAccess());
                h.assertTrue(spentEntity != null && spentEntity.manual().isEmpty()
                        && !spent.getValue(LibraryBookcaseBlock.HAS_BOOK), "spent slot regenerated after reload");
                h.assertTrue(spent.getValue(LibraryBookcaseBlock.bookSlotProperty(spent)) == bookSlot, "taking manual changed its slot");
                h.getLevel().setBlockEntity(spentEntity);
                h.assertTrue(click(h, POS, spent) == InteractionResult.PASS, "reloaded empty case produced a book");
            }
        }
        h.assertItemEntityCountIs(ModItems.TECHNIQUE_MANUAL.get(), POS, 4, 10);
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void breakingAnyRackPartDropsStoredManualOnce(GameTestHelper h) {
        int broken = 0;
        for (boolean tall : new boolean[]{false, true}) {
            int height = tall ? 3 : 2;
            for (int dy = 0; dy < height; dy++) {
                place(h, tall, 4, Direction.SOUTH);
                ((BookcaseManualBlockEntity) h.getBlockEntity(POS)).setManual(manual());
                h.getLevel().destroyBlock(h.absolutePos(POS.above(dy)), true);
                for (int part = 0; part < height; part++) h.assertBlockPresent(Blocks.AIR, POS.above(part));
                broken++;
            }
        }
        h.assertItemEntityCountIs(ModItems.TECHNIQUE_MANUAL.get(), POS, 4, broken);
        h.assertItemEntityCountIs(HallFurniture.item(HallFurniture.LIBRARY_BOOKCASE), POS, 4, 2);
        h.assertItemEntityCountIs(HallFurniture.item(HallFurniture.LIBRARY_BOOKCASE_TALL), POS, 4, 3);
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void creativeBreakDoesNotSpillGeneratedManual(GameTestHelper h) {
        for (boolean tall : new boolean[]{false, true}) {
            place(h, tall, 5, Direction.SOUTH);
            ((BookcaseManualBlockEntity) h.getBlockEntity(POS)).setManual(manual());
            BlockPos top = h.absolutePos(POS.above(tall ? 2 : 1));
            BlockState state = h.getLevel().getBlockState(top);
            state.getBlock().playerWillDestroy(h.getLevel(), top, state, h.makeMockPlayer(GameType.CREATIVE));
            h.getLevel().removeBlock(top, false);
            h.assertBlockPresent(Blocks.AIR, POS);
        }
        h.assertItemEntityCountIs(ModItems.TECHNIQUE_MANUAL.get(), POS, 4, 0);
        h.assertItemEntityCountIs(HallFurniture.item(HallFurniture.LIBRARY_BOOKCASE), POS, 4, 0);
        h.assertItemEntityCountIs(HallFurniture.item(HallFurniture.LIBRARY_BOOKCASE_TALL), POS, 4, 0);
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void playerPropIsEmptyAndGeneratedPropIsPersistent(GameTestHelper h) {
        h.setBlock(POS.below(), Blocks.STONE);
        h.setBlock(POS, Blocks.AIR);  // POS is the template's grass surface
        h.setBlock(POS.above(), Blocks.AIR);
        BlockPos support = h.absolutePos(POS.below());
        var item = ModLibrary.PROPPED_SHELF_ITEM.get();
        var placed = item.place(new BlockPlaceContext(h.getLevel(), null,
                InteractionHand.MAIN_HAND, new ItemStack(item),
                new BlockHitResult(Vec3.atCenterOf(support).add(0, .5, 0), Direction.UP, support, false)));
        h.assertTrue(placed.consumesAction(), "player prop placement failed");
        BlockState empty = h.getBlockState(POS);
        h.assertTrue(!empty.getValue(ProppedShelfBlock.HAS_BOOK), "player prop contains an infinite manual");
        h.assertTrue(click(h, POS, empty) == InteractionResult.PASS, "player prop created a manual");
        BlockState full = roundTripState(h, empty.setValue(ProppedShelfBlock.HAS_BOOK, true));
        h.setBlock(POS, full);
        click(h, POS, full);
        h.assertTrue(!h.getBlockState(POS).getValue(ProppedShelfBlock.HAS_BOOK), "prop book stayed visible");
        BlockState spent = roundTripState(h, h.getBlockState(POS));
        h.setBlock(POS, spent);
        click(h, POS, full);
        h.getLevel().destroyBlock(h.absolutePos(POS), true);
        h.assertItemEntityCountIs(ModItems.TECHNIQUE_MANUAL.get(), POS, 4, 1);
        // Breaking an unclaimed generated prop must also deliver its one original loot roll.
        h.setBlock(POS, full);
        h.getLevel().destroyBlock(h.absolutePos(POS), true);
        h.assertItemEntityCountIs(ModItems.TECHNIQUE_MANUAL.get(), POS, 4, 2);
        h.succeed();
    }
}
