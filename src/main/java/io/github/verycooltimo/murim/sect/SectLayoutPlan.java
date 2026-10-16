package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.world.hua.MountHuaPlan;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Plain building footprints inside the agreed 04.10 terraces; no roofs or architectural models.
 * tools/art/sect_layout_map.py reads these literals and MountHuaPlan.ZONES directly. */
public final class SectLayoutPlan {
    private SectLayoutPlan() {
    }

    public enum Kind {
        MAIN_HALL(8, Blocks.WHITE_CONCRETE.defaultBlockState()),
        ANCESTORS(7, Blocks.WHITE_CONCRETE.defaultBlockState()),
        HALL(5, Blocks.WHITE_CONCRETE.defaultBlockState()),
        TOWER(12, Blocks.WHITE_CONCRETE.defaultBlockState()),
        SCRIPTURES(7, Blocks.WHITE_CONCRETE.defaultBlockState()),
        HOUSE(4, Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState()),
        PAVILION(4, Blocks.YELLOW_CONCRETE.defaultBlockState()),
        GATE(6, Blocks.RED_CONCRETE.defaultBlockState()),
        ARCH(5, Blocks.RED_CONCRETE.defaultBlockState()),
        OPEN(1, Blocks.LIME_CONCRETE.defaultBlockState()),
        HIDDEN(1, Blocks.BLACK_CONCRETE.defaultBlockState());

        private final int height;
        private final BlockState state;

        Kind(int height, BlockState state) {
            this.height = height;
            this.state = state;
        }

        public int height() {
            return height;
        }

        public BlockState state() {
            return state;
        }

        public boolean outlineOnly() {
            return this == OPEN || this == HIDDEN;
        }
    }

    public static final int EAVE_OVERHANG = 2;
    public static final int WALK_MARGIN = 3;
    public static final int PAD_MARGIN = EAVE_OVERHANG + WALK_MARGIN;

    /** du/dv are offsets from the zone centre; facing is in the mountain's LOCAL frame. */
    public record Box(String id, String zoneId, int du, int dv, int width, int depth, Kind kind, Direction facing) {
        public MountHuaPlan.Zone zone() {
            return MountHuaPlan.ZONES.stream().filter(z -> z.id().equals(zoneId)).findFirst().orElseThrow();
        }

        public int minU() {
            return (int) Math.floor(zone().u() + du - width / 2.0);
        }

        public int minV() {
            return (int) Math.floor(zone().v() + dv - depth / 2.0);
        }

        /** Exclusive maximum bounds, including the roof and clear space beyond it. */
        public int[] requiredPad() {
            int margin = padMargin();
            return new int[] {minU() - margin, minV() - margin,
                    minU() + width + margin, minV() + depth + margin};
        }

        /** Fixed author pads: two cells around the gate, one in front of the arch; no extra walking strip. */
        public int padMargin() {
            return kind.outlineOnly() ? 0 : id.equals("sect_gate") ? EAVE_OVERHANG
                    : id.equals("arch") ? 1 : PAD_MARGIN;
        }

        /** Two-block wide, three-block high entrance; ground outline remains continuous. */
        public boolean door(int x, int z, int y) {
            if (kind.outlineOnly() || y < 1 || y > 3) {
                return false;
            }
            int midX = (width - 2) / 2;
            int midZ = (depth - 2) / 2;
            return switch (facing) {
                case NORTH -> z == 0 && x >= midX && x < midX + 2;
                case SOUTH -> z == depth - 1 && x >= midX && x < midX + 2;
                case WEST -> x == 0 && z >= midZ && z < midZ + 2;
                case EAST -> x == width - 1 && z >= midZ && z < midZ + 2;
                default -> false;
            };
        }

        /** Arch = two slender posts and a lintel; buildings = perimeter with entrances. */
        public boolean marker(int x, int z, int y) {
            if (kind == Kind.ARCH) {
                return x == 0 || x == width - 1 || y == kind.height() - 1;
            }
            boolean perimeter = x == 0 || x == width - 1 || z == 0 || z == depth - 1;
            // Rear-east furnace room in the alchemist's hall, with a two-wide inner door.
            boolean furnaceRoom = id.equals("alchemy") && kind == Kind.HALL
                    && (x == 9 && z >= 6 || z == 6 && x >= 9)
                    && !(z == 6 && x >= 11 && x <= 12 && y >= 1 && y <= 3);
            if ((!perimeter && !furnaceRoom) || door(x, z, y)) {
                return false;
            }
            // A rear exit, aligned with the four-wide walk up to the ancestors.
            return !(kind == Kind.MAIN_HALL && z == depth - 1 && y >= 1 && y <= 3
                    && x >= (width - 4) / 2 && x < (width - 4) / 2 + 4);
        }
    }

