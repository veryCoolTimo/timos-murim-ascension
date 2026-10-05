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
import io.github.verycooltimo.murim.world.location.LocationTemplates;
import io.github.verycooltimo.murim.world.location.ModLocations;
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
    /** How far above the lowest ground the mouth may stand (stair steps = 10 + this). */
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
        LibraryPiece piece = piece(seed, cx, cz, dir, Ground.of(context), context.chunkGenerator().getSeaLevel(),
                context.heightAccessor().getMinBuildHeight(), false);
        if (piece == null) {
            return Optional.empty();
        }
        // The author's captured archive (docs/design/28-location-capture.md) replaces the procedural one on the same
        // site; the extra random draw happens only with a template, procedural archives keep their seeds.
        CapturedLocation tpl = LocationTemplates.get(ModLocations.ARCHIVE);
        if (tpl != null) {
            Rotation rot = Rotation.getRandom(context.random());
            Ground ground = Ground.of(context);
            return Optional.of(new GenerationStub(piece.archive(13, LibraryPlan.TOP, 13),
                    builder -> builder.addPiece(templatePiece(tpl, cx, cz, rot, ground))));
        }
        return Optional.of(new GenerationStub(piece.archive(13, LibraryPlan.TOP, 13), builder -> builder.addPiece(piece)));
    }

    /** The captured archive centred on (cx, cz), its ground level on the local ground. */
    public static LocationTemplatePiece templatePiece(CapturedLocation tpl, int cx, int cz, Rotation rot, Ground ground) {
        Ground.Placement p = Ground.place(tpl, cx, cz, rot, ground);
        return new LocationTemplatePiece(tpl, p.origin(), rot);
    }

    /**
     * The procedural archive centred on (cx, cz), or null where it cannot stand (water over the site, the mouth too
     * high above the lowest ground, too deep). {@code force}: skip the rejections (showcase world).
     */
    public static LibraryPiece piece(long seed, int cx, int cz, Direction dir, Ground ground, int seaLevel, int minBuildHeight,
            boolean force) {
        int x0 = cx - LibraryPlan.WIDTH / 2;
        int z0 = cz - LibraryPlan.DEPTH / 2;
        LibraryPiece probe = new LibraryPiece(seed, x0, 0, z0, dir, 30);
        int t = LibraryPlan.TUNNEL;
        int[][] points = {{1, t + 1}, {25, t + 1}, {1, t + 25}, {25, t + 25}, {13, t + 13}, {13, t + 2}, {11, t - 3}};
        int min = Integer.MAX_VALUE;
        for (int[] p : points) {
            BlockPos w = probe.world(p[0], 0, p[1]);
            if (!ground.dry(w.getX(), w.getZ()) && !force) {
                return null; // water over the site
            }
            min = Math.min(min, ground.floor(w.getX(), w.getZ()) + 1);
        }
        int baseY = min - 1 - COVER - (LibraryPlan.CEILING + 2);
        // The mouth's z depends on how many steps lead down, which depends on the ground at the mouth: two rounds.
        int mouthZ = t - LibraryPlan.LANDING - 13;
        int mouthTop = 0;
        int mouthY = 0;
        for (int round = 0; round < 2; round++) {
            BlockPos mouth = probe.world(11, 0, Math.max(0, mouthZ - 1));
            mouthTop = ground.floor(mouth.getX(), mouth.getZ());
            mouthY = mouthTop - baseY;
            mouthZ = Math.max(1, t - LibraryPlan.LANDING - Math.max(0, mouthY - LibraryPlan.TOP));
        }
        if (!force && (mouthTop - (min - 1) > MAX_MOUTH_RISE || mouthTop < min - 2 || min <= seaLevel
                || baseY < minBuildHeight + 4 || min > 190)) {
            return null;
        }
        return new LibraryPiece(seed, x0, baseY, z0, dir, force ? Math.max(LibraryPlan.TOP, mouthY) : mouthY);
    }

    /** A captured archive pulls the ground around its box to its level (beard on the template piece only). */
    @Override
    public TerrainAdjustment terrainAdaptation() {
        return LocationTemplates.get(ModLocations.ARCHIVE) != null ? TerrainAdjustment.BEARD_THIN : super.terrainAdaptation();
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
