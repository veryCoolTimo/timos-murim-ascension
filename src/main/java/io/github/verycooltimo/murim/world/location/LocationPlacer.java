package io.github.verycooltimo.murim.world.location;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Stamps a captured location into a level: every part that meets the clip box (one chunk column during
 * generation, the whole box in a live world), then a footing under the bottom layer down to the ground, so a
 * location placed on a slope stands on earth instead of hanging over it. Used by the structure piece, the
 * Mount Hua sect overlay and the live {@code /murim location place}.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate.java#placeInWorld
 * (rotation about the pivot, clip by settings' bounding box, entities clipped by block position),
 * reference/minecraft-src/net/minecraft/world/level/levelgen/structure/TemplateStructurePiece.java (flags 2 during generation).
 */
public final class LocationPlacer {

    /** How deep the footing may go under the bottom layer. */
    static final int MAX_FOOTING = 40;

    private LocationPlacer() {
    }

    /**
     * Places the location with its local origin at {@code origin}, turned by {@code rotation} about it.
     *
     * @param clip  only blocks inside this box are written; null = everything (live world)
     * @param flags block update flags (2 during generation, 3 in a live world)
     * @return false if a template is missing (the caller may fall back to the procedural builder)
     */
    public static boolean place(MinecraftServer server, ServerLevelAccessor level, CapturedLocation loc, BlockPos origin,
            Rotation rotation, BoundingBox clip, RandomSource random, int flags) {
        boolean ok = true;
        for (CapturedLocation.Part part : loc.parts()) {
            BoundingBox pb = CapturedLocation.partBox(origin, rotation, part);
            if (clip != null && !pb.intersects(clip)) {
                continue;
            }
            StructureTemplate template = LocationTemplates.template(server, loc, part);
            if (template == null) {
                MurimMod.LOGGER.warn("Captured location {}: template {} is missing", loc.id(), part.template(loc.id()));
                ok = false;
                continue;
            }
            BlockPos at = CapturedLocation.world(origin, rotation, part.offset());
            StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation).setKnownShape(flags == 2);
            if (clip != null) {
                settings.setBoundingBox(clip);
            }
            template.placeInWorld(level, at, at, settings, random, flags);
        }
        footing(level, loc.box(origin, rotation), clip, flags);
        return ok;
    }

    /** Earth under every solid block of the bottom layer, down to the first solid ground. */
    static void footing(ServerLevelAccessor level, BoundingBox box, BoundingBox clip, int flags) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int y0 = box.minY();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                if (clip != null && (x < clip.minX() || x > clip.maxX() || z < clip.minZ() || z > clip.maxZ())) {
                    continue;
                }
                BlockState bottom = level.getBlockState(m.set(x, y0, z));
                if (bottom.isAir() || !bottom.isSolidRender(level, m)) {
                    continue;
                }
                BlockState fill = filler(bottom);
                for (int y = y0 - 1; y >= y0 - MAX_FOOTING && y > level.getMinBuildHeight(); y--) {
                    BlockState below = level.getBlockState(m.set(x, y, z));
                    if (!(below.isAir() || below.canBeReplaced() || !below.getFluidState().isEmpty()
                            || below.is(BlockTags.LEAVES) || below.is(BlockTags.LOGS))) {
                        break;
                    }
                    level.setBlock(m, fill, flags);
                }
            }
        }
    }

    private static BlockState filler(BlockState bottom) {
        if (bottom.is(BlockTags.DIRT) || !LocationCapture.terrain(bottom)) {
            return Blocks.DIRT.defaultBlockState();
        }
        return bottom;
    }
}
