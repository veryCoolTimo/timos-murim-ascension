package io.github.verycooltimo.murim.library;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.location.Ground;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Placement uses the complete heightmap, not just corners; the built archive has a sealed stone envelope. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class LibraryPlacementGameTests {
    private LibraryPlacementGameTests() { }

    private static final class Terrain implements Ground {
        final Map<BlockPos, Integer> heights = new HashMap<>();
        BlockPos wet;
        public int floor(int x, int z) { return heights.getOrDefault(new BlockPos(x, 0, z), 100); }
        public boolean dry(int x, int z) { return !new BlockPos(x, 0, z).equals(wet); }
    }

    @GameTest(template = "small_floor", batch = "library", timeoutTicks = 200)
    public static void fullFootprintDepthAndSiteRejection(GameTestHelper h) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            LibraryPiece probe = new LibraryPiece(19L, -LibraryPlan.WIDTH / 2, 0, -LibraryPlan.DEPTH / 2,
                    facing, LibraryPlan.ROCK_TOP + RuinedLibraryStructure.COVER);
            for (int terrain = 0; terrain < 4; terrain++) {
                Terrain ground = new Terrain();
                if (terrain == 1) ground.heights.put(probe.archive(7, 0, 9), 94); // narrow valley missed by old samples
                if (terrain == 2) {
                    for (int x = -LibraryPiece.ROCK_PAD; x < LibraryPlan.SIZE + LibraryPiece.ROCK_PAD; x++) {
                        for (int z = -LibraryPiece.ROCK_PAD; z < LibraryPlan.SIZE + LibraryPiece.ROCK_PAD; z++) {
                            ground.heights.put(probe.archive(x, 0, z), 96 + Math.floorMod(x + z, 7));
                        }
                    }
                }
                if (terrain == 3) ground.heights.put(probe.archive(-1, 0, 9), 95); // rock rind belongs to footprint too
                LibraryPiece piece = RuinedLibraryStructure.piece(19L, 0, 0, facing, ground, 63, -64, false);
                h.assertTrue(piece != null, "valid terrain rejected: " + facing + "/" + terrain);
                for (int x = -LibraryPiece.ROCK_PAD; x < LibraryPlan.SIZE + LibraryPiece.ROCK_PAD; x++) {
                    for (int z = -LibraryPiece.ROCK_PAD; z < LibraryPlan.SIZE + LibraryPiece.ROCK_PAD; z++) {
                        BlockPos roof = piece.archive(x, LibraryPlan.ROCK_TOP, z);
                        h.assertTrue(ground.floor(roof.getX(), roof.getZ()) - roof.getY() >= RuinedLibraryStructure.COVER,
                                "exposed archive at " + roof + ", " + facing + "/" + terrain);
                    }
                }
                BlockPos mouth = piece.world(11, LibraryPlan.TOP + piece.steps(), piece.mouthZ() - 1);
                h.assertTrue(mouth.getY() == ground.floor(mouth.getX(), mouth.getZ()), "mouth not at surface");
                h.assertTrue(piece.steps() >= 22, "descent too shallow");
            }
            Terrain water = new Terrain();
            water.wet = probe.archive(7, 0, 9);
            h.assertTrue(RuinedLibraryStructure.piece(19L, 0, 0, facing, water, 63, -64, false) == null,
                    "water inside unsampled footprint accepted");
            Terrain ravine = new Terrain();
            ravine.heights.put(probe.archive(7, 0, 9), 65);
            h.assertTrue(RuinedLibraryStructure.piece(19L, 0, 0, facing, ravine, 63, -64, false) == null,
                    "descent longer than piece accepted");
            h.assertTrue(RuinedLibraryStructure.piece(19L, 0, 0, facing, new Terrain(), 63, 60, false) == null,
                    "archive below build height accepted");
        }
        MurimMod.LOGGER.info("GameTest archive burial: four orientations, full footprint valleys/slopes/rind, ten-block cover, site rejection");
        h.succeed();
    }

    @GameTest(template = "library_floor", batch = "library", timeoutTicks = 200)
    public static void builtArchiveHasOnlyTheSurfaceMouth(GameTestHelper h) {
        LibraryPiece piece = LibraryTests.build(h, 0x1915L);
        BoundingBox b = piece.getBoundingBox();
        int surface = piece.world(11, LibraryPlan.TOP + piece.steps(), piece.mouthZ()).getY();
        // Build a continuous natural cap before repeating the same deterministic piece.
        for (BlockPos p : BlockPos.betweenClosed(b.minX(), b.minY() - 1, b.minZ(), b.maxX(), surface, b.maxZ())) {
            h.getLevel().setBlock(p, Blocks.STONE.defaultBlockState(), 2);
        }
        for (int y = 0; y <= LibraryPlan.ROCK_TOP; y++) {
            h.getLevel().setBlock(piece.archive(-1, y, 13), Blocks.AIR.defaultBlockState(), 2);
        }
        BoundingBox clip = new BoundingBox(b.minX(), b.minY() - 1, b.minZ(), b.maxX(), b.maxY(), b.maxZ());
        piece.postProcess(h.getLevel(), h.getLevel().structureManager(), h.getLevel().getChunkSource().getGenerator(),
                h.getLevel().getRandom(), clip, new ChunkPos(piece.world(0, 0, 0)), piece.world(0, 0, 0));
        for (int x = -LibraryPiece.ROCK_PAD; x < LibraryPlan.SIZE + LibraryPiece.ROCK_PAD; x++) {
            for (int z = -LibraryPiece.ROCK_PAD; z < LibraryPlan.SIZE + LibraryPiece.ROCK_PAD; z++) {
                BlockPos top = piece.archive(x, LibraryPlan.ROCK_TOP, z);
                h.assertTrue(surface - top.getY() >= RuinedLibraryStructure.COVER, "structure within surface cover at " + top);
                // Every column above the archive is closed, with no second surface opening.
                for (int y = top.getY(); y <= surface; y++) {
                    h.assertTrue(!h.getLevel().getBlockState(new BlockPos(top.getX(), y, top.getZ())).isAir(),
                            "hole through archive cap at " + top.getX() + "," + y + "," + top.getZ());
                }
            }
        }
        for (LibraryPaths.Tread tread : LibraryPaths.routes(LibraryPlan.TOP + piece.steps()).get(0).treads()) {
            BlockPos floor = piece.world(tread.x(), tread.y(), tread.z());
            h.assertTrue(!h.getLevel().getBlockState(floor).getCollisionShape(h.getLevel(), floor).isEmpty(), "unsupported descent " + floor);
            for (int height = 1; height <= 3; height++) {
                h.assertTrue(h.getLevel().getBlockState(floor.above(height)).isAir(), "descent blocked " + floor.above(height));
            }
        }
        // An exterior cave adjoining a wall meets natural stone, never timber or a floating frame.
        for (int y = 0; y <= LibraryPlan.ROCK_TOP; y++) {
            BlockPos stone = piece.archive(-1, y, 13);
            h.assertTrue(!h.getLevel().getBlockState(stone).isAir(), "unsealed side cave at " + stone);
        }
        MurimMod.LOGGER.info("GameTest archive surface: natural cap >=10, sealed shell, only mouth, descent 3 wide / 3 high");
        h.succeed();
    }
}
