package io.github.verycooltimo.murim.library;

import com.mojang.serialization.MapCodec;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Optional;

/**
 * The abandoned archive as a world structure (docs/design/25-ruined-library.md §3): {@code /locate structure
 * murim:ruined_library}, rare (structure_set spacing 48 chunks), in forests, taiga, hills, plains, meadows, savanna
 * and cherry groves (tag {@code murim:has_structure/ruined_library}), never at Mount Hua.
 *
 * <p>The archive is dug in: its rock ceiling stays at least three blocks under the lowest ground over it, and only
 * the rock mouth of the tunnel shows on the surface. The site is rejected over water and where the mouth would sit
 * more than six blocks above the lowest ground (the stair down would leave the piece).
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/structure/Structure.java (#findGenerationPoint,
 * #simpleCodec, GenerationStub), reference/minecraft-src/net/minecraft/world/level/chunk/ChunkGenerator.java#getBaseHeight,
 * reference/neoforge-src/net/neoforged/neoforge/server/ServerLifecycleHooks.java#getCurrentServer.
 */
public class RuinedLibraryStructure extends Structure {

    public static final MapCodec<RuinedLibraryStructure> CODEC = simpleCodec(RuinedLibraryStructure::new);

    /** Rock and earth between the archive's top (local y 22) and the lowest ground over it. */
    static final int COVER = 3;
    /** How far above the lowest ground the mouth may stand (stair steps = 11 + this). */
    static final int MAX_MOUTH_RISE = 6;
    /** Keep away from the Mount Hua massif's box. */
    static final int HUA_MARGIN = 160;

    public RuinedLibraryStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        int cx = context.chunkPos().getMiddleBlockX();
        int cz = context.chunkPos().getMiddleBlockZ();
        if (nearMountHua(cx, cz)) {
            return Optional.empty();
        }
        Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(context.random());
        long seed = context.random().nextLong();
        int x0 = cx - LibraryPlan.WIDTH / 2;
        int z0 = cz - LibraryPlan.DEPTH / 2;
        LibraryPiece probe = new LibraryPiece(seed, x0, 0, z0, dir, 30);
        ChunkGenerator gen = context.chunkGenerator();
        int t = LibraryPlan.TUNNEL;
        int[][] points = {{1, t + 1}, {25, t + 1}, {1, t + 25}, {25, t + 25}, {13, t + 13}, {13, t + 2}, {11, t - 3}};
        int min = Integer.MAX_VALUE;
        for (int[] p : points) {
            BlockPos w = probe.world(p[0], 0, p[1]);
            int surface = gen.getBaseHeight(w.getX(), w.getZ(), Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
            int floor = gen.getBaseHeight(w.getX(), w.getZ(), Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState());
            if (surface != floor) {
                return Optional.empty(); // water over the site
            }
            min = Math.min(min, floor);
        }
        BlockPos mouth = probe.world(11, 0, t - 14);
        int mouthTop = gen.getBaseHeight(mouth.getX(), mouth.getZ(), Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState()) - 1;
        int baseY = min - 1 - COVER - (LibraryPlan.CEILING + 2);
        int mouthY = mouthTop - baseY;
        if (mouthTop - (min - 1) > MAX_MOUTH_RISE || mouthTop < min - 2 || min <= gen.getSeaLevel()
                || baseY < context.heightAccessor().getMinBuildHeight() + 4 || min > 190) {
            return Optional.empty();
        }
        LibraryPiece piece = new LibraryPiece(seed, x0, baseY, z0, dir, mouthY);
        return Optional.of(new GenerationStub(piece.archive(13, LibraryPlan.TOP, 13), builder -> builder.addPiece(piece)));
    }

    /**
     * Not at Mount Hua: its site is chosen at server start ({@link MountHuaSites}); before that only spawn chunks
     * generate, and the mountain is at least 2700 blocks from spawn.
     */
    static boolean nearMountHua(int x, int z) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        MountHuaSite site = server == null ? null : MountHuaSites.get(server);
        return site != null && x >= site.minX() - HUA_MARGIN && x <= site.maxX() + HUA_MARGIN
                && z >= site.minZ() - HUA_MARGIN && z <= site.maxZ() + HUA_MARGIN;
    }

    @Override
    public StructureType<?> type() {
        return ModLibrary.STRUCTURE_TYPE.get();
    }
}
