package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.SectMissionBoard;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Puts the contract board at the layout's board spot («the board by the main hall court», {@link SectLayoutFile#boards}) in a world
 * that has none (docs/adr/0016): the baked sect carries no board block, so without this a fresh world has no contracts to reach.
 * The spot is on the court paving at the edge of the main hall; the board hangs on whatever wall is there. The author's blocks are
 * never replaced: only an empty cell gets the board, and when no wall stands behind it a one-block post is set in the empty cell
 * behind. Done once per world (a saved flag), after the chunk is loaded; a board that is already there (a hand-built world) counts.
 *
 * <p>The facing follows the site: the board looks the way local south (+v, toward the court) looks after the site's quarter turns.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectBoardPlacement {
    /** How often the server looks (ticks). */
    static final int EVERY = 100;
    /** A board this close to the spot counts as the spot's own (the author's board may hang a block off). */
    static final int NEAR = 3;

    private SectBoardPlacement() { }

    /** Saved flag: the spot was dealt with, so a board the player later takes down is not put back. */
    public static final class Done extends SavedData {
        public static final String NAME = "murim_sect_board";
        public static final Factory<Done> FACTORY = new Factory<>(Done::new, (tag, r) -> {
            Done d = new Done();
            d.done = tag.getBoolean("done");
            return d;
        });
        boolean done;

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putBoolean("done", done);
            return tag;
        }
    }

    /** The direction local south (toward the court) looks in the world of this site. */
    static Direction court(MountHuaSite site) {
        int[] d = site.rotateDir(0, 1);
        Direction dir = Direction.fromDelta(d[0], 0, d[1]);
        return dir == null ? Direction.SOUTH : dir;
    }

    static boolean hasBoard(ServerLevel level, BlockPos spot) {
        for (BlockPos p : BlockPos.betweenClosed(spot.offset(-NEAR, -NEAR, -NEAR), spot.offset(NEAR, NEAR, NEAR))) {
            if (level.getBlockState(p).is(SectMissionBoard.BOARD.get())) return true;
        }
        return false;
    }

    private static boolean sturdyBehind(ServerLevel level, BlockPos pos, Direction facing) {
        BlockPos support = pos.relative(facing.getOpposite());
        return level.getBlockState(support).isFaceSturdy(level, support, facing);
    }

    /**
     * Makes sure the board stands at {@code spot}. Returns true when there is one now (placed or already there), false when the
     * chunk is not loaded or the cell is taken by something that is not air (nothing is replaced).
     */
    public static boolean ensure(ServerLevel level, BlockPos spot, MountHuaSite site) {
        if (!level.isLoaded(spot)) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int k = 1; k <= NEAR + 1; k++) {
                if (!level.isLoaded(spot.relative(d, k))) return false;
            }
        }
        if (hasBoard(level, spot)) return true;
        if (!level.getBlockState(spot).isAir()) return false;
        Direction want = court(site);
        Direction facing = null;
        if (sturdyBehind(level, spot, want)) {
            facing = want;
        } else {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (sturdyBehind(level, spot, d)) {
                    facing = d;
                    break;
                }
            }
        }
        if (facing == null) {
            // nothing to hang on: a post of dark planks behind the board, in the empty cell only
            BlockPos post = spot.relative(want.getOpposite());
            if (!level.getBlockState(post).isAir()) return false;
            level.setBlock(post, Blocks.DARK_OAK_PLANKS.defaultBlockState(), 3);
            facing = want;
        }
        if (SectMissionBoard.fits(level, spot, facing)) {
            SectMissionBoard.placeWhole(level, spot, facing);   // the 3x2 board; the spot is its bottom-middle block
        } else {
            level.setBlock(spot, SectMissionBoard.BOARD.get().defaultBlockState().setValue(SectMissionBoard.FACING, facing), 3);
        }
        MurimMod.LOGGER.info("Contract board placed at {} facing {}", spot, facing);
        return true;
    }

    private static final java.util.Set<BlockPos> GAVE_UP = new java.util.HashSet<>();

    /** Server stopped: forget cross-world state. */
    public static void clearGaveUp() { GAVE_UP.clear(); }

    /**
     * {@link #ensure} that does not retry for ever: when the spot is blocked (a foreign block, furniture of an old world), the
     * nearest free cell within {@link #NEAR} blocks takes the board, so the contracts stay reachable. Returns false only while
     * the chunks are not loaded (try again later); true when the board stands or the spot was given up (logged once).
     */
    public static boolean ensureOrNearby(ServerLevel level, BlockPos spot, MountHuaSite site) {
        if (ensure(level, spot, site)) return true;
        int far = 2 * NEAR + 1;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                if (!level.isLoaded(spot.offset(sx * far, 0, sz * far))) return false;
            }
        }
        if (!level.isLoaded(spot)) return false;
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos c : BlockPos.betweenClosed(spot.offset(-NEAR, -1, -NEAR), spot.offset(NEAR, 1, NEAR))) {
            if (!c.equals(spot) && level.getBlockState(c).isAir()) cells.add(c.immutable());
        }
        cells.sort(java.util.Comparator.comparingDouble((BlockPos c) -> c.distSqr(spot)));
        for (BlockPos c : cells) {
            if (ensure(level, c, site)) {
                MurimMod.LOGGER.warn("Contract board spot {} is taken; the board stands at {} instead", spot, c);
                return true;
            }
        }
        if (GAVE_UP.add(spot.immutable())) {
            MurimMod.LOGGER.warn("Contract board spot {} is taken and no free cell is near; no board is placed there", spot);
        }
        return true;
    }

    @SubscribeEvent
    static void serverTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % EVERY != 0) return;
        ServerLevel level = server.overworld();
        Done done = level.getDataStorage().computeIfAbsent(Done.FACTORY, Done.NAME);
        if (done.done) return;
        SectLayout layout = SectLife.layout(level);
        SectLayoutFile file = SectMap.fileOf(layout);
        if (file == null || !(layout instanceof SectLayout.Hua hua) || !hua.site().canonical() || file.boards().isEmpty()) return;
        boolean all = true;
        for (BlockPos spot : file.boards()) all &= ensureOrNearby(level, spot, hua.site());
        if (all) {
            done.done = true;
            done.setDirty();
        }
    }
}
