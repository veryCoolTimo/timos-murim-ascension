package io.github.verycooltimo.murim.library;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real block interactions, save/load and drops; uses the existing small_floor template. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class LibraryShelfGameTests {
    private static final BlockPos POS = new BlockPos(1, 2, 1);
    private static final String FLOOR = "small_floor";

    private LibraryShelfGameTests() {}

    private static ArchiveShelfBlockEntity place(GameTestHelper h, Direction facing) {
        return place(h, facing, 2);
    }

    private static ArchiveShelfBlockEntity place(GameTestHelper h, Direction facing, int variant) {
        h.setBlock(POS, Blocks.AIR);
        h.setBlock(POS, ModLibrary.ARCHIVE_SHELF.get().defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, facing).setValue(ArchiveShelfBlock.VARIANT, variant));
        h.assertTrue(h.getBlockEntity(POS) instanceof ArchiveShelfBlockEntity, "shelf entity was not created");
        return (ArchiveShelfBlockEntity) h.getBlockEntity(POS);
    }

    private static BlockHitResult aim(GameTestHelper h, int slot) {
        BlockPos p = h.absolutePos(POS);
        Direction facing = h.getBlockState(POS).getValue(HorizontalDirectionalBlock.FACING);
        double[] r = LibraryShelfLayout.bookBounds(h.getBlockState(POS).getValue(ArchiveShelfBlock.VARIANT), slot);
        double u = r == null ? (slot % 3 + .5D) / 3 : (r[0] + r[2]) / 32.0D;
        double x = switch (facing) { case NORTH -> 1 - u; case SOUTH -> u; case EAST -> 1; default -> 0; };
        double z = switch (facing) { case WEST -> u; case EAST -> 1 - u; case SOUTH -> 1; default -> 0; };
        double y = r == null ? (slot < 3 ? .75D : .25D) : 1.0D - (r[1] + r[3]) / 32.0D;
        return new BlockHitResult(new Vec3(p.getX() + x, p.getY() + y, p.getZ() + z), facing, p, false);
    }

    private static void use(GameTestHelper h, Player player, ItemStack stack, BlockHitResult hit) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockState state = h.getBlockState(POS);
        var result = state.useItemOn(stack, h.getLevel(), player, InteractionHand.MAIN_HAND, hit);
        if (result == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            state.useWithoutItem(h.getLevel(), player, hit);
        }
    }

    private static void occupied(GameTestHelper h, int slot, boolean expected) {
        h.assertTrue(h.getBlockState(POS).getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot)) == expected,
                "occupancy of slot " + slot + " must be " + expected);
    }

    @GameTest(template = FLOOR, batch = "library")
    public static void onlyMarkedBooksWorkInEveryLayoutAndFacing(GameTestHelper h) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (int variant = 0; variant < 4; variant++) {
                ArchiveShelfBlockEntity shelf = place(h, facing, variant);
                Player player = h.makeMockPlayer(GameType.SURVIVAL);
                for (int slot = 0; slot < 6; slot++) {
                    boolean available = LibraryShelfLayout.available(variant, slot);
                    use(h, player, new ItemStack(Items.BOOK, 2), aim(h, slot));
                    h.assertTrue(!shelf.getItem(slot).isEmpty() == available, facing + " layout " + variant + " slot " + slot);
                    h.assertTrue(player.getMainHandItem().getCount() == (available ? 1 : 2), "decorative area consumed a book");
                    occupied(h, slot, available);
                    if (!available) {
                        shelf.setItem(slot, new ItemStack(Items.BOOK));
                        h.assertTrue(shelf.getItem(slot).isEmpty() && !shelf.canPlaceItem(slot, new ItemStack(Items.BOOK)),
                                "automation filled a decorative slot");
                        use(h, player, ItemStack.EMPTY, aim(h, slot));
                        h.assertTrue(player.getInventory().countItem(Items.BOOK) == 0, "decorative texture produced a book");
                        continue;
                    }
                    use(h, player, new ItemStack(Items.BOOK, 2), aim(h, slot));
                    h.assertTrue(player.getMainHandItem().getCount() == 2, "occupied slot consumed a book");
                    use(h, player, ItemStack.EMPTY, aim(h, slot));
                    occupied(h, slot, false);
                    h.assertTrue(shelf.getItem(slot).isEmpty() && player.getInventory().countItem(Items.BOOK) == 1,
                            "marked book was not returned exactly once");
                    ItemStack returned = player.getInventory().removeItem(player.getInventory().selected, 1);
                    use(h, player, returned, aim(h, slot));
                    occupied(h, slot, true);
                    h.assertTrue(shelf.getItem(slot).is(Items.BOOK), "returning a book did not restore the slot");
                    shelf.removeItem(slot, 1);
                    h.assertTrue(shelf.lastSlot() == slot
                            && h.getBlockState(POS).getAnalogOutputSignal(h.getLevel(), h.absolutePos(POS)) == slot + 1,
                            "comparator did not remember the marked cell");
                }
            }
        }
        h.succeed();
    }

    @GameTest(template = FLOOR, batch = "library")
    public static void fullInventoryDropsTheTakenBook(GameTestHelper h) {
        ArchiveShelfBlockEntity shelf = place(h, Direction.NORTH);
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(Vec3.atCenterOf(h.absolutePos(POS)));
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
        shelf.setItem(3, new ItemStack(Items.ENCHANTED_BOOK));
        // Vanilla default block interaction is also reached while holding a non-book.
        use(h, player, new ItemStack(Items.STONE, 64), aim(h, 3));
        h.assertTrue(shelf.getItem(3).isEmpty(), "full inventory prevented removal");
        occupied(h, 3, false);
        h.runAfterDelay(2, () -> {
            h.assertItemEntityCountIs(Items.ENCHANTED_BOOK, POS, 3, 1);
            h.succeed();
        });
    }

    @GameTest(template = FLOOR, batch = "library")
    public static void returnsJunkAndTechniqueComponents(GameTestHelper h) {
        ArchiveShelfBlockEntity shelf = place(h, Direction.SOUTH);
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack junk = JunkFactory.stack(new JunkBook(JunkKind.CLUE, 923L, 19, false));
        ItemStack manual = new ItemStack(ModItems.TECHNIQUE_MANUAL.get());
        manual.set(ModDataComponents.TECHNIQUE.get(), ResourceLocation.parse("murim:six_harmonies"));
        manual.set(ModDataComponents.MANUAL_DEPTH.get(), 1);
        for (ItemStack original : new ItemStack[] {junk, manual}) {
            ItemStack expected = original.copy();
            use(h, player, original, aim(h, 2));
            h.assertTrue(ItemStack.isSameItemSameComponents(expected, shelf.getItem(2)), "insertion lost book components");
            use(h, player, ItemStack.EMPTY, aim(h, 2));
            int index = -1;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                if (ItemStack.isSameItemSameComponents(expected, player.getInventory().getItem(i))) { index = i; break; }
            }
            h.assertTrue(index >= 0, "returned book lost its text/technique");
            use(h, player, player.getInventory().removeItem(index, 1), aim(h, 2));
            h.assertTrue(ItemStack.isSameItemSameComponents(expected, shelf.getItem(2)), "putting it back changed the book");
            shelf.removeItem(2, 1);
        }
        use(h, player, new ItemStack(Items.STICK), aim(h, 3));
        h.assertTrue(shelf.getItem(3).isEmpty() && player.getMainHandItem().is(Items.STICK), "non-book accepted");
        BlockHitResult front = aim(h, 3);
        BlockHitResult back = new BlockHitResult(front.getLocation(), Direction.NORTH, front.getBlockPos(), false);
        use(h, player, new ItemStack(Items.BOOK), back);
        h.assertTrue(shelf.isEmpty() && player.getMainHandItem().is(Items.BOOK), "back-face click accepted");
        h.succeed();
    }

    @GameTest(template = FLOOR, batch = "library")
    public static void saveSyncCreativeAndBreak(GameTestHelper h) {
        ArchiveShelfBlockEntity shelf = place(h, Direction.WEST);
        Player player = h.makeMockPlayer(GameType.CREATIVE);
        use(h, player, new ItemStack(Items.WRITABLE_BOOK), aim(h, 3));
        h.assertTrue(player.getMainHandItem().getCount() == 1, "creative insertion consumed the held book");
        ItemStack junk = JunkFactory.stack(new JunkBook(JunkKind.CLUE, 391L, 19, false));
        shelf.setItem(2, junk);
        var registries = h.getLevel().registryAccess();
        CompoundTag tag = shelf.saveWithFullMetadata(registries);
        BlockEntity restored = BlockEntity.loadStatic(h.absolutePos(POS), h.getBlockState(POS), tag, registries);
        h.assertTrue(restored instanceof ArchiveShelfBlockEntity loaded
                && ItemStack.isSameItemSameComponents(junk, loaded.getItem(2)) && loaded.lastSlot() == 2, "save/load lost contents");
        ArchiveShelfBlockEntity clientCopy = new ArchiveShelfBlockEntity(h.absolutePos(POS), h.getBlockState(POS));
        clientCopy.loadCustomOnly(shelf.getUpdateTag(registries), registries);
        h.assertTrue(ItemStack.isSameItemSameComponents(junk, clientCopy.getItem(2)), "update tag lost components");
        h.getLevel().destroyBlock(h.absolutePos(POS), true);
        h.runAfterDelay(2, () -> {
            h.assertItemEntityCountIs(Items.WRITABLE_BOOK, POS, 3, 1);
            h.assertItemEntityCountIs(junk.getItem(), POS, 3, 1);
            h.assertItemEntityCountIs(ModLibrary.ARCHIVE_SHELF_ITEM.get(), POS, 3, 1);
            h.succeed();
        });
    }

    @GameTest(template = FLOOR, batch = "library")
    public static void railingConnectsAndShelfStatesStaySmall(GameTestHelper h) {
        h.setBlock(POS, ModLibrary.BOOKSHELF_RAILING.get());
        h.setBlock(POS.east(), ModLibrary.BOOKSHELF_RAILING.get());
        h.assertTrue(h.getBlockState(POS).getValue(FenceBlock.EAST), "railing must join east neighbour");
        h.assertTrue(h.getBlockState(POS.east()).getValue(FenceBlock.WEST), "connection must be reciprocal");
        h.setBlock(POS.north(), ModLibrary.BOOKSHELF_RAILING.get());
        h.assertTrue(h.getBlockState(POS).getValue(FenceBlock.NORTH), "corner must connect");
        h.setBlock(POS.east(), Blocks.AIR);
        h.assertTrue(!h.getBlockState(POS).getValue(FenceBlock.EAST), "connection must disappear when neighbour breaks");
        h.assertTrue(ModLibrary.ARCHIVE_SHELF.get().getStateDefinition().getPossibleStates().size() == 1024, "shelf state explosion");
        h.assertTrue(ModLibrary.BOOKSHELF_RAILING.get().getStateDefinition().getPossibleStates().size() == 32, "railing state explosion");
        h.succeed();
    }
}
