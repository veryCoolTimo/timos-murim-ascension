package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.network.DialoguePayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Camera tour of the newcomer admission (docs/design/39-player-path-v2.md, «Приход новичка», author 18.10): no walking tour,
 * the mentor's dialogue lines each carry a {@code shot} id and the client camera glides to that fixed view of a building.
 * The shots are data of the sect layout ({@link SectLayoutFile#tour()}, anchored to the live site, so they work in every
 * world and at every rotation); the one dynamic shot, {@code bed}, is found around the player's own bed.
 *
 * <p>The server resolves the shot to world coordinates and checks that the eye cell is free; the client only moves the camera
 * ({@code client/sect/DialogueCamera}). Nothing here touches the world.
 */
public final class SectTour {

    /** Shot ids used by {@code mentor.json}; the layout carries the first five, {@code bed} is computed. */
    public static final String BED = "bed";
    public static final String DORMS = "dorms";

    /** Eye height above the bed floor and the distances tried around it, blocks. */
    private static final double BED_EYE = 1.7D;
    private static final double[] BED_DISTANCES = {3.4D, 2.9D, 2.4D, 1.9D};
    /** How far an eye cell inside a wall may be lifted before the shot is given up. */
    private static final int LIFT = 4;

    private SectTour() {
    }

    /** The shot {@code id} for this player, or empty (no layout file, unknown id): the camera then stays on the speaker. */
    public static Optional<DialoguePayloads.Shot> shot(ServerPlayer p, SectLayout layout, String id) {
        if (layout == null || layout.file() == null) {
            return Optional.empty();
        }
        if (BED.equals(id)) {
            Optional<DialoguePayloads.Shot> own = bed(p);
            return own.isPresent() ? own : shot(p, layout, DORMS);
        }
        SectLayoutFile.Tour t = layout.file().tour().get(id);
        if (t == null) {
            return Optional.empty();
        }
        Vec3 eye = lifted(p.serverLevel(), t.camera());
        return Optional.of(new DialoguePayloads.Shot(eye, t.aim(), t.fov()));
    }

    /** The eye cell must not be inside a block; a shot that starts in a wall is lifted until the cell is free. */
    public static Vec3 lifted(ServerLevel level, Vec3 eye) {
        for (int i = 0; i <= LIFT; i++) {
            Vec3 at = eye.add(0.0D, i, 0.0D);
            if (free(level, BlockPos.containing(at))) {
                return at;
            }
        }
        return eye;
    }

    /** True when the camera cell does not collide: air, or a block without a collision shape (rug, slab-free decor). */
    public static boolean free(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return true;
        }
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /**
     * The player's own bed as a shot: an eye 1.7 blocks above the floor at 1.9–3.4 blocks from the bed, from the side with the
     * most room (the foot end first, so the whole bed lies along the view). Empty when the player has a bedroll only or the
     * bed is not loaded.
     */
    public static Optional<DialoguePayloads.Shot> bed(ServerPlayer p) {
        BlockPos head = SectRooms.bed(p);
        ServerLevel level = p.serverLevel();
        if (head == null || !level.isLoaded(head)) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(head);
        if (!(state.getBlock() instanceof BedBlock)) {
            return Optional.empty();
        }
        Direction facing = state.getValue(BedBlock.FACING);
        Vec3 aim = Vec3.atCenterOf(head).add(Vec3.atLowerCornerOf(facing.getOpposite().getNormal()).scale(0.5D)).add(0.0D, -0.1D, 0.0D);
        // The foot end first, then the sides, then the head end; each at the three distances, farthest first.
        Direction[] order = {facing.getOpposite(), facing.getClockWise(), facing.getCounterClockWise(), facing};
        for (Direction side : order) {
            for (double d : BED_DISTANCES) {
                for (double slant : new double[] {0.0D, 0.9D, -0.9D, 1.6D, -1.6D}) {
                    Direction across = side.getClockWise();
                    Vec3 eye = new Vec3(aim.x + side.getStepX() * d + across.getStepX() * slant, head.getY() + BED_EYE,
                            aim.z + side.getStepZ() * d + across.getStepZ() * slant);
                    if (clear(level, p, eye, aim)) {
                        return Optional.of(new DialoguePayloads.Shot(eye, aim, 55.0F));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The eye cell and the cell above it are free and the line from the eye to the aim point hits nothing before it. */
    static boolean clear(ServerLevel level, ServerPlayer p, Vec3 eye, Vec3 aim) {
        BlockPos cell = BlockPos.containing(eye);
        // Dormitory floors are three blocks high: only the eye cell has to be free, not the one above it.
        if (!free(level, cell)) {
            return false;
        }
        // API: reference/minecraft-src/net/minecraft/world/level/BlockGetter.java#clip
        HitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(aim) < 1.3D;
    }
}
