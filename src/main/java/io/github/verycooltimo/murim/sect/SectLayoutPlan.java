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
    }

    // North is the approach from the trail. Side wings face their own courtyards.
    // The camp outline contains its separately listed dorms, dining rooms and pole yard.
    public static final List<Box> BOXES = List.of(
            new Box("gate", "gate", 0, 0, 24, 8, Kind.GATE, Direction.NORTH),
            new Box("sect_gate", "sect_gate", 0, 0, 24, 6, Kind.GATE, Direction.NORTH),
            new Box("pav_north", "pav_north", 0, 0, 10, 8, Kind.PAVILION, Direction.NORTH),
            new Box("pav_west", "pav_west", 0, 0, 10, 8, Kind.PAVILION, Direction.EAST),
            new Box("pav_south", "pav_south", 0, 0, 12, 10, Kind.PAVILION, Direction.NORTH),
            new Box("pav_east", "pav_east", 0, 0, 8, 6, Kind.PAVILION, Direction.WEST),
            new Box("training", "training", 0, 0, 48, 32, Kind.OPEN, Direction.NORTH),
            new Box("bell", "bell", 0, 0, 7, 7, Kind.TOWER, Direction.EAST),
            new Box("treasury_office", "treasury", -5, 0, 10, 12, Kind.HALL, Direction.WEST),
            new Box("treasury_store", "treasury", 6, 0, 8, 12, Kind.HOUSE, Direction.NORTH),
            new Box("mentor", "mentor", 0, 0, 8, 6, Kind.OPEN, Direction.NORTH),
            new Box("sparring", "sparring", 0, 0, 28, 20, Kind.OPEN, Direction.NORTH),
            new Box("dining_hall", "dining", -4, 0, 12, 8, Kind.HALL, Direction.WEST),
            new Box("dining_kitchen", "dining", 7, 0, 6, 8, Kind.HALL, Direction.WEST),
            new Box("alchemy", "alchemy", 0, 0, 12, 8, Kind.PAVILION, Direction.NORTH),
            new Box("camp", "camp", 0, 0, 38, 48, Kind.OPEN, Direction.NORTH),
            new Box("dorm_2nd", "dorm_2nd", 0, 0, 12, 16, Kind.HOUSE, Direction.EAST),
            new Box("dorm_3rd", "dorm_3rd", 0, 0, 14, 20, Kind.HOUSE, Direction.EAST),
            new Box("poles", "poles", 0, 0, 12, 10, Kind.OPEN, Direction.NORTH),
            new Box("main_hall", "main_hall", 0, 0, 46, 18, Kind.MAIN_HALL, Direction.NORTH),
            new Box("ancestors", "ancestors", 0, 0, 34, 12, Kind.ANCESTORS, Direction.NORTH),
            new Box("scriptures", "scriptures", 0, 0, 16, 14, Kind.SCRIPTURES, Direction.NORTH),
            new Box("elder_1", "elders", -12, 2, 10, 12, Kind.HOUSE, Direction.NORTH),
            new Box("elder_2", "elders", 0, 2, 10, 12, Kind.HOUSE, Direction.NORTH),
            new Box("elder_3", "elders", 12, 2, 10, 12, Kind.HOUSE, Direction.NORTH),
            new Box("elder_yard_1", "elders", -12, -7, 10, 4, Kind.OPEN, Direction.NORTH),
            new Box("elder_yard_2", "elders", 0, -7, 10, 4, Kind.OPEN, Direction.NORTH),
            new Box("elder_yard_3", "elders", 12, -7, 10, 4, Kind.OPEN, Direction.NORTH),
            new Box("grove", "grove", 0, 0, 26, 20, Kind.OPEN, Direction.NORTH),
            new Box("vault", "vault", 0, 0, 14, 12, Kind.HIDDEN, Direction.WEST),
            new Box("penance", "penance", 0, 0, 10, 10, Kind.HIDDEN, Direction.SOUTH));
}
