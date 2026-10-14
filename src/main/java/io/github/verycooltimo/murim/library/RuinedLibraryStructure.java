package io.github.verycooltimo.murim.library;

import com.mojang.serialization.MapCodec;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import io.github.verycooltimo.murim.world.location.CapturedLocation;
import io.github.verycooltimo.murim.world.location.Ground;
import io.github.verycooltimo.murim.world.location.LocationTemplatePiece;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Optional;

/**
 * The abandoned archive as a world structure (docs/design/25-ruined-library.md §3): {@code /locate structure
 * murim:ruined_library}, rare (structure_set spacing 48 chunks), in forests, taiga, hills, plains, meadows, savanna
 * and cherry groves (tag {@code murim:has_structure/ruined_library}), never at Mount Hua.
 *
 * <p>The archive is dug in: its rock ceiling stays at least ten blocks under the lowest ground over it, and only
 * the rock mouth of the tunnel shows on the surface. Every footprint column is checked, including valleys between
 * the corners. Sites over water, below build height or with an exposed descent are rejected.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/structure/Structure.java (#findGenerationPoint,
 * #simpleCodec, GenerationStub), reference/minecraft-src/net/minecraft/world/level/chunk/ChunkGenerator.java#getBaseHeight,
 * reference/neoforge-src/net/neoforged/neoforge/server/ServerLifecycleHooks.java#getCurrentServer.
 */
public class RuinedLibraryStructure extends Structure {

    public static final MapCodec<RuinedLibraryStructure> CODEC = simpleCodec(RuinedLibraryStructure::new);

    /** Rock and earth between the archive's natural rock backfill (ROCK_TOP) and the lowest ground over it. */
    static final int COVER = 10;
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
        LibraryPiece piece = piece(seed, cx, cz, dir, Ground.of(context), context.chunkGenerator().getSeaLevel(),
                context.heightAccessor().getMinBuildHeight(), false);
        if (piece == null) {
            return Optional.empty();
        }
        // Surface-anchored captures do not supply a deep entrance. World generation uses the buried piece;
        // templatePiece remains available to the explicitly requested capture/showcase workflow.
        return Optional.of(new GenerationStub(piece.archive(13, LibraryPlan.TOP, 13), builder -> builder.addPiece(piece)));
    }

    /** The captured archive centred on (cx, cz), its ground level on the local ground. */
    public static LocationTemplatePiece templatePiece(CapturedLocation tpl, int cx, int cz, Rotation rot, Ground ground) {
        Ground.Placement p = Ground.place(tpl, cx, cz, rot, ground);
        return new LocationTemplatePiece(tpl, p.origin(), rot);
    }

    /**
     * The procedural archive centred on (cx, cz), or null where it cannot stand (water over the site, no covered descent,
     * below build height). {@code force}: permit wet/high showcase terrain, still keep depth and a continuous descent.
     */
    public static LibraryPiece piece(long seed, int cx, int cz, Direction dir, Ground ground, int seaLevel, int minBuildHeight,
            boolean force) {
        int x0 = cx - LibraryPlan.WIDTH / 2;
        int z0 = cz - LibraryPlan.DEPTH / 2;
        LibraryPiece probe = new LibraryPiece(seed, x0, 0, z0, dir, LibraryPlan.ROCK_TOP + COVER);
        int t = LibraryPlan.TUNNEL;
        int lowest = Integer.MAX_VALUE;
        // Full 31x31 footprint, including the shell and roof: no sparse corner sampling.
        for (int ax = -LibraryPiece.ROCK_PAD; ax < LibraryPlan.SIZE + LibraryPiece.ROCK_PAD; ax++) {
            for (int az = -LibraryPiece.ROCK_PAD; az < LibraryPlan.SIZE + LibraryPiece.ROCK_PAD; az++) {
                BlockPos w = probe.archive(ax, 0, az);
                if (!force && !ground.dry(w.getX(), w.getZ())) return null;
                lowest = Math.min(lowest, ground.floor(w.getX(), w.getZ()));
            }
        }
        int baseY = lowest - COVER - LibraryPlan.ROCK_TOP;
        if (baseY < minBuildHeight + 4 || !force && (lowest <= seaLevel || lowest > 190)) return null;
        // Search exact mouth solutions rather than iterating two guesses on a sloping heightmap.
        for (int mouthZ = 1; mouthZ < t - LibraryPlan.LANDING; mouthZ++) {
            BlockPos mouth = probe.world(11, 0, mouthZ - 1);
            int mouthY = ground.floor(mouth.getX(), mouth.getZ()) - baseY;
            if (mouthY < LibraryPlan.TOP || mouthY - LibraryPlan.TOP > t - LibraryPlan.LANDING - 1
                    || LibraryPaths.mouthZ(mouthY) != mouthZ) continue;
            boolean covered = true;
            for (int z = Math.max(0, mouthZ - 2); z < t && covered; z++) {
                int crown = baseY + LibraryPaths.entranceFloor(mouthY, z) + 5;
                for (int x = 8; x <= 14; x++) {
                    BlockPos w = probe.world(x, 0, z);
                    int outcrop = mouthRockTop(seed, x, z, mouthZ, mouthY);
                    if ((!force && !ground.dry(w.getX(), w.getZ()))
                            || (z > mouthZ + 2 && crown > Math.max(ground.floor(w.getX(), w.getZ()) - 2,
                                    outcrop == Integer.MIN_VALUE ? outcrop : baseY + outcrop - 1))) {
                        covered = false;
                        break;
                    }
                }
            }
            if (covered) return new LibraryPiece(seed, x0, baseY, z0, dir, mouthY);
        }
        return null; // No walkable, covered descent at this site.
    }

    /** Exact outcrop envelope used by LibraryPiece.Builder.mouth, for descent exposure checks. */
    static int mouthRockTop(long seed, int x, int z, int mouthZ, int mouthY) {
        if (z < Math.max(0, mouthZ - 3) || z > Math.min(LibraryPlan.TUNNEL - 1, mouthZ + 12)) return Integer.MIN_VALUE;
        double ex = (x - 11.0D) / (10.0D + LibraryPlan.noise(seed, z, 51, 0) * 2.0D);
        double ez = (z - mouthZ - 4.0D) / 8.0D;
        double e = ex * ex + ez * ez;
        return e >= 1.0D ? Integer.MIN_VALUE : mouthY + (int) Math.round(10.0D * (1.0D - e)
                + (LibraryPlan.noise(seed, x, 55, z) - 0.5D) * 3.0D);
    }

    /** No surface beard: the natural rock shell is already below the complete terrain footprint. */
    @Override
    public TerrainAdjustment terrainAdaptation() {
        return TerrainAdjustment.NONE;
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
