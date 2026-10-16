package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.registry.ModBlocks;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Conservative tree recognition, sampled before cleanup writes can split a trunk from its crown. */
final class HuaVegetation {
    private static final Set<String> TRUNKS = Set.of("minecraft:oak_log", "minecraft:spruce_log",
            "minecraft:birch_log", "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log",
            "minecraft:mangrove_log", "minecraft:cherry_log", "murim:plum_log");
    private final Function<BlockPos, BlockState> read;
    private final Map<BlockPos, Boolean> trees = new HashMap<>();

    HuaVegetation(Function<BlockPos, BlockState> read) { this.read = read; }

    /** Both leaf persistence values are generated here; PERSISTENT is not an ownership flag. */
    static boolean foliage(BlockState s) {
        return s.getBlock() instanceof LeavesBlock || s.is(Blocks.BAMBOO) || s.is(Blocks.VINE)
                || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN)
                || s.is(Blocks.LARGE_FERN) || s.is(Blocks.MOSS_CARPET) || s.is(Blocks.PINK_PETALS)
                || s.is(ModBlocks.CLIMBING_MOSS.get());
    }

    private static boolean trunk(BlockState s) {
        // Stripped timber, all-bark wood (including plum_wood columns), planks and beams stay builds.
        return TRUNKS.contains(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
    }

    boolean treeLog(BlockPos at) {
        if (!trunk(read.apply(at))) return false;
        Boolean known = trees.get(at);
        if (known != null) return known;
        Set<BlockPos> component = new HashSet<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        pending.add(at.immutable());
        boolean rooted = false, crown = false, building = false;
        while (!pending.isEmpty() && component.size() <= 512) {
            BlockPos p = pending.removeFirst();
            if (!component.add(p)) continue;
            BlockState state = read.apply(p);
            BlockState below = read.apply(p.below());
            boolean root = state.hasProperty(BlockStateProperties.AXIS)
                    && state.getValue(BlockStateProperties.AXIS) == Direction.Axis.Y && below.is(BlockTags.DIRT);
            rooted |= root;
            // The pad cleanup also removes old stumps under detached, persistent crowns.
            if (root && !crown) for (int y = 1; y <= 32; y++) {
                if (read.apply(p.above(y)).getBlock() instanceof LeavesBlock) { crown = true; break; }
            }
            for (Direction direction : Direction.values()) {
                BlockPos q = p.relative(direction);
                BlockState neighbour = read.apply(q);
                if (trunk(neighbour)) {
                    if (!component.contains(q)) pending.add(q);
                } else if (neighbour.getBlock() instanceof LeavesBlock) {
                    crown = true;
                } else if (!HuaRegrade.natural(neighbour)) {
                    building = true;
                }
            }
        }
        // Ambiguous or very large wooden structures are protected, even next to a real tree.
        boolean natural = pending.isEmpty() && component.size() <= 512 && rooted && crown && !building;
        for (BlockPos p : component) trees.put(p, natural);
        return natural;
    }
}
