package io.github.verycooltimo.murim.world;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Узел ци — простая мировая фича (docs/design/19b §3, автор 03.10: «пока простая»).
 *
 * <p>Вид выбирается по месту, простыми правилами: высота ≥ 110 — пик; вода в 4 блоках — водный;
 * листва над головой — лесной; иначе — алтарь с кольцом из каменных кирпичей. Редкость —
 * в placed feature (rarity_filter), здесь только постановка.
 * API: reference/minecraft-src/net/minecraft/world/level/levelgen/feature/Feature.java#place
 */
public class QiNodeFeature extends Feature<NoneFeatureConfiguration> {

    public static final int PEAK_HEIGHT = 110;

    public QiNodeFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());
        BlockPos pos = new BlockPos(origin.getX(), y, origin.getZ());
        BlockState ground = level.getBlockState(pos.below());
        if (!ground.isSolid() || !level.getFluidState(pos).isEmpty() || !level.getBlockState(pos).canBeReplaced()) {
            return false;
        }
        PlaceKind kind = classify(level, pos);
        if (kind == PlaceKind.ALTAR) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos p = pos.offset(dx, -1, dz);
                    if (level.getBlockState(p).isSolid()) {
                        level.setBlock(p, (dx + dz) % 2 == 0 ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState()
                                : Blocks.STONE_BRICKS.defaultBlockState(), 2);
                    }
                }
            }
            level.setBlock(pos.below(), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 2);
        }
        level.setBlock(pos, ModWorld.SPIRIT_VEIN.get().defaultBlockState().setValue(SpiritVeinBlock.KIND, kind), 2);
        return true;
    }

    static PlaceKind classify(WorldGenLevel level, BlockPos pos) {
        if (pos.getY() >= PEAK_HEIGHT) {
            return PlaceKind.PEAK;
        }
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-4, -2, -4), pos.offset(4, 1, 4))) {
            if (level.getFluidState(p).is(FluidTags.WATER)) {
                return PlaceKind.WATER;
            }
        }
        for (int dy = 2; dy <= 10; dy++) {
            if (level.getBlockState(pos.above(dy)).is(BlockTags.LEAVES)) {
                return PlaceKind.FOREST;
            }
        }
        return PlaceKind.ALTAR;
    }
}
