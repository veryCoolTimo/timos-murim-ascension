package io.github.verycooltimo.murim.world.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Shared placement helpers of the red timber kit (pillar, beam, bracket).
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/item/context/BlockPlaceContext.java#getClickLocation,
 * #getClickedFace, #replacingClickedOnBlock; UseOnContext#getHorizontalDirection.
 */
final class TimberPlacement {

    private TimberPlacement() {
    }

    /** The block the player actually clicked (the new block goes next to it unless it is replaced). */
    static BlockPos clickedPos(BlockPlaceContext ctx) {
        return ctx.replacingClickedOnBlock() ? ctx.getClickedPos() : ctx.getClickedPos().relative(ctx.getClickedFace().getOpposite());
    }

    static BlockState clickedState(BlockPlaceContext ctx) {
        return ctx.getLevel().getBlockState(clickedPos(ctx));
    }

    /**
     * The clicked point in the NEW block's local coordinates (0..1 after clamping). Clicking the side
     * of a wall gives 0 or 1 across the face normal, so the piece hugs the clicked wall.
     */
    static Vec3 localHit(BlockPlaceContext ctx) {
        BlockPos p = ctx.getClickedPos();
        Vec3 c = ctx.getClickLocation();
        return new Vec3(TimberOffset.clamp(c.x - p.getX()), TimberOffset.clamp(c.y - p.getY()), TimberOffset.clamp(c.z - p.getZ()));
    }

    /** Offset of an existing kit piece, if the state is one. */
    static TimberOffset offsetOf(BlockState s) {
        if (s.getBlock() instanceof RedPillarBlock) {
            // a lying pillar's offset means height + across, not a floor slot
            return s.getValue(RedPillarBlock.AXIS) == net.minecraft.core.Direction.Axis.Y ? s.getValue(RedPillarBlock.OFFSET) : null;
        }
        if (s.getBlock() instanceof BeamBracketBlock) {
            return s.getValue(BeamBracketBlock.OFFSET);
        }
        return null;
    }

    static Direction.Axis cross(Direction.Axis axis) {
        return axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
    }

    /** Axis across the player's view: standing in front of a gate, the lintel runs left-right. */
    static Direction.Axis acrossView(BlockPlaceContext ctx) {
        return ctx.getHorizontalDirection().getClockWise().getAxis();
    }
}
