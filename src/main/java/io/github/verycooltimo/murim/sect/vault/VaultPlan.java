package io.github.verycooltimo.murim.sect.vault;

import io.github.verycooltimo.murim.sect.seal.SealRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The main vault of the head of Mount Hua, as blocks, in the mountain's local frame (design doc 46; author 21.10 and 23.10; refs
 * docs/design/reference/vault-1021/). {@code u} is east and {@code v} south at rotation 0, {@code y} is relative to the site's
 * foot height: the frame of the baked sect, so {@link HuaVault} puts it into a world with the sect's own turn and shift.
 *
 * <pre>
 *  hall   hatch (u 7..10, v 88..91, y 176) beside the shrine
 *  shaft  flight 1: 14 steps west under the hall, landing, flight 2: 15 steps east
 *  tunnel vaulted: 6 wide, walls 5 high, then stair and brick tiers to a crown 8 high; 27 long (u 12..17, v 96..66)
 *  door   cold iron, 6 wide, a round arch 7 high, plane v = 64 in a wall 3 thick
 *  study  20 x 13 x 7 (u 5..24, v 50..62): a library as the Scripture Hall: three shelves on the back wall (the middle one stands over the hole)
 *  pit    18 x 15 x 5 under it (u 6..23, v 44..58): sword stands, a chest on a dais, two heaps of gold, entered through the hole under the middle shelf
 * </pre>
 * The shaft and the tunnel are lined with sealed stone brick; the door wall, the study and the pit are wrapped by three layers: a
 * lining and two of {@link VaultRegistry#VAULT_IRON}. All of it is unbreakable and blast-proof.
 */
public final class VaultPlan {

    public static final int HALL_FLOOR = 176;
    public static final int HATCH_U0 = 7, HATCH_U1 = 10, LANE_A_V0 = 88, LANE_A_V1 = 91;
    public static final int LANE_B_V0 = 93, LANE_B_V1 = 96;
    /** Tunnel: west and east edge (6 wide), floor block y, from the second landing (v = 96) to the door recess (v = 66). */
    public static final int TUNNEL_U0 = 12, TUNNEL_U1 = 17, TUNNEL_FLOOR = 146, TUNNEL_V_START = 92, TUNNEL_V_END = 66;
    /** Tunnel crown: the highest interior cell. */
    public static final int CROWN = TUNNEL_FLOOR + 8;
    public static final int DOOR_V = 64;
    public static final int STUDY_U0 = 5, STUDY_U1 = 24, STUDY_V0 = 50, STUDY_V1 = 62;
    public static final int STUDY_TOP = TUNNEL_FLOOR + 7;
    public static final int PIT_U0 = 6, PIT_U1 = 23, PIT_V0 = 44, PIT_V1 = 58, PIT_FLOOR = 139;
    /** The three library shelves at the back wall: 4 wide, 4 high (two pairs of scroll shelf); the middle one stands over the hole. */
    public static final int SHELF_V = 50, SHELF_W = 4, SHELF_DEPTH = 2;
    public static final int MIDDLE_U = 6;
    /** Where the pit is entered: the hole in the floor under the middle shelf (dig the shelf and it shows), and the ladder in it. */
    public static final int LADDER_U = 6, LADDER_V = 50;
    /** The loot chest: the top-left corner of the pit, as in the ref (author 26.10). */
    public static final BlockPos STUDY_CHEST = new BlockPos(8, TUNNEL_FLOOR + 1, 61);
    public static final BlockPos CHEST = new BlockPos(7, PIT_FLOOR + 1, PIT_V0);
    /** The one ring of inlay on the floor round the treasure (plan cells), and the cell of the hero sword's pedestal. */
    public static final int RING_U0 = 6, RING_U1 = 23, RING_V0 = 45, RING_V1 = 57, HERO_U = 10, HERO_V = 56;
    /** The spears and swords lean on the west wall of the pit (the left wall of the ref), one prop block each. */
    public static final BlockPos ARMS = new BlockPos(PIT_U0, PIT_FLOOR + 1, 45);
    /** The tablets of the founders' rite: {u, order}; they stand on the altars at v = 92, y = 178. */
    public static final int[][] TABLETS = {{1, 1}, {10, 2}, {0, 3}, {11, 4}};
    public static final int TABLET_V = 92, TABLET_Y = 178;
    public static final BlockPos VERSE = new BlockPos(4, 177, 91);
    public static final BlockPos TRIAL_SPOT = new BlockPos(TUNNEL_U0 - 1, 150, 69);

    public enum Kind { CHEST, SWORD_STAND, CLUE_VERSE, ORIGINAL, LETTER }

    /** A block that needs more than a state: a chest with its loot, a stand with its swords, a clue spot. */
    public record Special(Kind kind, BlockPos local, BlockState state, String data) {
    }

    /** The whole plan. */
    public static final class Plan {
        public final Map<Long, BlockState> blocks = new HashMap<>();
        public final List<Special> specials = new ArrayList<>();
        public final Set<Long> approach = new HashSet<>();
        public final Set<Long> suite = new HashSet<>();
        public BoundingBox bounds;
    }

    /** Door cells {col, row from the bottom}: a round arch of radius three cells on four straight rows (the cells with 96 of 256 px inside the circle). */
    private static final int[][] DOOR = doorTable();
    private static final Plan PLAN = build();

    private VaultPlan() {
    }

    public static Plan get() {
        return PLAN;
    }

    private static int[][] doorTable() {
        List<int[]> out = new ArrayList<>();
        for (int rt = 0; rt < 7; rt++) {
            for (int c = 0; c < 6; c++) {
                int n = 0;
                for (int y = rt * 16; y < rt * 16 + 16; y++) {
                    for (int x = c * 16; x < c * 16 + 16; x++) {
                        if (y >= 48 || (x + 0.5 - 48) * (x + 0.5 - 48) + (y + 0.5 - 48) * (y + 0.5 - 48) <= 48.0 * 48.0) {
                            n++;
                        }
                    }
                }
                if (n >= 96) {
                    out.add(new int[] {c, 6 - rt});
                }
            }
        }
        return out.toArray(new int[0][]);
    }

    private static long key(int u, int y, int v) {
        return BlockPos.asLong(u, y, v);
    }

    // ------------------------------------------------------------------ states

    private static BlockState stairs(Direction ascends) {
        return VaultRegistry.VAULT_BRICK_STAIRS.get().defaultBlockState().setValue(StairBlock.FACING, ascends);
    }

    private static BlockState chamfer(Direction towardWall) {
        return VaultRegistry.VAULT_BRICK_STAIRS.get().defaultBlockState().setValue(StairBlock.FACING, towardWall)
                .setValue(StairBlock.HALF, Half.TOP);
    }

    private static BlockState slab() {
        return VaultRegistry.VAULT_BRICK_SLAB.get().defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
    }

    private static BlockState wall() {
        return VaultRegistry.VAULT_BRICK.get().defaultBlockState();
    }

    /** The lighter stone of the arch trim and of the ribs of the tunnel. */
    private static BlockState rib() {
        return VaultRegistry.VAULT_BRICK_TRIM.get().defaultBlockState();
    }

    private static BlockState iron() {
        return VaultRegistry.VAULT_IRON.get().defaultBlockState();
    }

    private static BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }

    private static BlockState lantern(boolean hanging) {
        return Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, hanging);
    }

    /** Rugs show their trim only on sides without a rug: set the flags the way a placement would, since no shape update runs. */
    private static void connectRugs(Plan p) {
        Map<Long, BlockState> out = new HashMap<>();
        for (Map.Entry<Long, BlockState> e : p.blocks.entrySet()) {
            BlockState s = e.getValue();
            if (s.getBlock() instanceof io.github.verycooltimo.murim.decor.StoRugBlock rug) {
                long k = e.getKey();
                int u = BlockPos.getX(k), y = BlockPos.getY(k), v = BlockPos.getZ(k);
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockState n = p.blocks.get(key(u + d.getStepX(), y, v + d.getStepZ()));
                    boolean same = n != null && n.is(rug);
                    s = s.setValue(d == Direction.NORTH ? io.github.verycooltimo.murim.decor.StoRugBlock.NORTH : d == Direction.EAST ? io.github.verycooltimo.murim.decor.StoRugBlock.EAST
                            : d == Direction.SOUTH ? io.github.verycooltimo.murim.decor.StoRugBlock.SOUTH : io.github.verycooltimo.murim.decor.StoRugBlock.WEST, same);
                }
                out.put(k, s);
            }
        }
        p.blocks.putAll(out);
    }

    private static BlockState treasure(int kind) {
        return VaultRegistry.VAULT_TREASURE.get().defaultBlockState().setValue(VaultTreasureBlock.KIND, kind);
    }

    private static BlockState decor(int kind, Direction facing) {
        return VaultDecorBlock.of(VaultRegistry.VAULT_DECOR.get(), kind, facing);
    }

    private static BlockState prop(int kind, Direction facing) {
        return VaultRegistry.VAULT_PROP.get().defaultBlockState().setValue(VaultPropBlock.KIND, kind).setValue(VaultPropBlock.FACING, facing);
    }

    private static BlockState floor(boolean hatch) {
        return VaultRegistry.VAULT_FLOOR.get().defaultBlockState().setValue(VaultFloorBlock.HATCH, hatch);
    }

    // ------------------------------------------------------------------ builder

    private static final class Builder {
        final Plan plan = new Plan();

        void put(int u, int y, int v, BlockState s) {
            plan.blocks.put(key(u, y, v), s);
        }

        void fill(int u0, int u1, int v0, int v1, int y0, int y1, BlockState s) {
            for (int u = u0; u <= u1; u++) {
                for (int v = v0; v <= v1; v++) {
                    for (int y = y0; y <= y1; y++) {
                        put(u, y, v, s);
                    }
                }
            }
        }

        void interior(Set<Long> into, int u0, int u1, int v0, int v1, int y0, int y1) {
            for (int u = u0; u <= u1; u++) {
                for (int v = v0; v <= v1; v++) {
                    for (int y = y0; y <= y1; y++) {
                        into.add(key(u, y, v));
                    }
                }
            }
        }
    }

    private static Plan build() {
        Builder b = new Builder();
        Plan p = b.plan;

        // ---- the hollow volumes
        for (int u = 10; u >= -3; u--) {
            int s = 165 + u;
            b.interior(p.approach, u, u, LANE_A_V0, LANE_A_V1, s + 1, Math.min(s + 5, HALL_FLOOR - 1));
        }
        b.interior(p.approach, -8, -4, LANE_A_V0, LANE_B_V1, 162, 168);
        for (int u = -3; u <= 11; u++) {
            int s = 158 - u;
            b.interior(p.approach, u, u, LANE_B_V0, LANE_B_V1, s + 1, s + 5);
        }
        b.interior(p.approach, TUNNEL_U0, TUNNEL_U1, LANE_B_V0, LANE_B_V1, TUNNEL_FLOOR + 1, CROWN);
        b.interior(p.approach, TUNNEL_U0, TUNNEL_U1, TUNNEL_V_END, TUNNEL_V_START, TUNNEL_FLOOR + 1, CROWN);
        // door recess: the cells of the arch, three deep
        for (int v = DOOR_V - 1; v <= DOOR_V + 1; v++) {
            for (int[] d : DOOR) {
                p.suite.add(key(TUNNEL_U0 + d[0], TUNNEL_FLOOR + 1 + d[1], v));
            }
        }
        b.interior(p.suite, STUDY_U0, STUDY_U1, STUDY_V0, STUDY_V1, TUNNEL_FLOOR + 1, STUDY_TOP);
        b.interior(p.suite, PIT_U0, PIT_U1, PIT_V0, PIT_V1, PIT_FLOOR + 1, PIT_FLOOR + 5);

        // ---- shells: bricks round the approach, lining + two iron layers round the suite
        Set<Long> all = new HashSet<>(p.approach);
        all.addAll(p.suite);
        for (long c : p.approach) {
            for (int du = -1; du <= 1; du++) {
                for (int dv = -1; dv <= 1; dv++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        long k = key(BlockPos.getX(c) + du, BlockPos.getY(c) + dy, BlockPos.getZ(c) + dv);
                        if (!all.contains(k) && BlockPos.getY(k) < HALL_FLOOR && !p.blocks.containsKey(k)) {
                            p.blocks.put(k, wall());
                        }
                    }
                }
            }
        }
        Set<Long> ring = new HashSet<>();
        for (long c : p.suite) {
            int cu = BlockPos.getX(c), cy = BlockPos.getY(c), cv = BlockPos.getZ(c);
            for (int du = -3; du <= 3; du++) {
                for (int dv = -3; dv <= 3; dv++) {
                    for (int dy = -3; dy <= 3; dy++) {
                        long k = key(cu + du, cy + dy, cv + dv);
                        if (!all.contains(k) && BlockPos.getY(k) < HALL_FLOOR) {
                            ring.add(k);
                        }
                    }
                }
            }
        }
        for (long k : ring) {
            p.blocks.put(k, iron());
        }
        for (long c : p.approach) {
            if (BlockPos.getY(c) == HALL_FLOOR - 1) {
                p.blocks.put(key(BlockPos.getX(c), HALL_FLOOR, BlockPos.getZ(c)), floor(false));
            }
        }
        for (long c : all) {
            p.blocks.put(c, air());
        }
        for (int u = HATCH_U0; u <= HATCH_U1; u++) {
            for (int v = LANE_A_V0; v <= LANE_A_V1; v++) {
                p.blocks.put(key(u, HALL_FLOOR, v), floor(true));
            }
        }
        p.blocks.put(key(9, HALL_FLOOR + 1, 90), air());
        p.blocks.put(key(10, HALL_FLOOR + 1, 90), air());

        shaft(b);
        tunnel(b);
        door(b);
        study(b);
        pit(b);
        hole(b);
        // The memorial stone with the verse (a clue block, "ancestors_verse") beside the shrine is no longer placed: the author wants no quest marker
        // block anywhere in the sect (26.10). The rite itself (VaultMechanism, the four lock tablets) is unchanged.

        connectRugs(p);

        int u0 = Integer.MAX_VALUE, u1 = Integer.MIN_VALUE, v0 = Integer.MAX_VALUE, v1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
        for (long k : p.blocks.keySet()) {
            u0 = Math.min(u0, BlockPos.getX(k));
            u1 = Math.max(u1, BlockPos.getX(k));
            v0 = Math.min(v0, BlockPos.getZ(k));
            v1 = Math.max(v1, BlockPos.getZ(k));
            y0 = Math.min(y0, BlockPos.getY(k));
            y1 = Math.max(y1, BlockPos.getY(k));
        }
        p.bounds = new BoundingBox(u0, y0, v0, u1, y1, v1);
        return p;
    }

    // ------------------------------------------------------------------ shaft

    private static void shaft(Builder b) {
        b.fill(-8, -4, LANE_A_V0, LANE_B_V1, 161, 161, wall());
        for (int u = 10; u >= -3; u--) {
            for (int v = LANE_A_V0; v <= LANE_A_V1; v++) {
                b.put(u, 165 + u, v, stairs(Direction.EAST));
            }
        }
        for (int u = -3; u <= 11; u++) {
            for (int v = LANE_B_V0; v <= LANE_B_V1; v++) {
                b.put(u, 158 - u, v, stairs(Direction.WEST));
            }
        }
        for (int u = 5; u >= -3; u -= 2) {
            b.put(u, 165 + u + 5, u % 4 == 0 ? 89 : 90, lantern(true));
        }
        for (int u = -3; u <= 11; u += 2) {
            b.put(u, 158 - u + 5, u % 4 == 0 ? 94 : 95, lantern(true));
        }
        b.put(-6, 168, 92, lantern(true));
    }

    // ------------------------------------------------------------------ tunnel: a true vault of stairs, bricks and slabs

    /**
     * Cross-section, 6 wide (c = 0..5), floor at y = f: walls f+1 .. f+5 (5 high); tier 1 (f+6): upside-down stairs at the walls; tier 2
     * (f+7): wall at the edges, stairs at c = 1 and 4; tier 3 (f+8): the crown, c = 2 and 3, 8 high under a flat cap; a bottom slab plinth runs
     * along both walls at f+1.
     */
    private static void tunnel(Builder b) {
        int f = TUNNEL_FLOOR;
        int u0 = TUNNEL_U0, u1 = TUNNEL_U1;
        for (int v = LANE_B_V1; v >= TUNNEL_V_END; v--) {
            for (int u = u0; u <= u1; u++) {
                b.put(u, f, v, wall());
            }
            b.put(u0, f + 1, v, slab());
            b.put(u1, f + 1, v, slab());
            b.put(u0, f + 6, v, chamfer(Direction.WEST));
            b.put(u1, f + 6, v, chamfer(Direction.EAST));
            b.put(u0, f + 7, v, wall());
            b.put(u1, f + 7, v, wall());
            b.put(u0 + 1, f + 7, v, chamfer(Direction.WEST));
            b.put(u1 - 1, f + 7, v, chamfer(Direction.EAST));
            b.put(u0, f + 8, v, wall());
            b.put(u1, f + 8, v, wall());
            b.put(u0 + 1, f + 8, v, wall());
            b.put(u1 - 1, f + 8, v, wall());
        }
        // ribs every six: a ring of polished granite up the walls and over the crown
        for (int v = TUNNEL_V_START - 2; v >= TUNNEL_V_END + 2; v -= 6) {
            for (int y = f + 1; y <= f + 8; y++) {
                b.put(u0 - 1, y, v, rib());
                b.put(u1 + 1, y, v, rib());
            }
            for (int u = u0; u <= u1; u++) {
                b.put(u, f + 9, v, rib());
            }
        }
        // lanterns from the crown every third block, left and right in turn
        int n = 0;
        for (int v = TUNNEL_V_START - 1; v >= TUNNEL_V_END; v -= 3) {
            b.put(n++ % 2 == 0 ? u0 + 2 : u1 - 2, f + 8, v, lantern(true));
        }
        b.put(u0 + 1, f + 1, TUNNEL_V_END + 1, lantern(false));
        b.put(u1 - 1, f + 1, TUNNEL_V_END + 1, lantern(false));
        b.put(u0 + 2, f + 8, LANE_B_V0 + 1, lantern(true));
    }

    // ------------------------------------------------------------------ door: the arch, in an iron frame

    private static void door(Builder b) {
        int f = TUNNEL_FLOOR;
        java.util.Set<Long> arch = new HashSet<>();
        for (int[] d : DOOR) {
            arch.add(key(TUNNEL_U0 + d[0], f + 1 + d[1], 0));
        }
        for (int v = DOOR_V - 1; v <= DOOR_V + 1; v++) {
            for (int u = TUNNEL_U0; u <= TUNNEL_U1; u++) {
                b.put(u, f, v, wall());
                for (int y = f + 1; y <= f + 9; y++) {
                    b.put(u, y, v, arch.contains(key(u, y, 0)) ? air() : iron());
                }
            }
        }
        // the stone arch trim round the opening, on the tunnel face of the door wall (author 24.10: refs 1 and 5)
        int faceV = DOOR_V + 1;
        for (int u = TUNNEL_U0; u <= TUNNEL_U1; u++) {
            for (int y = f + 1; y <= f + 9; y++) {
                if (arch.contains(key(u, y, 0))) {
                    continue;
                }
                boolean next = arch.contains(key(u + 1, y, 0)) || arch.contains(key(u - 1, y, 0)) || arch.contains(key(u, y + 1, 0)) || arch.contains(key(u, y - 1, 0));
                if (next) {
                    b.put(u, y, faceV, rib());
                }
            }
        }
        for (int[] d : DOOR) {
            b.put(TUNNEL_U0 + d[0], f + 1 + d[1], DOOR_V, VaultRegistry.VAULT_DOOR.get().defaultBlockState()
                    .setValue(VaultDoorBlock.FACING, Direction.SOUTH).setValue(VaultDoorBlock.COL, d[0]).setValue(VaultDoorBlock.ROW, d[1]));
        }
    }

    // ------------------------------------------------------------------ study: the sect's treasury study, as the Scripture Hall

    private static <T extends Comparable<T>> BlockState set(BlockState s, Property<T> p, String v) {
        return s.setValue(p, p.getValue(v).orElseThrow(() -> new IllegalStateException(p.getName() + "=" + v)));
    }

    /** A block of the mod by id, with the properties given as name, value pairs. */
    private static BlockState deco(String id, String... kv) {
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("murim", id));
        if (b == Blocks.AIR) {
            throw new IllegalStateException("no block murim:" + id);
        }
        BlockState s = b.defaultBlockState();
        for (int i = 0; i < kv.length; i += 2) {
            Property<?> p = b.getStateDefinition().getProperty(kv[i]);
            if (p == null) {
                throw new IllegalStateException("murim:" + id + " has no property " + kv[i]);
            }
            s = set(s, p, kv[i + 1]);
        }
        return s;
    }

    private static void shelfUnit(Builder b, int u, int v, int y, String facing, int variant) {
        b.put(u, y, v, deco("vault_shelf", "facing", facing, "half", "lower", "variant", String.valueOf(variant)));
        b.put(u, y + 1, v, deco("vault_shelf", "facing", facing, "half", "upper", "variant", String.valueOf(variant)));
    }

    /** Barrels or crates in a pile. */
    private static void crates(Builder b, int u0, int u1, int v0, int v1, int y, int high) {
        for (int u = u0; u <= u1; u++) {
            for (int v = v0; v <= v1; v++) {
                for (int h = 0; h < high; h++) {
                    b.plan.specials.add(new Special(Kind.CHEST, new BlockPos(u, y + h, v),
                            Blocks.BARREL.defaultBlockState().setValue(net.minecraft.world.level.block.BarrelBlock.FACING, Direction.UP),
                            "murim:chests/main_vault_crate"));
                }
            }
        }
    }

    /**
     * The study as an airy archive storeroom (round 5; ref panels 5-7: a pale grey-green room, a few tall pale-wood shelves with a lot of empty
     * space, mostly white boxes and bundles of paper, one ladder, some crates, cool lamps, no rugs). The hidden shelf stands alone in the back
     * corner by the west wall, with a scuff on the floor in front of it and a draught of dust at its base; it is ordinary blocks, mined one by one.
     */
    private static void study(Builder b) {
        int f = TUNNEL_FLOOR;
        BlockState wallStone = VaultRegistry.VAULT_PLASTER.get().defaultBlockState();
        BlockState floorStone = Blocks.SMOOTH_STONE.defaultBlockState();
        for (int u = STUDY_U0 - 1; u <= STUDY_U1 + 1; u++) {
            for (int v = STUDY_V0 - 1; v <= STUDY_V1 + 1; v++) {
                for (int y = f; y <= STUDY_TOP + 1; y++) {
                    boolean inside = u >= STUDY_U0 && u <= STUDY_U1 && v >= STUDY_V0 && v <= STUDY_V1 && y > f && y <= STUDY_TOP;
                    if (inside || v == DOOR_V - 1) {
                        continue;
                    }
                    b.put(u, y, v, y == f ? floorStone : wallStone);
                }
            }
        }
        // the door wall facing the study, with the arch in it
        java.util.Set<Long> arch = new HashSet<>();
        for (int[] d : DOOR) {
            arch.add(key(TUNNEL_U0 + d[0], f + 1 + d[1], 0));
        }
        for (int u = STUDY_U0 - 1; u <= STUDY_U1 + 1; u++) {
            for (int y = f; y <= STUDY_TOP + 1; y++) {
                if (!arch.contains(key(u, y, 0))) {
                    b.put(u, y, DOOR_V - 1, y == f ? floorStone : wallStone);
                }
            }
        }
        for (int u = TUNNEL_U0; u <= TUNNEL_U1; u++) {
            b.put(u, f, DOOR_V - 1, wall());
        }
        // the hidden shelf: four wide, two deep, four high, sparse boxes; in front of it a scuffed streak in the floor
        for (int u = MIDDLE_U; u < MIDDLE_U + SHELF_W; u++) {
            for (int v = SHELF_V; v < SHELF_V + SHELF_DEPTH; v++) {
                shelfUnit(b, u, v, f + 1, "south", 3);
                shelfUnit(b, u, v, f + 3, "south", 3);
            }
        }
        for (int[] c : new int[][] {{6, 53}, {7, 53}, {8, 53}, {9, 53}, {7, 54}, {8, 54}}) {
            b.put(c[0], f, c[1], Blocks.ANDESITE.defaultBlockState());
        }
        // a few shelves: two on the back wall, one run on the west wall, one back-to-back row in the east half with the ladder
        for (int u = 12; u <= 15; u++) {
            shelfUnit(b, u, SHELF_V, f + 1, "south", 3);
            shelfUnit(b, u, SHELF_V, f + 3, "south", 3);
        }
        for (int u = 20; u <= 23; u++) {
            shelfUnit(b, u, SHELF_V, f + 1, "south", 3);
            shelfUnit(b, u, SHELF_V, f + 3, "south", 3);
        }
        for (int v = 54; v <= 57; v++) {
            shelfUnit(b, STUDY_U0, v, f + 1, "east", 3);
            shelfUnit(b, STUDY_U0, v, f + 3, "east", 3);
        }
        for (int v = 53; v <= 57; v++) {
            for (int y : new int[] {f + 1, f + 3}) {
                shelfUnit(b, 19, v, y, "west", 3);
                shelfUnit(b, 20, v, y, "east", 2);
            }
        }
        // Contents only: retain every shelf cell and both halves of its original geometry.
        String[] originals = {"six_harmonies", "falling_petal_sword", "seven_plum_blossoms",
                "seven_stars_shift", "dark_fragrance_step", "method/six_harmonies", "method/purple_sky_qi", "bamboo_leaf_hand"};
        int[] archiveU = {6, 8, 12, 13, 15, 20, 22, 23};
        for (int i = 0; i < originals.length; i++) {
            BlockPos at = new BlockPos(archiveU[i], f + 1, archiveU[i] < 10 ? SHELF_V + 1 : SHELF_V);
            b.plan.specials.add(new Special(Kind.ORIGINAL, at, b.plan.blocks.get(at.asLong()), originals[i]));
        }
        BlockPos letter = new BlockPos(STUDY_U0, f + 1, 57);
        BlockState letterState = VaultRegistry.VAULT_NOTE_SHELF.get().defaultBlockState()
                .setValue(VaultNoteShelfBlock.FACING, Direction.EAST).setValue(VaultNoteShelfBlock.TAKEN, false);
        b.put(letter.getX(), letter.getY(), letter.getZ(), letterState);
        b.put(letter.getX(), letter.getY() + 1, letter.getZ(), letterState.setValue(VaultNoteShelfBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        b.plan.specials.add(new Special(Kind.LETTER, letter, letterState, ""));
        // the ladder against the row
        for (int y = f + 1; y <= f + 4; y++) {
            b.put(18, y, 55, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST));
        }
        for (BlockPos chest : List.of(STUDY_CHEST, new BlockPos(24, f + 1, 53),
                new BlockPos(24, f + 1, 54), new BlockPos(22, f + 1, 61))) {
            b.plan.specials.add(new Special(Kind.CHEST, chest,
                    VaultRegistry.VAULT_CHEST.get().defaultBlockState().setValue(VaultChestBlock.FACING, chest.getX() == 24 ? Direction.WEST : Direction.SOUTH),
                    "murim:chests/main_vault_study"));
        }
        // crates (barrels), chests and bundles of paper
        crates(b, 22, 23, 53, 54, f + 1, 2);
        crates(b, 23, 24, 56, 57, f + 1, 1);
        crates(b, 6, 7, 60, 61, f + 1, 2);

        for (int[] c : new int[][] {{6, 62}, {22, 62}, {10, 56}, {10, 57}, {12, 53}, {16, 52}, {8, 54}}) {
            b.put(c[0], f + 1, c[1], deco("book_stack"));
        }
        b.put(6, f + 3, 60, deco("book_stack"));
        // one plain reading table, with two chairs, in the open middle
        for (int u = 11; u <= 14; u++) {
            b.put(u, f + 1, 56, deco("int_writing_desk", "facing", "south", "nx", u > 11 ? "true" : "false", "px", u < 14 ? "true" : "false"));
        }
        b.put(12, f + 2, 56, deco("book_stack"));
        b.put(12, f + 1, 57, deco("int_chair", "facing", "north"));
        b.put(13, f + 1, 57, deco("int_chair", "facing", "north"));
        // the even cool light of the room: invisible light blocks over the aisles (the lanterns alone are too high up to light the floor)
        for (int u : new int[] {8, 12, 16, 20, 23}) {
            for (int v : new int[] {52, 56, 60}) {
                b.put(u, f + 5, v, Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 14));
            }
        }
        // cool lamps: soul lanterns hung from the ceiling, evenly over the open floor
        for (int[] c : new int[][] {{9, 55}, {15, 53}, {15, 58}, {21, 59}, {9, 60}, {11, 52}, {18, 51}, {23, 51}}) {
            b.put(c[0], STUDY_TOP, c[1], Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
    }

    // ------------------------------------------------------------------ pit: the treasure of the ref «두둥», literally

    /**
     * The pit as in the author's top-down panel (round 8): a dark grey-green stone hall, two pale linen cloths at an angle, one loose heap of coins
     * and gems on the first, a stepped stack of gold bars on the second, a chest in the top-left corner, boxes and bundles in the top-right, spears
     * and swords leaning on the left wall, sacks in the lower left. Only the heap and the bars give light; the rest stays dark. The cloths, the heap and
     * the bars are one scene of models that reach over the cells (tools/art/vault_pit.py, {@link VaultTreasureLayout}).
     */
    private static void pit(Builder b) {
        int f = TUNNEL_FLOOR;
        BlockState wallStone = VaultRegistry.VAULT_PIT_BRICK.get().defaultBlockState();
        BlockState floorStone = VaultRegistry.VAULT_PIT_FLAG.get().defaultBlockState();
        for (int u = PIT_U0 - 1; u <= PIT_U1 + 1; u++) {
            for (int v = PIT_V0 - 1; v <= PIT_V1 + 1; v++) {
                for (int y = PIT_FLOOR; y <= PIT_FLOOR + 6; y++) {
                    boolean inside = u >= PIT_U0 && u <= PIT_U1 && v >= PIT_V0 && v <= PIT_V1 && y > PIT_FLOOR && y < PIT_FLOOR + 6;
                    if (inside || y == f - 1 || y == f) {
                        continue;
                    }
                    b.put(u, y, v, y == PIT_FLOOR ? floorStone : wallStone);
                }
            }
        }
        for (int u = PIT_U0 - 1; u <= PIT_U1 + 1; u++) {
            for (int v = PIT_V0 - 1; v <= PIT_V1 + 1; v++) {
                b.put(u, f - 1, v, wallStone);
            }
        }
        int py = PIT_FLOOR + 1;
        // the floor: polished dark stone with ONE ring of inlay round the treasure (stripes along the sides, a knot at each corner)
        for (int u = RING_U0; u <= RING_U1; u++) {
            for (int v = RING_V0; v <= RING_V1; v++) {
                boolean cu = u == RING_U0 || u == RING_U1, cv = v == RING_V0 || v == RING_V1;
                if (!cu && !cv) {
                    continue;
                }
                int kind = cu && cv ? 2 : cv ? 0 : 1;
                b.put(u, PIT_FLOOR, v, VaultRegistry.VAULT_PIT_BORDER.get().defaultBlockState().setValue(VaultPitBorderBlock.KIND, kind));
            }
        }
        // the walls: plain dark lacquer between stone pillars; each bay of four or more cells has ONE big framed panel, 2 blocks wide and 2 high, in the middle rows
        BlockState lacquer = VaultRegistry.VAULT_LACQUER.get().defaultBlockState();
        BlockState pillar = VaultRegistry.VAULT_PIT_PILLAR.get().defaultBlockState();
        BlockState beam = VaultRegistry.VAULT_BEAM.get().defaultBlockState();
        for (int side = 0; side < 4; side++) {          // 0 north (v = PIT_V0 - 1), 1 south, 2 west (u = PIT_U0 - 1), 3 east
            boolean alongU = side < 2;
            int n = alongU ? PIT_U1 - PIT_U0 + 3 : PIT_V1 - PIT_V0 + 3;       // the ring cells of this side, corners included
            int c0 = alongU ? PIT_U0 - 1 : PIT_V0 - 1;
            int fixed = side == 0 ? PIT_V0 - 1 : side == 1 ? PIT_V1 + 1 : side == 2 ? PIT_U0 - 1 : PIT_U1 + 1;
            Direction facing = side == 0 ? Direction.SOUTH : side == 1 ? Direction.NORTH : side == 2 ? Direction.EAST : Direction.WEST;
            java.util.TreeSet<Integer> posts = new java.util.TreeSet<>(java.util.List.of(0, n - 1));
            for (int i = 5; i < n - 3; i += 5) {
                posts.add(i);
            }
            for (int i = 0; i < n; i++) {
                int u = alongU ? c0 + i : fixed, v = alongU ? fixed : c0 + i;
                for (int y = PIT_FLOOR + 1; y <= PIT_FLOOR + 4; y++) {
                    b.put(u, y, v, posts.contains(i) ? pillar : lacquer);
                }
                b.put(u, PIT_FLOOR + 5, v, beam);
            }
            Integer prev = null;
            for (int post : posts) {
                if (prev != null && post - prev - 1 >= 4) {
                    int first = prev + 1 + (post - prev - 1 - 2) / 2;      // the panel's left cell along the wall
                    for (int k = 0; k < 2; k++) {
                        int i = first + k;
                        int u = alongU ? c0 + i : fixed, v = alongU ? fixed : c0 + i;
                        // seen from the room, which of the two cells is on the left: south-facing wall: lower u; north-facing: higher u; east-facing: higher v; west-facing: lower v
                        boolean lowIsLeft = facing == Direction.SOUTH || facing == Direction.WEST;
                        boolean left = (k == 0) == lowIsLeft;
                        b.put(u, PIT_FLOOR + 3, v, VaultPanelBlock.of(VaultRegistry.VAULT_PANEL.get(), left ? 0 : 1, facing));
                        b.put(u, PIT_FLOOR + 2, v, VaultPanelBlock.of(VaultRegistry.VAULT_PANEL.get(), left ? 2 : 3, facing));
                    }
                }
                prev = post;
            }
        }
        // carved beams across the ceiling, clear of the hole
        for (int v : new int[] {47, 53, 57}) {
            for (int u = PIT_U0; u <= PIT_U1; u++) {
                b.put(u, PIT_FLOOR + 5, v, beam);
            }
        }
        // two hanging lanterns and a third in the middle: warm plain block light, dim and rich
        for (int[] c : new int[][] {{12, 50}, {20, 50}, {15, 55}}) {
            b.put(c[0], PIT_FLOOR + 5, c[1], deco("round_silk_lantern", "attach", "ceiling", "facing", "north"));
        }
        // red cloth banners on the lacquer panels, hanging from the cornice
        b.put(10, PIT_FLOOR + 5, 44, decor(VaultDecorBlock.BANNER_RED, Direction.SOUTH));
        b.put(20, PIT_FLOOR + 5, 44, decor(VaultDecorBlock.BANNER_MAROON, Direction.SOUTH));
        b.put(10, PIT_FLOOR + 5, 58, decor(VaultDecorBlock.BANNER_RED, Direction.NORTH));
        b.put(20, PIT_FLOOR + 5, 58, decor(VaultDecorBlock.BANNER_MAROON, Direction.NORTH));
        b.put(23, PIT_FLOOR + 5, 48, decor(VaultDecorBlock.BANNER_MAROON, Direction.WEST));
        b.put(23, PIT_FLOOR + 5, 54, decor(VaultDecorBlock.BANNER_RED, Direction.WEST));
        // the treasure scene: cloths, heaps, vessels, open chests, the stack of bars
        for (int[] c : VaultTreasureLayout.CELLS) {
            b.put(c[0], py, c[1], treasure(c[2]));
        }
        // the hero sword alone on its pedestal near the heap, in the light of the lanterns
        b.put(HERO_U, py, HERO_V, decor(VaultDecorBlock.HERO, Direction.EAST));
        // the tall stands of the relic Hua swords on the north and south walls, a plaque above each
        b.put(14, py, 44, decor(VaultDecorBlock.RACK_A, Direction.SOUTH));
        b.put(14, py + 2, 44, decor(VaultDecorBlock.PLAQUE, Direction.SOUTH));
        b.put(14, py, 58, decor(VaultDecorBlock.RACK_B, Direction.NORTH));
        b.put(14, py + 2, 58, decor(VaultDecorBlock.PLAQUE, Direction.NORTH));
        // the chest (loot) in the top-left corner, boxes and bundles stacked in the top-right
        b.plan.specials.add(new Special(Kind.CHEST, CHEST, VaultRegistry.VAULT_CHEST.get().defaultBlockState().setValue(VaultChestBlock.FACING, Direction.SOUTH), "murim:chests/main_vault"));
        b.put(23, py, 44, prop(2, Direction.WEST));
        b.put(22, py, 44, prop(3, Direction.SOUTH));
        b.put(21, py, 44, prop(2, Direction.SOUTH));
        b.put(23, py, 45, prop(3, Direction.WEST));
        b.put(23, py + 1, 44, prop(3, Direction.SOUTH));
        // spears and relic swords leaning against the west wall, their front towards the room
        b.put(ARMS.getX(), py, ARMS.getZ(), prop(4, Direction.EAST));
        b.put(ARMS.getX(), py, ARMS.getZ() + 1, decor(VaultDecorBlock.LEAN_0, Direction.EAST));
        b.put(ARMS.getX(), py, ARMS.getZ() + 2, decor(VaultDecorBlock.LEAN_0 + 1, Direction.EAST));
        b.put(ARMS.getX(), py, ARMS.getZ() + 3, prop(4, Direction.EAST));
        b.put(ARMS.getX(), py, ARMS.getZ() + 4, decor(VaultDecorBlock.LEAN_0 + 2, Direction.EAST));
        // sacks in the lower-left corner
        b.put(6, py, 57, prop(0, Direction.EAST));
        b.put(6, py, 58, prop(1, Direction.EAST));
        b.put(7, py, 58, prop(0, Direction.NORTH));
        b.put(7, py, 57, prop(1, Direction.EAST));
        b.put(8, py, 58, prop(1, Direction.NORTH));
        // plain block light so that the stands and the hero sword read (the hall stays dim)
        for (int[] l : new int[][] {{12, PIT_FLOOR + 3, 46}, {17, PIT_FLOOR + 3, 46}, {12, PIT_FLOOR + 3, 56}, {17, PIT_FLOOR + 3, 56}, {10, PIT_FLOOR + 3, 55}}) {
            b.put(l[0], l[1], l[2], Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 10));
        }
        // the only extra light: two invisible blocks under the hole, so the room above is warmed by block light
        for (int[] l : new int[][] {{8, PIT_FLOOR + 5, 50}, {9, PIT_FLOOR + 5, 51}}) {
            b.put(l[0], l[1], l[2], Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 15));
        }
    }

    /**
     * The hole to the pit: a real opening, 4 x 2 cells in the floor under the hidden shelf and through the roof of the pit (air, nothing in it),
     * with the ladder on its west side. From the room you look in and down.
     */
    private static void hole(Builder b) {
        for (int u = MIDDLE_U; u < MIDDLE_U + SHELF_W; u++) {
            for (int v = SHELF_V; v < SHELF_V + SHELF_DEPTH; v++) {
                for (int y = TUNNEL_FLOOR - 1; y <= TUNNEL_FLOOR; y++) {
                    b.plan.suite.add(key(u, y, v));
                    b.put(u, y, v, air());
                }
            }
        }
        b.put(LADDER_U + 1, PIT_FLOOR + 1, LADDER_V - 1, lantern(false));
        b.put(LADDER_U - 1, TUNNEL_FLOOR + 1, LADDER_V, lantern(false));
        for (int y = PIT_FLOOR + 1; y <= TUNNEL_FLOOR; y++) {
            b.put(LADDER_U - 1, y, LADDER_V, wall());
            b.put(LADDER_U, y, LADDER_V, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST));
        }
    }

    /** True if the plan cell (u, v) is part of the opening (the ladder cell included). */
    static boolean inHole(int u, int v) {
        return u >= MIDDLE_U && u < MIDDLE_U + SHELF_W && v >= SHELF_V && v < SHELF_V + SHELF_DEPTH;
    }

    // ------------------------------------------------------------------ queries

    public static boolean inApproach(int u, int y, int v) {
        return PLAN.approach.contains(key(u, y, v));
    }

    public static boolean inSuite(int u, int y, int v) {
        return PLAN.suite.contains(key(u, y, v));
    }

    public static List<BlockPos> hatchCells() {
        List<BlockPos> out = new ArrayList<>();
        for (int u = HATCH_U0; u <= HATCH_U1; u++) {
            for (int v = LANE_A_V0; v <= LANE_A_V1; v++) {
                out.add(new BlockPos(u, HALL_FLOOR, v));
            }
        }
        return out;
    }

    /** Local positions of the door cells. */
    public static List<BlockPos> doorCells() {
        List<BlockPos> out = new ArrayList<>();
        for (int[] d : DOOR) {
            out.add(new BlockPos(TUNNEL_U0 + d[0], TUNNEL_FLOOR + 1 + d[1], DOOR_V));
        }
        return out;
    }

    /** The lowest-left door cell (col 0, row 0). */
    public static BlockPos doorCorner() {
        return new BlockPos(TUNNEL_U0, TUNNEL_FLOOR + 1, DOOR_V);
    }
}
