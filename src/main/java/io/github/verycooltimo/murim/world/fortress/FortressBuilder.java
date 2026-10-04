package io.github.verycooltimo.murim.world.fortress;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.Random;

/**
 * Стройка крепости Зелёного Леса (docs/design/26-boss.md §2): площадка на цоколе, частокол
 * с воротами, плац-арена 25×25, главный зал с креслом хозяина, сокровищница за залом.
 *
 * <p>Координаты — локальные, от центра плаца {@code (0, y0, 0)}: ворота на −Z, зал на +Z.
 * Поворот крепости — {@link Rotation} (позиции и блоки поворачиваются вместе, как у
 * шаблонов структур: {@link BlockState#rotate}). Верх пола плаца — {@code y0 − 1}, игрок стоит на
 * {@code y0}.
 *
 * <p>Первая итерация (§8 п.3): без внешнего двора с хижинами и без разрушаемых столбов.
 */
public final class FortressBuilder {

    /** Куда ставить блоки: генератор (по чанку) или живой мир (тест, команда). */
    public interface Sink {
        boolean owns(int x, int z);

        BlockState get(int x, int y, int z);

        void set(int x, int y, int z, BlockState state);
    }

    /** Площадка: локальные границы. */
    public static final int MIN_X = -17, MAX_X = 17, MIN_Z = -20, MAX_Z = 32;
    /** Плац: |x|, |z| ≤ 12 (25×25). */
    public static final int YARD = 12;
    /** Кресло хозяина и сундук сокровищницы, локально. */
    public static final int THRONE_Z = 21;
    public static final int VAULT_Z = 29;
    /** Решётка между залом и сокровищницей. */
    public static final int VAULT_DOOR_Z = 24;
    /** Сколько блоков над площадкой расчищается. */
    static final int CLEAR = 18;
    /** Насколько глубоко цоколь спускается до земли. */
    static final int MAX_FOUNDATION = 40;

    private final Sink sink;
    private final int cx;
    private final int y0;
    private final int cz;
    private final Rotation rot;
    private final long seed;

    public FortressBuilder(Sink sink, int cx, int y0, int cz, Rotation rot, long seed) {
        this.sink = sink;
        this.cx = cx;
        this.y0 = y0;
        this.cz = cz;
        this.rot = rot;
        this.seed = seed;
    }

    /** Мировая позиция локальной точки. */
    public static BlockPos world(int cx, int y, int cz, Rotation rot, int lx, int lz) {
        return switch (rot) {
            case CLOCKWISE_90 -> new BlockPos(cx - lz, y, cz + lx);
            case CLOCKWISE_180 -> new BlockPos(cx - lx, y, cz - lz);
            case COUNTERCLOCKWISE_90 -> new BlockPos(cx + lz, y, cz - lx);
            default -> new BlockPos(cx + lx, y, cz + lz);
        };
    }

    public static Rotation rotation(long seed) {
        return Rotation.values()[(int) Math.floorMod(seed, 4L)];
    }

    private void put(int lx, int y, int lz, BlockState state) {
        BlockPos p = world(cx, y, cz, rot, lx, lz);
        if (sink.owns(p.getX(), p.getZ())) {
            sink.set(p.getX(), p.getY(), p.getZ(), state.rotate(rot));
        }
    }

    private void put(int lx, int y, int lz, Block block) {
        put(lx, y, lz, block.defaultBlockState());
    }

    public void build() {
        platform();
        palisade();
        yard();
        hall();
        vault();
    }

    // ------------------------------------------------------------------ площадка

