package io.github.verycooltimo.murim.world.hua;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Surface of the sect's terraces (author 13.10: «пол не заменён на новые блоки»): the Hua ground family from
 * {@link HuaGround} instead of grass, dirt path and mossy cobble. Courtyards of the sect get small square pavers,
 * hall fronts long rectangular slabs, service yards flagstone, the arch approach rectangular paving and the camp yard packed
 * earth. A clean stone rim and a few worn joints keep the «poor, run-down» sect of the plan.
 */
public final class HuaPaving {

    private HuaPaving() {
    }

    /** Top block of a terrace column; {@code edge} = blocks to the pad's border, {@code r} = hash 0..15, {@code path} = path noise. */
    public static BlockState surface(String zoneId, double edge, int r, double path) {
        BlockState main = main(zoneId);
        // Two whole rows on the pad side of the retaining wall stay clean and continuous.
        if (edge < 2.0) {
            return HuaGround.HUA_EDGE_STONE.get().defaultBlockState();
        }
        // A little wear in sheltered joints; building pads should still read as paved terraces.
        if (!main.is(HuaGround.HUA_PACKED_EARTH.get()) && r == 0 && path > 0.35) {
            return HuaGround.HUA_PACKED_EARTH.get().defaultBlockState();
        }
        return main;
    }

    private static BlockState main(String zoneId) {
        return switch (zoneId) {
            case "training", "sect_gate", "mentor", "bell" -> HuaGround.HUA_COURTYARD_PAVERS.get().defaultBlockState();
            case "arch", "main_hall", "ancestors", "scriptures", "elders" -> HuaGround.HUA_RECTANGULAR_PAVING.get().defaultBlockState();
            case "camp", "poles" -> HuaGround.HUA_PACKED_EARTH.get().defaultBlockState();
            default -> HuaGround.HUA_FLAGSTONE_PAVING.get().defaultBlockState(); // gate, treasury, dining, alchemy, dorms
        };
    }

    /** True for the Hua ground family (cubes, slabs, stairs, kerb): terrace cleanup must not paint grass over it. */
    public static boolean isPaving(BlockState state) {
        var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key.getNamespace().equals("murim") && key.getPath().startsWith("hua_")
                && (key.getPath().contains("paving") || key.getPath().contains("pavers") || key.getPath().contains("packed_earth")
                || key.getPath().contains("edge_stone") || key.getPath().contains("_layer") || key.getPath().contains("dry_stone"));
    }
}
