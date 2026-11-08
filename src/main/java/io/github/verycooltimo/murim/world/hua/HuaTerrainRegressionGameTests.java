package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.library.LibraryPiece;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Regressions for the new-world portal, exposed dripstone and sealed massif reports (21.10). */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HuaTerrainRegressionGameTests {
    private HuaTerrainRegressionGameTests() { }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void wholeIntersectingPortalIsSuppressedWithoutChangingSharedReferences(GameTestHelper helper) {
        MountHuaSite site = new MountHuaSite(0, 0, 70, 0, 1915L, true);
        var registry = helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE);
        Structure portal = registry.get(ResourceLocation.withDefaultNamespace("ruined_portal"));
        helper.assertTrue(portal != null, "vanilla portal missing from registry");
        // Use a real piece with a known box; placement is deliberately not invoked. Suppression
        // must depend on the start's full box, regardless of which chunk is being decorated.
        StructureStart near = start(portal, 0, 0);
        StructureStart far = start(portal, site.maxX() + 5000, site.maxZ() + 5000);
        LongSet ids = new LongOpenHashSet();
        ids.add(near.getChunkPos().toLong());
        ids.add(far.getChunkPos().toLong());
        Map<Structure, LongSet> original = Map.of(portal, ids);
        var filtered = HuaSurfaceStructures.suppressReferences(original, site, List.of(near, far));
        helper.assertTrue(!filtered.get(portal).contains(near.getChunkPos().toLong()), "floating portal still placeable");
        helper.assertTrue(filtered.get(portal).contains(far.getChunkPos().toLong()), "outside portal suppressed");
        helper.assertTrue(ids.size() == 2, "shared references mutated");
        var adjacent = HuaSurfaceStructures.suppressReferences(original, site, List.of(near, far));
        helper.assertTrue(adjacent.get(portal).equals(filtered.get(portal)), "placement chunks disagree");
        BoundingBox crossing = new BoundingBox(site.minX() - 32, 100, 0, 0, 150, 0);
        helper.assertTrue(HuaSurfaceStructures.intersects(site, crossing), "cross-boundary start missed");
        helper.succeed();
    }

    private static StructureStart start(Structure structure, int x, int z) {
        var piece = new LibraryPiece(1915L, x, 70, z, Direction.NORTH, 90);
        return new StructureStart(structure, new ChunkPos(x >> 4, z >> 4), 0, new PiecesContainer(List.of(piece)));
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void dripstoneSpikesAreRemovedAndSurfaceRockRepaired(GameTestHelper helper) {
        var rock = Blocks.GRANITE.defaultBlockState();
        var pointed = Blocks.POINTED_DRIPSTONE.defaultBlockState();
        helper.assertTrue(MountHuaChunkWriter.surfaceDripstone(pointed, true, rock).isAir(), "surface spike survived");
        helper.assertTrue(MountHuaChunkWriter.surfaceDripstone(Blocks.DRIPSTONE_BLOCK.defaultBlockState(), true, rock).isAir(),
                "large dripstone became a granite spike");
        helper.assertTrue(MountHuaChunkWriter.surfaceDripstone(Blocks.DRIPSTONE_BLOCK.defaultBlockState(), false, rock).equals(rock),
                "dripstone exposed by a terrain cut was not repaired");
        helper.assertTrue(MountHuaChunkWriter.surfaceDripstone(pointed.setValue(BlockStateProperties.WATERLOGGED, true), true, rock)
                .is(Blocks.WATER), "waterlogged spike lost water");
        var paving = HuaGround.HUA_COURTYARD_PAVERS.get().defaultBlockState();
        helper.assertTrue(MountHuaChunkWriter.surfaceDripstone(paving, false, rock) == paving, "sect paving changed");
        helper.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void noStairSetIntoAFlatRunOfAPathFlight(GameTestHelper helper) {
        helper.assertTrue(MountHuaChunkWriter.isRealStep(150, 151), "a rising column must get a tread");
        helper.assertTrue(!MountHuaChunkWriter.isRealStep(150, 150), "flat ground got a tread (a pit with a stair in it)");
        helper.assertTrue(!MountHuaChunkWriter.isRealStep(150, 152), "a two-block riser is not a tread");
        helper.assertTrue(!MountHuaChunkWriter.isRealStep(150, 149), "a falling column got a tread");
        helper.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void cavesKeepShellEvenBesideANarrowGully(GameTestHelper helper) {
        int radius = MountHuaChunkWriter.CAVE_SHELL;
        int size = 16 + 2 * radius;
        int[] envelope = new int[size * size];
        Arrays.fill(envelope, 200);
        helper.assertTrue(MountHuaChunkWriter.caveCeiling(envelope, size, 0, 0, 200) == 200 - radius, "surface shell missing");
        // This offset fell between the WIP's sparse probes, across a chunk boundary.
        envelope[(radius + 3) * size + radius - 7] = 120;
        int ceiling = MountHuaChunkWriter.caveCeiling(envelope, size, 0, 0, 200);
        helper.assertTrue(ceiling == 120 - radius, "cave can break through narrow gully wall");
        HuaNoise noise = new HuaNoise(1915L);
        int air = 0, solid = 0;
        for (int z = -64; z <= 64; z += 4) for (int x = -64; x <= 64; x += 4) {
            helper.assertTrue(!MountHuaChunkWriter.caveNoise(noise, x, ceiling + 1, z, -1), "cave entered shell");
            for (int y = 40; y <= 100; y += 4) {
                if (MountHuaChunkWriter.caveNoise(noise, x, y, z, ceiling - y)) air++; else solid++;
            }
        }
        helper.assertTrue(air > 0 && solid > air, "interior has no caves or excessive voids: " + air + "/" + solid);
        helper.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void caveColumnsExcludeSectPadsPathsAndStairs(GameTestHelper helper) {
        MountHuaSite site = new MountHuaSite(0, 0, 70, 0, 1915L, true);
        for (var zone : MountHuaPlan.ZONES) {
            helper.assertTrue(!MountHuaChunkWriter.caveColumn(site, zone.u(), zone.v()), "cave under " + zone.id());
        }
        for (double[] path : MountHuaPlan.PATHS) for (int i = 0; i <= 20; i++) {
            double t = i / 20.0;
            double u = path[0] + (path[3] - path[0]) * t;
            double v = path[1] + (path[4] - path[1]) * t;
            helper.assertTrue(!MountHuaChunkWriter.caveColumn(site, u, v), "cave under connecting stair/path");
        }
        int eligible = 0;
        for (int z = site.minZ(); z <= site.maxZ(); z += 32) for (int x = site.minX(); x <= site.maxX(); x += 32) {
            if (MountHuaChunkWriter.caveColumn(site, site.centerU(x, z), site.centerV(x, z))) eligible++;
        }
        helper.assertTrue(eligible > 0, "exclusions disable caves throughout massif");
        helper.succeed();
    }
}