    // North is the approach from the trail. Side wings face their own courtyards.
    // The housing camp contains the two dorms and their east courtyard; practice is on training.
    public static final List<Box> BOXES = List.of(
            new Box("gate", "gate", 0, 0, 24, 8, Kind.GATE, Direction.NORTH),
            new Box("sect_gate", "sect_gate", 0, 0, 24, 6, Kind.GATE, Direction.NORTH),
            new Box("pav_north", "pav_north", 0, 0, 10, 8, Kind.PAVILION, Direction.NORTH),
            new Box("pav_west", "pav_west", 0, 0, 10, 8, Kind.PAVILION, Direction.EAST),
            new Box("pav_south", "pav_south", 0, 0, 12, 10, Kind.PAVILION, Direction.NORTH),
            new Box("pav_east", "pav_east", 0, 0, 8, 6, Kind.PAVILION, Direction.WEST),
            new Box("training", "training", 0, 0, 48, 47, Kind.OPEN, Direction.NORTH),
            new Box("bell", "bell", 0, 0, 7, 7, Kind.TOWER, Direction.EAST),
            // One building (author 15.10): the finance hall with the treasury vault in its basement.
            new Box("treasury", "treasury", 0, 0, 18, 12, Kind.HALL, Direction.WEST),
            new Box("mentor", "mentor", 0, 0, 6, 8, Kind.OPEN, Direction.EAST),
            new Box("arch", "arch", 0, 0, 5, 2, Kind.ARCH, Direction.NORTH),
            new Box("dining_hall", "dining", -4, 0, 12, 8, Kind.HALL, Direction.WEST),
            new Box("dining_kitchen", "dining", 8, 0, 6, 8, Kind.HALL, Direction.WEST),
            new Box("alchemy", "alchemy", 0, 0, 16, 12, Kind.HALL, Direction.NORTH),
            new Box("camp", "camp", 0, 0, 40, 64, Kind.OPEN, Direction.NORTH),
            new Box("dorm_2nd", "dorm_2nd", 0, 0, 20, 20, Kind.HOUSE, Direction.EAST),
            new Box("dorm_3rd", "dorm_3rd", 0, 0, 20, 22, Kind.HOUSE, Direction.EAST),
            new Box("poles", "poles", 0, 0, 12, 10, Kind.OPEN, Direction.NORTH),
            new Box("main_hall", "main_hall", 0, 0, 50, 21, Kind.MAIN_HALL, Direction.NORTH),
            new Box("hall_court", "main_hall", 0, -13, 46, 5, Kind.OPEN, Direction.NORTH),
            new Box("ancestors", "ancestors", 0, 0, 34, 12, Kind.ANCESTORS, Direction.NORTH),
            new Box("scriptures", "scriptures", 0, 0, 16, 14, Kind.SCRIPTURES, Direction.NORTH),
            new Box("elder_1", "elders", -15, 1, 10, 12, Kind.HOUSE, Direction.NORTH),
            new Box("elder_2", "elders", 0, 1, 10, 12, Kind.HOUSE, Direction.NORTH),
            new Box("elder_3", "elders", 15, 1, 10, 12, Kind.HOUSE, Direction.NORTH),
            new Box("elder_yard_1", "elders", -15, -9, 10, 8, Kind.OPEN, Direction.NORTH),
            new Box("elder_yard_2", "elders", 0, -9, 10, 8, Kind.OPEN, Direction.NORTH),
            new Box("elder_yard_3", "elders", 15, -9, 10, 8, Kind.OPEN, Direction.NORTH),
            new Box("grove", "grove", 0, 0, 26, 20, Kind.OPEN, Direction.NORTH),
            new Box("vault", "vault", 0, 0, 14, 12, Kind.HIDDEN, Direction.WEST),
            new Box("penance", "penance", 0, 0, 10, 10, Kind.HIDDEN, Direction.SOUTH));

    /** Walkable corridors use the actual graded worldgen paths, including their stair heights. */
    public record Corridor(String id, int firstPath, int pathCount) {
        public int width(int index) {
            return MountHuaPlan.pathWidth(segment(index));
        }

        public double[] segment(int index) {
            return MountHuaPlan.PATHS[firstPath + index];
        }
    }

    public static final List<Corridor> CORRIDORS = List.of(new Corridor("ancestors_path",
            MountHuaPlan.ANCESTORS_PATH_FIRST, MountHuaPlan.ANCESTORS_PATH_COUNT));
}
