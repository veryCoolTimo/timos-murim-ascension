package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** GameTests of the interior seating set (decor/IntSeating): sitting, one sitter per seat, joining pieces. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class IntSeatingGameTests {

    private static final String FLOOR = "small_floor";

    private IntSeatingGameTests() {
    }

    /** Chair, stool, bench and mat seat one player each; the seat entity goes with the block. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void seatsSeatOnePlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos[] at = {new BlockPos(1, 3, 1), new BlockPos(3, 3, 1), new BlockPos(1, 3, 3), new BlockPos(3, 3, 3), new BlockPos(2, 3, 2)};
        helper.setBlock(at[0], IntSeating.CHAIR.get());
        helper.setBlock(at[1], IntSeating.STOOL.get());
        helper.setBlock(at[2], IntSeating.BENCH.get());
        helper.setBlock(at[3], IntSeating.MAT.get());
        helper.setBlock(at[4], IntSeating.ARMCHAIR.get());
        for (BlockPos p : at) {
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.setPos(Vec3.atCenterOf(helper.absolutePos(p.north())));
            helper.assertTrue(SeatEntity.sit(level, helper.absolutePos(p), player), "did not sit on " + helper.getBlockState(p));
            helper.assertTrue(player.getVehicle() instanceof SeatEntity, "vehicle " + player.getVehicle());
            Player other = helper.makeMockPlayer(GameType.SURVIVAL);
            helper.assertFalse(SeatEntity.sit(level, helper.absolutePos(p), other), "two on " + helper.getBlockState(p));
            player.getVehicle().positionRider(player);
            double above = player.getY() - helper.absolutePos(p).getY();
            helper.assertTrue(above > -0.7D && above < 0.5D, "rider y offset " + above);
        }
        helper.setBlock(at[0], Blocks.AIR);
        helper.runAfterDelay(3, () -> helper.succeedWhen(() -> {
            BlockPos chair = helper.absolutePos(at[0]);
            List<SeatEntity> gone = level.getEntitiesOfClass(SeatEntity.class, new AABB(chair).inflate(0.4D)).stream()
                    .filter(e -> BlockPos.containing(e.getX(), e.getY() + 0.4D, e.getZ()).equals(chair)).toList();
            helper.assertTrue(gone.isEmpty(), "seat left behind: " + gone.size());
        }));
    }

    /** Desks and tables are not seats. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void desksAreNoSeats(GameTestHelper helper) {
        BlockPos at = new BlockPos(2, 3, 2);
        helper.setBlock(at, IntSeating.LOW_DESK.get());
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.assertFalse(SeatEntity.sit(helper.getLevel(), helper.absolutePos(at), player), "sat on a desk");
        helper.succeed();
    }

    /** Benches next to each other join along their length and only the free ends keep end boards. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void benchesJoin(GameTestHelper helper) {
        BlockPos a = new BlockPos(1, 3, 2);
        helper.setBlock(a, IntSeating.BENCH.get().defaultBlockState());
        helper.setBlock(a.east(), IntSeating.BENCH.get().defaultBlockState());
        // facing south: model +x is east. A placed neighbour is joined by updateShape.
        BlockState s1 = helper.getBlockState(a);
        BlockState s2 = helper.getBlockState(a.east());
        helper.assertTrue(s1.getValue(IntBlocks.Linear.PX) && !s1.getValue(IntBlocks.Linear.NX), "first bench: " + s1);
        helper.assertTrue(s2.getValue(IntBlocks.Linear.NX) && !s2.getValue(IntBlocks.Linear.PX), "second bench: " + s2);
        helper.succeed();
    }

    /** Long tables join on all four sides, like the dining table. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void longTableJoins(GameTestHelper helper) {
        BlockPos a = new BlockPos(2, 3, 2);
        helper.setBlock(a, IntSeating.LONG_TABLE.get());
        helper.setBlock(a.north(), IntSeating.LONG_TABLE.get());
        BlockState s = helper.getBlockState(a);
        helper.assertTrue(s.getValue(PipeBlock.NORTH) && !s.getValue(PipeBlock.SOUTH), "table: " + s);
        helper.succeed();
    }

    /** The dais is a half block (walkable) and the tea tables carry their set in the model only. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void daisIsHalfHeight(GameTestHelper helper) {
        BlockPos at = new BlockPos(2, 3, 2);
        helper.setBlock(at, IntSeating.DAIS.get());
        double top = helper.getBlockState(at).getCollisionShape(helper.getLevel(), helper.absolutePos(at)).max(Direction.Axis.Y);
        helper.assertTrue(Math.abs(top - 0.5D) < 1.0E-6D, "dais top " + top);
        helper.succeed();
    }
}
