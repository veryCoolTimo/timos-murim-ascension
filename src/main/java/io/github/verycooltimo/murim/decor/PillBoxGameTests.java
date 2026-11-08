package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * GameTests of the pill box as a pouch (24.10): six compartments that take only pills; the contents travel with the box (kept in the
 * item, kept when the placed box is broken and when it is placed again); a sneak-right-click on the placed box toggles the lid without a menu;
 * the box item opens in the hand (the inventory click uses the same {@link PillBoxItem#open}).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class PillBoxGameTests {

    private static final String FLOOR = "small_floor";
    private static final BlockPos AT = new BlockPos(1, 3, 1);

    private PillBoxGameTests() {
    }

    private static ServerPlayer player(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "box-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        Vec3 at = helper.absoluteVec(new Vec3(2.5D, 3.0D, 2.5D));
        p.setPos(at.x, at.y, at.z);
        return p;
    }

    private static ItemStack pills() {
        return new ItemStack(ModItems.PILL_SNOW_PLUM.get(), 3);
    }

    private static PillBoxBlockEntity placed(GameTestHelper helper) {
        helper.setBlock(AT.below(), Blocks.STONE);
        helper.setBlock(AT, IntWorkshop.PILL_BOX.get().defaultBlockState());
        return (PillBoxBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(AT));
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void theCompartmentsTakeOnlyPills(GameTestHelper helper) {
        ServerPlayer p = player(helper);
        PillBoxBlockEntity box = placed(helper);
        helper.assertTrue(box.getContainerSize() == 6, "six compartments");
        PillBoxMenu menu = new PillBoxMenu(1, p.getInventory(), box, -1);
        for (ItemStack ok : List.of(new ItemStack(ModItems.PILL_VITALITY.get()), new ItemStack(ModItems.PILL_ORIGIN_ENERGY.get()),
                new ItemStack(ModItems.PILL_ANTIDOTE.get()), new ItemStack(ModItems.PILL_FASTING.get()))) {
            helper.assertTrue(menu.getSlot(0).mayPlace(ok) && box.canPlaceItem(0, ok), ok + " was refused");
        }
        for (ItemStack bad : List.of(new ItemStack(Items.STONE), new ItemStack(Items.BREAD), new ItemStack(IntWorkshop.item(IntWorkshop.PILL_BOX)),
                new ItemStack(ModItems.MEAL_BOWL.get()), new ItemStack(ModItems.THOUSAND_GINSENG.get()), new ItemStack(ModItems.BEAUTY_TEAR.get()))) {
            helper.assertTrue(!menu.getSlot(0).mayPlace(bad) && !box.canPlaceItem(0, bad), bad + " was accepted");
        }
        // shift click: a pill goes in, a stone stays where it is
        p.getInventory().setItem(9, pills());
        p.getInventory().setItem(10, new ItemStack(Items.STONE, 5));
        int pillSlot = slotOf(menu, p, 9);
        int stoneSlot = slotOf(menu, p, 10);
        menu.quickMoveStack(p, pillSlot);
        helper.assertTrue(box.getItem(0).is(ModItems.PILL_SNOW_PLUM.get()) && box.getItem(0).getCount() == 3, "the pills did not go in: " + box.getItem(0));
        helper.assertTrue(menu.quickMoveStack(p, stoneSlot).isEmpty() && p.getInventory().getItem(10).getCount() == 5, "a stone moved");
        helper.succeed();
    }

    private static int slotOf(PillBoxMenu menu, ServerPlayer p, int inventoryIndex) {
        for (Slot s : menu.slots) {
            if (s.container == p.getInventory() && s.getContainerSlot() == inventoryIndex) {
                return s.index;
            }
        }
        throw new IllegalStateException("no slot for inventory index " + inventoryIndex);
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void contentsSurviveBreakingAndPlacingTheBox(GameTestHelper helper) {
        ServerPlayer p = player(helper);
        PillBoxBlockEntity box = placed(helper);
        box.setItem(0, pills());
        box.setItem(3, new ItemStack(ModItems.PILL_SNOW_PLUM.get(), 2));
        BlockPos abs = helper.absolutePos(AT);
        helper.getLevel().destroyBlock(abs, true);
        List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(abs).inflate(2.0D));
        ItemStack dropped = drops.stream().map(ItemEntity::getItem).filter(s -> s.getItem() instanceof PillBoxItem).findFirst().orElse(ItemStack.EMPTY);
        helper.assertTrue(!dropped.isEmpty(), "the box was not dropped");
        helper.assertTrue(PillPouch.count(dropped) == 5, "the dropped box holds " + PillPouch.count(dropped) + " pills, not 5");
        // placing it back restores the compartments in place
        helper.setBlock(AT.below(), Blocks.STONE);
        helper.assertTrue(dropped.getItem() instanceof PillBoxItem item
                && item.place(new BlockPlaceContext(helper.getLevel(), null, InteractionHand.MAIN_HAND, dropped,
                new BlockHitResult(Vec3.atCenterOf(helper.absolutePos(AT.below())).add(0, .5, 0), Direction.UP, helper.absolutePos(AT.below()), false))).consumesAction(),
                "the box was not placed");
        PillBoxBlockEntity again = (PillBoxBlockEntity) helper.getLevel().getBlockEntity(abs);
        helper.assertTrue(again != null && again.getItem(0).is(ModItems.PILL_SNOW_PLUM.get()) && again.getItem(0).getCount() == 3
                && again.getItem(3).is(ModItems.PILL_SNOW_PLUM.get()) && again.getItem(3).getCount() == 2, "the pills did not come back");
        // and it survives a save and a load
        net.minecraft.nbt.CompoundTag tag = again.saveWithFullMetadata(helper.getLevel().registryAccess());
        PillBoxBlockEntity loaded = new PillBoxBlockEntity(abs, again.getBlockState());
        loaded.loadWithComponents(tag, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.getItem(0).getCount() == 3 && loaded.getItem(3).getCount() == 2, "the pills were lost in the save");
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void theBoxItemKeepsItsPillsInTheContainerComponent(GameTestHelper helper) {
        ServerPlayer p = player(helper);
        ItemStack box = new ItemStack(IntWorkshop.item(IntWorkshop.PILL_BOX));
        helper.assertTrue(box.getMaxStackSize() == 1, "a box with pills must not stack");
        p.getInventory().setItem(4, box);
        PillPouch.OfItem pouch = new PillPouch.OfItem(p.getInventory(), 4);
        helper.assertTrue(pouch.isEmpty() && !box.has(DataComponents.CONTAINER), "a new box is not empty");
        pouch.setItem(2, pills());
        helper.assertTrue(PillPouch.count(box) == 3 && PillPouch.read(box).get(2).is(ModItems.PILL_SNOW_PLUM.get()), "the item did not take the pills");
        // the menu works on the very stack and writes back at once
        PillBoxMenu menu = new PillBoxMenu(1, p.getInventory(), pouch, 4);
        helper.assertTrue(menu.stillValid(p), "the pouch of a held box is not valid");
        Slot locked = menu.slots.get(slotOf(menu, p, 4));
        helper.assertTrue(!locked.mayPickup(p) && !locked.mayPlace(new ItemStack(ModItems.PILL_VITALITY.get())), "the open box can be moved");
        menu.getSlot(2).set(ItemStack.EMPTY);
        helper.assertTrue(!box.has(DataComponents.CONTAINER), "an emptied pouch keeps an empty component");
        // the box leaves the slot: the menu is no longer valid
        p.getInventory().setItem(4, ItemStack.EMPTY);
        helper.assertTrue(!menu.stillValid(p), "the menu stayed valid without the box");
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void theBoxItemOpensInTheHandAndFromTheInventory(GameTestHelper helper) {
        ServerPlayer p = player(helper);
        ItemStack box = new ItemStack(IntWorkshop.item(IntWorkshop.PILL_BOX));
        p.getInventory().setItem(p.getInventory().selected, box);
        ((PillBoxItem) box.getItem()).use(helper.getLevel(), p, InteractionHand.MAIN_HAND);
        helper.assertTrue(p.containerMenu instanceof PillBoxMenu m && m.lockedSlot() == p.getInventory().selected, "the box did not open in the hand");
        p.closeContainer();
        helper.assertTrue(!(p.containerMenu instanceof PillBoxMenu), "the menu stayed open");
        // the inventory click: the server opens the box at that slot, and nothing for a slot without a box
        p.getInventory().setItem(20, new ItemStack(IntWorkshop.item(IntWorkshop.PILL_BOX)));
        PillBoxItem.open(p, 21);
        helper.assertTrue(!(p.containerMenu instanceof PillBoxMenu), "a slot without a box opened a menu");
        PillBoxItem.open(p, 20);
        helper.assertTrue(p.containerMenu instanceof PillBoxMenu m && m.lockedSlot() == 20, "the inventory click did not open the box");
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void aShiftRightClickTogglesTheLidWithoutOpeningAMenu(GameTestHelper helper) {
        ServerPlayer p = player(helper);
        PillBoxBlockEntity box = placed(helper);
        BlockPos abs = helper.absolutePos(AT);
        BlockState closed = helper.getLevel().getBlockState(abs);
        helper.assertTrue(!closed.getValue(PillBoxBlock.OPEN), "the box starts open");
        box.setItem(0, pills());
        p.setShiftKeyDown(true);
        var originalMenu = p.containerMenu;
        closed.useWithoutItem(helper.getLevel(), p, new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false));
        helper.assertTrue(p.containerMenu == originalMenu, "the placed box opened a menu");
        helper.assertTrue(helper.getLevel().getBlockState(abs).getValue(PillBoxBlock.OPEN), "the lid did not open");
        // Another player can close it: the state belongs to the block, not to a viewer.
        ServerPlayer q = player(helper);
        q.setShiftKeyDown(true);
        var otherMenu = q.containerMenu;
        helper.getLevel().getBlockState(abs).useWithoutItem(helper.getLevel(), q, new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false));
        helper.assertTrue(q.containerMenu == otherMenu && p.containerMenu == originalMenu, "toggling the lid changed a menu");
        helper.assertTrue(!helper.getLevel().getBlockState(abs).getValue(PillBoxBlock.OPEN), "the second click did not close the lid");
        helper.assertTrue(box.getItem(0).getCount() == 3, "toggling the lid changed its contents");
        // the open box is taller (the standing lid) and still inside its block
        BlockState open = closed.setValue(PillBoxBlock.OPEN, true);
        var shape = open.getShape(helper.getLevel(), abs);
        helper.assertTrue(shape.max(Direction.Axis.Y) > closed.getShape(helper.getLevel(), abs).max(Direction.Axis.Y) && shape.max(Direction.Axis.Y) <= 1.0D
                && shape.max(Direction.Axis.Z) <= 1.0D, "the open shape");
        // It stays open beyond the old viewer-counter recheck interval, without any menu.
        helper.getLevel().getBlockState(abs).useWithoutItem(helper.getLevel(), p,
                new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false));
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(helper.getLevel().getBlockState(abs).getValue(PillBoxBlock.OPEN), "the lid closed without a click");
            helper.assertTrue(p.containerMenu == originalMenu, "a menu opened later");
            helper.succeed();
        });
    }
    @GameTest(template = FLOOR, batch = "decor")
    public static void smallBoxRejectsSecondKindAndStopsAt64(GameTestHelper h) {
        ServerPlayer p = player(h);
        PillBoxBlockEntity box = placed(h);
        PillBoxMenu menu = new PillBoxMenu(1, p.getInventory(), box, -1);
        for (int i = 0; i < 4; i++) box.setItem(i, new ItemStack(ModItems.PILL_VITALITY.get(), 16));
        ItemStack second = new ItemStack(ModItems.PILL_SNOW_PLUM.get());
        h.assertTrue(!box.canPlaceItem(4, second) && !menu.getSlot(4).mayPlace(second), "second kind accepted");
        box.setItem(4, second);
        h.assertTrue(box.getItem(4).isEmpty(), "direct insertion bypassed kind rule");
        p.getInventory().setItem(9, new ItemStack(ModItems.PILL_VITALITY.get(), 16));
        h.assertTrue(menu.quickMoveStack(p, slotOf(menu, p, 9)).isEmpty(), "more than 64 fit");
        box.removeItem(0, 1);
        menu.quickMoveStack(p, slotOf(menu, p, 9));
        h.assertTrue(p.getInventory().getItem(9).getCount() == 15, "capacity did not admit exactly one");
        ItemStack held = new ItemStack(IntWorkshop.item(IntWorkshop.PILL_BOX));
        p.getInventory().setItem(4, held);
        PillPouch.OfItem pouch = new PillPouch.OfItem(p.getInventory(), 4);
        pouch.setItem(0, pills());
        h.assertTrue(!pouch.canPlaceItem(1, new ItemStack(ModItems.PILL_VITALITY.get())), "held box accepts mixed kinds");
        h.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void crateSelectsChosenPillAndShiftUseToggles(GameTestHelper h) {
        ServerPlayer p = player(h);
        h.setBlock(AT, IntWorkshop.PILL_CRATE.get().defaultBlockState());
        BlockPos at = h.absolutePos(AT);
        PillBoxBlockEntity box = (PillBoxBlockEntity) h.getLevel().getBlockEntity(at);
        box.setItem(0, pills());
        box.setItem(1, new ItemStack(ModItems.PILL_VITALITY.get(), 16));
        h.assertTrue(box.getContainerSize() == 18 && !box.canPlaceItem(2, new ItemStack(Items.STONE)), "crate inventory filter");
        var hit = new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false);
        h.getLevel().getBlockState(at).useWithoutItem(h.getLevel(), p, hit);
        h.assertTrue(p.containerMenu instanceof PillBoxMenu, "normal use did not open selection");
        PillBoxMenu menu = (PillBoxMenu) p.containerMenu;
        menu.clicked(1, 0, net.minecraft.world.inventory.ClickType.PICKUP, p);
        h.assertTrue(menu.getCarried().is(ModItems.PILL_VITALITY.get()) && menu.getCarried().getCount() == 1
                && box.getItem(0).getCount() == 3 && box.getItem(1).getCount() == 15, "selection took wrong kind or count");
        menu.setCarried(ItemStack.EMPTY);
        menu.quickMoveStack(p, 0);
        h.assertTrue(box.getItem(0).isEmpty() && p.getInventory().countItem(ModItems.PILL_SNOW_PLUM.get()) == 3,
                "shift selection did not take stack");
        p.closeContainer();
        var before = p.containerMenu;
        p.setShiftKeyDown(true);
        h.getLevel().getBlockState(at).useWithoutItem(h.getLevel(), p, hit);
        h.assertTrue(p.containerMenu == before && h.getLevel().getBlockState(at).getValue(PillBoxBlock.OPEN), "shift use did not toggle lid");
        h.getLevel().getBlockState(at).useWithoutItem(h.getLevel(), p, hit);
        h.assertTrue(!h.getLevel().getBlockState(at).getValue(PillBoxBlock.OPEN), "second shift use did not close lid");
        h.succeed();
    }

    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/server/level/ServerPlayerGameMode.java#useItemOn
    // Exercise the real event/held-item bypass, rather than invoking useWithoutItem directly.
    @GameTest(template = FLOOR, batch = "decor")
    public static void bothPillContainersSneakUseWithEmptyHandAndSword(GameTestHelper h) {
        ServerPlayer p = player(h);
        BlockPos at = h.absolutePos(AT);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false);
        for (var block : List.of(IntWorkshop.PILL_BOX.get(), IntWorkshop.PILL_CRATE.get())) {
            h.setBlock(AT.below(), Blocks.STONE);
            h.setBlock(AT, block.defaultBlockState());
            PillBoxBlockEntity box = (PillBoxBlockEntity) h.getLevel().getBlockEntity(at);
            box.setItem(0, pills());
            p.setShiftKeyDown(true);
            var menu = p.containerMenu;
            for (ItemStack held : List.of(ItemStack.EMPTY, new ItemStack(Items.IRON_SWORD))) {
                p.setItemInHand(InteractionHand.MAIN_HAND, held);
                for (boolean expected : new boolean[] {true, false}) {
                    p.gameMode.useItemOn(p, h.getLevel(), held, InteractionHand.MAIN_HAND, hit);
                    h.assertTrue(h.getLevel().getBlockState(at).getValue(PillBoxBlock.OPEN) == expected,
                            "sneak click failed for " + block + " with " + held);
                    h.assertTrue(p.containerMenu == menu, "sneak click opened a menu");
                }
            }
            // OPEN belongs to the serialized block state; the contents keep their own save/update path.
            p.setShiftKeyDown(false);
            p.gameMode.useItemOn(p, h.getLevel(), p.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            h.assertTrue(p.containerMenu instanceof PillBoxMenu && !h.getLevel().getBlockState(at).getValue(PillBoxBlock.OPEN),
                    "plain click must open the menu without toggling the lid");
            h.assertTrue(box.getItem(0).getCount() == 3, "interaction changed the pills");
            p.closeContainer();
        }
        // A held item must not force block use on unrelated blocks.
        h.setBlock(AT, Blocks.CHEST);
        p.setShiftKeyDown(true);
        var event = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(
                p, InteractionHand.MAIN_HAND, at, hit);
        PillBoxInteractions.sneakUse(event);
        h.assertTrue(event.getUseBlock() == net.neoforged.neoforge.common.util.TriState.DEFAULT,
                "unrelated block interaction was forced");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "decor")
    public static void caveGetsCrateJarAndNoteChestAndKeepsAuthorBlocks(GameTestHelper h) {
        BlockPos origin = h.absolutePos(new BlockPos(5, 2, 5));
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-4, -1, -4), origin.offset(4, 2, 4))) {
            h.getLevel().setBlock(p, p.getY() == origin.getY() - 1 ? Blocks.STONE.defaultBlockState()
                    : Blocks.AIR.defaultBlockState(), 3);
        }
        // origin is the author's x=-840, z=3254, y=232 (floor decor level).
        BlockPos boxPos = origin.above(), jarPos = origin.north().above();
        BlockPos chestPos = origin.offset(2, 0, -2);
        h.getLevel().setBlock(origin, HallFurniture.DINING_TABLE.get().defaultBlockState(), 3);
        h.getLevel().setBlock(origin.north(), HallFurniture.DINING_TABLE.get().defaultBlockState(), 3);
        h.getLevel().setBlock(origin.offset(0, 0, -2), ShrineDecor.CANDLE_STAND_SMALL.get().defaultBlockState()
                .setValue(IntTabletopBlock.FACING, Direction.EAST), 3);
        h.getLevel().setBlock(origin.offset(1, 0, -2), SectOrnaments.WATER_VAT.get().defaultBlockState(), 3);
        h.getLevel().setBlock(chestPos, IntStore.CHEST.get().defaultBlockState()
                .setValue(StoStorageBlock.FACING, Direction.SOUTH), 3);
        for (int x = 2; x <= 4; x++) {
            for (int z = -1; z <= 0; z++) {
                h.getLevel().setBlock(origin.offset(x, 0, z), IntStore.RUG_STRAW.get().defaultBlockState(), 3);
            }
        }
        for (int x = 0; x <= 2; x++) {
            h.getLevel().setBlock(origin.offset(x, 0, 1), IntStore.SCREEN.get().defaultBlockState(), 3);
            h.getLevel().setBlock(origin.offset(x, 1, 1), IntStore.SCREEN.get().defaultBlockState()
                    .setValue(StoScreenBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 3);
        }
        // Preserve both authored bed halves, including their orientation.
        h.getLevel().setBlock(origin.offset(3, 0, -2), IntStore.BED_BLUE.get().defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.FACING, Direction.EAST), 3);
        h.getLevel().setBlock(origin.offset(4, 0, -2), IntStore.BED_BLUE.get().defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.FACING, Direction.EAST)
                .setValue(net.minecraft.world.level.block.BedBlock.PART,
                        net.minecraft.world.level.block.state.properties.BedPart.HEAD), 3);
        h.getLevel().setBlock(boxPos, TabletopSupport.onSupport(IntWorkshop.PILL_BOX.get().defaultBlockState()
                .setValue(PillBoxBlock.FACING, Direction.EAST).setValue(PillBoxBlock.OPEN, true), h.getLevel(), boxPos), 3);
        var old = (PillBoxBlockEntity) h.getLevel().getBlockEntity(boxPos);
        var chest = (net.minecraft.world.Container) h.getLevel().getBlockEntity(chestPos);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
        java.util.Map<BlockPos, BlockState> before = new java.util.HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-4, -1, -4), origin.offset(4, 2, 4))) {
            before.put(p.immutable(), h.getLevel().getBlockState(p));
        }
        // A box with the author's own pills in it, or a table without a free top, is refused without a change.
        old.setItem(0, new ItemStack(ModItems.PILL_VITALITY.get(), 7));
        h.assertTrue(!ChungMyungCave.furnish(h.getLevel(), origin) && h.getLevel().getBlockState(boxPos).is(IntWorkshop.PILL_BOX.get())
                && old.countItem(ModItems.PILL_VITALITY.get()) == 7, "filled box was replaced");
        old.setItem(0, ItemStack.EMPTY);
        h.getLevel().setBlock(jarPos, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        h.assertTrue(!ChungMyungCave.furnish(h.getLevel(), origin) && h.getLevel().getBlockState(boxPos).is(IntWorkshop.PILL_BOX.get()),
                "table without a free top was furnished");
        h.getLevel().setBlock(jarPos, Blocks.AIR.defaultBlockState(), 3);
        // Exercise the registered command, with an explicit world position.
        String command = "murim dev cave chungmyung " + origin.getX() + " " + origin.getY() + " " + origin.getZ();
        h.getLevel().getServer().getCommands().performPrefixedCommand(
                player(h).createCommandSourceStack().withPermission(4), command);
        h.assertTrue(h.getLevel().getBlockState(boxPos).is(IntWorkshop.PILL_CRATE.get()), "command did not replace table box");
        var crate = (PillBoxBlockEntity) h.getLevel().getBlockEntity(boxPos);
        h.assertTrue(crate.isCrate() && crate.countItem(ModItems.PILL_SNOW_PLUM.get()) == 3
                && crate.countItem(ModItems.PILL_VITALITY.get()) == 32, "crate counts wrong");
        h.assertTrue(h.getLevel().getBlockState(boxPos).getValue(PillBoxBlock.FACING) == Direction.EAST
                && h.getLevel().getBlockState(boxPos).getValue(TabletopSupport.SINK) == 6, "crate facing/support changed");
        var jar = h.getLevel().getBlockState(jarPos);
        h.assertTrue(jar.is(IntWorkshop.WINE_JAR.get()) && jar.getValue(WineBlock.FULL) && jar.getValue(TabletopSupport.SINK) == 6
                && jar.getValue(IntTabletopBlock.FACING) == Direction.EAST, "wine jar missing on the free end of the table");
        int filled = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) if (!chest.getItem(i).isEmpty()) filled++;
        h.assertTrue(filled == 3 && chest.countItem(IntWorkshop.CAVE_NOTE.get()) == 1
                && chest.countItem(ModItems.PILL_PLUM_BLOSSOM.get()) == 2 && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 7, "supplies missing or author's contents changed");
        for (var entry : before.entrySet()) {
            BlockPos p = entry.getKey();
            if (!p.equals(boxPos) && !p.equals(jarPos)) {
                h.assertTrue(h.getLevel().getBlockState(p).equals(entry.getValue()), "authored block or free space changed at " + p);
            }
        }
        // Even taking the gifts and reloading the chest must not restock or create another chest.
        chest.removeItemNoUpdate(1);
        chest.removeItemNoUpdate(2);
        var entity = h.getLevel().getBlockEntity(chestPos);
        var saved = entity.saveWithFullMetadata(h.getLevel().registryAccess());
        entity.loadWithComponents(saved, h.getLevel().registryAccess());
        h.getLevel().getServer().getCommands().performPrefixedCommand(
                player(h).createCommandSourceStack().withPermission(4), command);
        h.assertTrue(!ChungMyungCave.furnish(h.getLevel(), origin), "rerun was not refused");
        h.assertTrue(chest.countItem(IntWorkshop.CAVE_NOTE.get()) == 0 && chest.countItem(ModItems.PILL_PLUM_BLOSSOM.get()) == 0,
                "claimed supplies were restocked");
        int chests = 0;
        for (BlockPos p : before.keySet()) if (h.getLevel().getBlockState(p).is(IntStore.CHEST.get())) chests++;
        h.assertTrue(chests == 1, "a second chest appeared");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "decor")
    public static void existingCrateGetsOneNewWallChestWithoutRestockingCrate(GameTestHelper h) {
        BlockPos table = h.absolutePos(new BlockPos(5, 2, 5));
        for (BlockPos p : BlockPos.betweenClosed(table.offset(-4, -1, -4), table.offset(4, 2, 4))) {
            h.getLevel().setBlock(p, p.getY() == table.getY() - 1 || p.getZ() == table.getZ() - 3
                    ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
        }
        h.getLevel().setBlock(table, HallFurniture.DINING_TABLE.get().defaultBlockState(), 3);
        h.getLevel().setBlock(table.above(), TabletopSupport.onSupport(IntWorkshop.PILL_CRATE.get().defaultBlockState(),
                h.getLevel(), table.above()), 3);
        var crate = (PillBoxBlockEntity) h.getLevel().getBlockEntity(table.above());
        crate.setItem(0, new ItemStack(ModItems.PILL_VITALITY.get(), 7));
        h.assertTrue(ChungMyungCave.furnish(h.getLevel(), table), "old crate did not get the chest upgrade");
        int chests = 0;
        for (BlockPos p : BlockPos.betweenClosed(table.offset(-4, 0, -4), table.offset(4, 2, 4))) {
            if (!h.getLevel().getBlockState(p).is(IntStore.CHEST.get())) continue;
            chests++;
            var chest = (net.minecraft.world.Container) h.getLevel().getBlockEntity(p);
            h.assertTrue(p.getY() == table.getY() && p.getZ() == table.getZ() - 2,
                    "chest is not on the floor against the wall");
            h.assertTrue(chest.countItem(IntWorkshop.CAVE_NOTE.get()) == 1
                    && chest.countItem(ModItems.PILL_PLUM_BLOSSOM.get()) == 2, "new chest supplies wrong");
        }
        h.assertTrue(chests == 1 && crate.countItem(ModItems.PILL_VITALITY.get()) == 7,
                "chest count or existing crate changed");
        h.assertTrue(!ChungMyungCave.furnish(h.getLevel(), table), "second run changed the cave");
        h.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void cupDrinksOnceThenRefillsWithoutNauseaOrDamage(GameTestHelper h) {
        ServerPlayer p = player(h);
        h.setBlock(AT, IntWorkshop.WINE_CUP.get().defaultBlockState());
        BlockPos at = h.absolutePos(AT);
        var hit = new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false);
        float health = p.getHealth();
        h.getLevel().getBlockState(at).useWithoutItem(h.getLevel(), p, hit);
        h.assertTrue(!h.getLevel().getBlockState(at).getValue(WineBlock.FULL)
                && p.getEffect(IntWorkshop.TIPSY).getDuration() == 1800, "cup did not empty / tipsy duration wrong");
        h.assertTrue(!p.hasEffect(net.minecraft.world.effect.MobEffects.CONFUSION) && p.getHealth() == health, "wine caused nausea or damage");
        p.removeEffect(IntWorkshop.TIPSY);
        h.getLevel().getBlockState(at).useWithoutItem(h.getLevel(), p, hit);
        h.assertTrue(!p.hasEffect(IntWorkshop.TIPSY) && !h.getLevel().getBlockState(at).getValue(WineBlock.FULL), "empty cup granted another drink");
        h.setBlock(AT.east(), IntWorkshop.WINE_JAR.get().defaultBlockState());
        h.getLevel().getBlockState(at).useWithoutItem(h.getLevel(), p, hit);
        h.assertTrue(h.getLevel().getBlockState(at).getValue(WineBlock.FULL) && !p.hasEffect(IntWorkshop.TIPSY), "refill must not drink");
        h.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void smallBoxNormalUseSelectsAndRepeatedClicksTakeOne(GameTestHelper h) {
        ServerPlayer p = player(h);
        PillBoxBlockEntity box = placed(h);
        box.setItem(0, pills());
        BlockPos at = h.absolutePos(AT);
        h.getLevel().getBlockState(at).useWithoutItem(h.getLevel(), p,
                new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
        h.assertTrue(p.containerMenu instanceof PillBoxMenu && !h.getLevel().getBlockState(at).getValue(PillBoxBlock.OPEN),
                "normal use should select without changing lid");
        PillBoxMenu menu = (PillBoxMenu) p.containerMenu;
        menu.clicked(0, 0, net.minecraft.world.inventory.ClickType.PICKUP, p);
        menu.clicked(0, 0, net.minecraft.world.inventory.ClickType.PICKUP, p);
        h.assertTrue(menu.getCarried().getCount() == 2 && box.getItem(0).getCount() == 1, "repeated clicks did not take one each");
        h.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void rarePillsAlsoFit64AndWithdrawWithTheirNormalStackLimit(GameTestHelper h) {
        ServerPlayer p = player(h);
        PillBoxBlockEntity box = placed(h);
        PillBoxMenu menu = new PillBoxMenu(1, p.getInventory(), box, -1);
        for (int i = 0; i < 16; i++) {
            p.getInventory().setItem(9, new ItemStack(ModItems.PILL_ORIGIN_ENERGY.get(), 4));
            menu.quickMoveStack(p, slotOf(menu, p, 9));
        }
        h.assertTrue(box.countItem(ModItems.PILL_ORIGIN_ENERGY.get()) == 64, "rare pills do not fit 64");
        menu.quickMoveStack(p, 0);
        h.assertTrue(box.countItem(ModItems.PILL_ORIGIN_ENERGY.get()) == 60
                && p.getInventory().countItem(ModItems.PILL_ORIGIN_ENERGY.get()) == 4, "withdrawal not a native stack");
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack stack = p.getInventory().getItem(i);
            if (stack.is(ModItems.PILL_ORIGIN_ENERGY.get())) h.assertTrue(stack.getMaxStackSize() == 4, "storage capacity leaked into inventory");
        }
        h.succeed();
    }

}
