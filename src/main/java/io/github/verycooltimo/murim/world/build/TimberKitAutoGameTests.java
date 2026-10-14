package io.github.verycooltimo.murim.world.build;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.HallDecor;
import io.github.verycooltimo.murim.decor.ThinWallBlock;
import io.github.verycooltimo.murim.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class TimberKitAutoGameTests {
    private TimberKitAutoGameTests() {}

    private static BlockState place(GameTestHelper h, BlockItem item, BlockPos clicked, Direction face, Vec3 point) {
        BlockPlaceContext context = new BlockPlaceContext(h.getLevel(), null, InteractionHand.MAIN_HAND, new ItemStack(item),
                new BlockHitResult(h.absoluteVec(point), face, h.absolutePos(clicked), false));
        BlockPos target = context.getClickedPos();
        h.assertTrue(item.place(context).consumesAction(), "placement refused for " + item);
        return h.getLevel().getBlockState(target);
    }

    @GameTest(template = "small_floor", batch = "building_kit")
    public static void beamEndsFollowPlacementAndRemoval(GameTestHelper h) {
        BlockPos p = new BlockPos(2, 3, 2);
        h.setBlock(p.west(), Blocks.STONE);
        BlockState placed = place(h, (BlockItem) ModBlocks.RED_BEAM.get().asItem(), p.west(), Direction.EAST,
                new Vec3(2, 3.85, 2.5));
        h.assertTrue(placed.getValue(RedBeamBlock.NEG_END) == RedBeamBlock.End.BEAM, "wall did not close placed end " + placed);
        BlockState beam = h.getBlockState(p);
        // Exact matching short end; a reversed or misplaced end must not close the beam.
        BlockState stub = ModBlocks.BEAM_STUB.get().defaultBlockState().setValue(BeamStubBlock.FACING, Direction.EAST);
        h.setBlock(p.east(), stub);
        h.assertTrue(h.getBlockState(p).getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.BEAM, "stub not joined");
        h.setBlock(p.east(), stub.setValue(BeamStubBlock.FACING, Direction.WEST));
        h.assertTrue(h.getBlockState(p).getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.NONE, "reversed stub joined");
        h.setBlock(p.east(), beam.setValue(RedBeamBlock.SIDE, RedBeamBlock.Side.PLUS));
        h.assertTrue(h.getBlockState(p).getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.NONE, "offset beam joined");
        h.setBlock(p.east(), beam);
        h.assertTrue(h.getBlockState(p).getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.BEAM, "beam not joined");
        h.setBlock(p.east(), ModBlocks.RED_PILLAR.get().defaultBlockState().setValue(RedPillarBlock.OFFSET, TimberOffset.EAST));
        h.assertTrue(h.getBlockState(p).getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.PILLAR_FAR, "far pillar not joined");
        h.setBlock(p.east(), Blocks.AIR);
        h.setBlock(p.west(), Blocks.AIR);
        h.assertTrue(h.getBlockState(p).getValue(RedBeamBlock.NEG_END) == RedBeamBlock.End.NONE
                && h.getBlockState(p).getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.NONE, "air did not reopen ends");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "building_kit")
    public static void beamFindsRecessedWallSurface(GameTestHelper h) {
        BlockPos p = new BlockPos(2, 3, 2);
        BlockState beam = ModBlocks.RED_BEAM.get().defaultBlockState().setValue(RedBeamBlock.AXIS, Direction.Axis.X);
        h.setBlock(p, beam);
        // East half of the adjacent cell begins 8px away; the existing middle tenon reaches it.
        h.setBlock(p.east(), HallDecor.THIN_WALL_PLASTER.get().defaultBlockState().setValue(ThinWallBlock.FACING, Direction.EAST));
        h.assertTrue(h.getBlockState(p).getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.PILLAR_MID, "recessed wall not reached");
        h.setBlock(p.east(), HallDecor.THIN_WALL_PLASTER.get().defaultBlockState().setValue(ThinWallBlock.FACING, Direction.WEST));
        h.assertTrue(h.getBlockState(p).getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.BEAM, "flush wall not closed");
        h.setBlock(p.east(), Blocks.AIR);
        h.assertTrue(h.getBlockState(p).getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.NONE, "removed wall still joined");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "building_kit")
    public static void bracketTracksEveryBeamSlot(GameTestHelper h) {
        BlockPos p = new BlockPos(2, 3, 2);
        BlockItem item = (BlockItem) ModBlocks.SIMPLE_BRACKET.get().asItem();
        for (Direction.Axis axis : new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z}) {
            for (RedBeamBlock.Side side : RedBeamBlock.Side.values()) {
                for (RedBeamBlock.Level3 level : RedBeamBlock.Level3.values()) {
                    h.setBlock(p, Blocks.AIR);
                    h.setBlock(p.above(), ModBlocks.RED_BEAM.get().defaultBlockState()
                            .setValue(RedBeamBlock.AXIS, axis).setValue(RedBeamBlock.SIDE, side).setValue(RedBeamBlock.LEVEL, level));
                    BlockState b = place(h, item, p.above(), Direction.DOWN, new Vec3(2.5, 4, 2.5));
                    h.assertTrue(b.getValue(SimpleBracketBlock.FACING).getAxis() == axis && b.getValue(SimpleBracketBlock.SIDE) == side,
                            "bracket lost beam slot " + b);
                    double top = b.getShape(h.getLevel(), h.absolutePos(p)).max(Direction.Axis.Y) * 16;
                    h.assertTrue(Math.abs(top - (16 + level.y0)) < 1e-6, "bracket not touching beam underside " + b);
                }
            }
        }
        h.setBlock(p.above(), ModBlocks.RED_BEAM.get().defaultBlockState().setValue(RedBeamBlock.LEVEL, RedBeamBlock.Level3.BOTTOM));
        h.assertTrue(h.getBlockState(p).getValue(SimpleBracketBlock.REST) == SimpleBracketBlock.Rest.UNDER_LOW, "changed beam level not followed");
        h.setBlock(p.above(), Blocks.AIR);
        h.assertTrue(h.getBlockState(p).getValue(SimpleBracketBlock.REST) == SimpleBracketBlock.Rest.FLOOR, "removed beam still snapped");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "building_kit")
    public static void bracketTracksPillarFacesAndTransforms(GameTestHelper h) {
        BlockPos p = new BlockPos(2, 3, 2);
        for (Direction support : Direction.Plane.HORIZONTAL) {
            for (TimberOffset offset : TimberOffset.values()) {
                h.setBlock(p.relative(support), ModBlocks.RED_PILLAR.get().defaultBlockState().setValue(RedPillarBlock.OFFSET, offset));
                BlockState b = SimpleBracketBlock.connect(ModBlocks.SIMPLE_BRACKET.get().defaultBlockState()
                        .setValue(SimpleBracketBlock.FACING, support.getOpposite()), h.getLevel(), h.absolutePos(p));
                h.setBlock(p, b);
                double edge = support.getAxisDirection() == Direction.AxisDirection.NEGATIVE
                        ? b.getShape(h.getLevel(), h.absolutePos(p)).min(support.getAxis()) * 16
                        : b.getShape(h.getLevel(), h.absolutePos(p)).max(support.getAxis()) * 16;
                double centre = TimberOffset.centrePx(offset.along(support.getAxis()));
                double expected = support.getAxisDirection() == Direction.AxisDirection.NEGATIVE ? -16 + centre + RedPillarBlock.HALF
                        : 16 + centre - RedPillarBlock.HALF;
                h.assertTrue(Math.abs(edge - expected) < 1e-6, "bracket not flush on pillar " + offset + " / " + support + ": " + edge);
                h.assertTrue(b.rotate(Rotation.CLOCKWISE_90).rotate(Rotation.COUNTERCLOCKWISE_90).equals(b), "rotation changed bracket");
                h.assertTrue(b.mirror(Mirror.LEFT_RIGHT).mirror(Mirror.LEFT_RIGHT).equals(b), "mirror changed bracket");
                h.setBlock(p.relative(support), Blocks.AIR);
                h.assertTrue(h.getBlockState(p).getValue(SimpleBracketBlock.REACH) == SimpleBracketBlock.Reach.NONE, "removed pillar still snapped");
            }
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "building_kit")
    public static void plainLintelExtendsWithoutSlotSelection(GameTestHelper h) {
        h.setBlock(new BlockPos(0, 3, 2), Blocks.STONE);
        BlockItem item = (BlockItem) ModBlocks.RED_LINTEL.get().asItem();
        for (int x = 1; x <= 3; x++) {
            BlockState s = place(h, item, new BlockPos(x - 1, 3, 2), Direction.EAST, new Vec3(x, 3.8, 2.5));
            h.assertTrue(s.getValue(RedBeamBlock.LEVEL) == RedBeamBlock.Level3.MIDDLE
                    && s.getValue(RedBeamBlock.SIDE) == RedBeamBlock.Side.CENTER, "lintel needs hand-picked slot " + s);
            h.assertTrue(s.getValue(RedBeamBlock.NEG_END) == RedBeamBlock.End.BEAM, "lintel not joined " + s);
        }
        h.succeed();
    }
}