    private static boolean soft(BlockState s) {
        return s.isAir() || s.canBeReplaced() || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.LOGS)
                || !s.getFluidState().isEmpty();
    }

    private void platform() {
        for (int lx = MIN_X; lx <= MAX_X; lx++) {
            for (int lz = MIN_Z; lz <= MAX_Z; lz++) {
                BlockPos p = world(cx, 0, cz, rot, lx, lz);
                if (!sink.owns(p.getX(), p.getZ())) {
                    continue;
                }
                boolean edge = lx == MIN_X || lx == MAX_X || lz == MIN_Z || lz == MAX_Z;
                boolean inYard = Math.abs(lx) <= YARD && Math.abs(lz) <= YARD;
                long h = (seed * 31L + lx * 73428767L) ^ (lz * 912931L);
                Random cr = new Random(h);
                // Расчистка над площадкой.
                for (int y = y0; y <= y0 + CLEAR; y++) {
                    sink.set(p.getX(), y, p.getZ(), Blocks.AIR.defaultBlockState());
                }
                // Верх: плац — камень, вокруг — утоптанная земля и гравий (на них не растут деревья).
                BlockState top;
                if (inYard) {
                    boolean border = Math.abs(lx) == YARD || Math.abs(lz) == YARD;
                    float f = cr.nextFloat();
                    top = border ? Blocks.STONE_BRICKS.defaultBlockState()
                            : f < 0.55F ? Blocks.POLISHED_ANDESITE.defaultBlockState()
                            : f < 0.80F ? Blocks.STONE_BRICKS.defaultBlockState()
                            : f < 0.92F ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState()
                            : Blocks.ANDESITE.defaultBlockState();
                } else if (edge) {
                    top = cr.nextFloat() < 0.3F ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
                } else {
                    float f = cr.nextFloat();
                    top = f < 0.5F ? Blocks.DIRT_PATH.defaultBlockState() : f < 0.8F ? Blocks.GRAVEL.defaultBlockState() : Blocks.PACKED_MUD.defaultBlockState();
                }
                sink.set(p.getX(), y0 - 1, p.getZ(), top);
                // Цоколь до земли: по краю — каменная кладка (видна со склона), внутри — земля.
                for (int y = y0 - 2, n = 0; n < MAX_FOUNDATION; y--, n++) {
                    BlockState have = sink.get(p.getX(), y, p.getZ());
                    if (!soft(have)) {
                        break;
                    }
                    BlockState fill = edge ? (cr.nextFloat() < 0.25F ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                            : cr.nextFloat() < 0.5F ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState())
                            : Blocks.DIRT.defaultBlockState();
                    sink.set(p.getX(), y, p.getZ(), fill);
                }
            }
        }
    }

    // ------------------------------------------------------------------ частокол и ворота

    private void palisade() {
        int px = MAX_X - 1, pzMin = MIN_Z + 1, pzMax = MAX_Z - 1;
        for (int lx = -px; lx <= px; lx++) {
            for (int lz = pzMin; lz <= pzMax; lz++) {
                boolean ring = Math.abs(lx) == px || lz == pzMin || lz == pzMax;
                if (!ring) {
                    continue;
                }
                if (lz == pzMin && Math.abs(lx) <= 2) {
                    continue;                                       // проход ворот
                }
                boolean tall = Math.floorMod(lx + lz, 4) == 0;
                Block log = Math.floorMod(lx * 7 + lz * 3, 5) == 0 ? Blocks.DARK_OAK_LOG : Blocks.SPRUCE_LOG;
                int top = tall ? 4 : 3;
                for (int y = 0; y <= top; y++) {
                    put(lx, y0 + y, lz, log);
                }
                put(lx, y0 + top + 1, lz, Blocks.SPRUCE_FENCE);     // заострённые колья
            }
        }
        // Ворота: столбы, перекладина, фонари.
        for (int side : new int[] {-3, 3}) {
            for (int y = 0; y <= 6; y++) {
                put(side, y0 + y, pzMin, Blocks.DARK_OAK_LOG);
            }
        }
        for (int lx = -4; lx <= 4; lx++) {
            put(lx, y0 + 6, pzMin, Blocks.DARK_OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        }
        for (int lx : new int[] {-1, 1}) {
            put(lx, y0 + 5, pzMin, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
        put(0, y0 + 7, pzMin, Blocks.BLACK_BANNER);
    }

    // ------------------------------------------------------------------ плац

    private void yard() {
        // Четыре деревянных столба: мешают прямому тарану (разрушаемые — во второй итерации).
        for (int sx : new int[] {-6, 6}) {
            for (int sz : new int[] {-6, 6}) {
                for (int y = 0; y <= 3; y++) {
                    put(sx, y0 + y, sz, Blocks.STRIPPED_DARK_OAK_LOG);
                }
                put(sx, y0 + 4, sz, Blocks.DARK_OAK_SLAB);
            }
        }
        // Жаровни по углам: подставка и костёр — свет на ночной плац.
        for (int sx : new int[] {-11, 11}) {
            for (int sz : new int[] {-11, 11}) {
                put(sx, y0, sz, Blocks.CHISELED_STONE_BRICKS);
                put(sx, y0 + 1, sz, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, false));
            }
        }
        // Стойки с оружием и бочки вдоль боков плаца, за его краем.
        for (int sz = -8; sz <= 8; sz += 4) {
            for (int side : new int[] {-14, 14}) {
                put(side, y0, sz, Blocks.SPRUCE_FENCE);
                put(side, y0 + 1, sz, Blocks.SPRUCE_FENCE);
                put(side, y0 + 2, sz, Blocks.SPRUCE_SLAB);
            }
        }
        put(-14, y0, 10, Blocks.BARREL);
        put(14, y0, 10, Blocks.BARREL);
        put(14, y0, -12, Blocks.BARREL);
        put(-14, y0, -12, Blocks.HAY_BLOCK);
    }

    // ------------------------------------------------------------------ главный зал

    private void hall() {
        int x0 = -9, x1 = 9, z0 = 14, z1 = 24;
        // Пол на ступень выше плаца, ступени к плацу.
        for (int lx = x0; lx <= x1; lx++) {
            for (int lz = z0; lz <= z1; lz++) {
                put(lx, y0, lz, Blocks.DARK_OAK_PLANKS);
            }
        }
        for (int lx = -4; lx <= 4; lx++) {
            put(lx, y0, 13, Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        }
        // Стены: бока и задняя, тёмное дерево; перед открыт между красными колоннами.
        for (int y = 1; y <= 5; y++) {
            for (int lz = z0; lz <= z1; lz++) {
                put(x0, y0 + y, lz, Blocks.DARK_OAK_PLANKS);
                put(x1, y0 + y, lz, Blocks.DARK_OAK_PLANKS);
            }
            for (int lx = x0; lx <= x1; lx++) {
                put(lx, y0 + y, z1, Blocks.DARK_OAK_PLANKS);
            }
            for (int lx : new int[] {x0, -5, 5, x1}) {
                put(lx, y0 + y, z0, Blocks.STRIPPED_MANGROVE_LOG);   // красные колонны
            }
            put(x0, y0 + y, z1, Blocks.STRIPPED_MANGROVE_LOG);
            put(x1, y0 + y, z1, Blocks.STRIPPED_MANGROVE_LOG);
        }
        // Окна в боковых стенах.
        for (int lz : new int[] {17, 21}) {
            put(x0, y0 + 3, lz, Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true)
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
            put(x1, y0 + 3, lz, Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true)
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        }
        // Крыша ступенчатой пирамидой из тёмной черепицы.
        int[][] layers = {{10, 13, 25}, {8, 15, 23}, {5, 17, 21}, {2, 18, 20}};
        for (int i = 0; i < layers.length; i++) {
            int w = layers[i][0];
            for (int lx = -w; lx <= w; lx++) {
                for (int lz = layers[i][1]; lz <= layers[i][2]; lz++) {
                    boolean rim = i == 0 && (Math.abs(lx) == w || lz == layers[i][1] || lz == layers[i][2]);
                    put(lx, y0 + 6 + i, lz, rim ? Blocks.DEEPSLATE_TILE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)
                            : Blocks.DEEPSLATE_TILES.defaultBlockState());
                }
            }
        }
        // Кресло хозяина: сиденье, спинка с тигровой шкурой, подлокотники; ковёр к плацу.
        put(0, y0 + 1, THRONE_Z, Blocks.DARK_OAK_SLAB);
        for (int lx = -1; lx <= 1; lx++) {
            put(lx, y0 + 1, THRONE_Z + 1, Blocks.DARK_OAK_PLANKS);
            put(lx, y0 + 2, THRONE_Z + 1, Blocks.DARK_OAK_PLANKS);
        }
        put(0, y0 + 3, THRONE_Z + 1, Blocks.ORANGE_WOOL);
        put(-1, y0 + 3, THRONE_Z + 1, Blocks.BLACK_WOOL);
        put(1, y0 + 3, THRONE_Z + 1, Blocks.BLACK_WOOL);
        put(-1, y0 + 1, THRONE_Z, Blocks.DARK_OAK_FENCE);
        put(1, y0 + 1, THRONE_Z, Blocks.DARK_OAK_FENCE);
        for (int lz = 15; lz < THRONE_Z; lz++) {
            put(0, y0 + 1, lz, lz == THRONE_Z - 1 ? Blocks.ORANGE_CARPET : Blocks.RED_CARPET);
        }
        put(-1, y0 + 1, THRONE_Z - 1, Blocks.BLACK_CARPET);
        put(1, y0 + 1, THRONE_Z - 1, Blocks.BLACK_CARPET);
        // Знамя на задней стене и фонари под потолком.
        put(0, y0 + 4, z1 - 1, Blocks.BLACK_WALL_BANNER.defaultBlockState().setValue(WallBannerBlock.FACING, Direction.NORTH));
        for (int lx : new int[] {-5, 5}) {
            for (int lz : new int[] {17, 22}) {
                put(lx, y0 + 5, lz, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
            }
        }
        // Решётка в сокровищницу (снимается, когда хозяин повержен).
        put(0, y0 + 1, VAULT_DOOR_Z, Blocks.IRON_BARS);
        put(0, y0 + 2, VAULT_DOOR_Z, Blocks.IRON_BARS);
    }

    // ------------------------------------------------------------------ сокровищница

    private void vault() {
        for (int lx = -4; lx <= 4; lx++) {
            for (int lz = 25; lz <= 31; lz++) {
                boolean wall = Math.abs(lx) == 4 || lz == 31;
                put(lx, y0, lz, Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    put(lx, y0 + y, lz, wall ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
                put(lx, y0 + 5, lz, Blocks.STONE_BRICK_SLAB);
            }
        }
        put(-3, y0 + 1, 30, Blocks.BARREL);
        put(3, y0 + 1, 30, Blocks.BARREL);
        put(0, y0 + 4, 27, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
    }

    /** Ворота сокровищницы: открыть (воздух) или закрыть (решётка). */
    public static void vaultDoor(Sink sink, int cx, int y0, int cz, Rotation rot, boolean open) {
        for (int y = 1; y <= 2; y++) {
            BlockPos p = world(cx, y0 + y, cz, rot, 0, VAULT_DOOR_Z);
            sink.set(p.getX(), p.getY(), p.getZ(), open ? Blocks.AIR.defaultBlockState() : Blocks.IRON_BARS.defaultBlockState());
        }
    }
}
