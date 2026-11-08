package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.decor.SectNamePlate;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The name tablet of a disciple's room (author 19.10): when the sect gives a player a bed, the server hangs a
 * {@link SectNamePlate} with his name on the wall beside the door of that room, on the corridor side; a reassignment
 * moves it. Several disciples may share a room, each has a tablet of his own (up to four places: either side of the door,
 * above it). The tablet's place is kept in the player's sect journal ({@link #KEY}).
 */
public final class SectNamePlates {
    /** Journal key of {@link SectState}: the tablet's cell as a long ({@code BlockPos#asLong}), absent or 0 for none. */
    public static final String KEY = "room.plate";

    private SectNamePlates() {
    }

    /**
     * Candidate cells for tablets of a room door, best first: beside the door (left end, right end, a second place on
     * each side), then above its middle. All on the corridor side, at eye level, facing the corridor.
     */
    public static List<BlockPos> candidates(SectDormPath.Path path) {
        Direction n = path.toCorridor();
        Direction lateral = n.getClockWise();
        List<BlockPos> cells = SectDormPath.sorted(path.corridorCells(), n);
        BlockPos lo = cells.get(0);
        BlockPos hi = cells.get(cells.size() - 1);
        List<BlockPos> out = new ArrayList<>();
        out.add(lo.relative(lateral.getOpposite()).above());
        out.add(hi.relative(lateral).above());
        out.add(lo.relative(lateral.getOpposite()).above(2));
        out.add(hi.relative(lateral).above(2));
        out.add(cells.get(cells.size() / 2).above(2));
        return out;
    }

    /** The tablet that belongs to {@code ownerId} at {@code pos}, else null. */
    static SectNamePlate.Entity plateAt(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof SectNamePlate.Entity e
                && level.getBlockState(pos).is(SectNamePlate.PLATE.get()) ? e : null;
    }

    /**
     * The cell for this owner's tablet: the cell that already holds his own, else the first free candidate whose wall
     * behind is solid. Empty when no candidate fits (the room is not loaded or has no wall beside the door).
     */
    public static Optional<BlockPos> choose(ServerLevel level, SectDormPath.Path path, String ownerId) {
        Direction n = path.toCorridor();
        BlockPos free = null;
        for (BlockPos pos : candidates(path)) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            SectNamePlate.Entity there = plateAt(level, pos);
            if (there != null && ownerId.equals(there.ownerId())) {
                return Optional.of(pos);
            }
            BlockPos wall = pos.relative(n.getOpposite());
            if (free == null && there == null && level.getBlockState(pos).canBeReplaced() && level.getBlockState(wall).isFaceSturdy(level, wall, n)) {
                free = pos;
            }
        }
        return Optional.ofNullable(free);
    }

    /**
     * Brings the tablet in line with the player's bed: hangs it for a real bed, moves it when the bed changed, takes it down
     * when there is none. Called when a bed is assigned and from the periodic check.
     */
    public static void sync(ServerPlayer p, SectLayout layout) {
        ServerLevel level = p.serverLevel();
        SectState state = SectService.state(p);
        long oldKey = state.entry(KEY, 0L);
        BlockPos old = oldKey == 0L ? null : BlockPos.of(oldKey);
        String id = p.getUUID().toString();
        BlockPos head = SectRooms.bed(p);
        Optional<SectDormPath.Path> path = head == null || layout == null ? Optional.empty() : SectDormPath.of(layout.file(), head);
        Optional<BlockPos> at = path.isEmpty() ? Optional.empty() : choose(level, path.get(), id);
        if (old != null && !(at.isPresent() && at.get().equals(old))) {
            SectNamePlate.Entity gone = plateAt(level, old);
            if (gone != null && id.equals(gone.ownerId())) {
                level.setBlock(old, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        if (at.isEmpty()) {
            if (old != null && path.isEmpty()) {
                p.setData(ModAttachments.SECT, SectService.state(p).record(KEY, 0L));
            }
            return;
        }
        BlockPos pos = at.get();
        if (plateAt(level, pos) == null) {
            BlockState plate = SectNamePlate.PLATE.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, path.get().toCorridor());
            level.setBlock(pos, plate, Block.UPDATE_ALL);
        }
        SectNamePlate.Entity be = plateAt(level, pos);
        if (be != null && (!id.equals(be.ownerId()) || !p.getGameProfile().getName().equals(be.ownerName()))) {
            be.setOwner(id, p.getGameProfile().getName());
        }
        if (oldKey != pos.asLong()) {
            p.setData(ModAttachments.SECT, SectService.state(p).record(KEY, pos.asLong()));
        }
    }
}
