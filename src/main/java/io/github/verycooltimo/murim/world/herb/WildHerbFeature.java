package io.github.verycooltimo.murim.world.herb;

import io.github.verycooltimo.murim.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/** Rarity and biome selection live in placed features/NeoForge modifiers, after vanilla trees.
 * Samples suitable positions within the owner chunk, choosing one without an edge bias.
 * API: MountHuaFeature#place and vanilla FeaturePlaceContext/Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.
 */
public final class WildHerbFeature extends Feature<NoneFeatureConfiguration> {
    private final boolean lingzhi;

    public WildHerbFeature(boolean lingzhi) {
        super(NoneFeatureConfiguration.CODEC);
        this.lingzhi = lingzhi;
    }

    /** Worldgen's ten equally likely age slots; exposed for deterministic registry/rarity checks. */
    public static BlockState ginsengPlant(int roll) {
        return roll == 0 ? ModBlocks.THOUSAND_GINSENG_PLANT.get().defaultBlockState()
                : ModBlocks.HUNDRED_GINSENG_PLANT.get().defaultBlockState();
    }

    @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        ChunkPos chunk = new ChunkPos(context.origin());
        BlockPos selected = null;
        BlockState chosen = null;
        int candidates = 0;
        // One-block margin keeps log attachments and neighbour reads inside this chunk.
        for (int x = chunk.getMinBlockX() + 1; x < chunk.getMaxBlockX(); x++) {
            for (int z = chunk.getMinBlockZ() + 1; z < chunk.getMaxBlockZ(); z++) {
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (lingzhi) {
                    // Heightmap may end at the top of a trunk; search down to its forest floor.
                    for (int y = Math.max(level.getMinBuildHeight() + 1, top - 32); y <= top; y++) {
                        BlockPos log = new BlockPos(x, y, z);
                        BlockState wood = level.getBlockState(log);
                        if (!wood.is(Blocks.DARK_OAK_LOG) && !wood.is(Blocks.SPRUCE_LOG)) continue;
                        for (Direction d : Direction.Plane.HORIZONTAL) {
                            BlockPos at = log.relative(d);
                            if (!level.getBlockState(at).isAir()) continue;
                            BlockState plant = ModBlocks.THOUSAND_LINGZHI_PLANT.get().defaultBlockState()
                                    .setValue(HerbPlantBlock.FACING, d.getOpposite());
                            if (plant.canSurvive(level, at) && context.random().nextInt(++candidates) == 0) {
                                selected = at;
                                chosen = plant;
                            }
                        }
                    }
                } else {
                    BlockPos at = new BlockPos(x, top, z);
                    if (!level.getBlockState(at).isAir()) continue;
                    BlockState plant = ModBlocks.THOUSAND_GINSENG_PLANT.get().defaultBlockState();
                    if (!plant.canSurvive(level, at)) continue;
                    boolean shade = false;
                    for (int dy = 1; dy <= 14 && at.getY() + dy < level.getMaxBuildHeight(); dy++) {
                        if (level.getBlockState(at.above(dy)).is(BlockTags.LEAVES)) { shade = true; break; }
                    }
                    if (shade && context.random().nextInt(++candidates) == 0) {
                        selected = at;
                        chosen = plant;
                    }
                }
            }
        }
        if (selected == null) return false;
        // Roll once after reservoir selection: nine young plants per ancient root on average.
        // API: Minecraft 1.21.1 RandomSource#nextInt(int), Block#defaultBlockState.
        if (!lingzhi) chosen = ginsengPlant(context.random().nextInt(10));
        return level.setBlock(selected, chosen, 2);
    }
}
