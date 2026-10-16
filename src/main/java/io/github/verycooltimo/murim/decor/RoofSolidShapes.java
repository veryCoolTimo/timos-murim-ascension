package io.github.verycooltimo.murim.decor;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Inscribed 1px collision/occlusion columns of the actual solid OBJ, cached by model and rotation.
 * The render mesh remains a few long planar pieces. VoxelShape only supports axis-aligned boxes;
 * inscribing its columns avoids hiding a neighbour above the visible sloping surface.
 */
final class RoofSolidShapes {
    private static final Map<String, VoxelShape> MODELS = new ConcurrentHashMap<>();
    private static final Map<String, VoxelShape> COMBINATIONS = new ConcurrentHashMap<>();
    private static final Map<BlockState, VoxelShape> STATES = new ConcurrentHashMap<>();

    private RoofSolidShapes() {}

    static VoxelShape get(BlockState state) {
        return STATES.computeIfAbsent(state, RoofSolidShapes::route);
    }

    private static VoxelShape route(BlockState state) {
        String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        String[] rules = RoofSolidShapeData.ROUTES.get(block);
        if (rules == null) throw new IllegalArgumentException("No solid roof shape: " + block);
        List<String> models = new ArrayList<>();
        for (String rule : rules) {
            String[] parts = rule.split(";", -1);
            boolean matches = true;
            if (!parts[0].isEmpty()) {
                for (String condition : parts[0].split(",")) {
                    String[] pair = condition.split("=", 2);
                    Property<?> property = state.getBlock().getStateDefinition().getProperty(pair[0]);
                    if (property == null || !serialized(state, property).equals(pair[1])) {
                        matches = false;
                        break;
                    }
                }
            }
            if (matches) models.add(parts[1] + ";" + parts[2] + ";" + parts[3]);
        }
        if (models.isEmpty()) throw new IllegalArgumentException("Unrouted solid roof state: " + state);
        String key = String.join("|", models);
        return COMBINATIONS.computeIfAbsent(key, ignored -> {
            VoxelShape shape = Shapes.empty();
            for (String model : models) shape = Shapes.or(shape, MODELS.computeIfAbsent(model, RoofSolidShapes::build));
            return shape.optimize();
        });
    }

    private static <T extends Comparable<T>> String serialized(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static VoxelShape build(String key) {
        String[] parts = key.split(";");
        String[] data = RoofSolidShapeData.MODELS.get(parts[0]).split(":", 2);
        double floor = Double.parseDouble(data[0]);
        boolean flip = Integer.parseInt(parts[1]) == 180;
        int turns = Integer.parseInt(parts[2]) / 90;
        VoxelShape shape = Shapes.empty();
        boolean[] used = new boolean[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                if (used[z * 16 + x]) continue;
                int value = height(data[1], x, z);
                double top = value / 16.0;
                if (top <= floor) continue;
                int endX = x + 1;
                while (endX < 16 && !used[z * 16 + endX] && height(data[1], endX, z) == value) endX++;
                int endZ = z + 1;
                boolean extend = true;
                while (endZ < 16 && extend) {
                    for (int xx = x; xx < endX; xx++) {
                        if (used[endZ * 16 + xx] || height(data[1], xx, endZ) != value) {
                            extend = false;
                            break;
                        }
                    }
                    if (extend) endZ++;
                }
                for (int zz = z; zz < endZ; zz++) {
                    for (int xx = x; xx < endX; xx++) used[zz * 16 + xx] = true;
                }
                double x0 = x, x1 = endX, z0 = z, z1 = endZ;
                double y0 = floor, y1 = top;
                if (flip) {
                    y0 = 16 - top;
                    y1 = 16 - floor;
                    z0 = 16 - endZ;
                    z1 = 16 - z;
                }
                for (int turn = 0; turn < turns; turn++) {
                    double nx0 = 16 - z1, nx1 = 16 - z0;
                    z0 = x0;
                    z1 = x1;
                    x0 = nx0;
                    x1 = nx1;
                }
                shape = Shapes.or(shape, Block.box(x0, y0, z0, x1, y1, z1));
            }
        }
        return shape.optimize();
    }

    private static int height(String data, int x, int z) {
        int offset = (z * 16 + x) * 3;
        return Integer.parseInt(data.substring(offset, offset + 3), 16);
    }
}
