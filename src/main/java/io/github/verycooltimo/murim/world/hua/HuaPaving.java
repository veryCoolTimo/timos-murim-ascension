package io.github.verycooltimo.murim.world.hua;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Surface of the sect's terraces (author 13.10: «пол не заменён на новые блоки»): the Hua ground family from
 * {@link HuaGround} instead of grass, dirt path and mossy cobble. Courtyards of the sect get small square pavers,
 * hall fronts long rectangular slabs, service yards flagstone, the training edges and the sparring square packed
 * earth. A worn rim and a few overgrown joints keep the «poor, run-down» sect of the plan.
 */
public final class HuaPaving {

    private HuaPaving() {
    }

    /** Top block of a terrace column; {@code edge} = blocks to the pad's border, {@code r} = hash 0..15, {@code path} = path noise. */
    public static BlockState surface(String zoneId, double edge, int r, double path) {
        BlockState main = main(zoneId);
        if (zoneId.equals("sparring")) {
            return r == 0 ? HuaGround.HUA_COURTYARD_PAVERS.get().defaultBlockState() : main;
        }
        if (edge < 1.0) {
            return r < 5 ? Blocks.GRASS_BLOCK.defaultBlockState() : HuaGround.HUA_PACKED_EARTH.get().defaultBlockState();
        }
        if (path > 0.55 && !main.is(HuaGround.HUA_PACKED_EARTH.get())) {
            return HuaGround.HUA_PACKED_EARTH.get().defaultBlockState();
        }
        if (r == 0) {
            return HuaGround.HUA_PACKED_EARTH.get().defaultBlockState();
        }
        if (r == 1 && edge < 4.0) {
            return Blocks.GRASS_BLOCK.defaultBlockState(); // overgrown joint near the rim
        }
        return main;
    }

    private static BlockState main(String zoneId) {
        return switch (zoneId) {
            case "training", "sect_gate", "mentor", "bell" -> HuaGround.HUA_COURTYARD_PAVERS.get().defaultBlockState();
            case "main_hall", "ancestors", "scriptures", "elders" -> HuaGround.HUA_RECTANGULAR_PAVING.get().defaultBlockState();
            case "camp", "poles", "sparring" -> HuaGround.HUA_PACKED_EARTH.get().defaultBlockState();
            default -> HuaGround.HUA_FLAGSTONE_PAVING.get().defaultBlockState(); // gate, treasury, dining, alchemy, dorms
        };
    }

    /** True for the Hua ground family (cubes, slabs, stairs, kerb): terrace cleanup must not paint grass over it. */
    public static boolean isPaving(BlockState state) {
        var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key.getNamespace().equals("murim") && key.getPath().startsWith("hua_")
                && (key.getPath().contains("paving") || key.getPath().contains("pavers") || key.getPath().contains("packed_earth")
                || key.getPath().contains("edge_stone") || key.getPath().contains("dry_stone"));
    }
}
