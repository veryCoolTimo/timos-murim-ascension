package io.github.verycooltimo.murim.decor;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Opt-in crate, wine jar and one stocked wooden chest; preserves all authored furniture and contents. */
public final class ChungMyungCave {
    static final String SUPPLIES = "murim_chungmyung_supplies";

    private ChungMyungCave() { }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("cave").then(Commands.literal("chungmyung")
                .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c ->
                        furnish(c.getSource().getLevel(), BlockPosArgument.getLoadedBlockPos(c, "pos")) ? 1 : 0)));
    }

    public static boolean furnish(ServerLevel level, BlockPos origin) {
        List<BlockPos> search = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-8, -8, -8), origin.offset(8, 8, 8))) {
            if (p.distSqr(origin) <= 64 && level.hasChunkAt(p)) search.add(p.immutable());
        }
        search.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
        BlockPos cratePos = search.stream().filter(p -> level.getBlockState(p).is(IntWorkshop.PILL_CRATE.get())
                && level.getBlockState(p.below()).getBlock() instanceof DiningTableBlock)
                .findFirst().orElse(null);
        BlockPos boxPos = cratePos != null ? cratePos : search.stream().filter(p -> level.getBlockState(p).is(IntWorkshop.PILL_BOX.get())
                && level.getBlockState(p.below()).getBlock() instanceof DiningTableBlock).findFirst().orElse(null);
        if (boxPos == null) return refused("tabletop pill box missing within eight blocks", origin);
        // The author's own pills are not ours to move. An existing furnished crate may receive the chest upgrade once.
        if (cratePos == null && !((PillBoxBlockEntity) level.getBlockEntity(boxPos)).isEmpty()) {
            return refused("the pill box is not empty", origin);
        }
        // The author's cave already has a wooden sto_chest by the bed, against the table's wall.
        // Reuse it, preserving every occupied slot; only create a chest if the layout has none.
        BlockPos chestPos = search.stream().filter(p -> p.distSqr(boxPos) <= 25
                && (level.getBlockState(p).is(IntStore.CHEST.get()) || level.getBlockState(p).is(Blocks.CHEST)))
                .findFirst().orElse(null);
        boolean newChest = chestPos == null;
        if (newChest) chestPos = chestSite(level, boxPos.below());
        if (chestPos == null) return refused("no safe wall-side floor space for the chest", origin);
        BlockEntity existing = newChest ? null : level.getBlockEntity(chestPos);
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/level/block/entity/BlockEntity.java
        // #getPersistentData, #loadAdditional/#saveAdditional persist optional NeoForgeData, defaulting to absent in old saves.
        // This server-only marker prevents restocking even after the player takes the supplies.
        if (existing != null && existing.getPersistentData().getBoolean(SUPPLIES)) {
            return refused("the cave supplies were already furnished", origin);
        }
        List<Integer> slots = new ArrayList<>();
        if (newChest) {
            slots.add(0);
            slots.add(1);
        } else if (existing instanceof Container chest) {
            for (int i = 0; i < chest.getContainerSize() && slots.size() < 2; i++) {
                if (chest.getItem(i).isEmpty()) slots.add(i);
            }
        }
        if (slots.size() < 2) return refused("the wooden chest needs two empty slots", origin);
        BlockPos table = boxPos.below();
        BlockPos jar = null;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = table.relative(direction);
            if (level.hasChunkAt(neighbour) && level.getBlockState(neighbour).is(level.getBlockState(table).getBlock())
                    && level.isEmptyBlock(neighbour.above())) {
                jar = neighbour.above();
                break;
            }
        }
        if (cratePos == null && jar == null) return refused("no free top on the pill box's table", origin);
        Direction facing = level.getBlockState(boxPos).getValue(PillBoxBlock.FACING);
        if (cratePos == null) {
            level.setBlock(boxPos, IntWorkshop.PILL_CRATE.get().defaultBlockState()
                    .setValue(PillBoxBlock.FACING, facing)
                    .setValue(TabletopSupport.SINK, TabletopSupport.sink(level, boxPos)), Block.UPDATE_ALL);
            PillBoxBlockEntity crate = (PillBoxBlockEntity) level.getBlockEntity(boxPos);
            // The same pills as the baked cave (tools/hua_bake/bake_extra.py CAVE_PILLS).
            crate.setItem(0, new ItemStack(ModItems.PILL_SNOW_PLUM.get(), 3));
            crate.setItem(1, new ItemStack(ModItems.PILL_VITALITY.get(), 16));
            crate.setItem(2, new ItemStack(ModItems.PILL_VITALITY.get(), 16));
            level.setBlock(jar, TabletopSupport.onSupport(IntWorkshop.WINE_JAR.get().defaultBlockState()
                    .setValue(IntTabletopBlock.FACING, facing), level, jar), Block.UPDATE_ALL);
        }
        if (newChest) {
            level.setBlock(chestPos, IntStore.CHEST.get().defaultBlockState()
                    .setValue(StoStorageBlock.FACING, chestFacing(level, chestPos)), Block.UPDATE_ALL);
        }
        BlockEntity chestEntity = level.getBlockEntity(chestPos);
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/Container.java#setItem;
        // world/level/block/entity/BaseContainerBlockEntity.java#setItem marks contents dirty.
        Container chest = (Container) chestEntity;
        chest.setItem(slots.get(0), new ItemStack(IntWorkshop.CAVE_NOTE.get()));
        chest.setItem(slots.get(1), new ItemStack(ModItems.PILL_PLUM_BLOSSOM.get(), 2));
        chestEntity.getPersistentData().putBoolean(SUPPLIES, true);
        chestEntity.setChanged();
        MurimMod.LOGGER.info("Chung Myung cave: crate {}, wine jar {}, note chest {}", boxPos, jar, chestPos);
        return true;
    }

    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/level/block/state/BlockBehaviour.java
    // #BlockStateBase#isFaceSturdy and LevelReader.java#isEmptyBlock. Require a solid floor/wall,
    // two clear blocks above the floor, and a clear approach. Never replace decor, rugs or paths.
    private static BlockPos chestSite(ServerLevel level, BlockPos table) {
        for (BlockPos p : BlockPos.betweenClosed(table.offset(-3, 0, -3), table.offset(3, 0, 3))) {
            if (p.distSqr(table) < 4 || !level.hasChunkAt(p) || !level.isEmptyBlock(p) || !level.isEmptyBlock(p.above())
                    || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;
            for (Direction wall : Direction.Plane.HORIZONTAL) {
                BlockPos back = p.relative(wall), front = p.relative(wall.getOpposite());
                if (level.getBlockState(back).isFaceSturdy(level, back, wall.getOpposite())
                        && level.isEmptyBlock(front) && level.isEmptyBlock(front.above())) return p.immutable();
            }
        }
        return null;
    }

    private static Direction chestFacing(ServerLevel level, BlockPos pos) {
        for (Direction wall : Direction.Plane.HORIZONTAL) {
            BlockPos back = pos.relative(wall);
            if (level.getBlockState(back).isFaceSturdy(level, back, wall.getOpposite())) return wall.getOpposite();
        }
        return Direction.SOUTH;
    }

    private static boolean refused(String reason, BlockPos origin) {
        MurimMod.LOGGER.info("Chung Myung cave at {}: no changes ({})", origin, reason);
        return false;
    }
}
