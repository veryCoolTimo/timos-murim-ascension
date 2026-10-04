package io.github.verycooltimo.murim.world.camp;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Строит лагерь по {@link CampLayout} ванильными блоками (docs/design/24-bandit-camp.md §2).
 *
 * <p>Земля не выравнивается плитой: частокол и двор берут высоту КАЖДОЙ колонки, а постройки —
 * свою опорную высоту (медиана рельефа под ними из генератора, одна на все чанки) и подсыпают
 * землю или срезают склон только под собой. Генерация идёт по чанку за раз, поэтому всё пишется
 * через {@link Sink}, который отбрасывает блоки вне текущего чанка; декор зависит только от
 * координат ({@link CampLayout#hash}), а не от порядка вызовов.
 */
public final class CampBuilder {

    /** Куда писать: мир генерации (кусок структуры) или живой мир (команда, GameTest). */
    public interface Sink {
        /** Пишет ли этот проход в колонку (x, z) — текущий чанк. */
        boolean owns(int x, int z);

        /** Верхний твёрдый блок колонки (без воды и листвы). */
        int ground(int x, int z);

        BlockState get(int x, int y, int z);

        void set(int x, int y, int z, BlockState state);

        /** Таблица добычи сундука или бочки в этой точке. */
        void loot(int x, int y, int z, ResourceKey<LootTable> table);

        /** Рамка с предметом на стене (висит на блоке позади себя). */
        void frame(int x, int y, int z, Direction facing, ItemStack item);

        /** Предмет в воздухе (ItemDisplay): мясо на вертеле. Координаты — центр предмета. */
        void display(double x, double y, double z, float yaw, ItemStack item);
    }

    /**
     * ItemDisplay «fixed» с предметом и масштабом — через NBT: сеттеры предмета у ItemDisplay приватные.
     * API: reference/minecraft-src/net/minecraft/world/entity/Display.java (ItemDisplay#readAdditionalSaveData:
     * "item", "item_display", Display#readAdditionalSaveData: "transformation"), Entity#load.
     */
    public static net.minecraft.world.entity.Display.ItemDisplay itemDisplay(net.minecraft.server.level.ServerLevel level,
                                                                             double x, double y, double z, float yaw, ItemStack item, float scale) {
        net.minecraft.world.entity.Display.ItemDisplay d = net.minecraft.world.entity.EntityType.ITEM_DISPLAY.create(level);
        if (d == null) {
            return null;
        }
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.put("item", item.save(level.registryAccess()));
        tag.putString("item_display", "fixed");
        net.minecraft.nbt.CompoundTag tr = new net.minecraft.nbt.CompoundTag();
        tr.put("translation", floats(0.0F, 0.0F, 0.0F));
        tr.put("left_rotation", floats(0.0F, 0.0F, 0.0F, 1.0F));
        tr.put("scale", floats(scale, scale, scale));
        tr.put("right_rotation", floats(0.0F, 0.0F, 0.0F, 1.0F));
        tag.put("transformation", tr);
        d.load(tag);
        d.moveTo(x, y, z, yaw, 0.0F);
        return d;
    }

    private static net.minecraft.nbt.ListTag floats(float... v) {
        net.minecraft.nbt.ListTag l = new net.minecraft.nbt.ListTag();
        for (float f : v) {
            l.add(net.minecraft.nbt.FloatTag.valueOf(f));
        }
        return l;
    }

    public static final ResourceKey<LootTable> LOOT_CRATE = loot("chests/bandit_camp/crate");
    public static final ResourceKey<LootTable> LOOT_CART = loot("chests/bandit_camp/cart");
    public static final ResourceKey<LootTable> LOOT_CHIEF = loot("chests/bandit_camp/chief");

    /**
     * Полотно шатра: ступени дают скат 45°, верхний полублок — конёк, полный блок — задняя стенка.
     * Гладкий песчаник и кварц читаются как небелёный и белёный холст, ель — как кожа на жердях
     * (шерстяная «ступенчатая горка» отвергнута: наклонной шерсти в ванили нет, codex 04.10).
     */
    record Canvas(Block stairs, Block slab, Block full) {
    }

    private static final Canvas[] TENT_CANVAS = {
            new Canvas(Blocks.SMOOTH_SANDSTONE_STAIRS, Blocks.SMOOTH_SANDSTONE_SLAB, Blocks.SMOOTH_SANDSTONE),
            new Canvas(Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB, Blocks.SPRUCE_PLANKS),
            new Canvas(Blocks.SMOOTH_QUARTZ_STAIRS, Blocks.SMOOTH_QUARTZ_SLAB, Blocks.SMOOTH_QUARTZ),
            new Canvas(Blocks.SMOOTH_SANDSTONE_STAIRS, Blocks.SMOOTH_SANDSTONE_SLAB, Blocks.SMOOTH_SANDSTONE)};
    private static final Block[] TENT_CARPET = {Blocks.BROWN_CARPET, Blocks.GRAY_CARPET, Blocks.GREEN_CARPET, Blocks.LIGHT_GRAY_CARPET};
    /** Шатёр главаря: охристо-красный холст или тёмная кожа; задняя стенка — шерсть цвета его знамени. */
    private static final Canvas[] CHIEF_CANVAS = {
            new Canvas(Blocks.SMOOTH_RED_SANDSTONE_STAIRS, Blocks.SMOOTH_RED_SANDSTONE_SLAB, Blocks.RED_WOOL),
            new Canvas(Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB, Blocks.BLACK_WOOL)};

    /** Масштаб мяса на вертеле (ItemDisplay). */
    public static final float MEAT_SCALE = 0.7F;

    private final CampLayout plan;
    private final int cx;
    private final int cz;
    /** Опорные высоты построек — по индексам {@link CampLayout#spots()}, последняя — ворота. */
    private final int[] heights;
    private final Sink sink;

    public CampBuilder(CampLayout plan, int cx, int cz, int[] heights, Sink sink) {
        this.plan = plan;
        this.cx = cx;
        this.cz = cz;
        this.heights = heights;
        this.sink = sink;
    }

    private static ResourceKey<LootTable> loot(String path) {
        return ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path));
    }

    public void build() {
        yard();
        gateGround();
        palisade();
        gate();
        for (int i = 0; i < plan.spots().size(); i++) {
            CampLayout.Spot s = plan.spots().get(i);
            int y = heights[i];
            switch (s.kind()) {
                case FIRE -> fire(s, y);
                case TENT -> tent(s, y, 2, 2, TENT_CANVAS[s.variant() & 3], TENT_CARPET[s.variant() & 3], false);
                case CHIEF_TENT -> tent(s, y, 3, 3, CHIEF_CANVAS[s.variant() & 1], Blocks.RED_CARPET, true);
                case LEAN_TO -> leanTo(s, y);
                case TOWER -> tower(s, y);
                case CART -> cart(s, y);
                case RACK -> rack(s, y);
                case CRATE -> crate(s, y);
            }
        }
    }

    // ------------------------------------------------------------------ двор и частокол

    /**
     * Утоптанный двор: тропа от ворот к костру и дальше наружу в лес, вокруг — тропа, гравий,
     * глина. Ни тропа, ни гравий, ни глина не держат ростки — деревья в лагере не вырастают.
     */
    private void yard() {
        int r = plan.radius() + CampLayout.TRAIL_LENGTH + 1;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = cx + dx, z = cz + dz;
                boolean trail = !plan.inside(dx, dz) && plan.onTrail(dx, dz);
                if (!sink.owns(x, z) || !(plan.inside(dx, dz) || trail)) {
                    continue;
                }
                int g = sink.ground(x, z);
                BlockState top = sink.get(x, g, z);
                if (!top.is(Blocks.GRASS_BLOCK) && !top.is(Blocks.DIRT) && !top.is(Blocks.PODZOL)
                        && !top.is(Blocks.COARSE_DIRT) && !top.is(Blocks.MYCELIUM) && !top.is(Blocks.SNOW_BLOCK)) {
                    continue;
                }
                long h = CampLayout.hash(plan.seed(), x, z) % 100L;
                double edge = Math.sqrt(dx * dx + dz * dz) / plan.radius();
                BlockState floor;
                if (plan.onPath(dx, dz) || trail) {
                    floor = h < 85 ? Blocks.DIRT_PATH.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
                } else if (edge > 0.78D) {
                    // У частокола — гравий и глина: на них не приживаются ростки деревьев.
                    floor = h < 50 ? Blocks.GRAVEL.defaultBlockState() : Blocks.PACKED_MUD.defaultBlockState();
                } else {
                    floor = h < 45 ? Blocks.DIRT_PATH.defaultBlockState()
                            : h < 65 ? Blocks.PACKED_MUD.defaultBlockState()
                            : Blocks.GRAVEL.defaultBlockState();
                }
                sink.set(x, g, z, floor);
                clearAbove(x, g + 1, z, 4);
            }
        }
    }

    /** Частокол: бревно на КАЖДОЙ колонке своей высоты — ограда идёт по рельефу. */
    private void palisade() {
        for (CampLayout.Stake s : plan.stakes()) {
            int x = cx + s.dx(), z = cz + s.dz();
            if (!sink.owns(x, z)) {
                continue;
            }
            int g = sink.ground(x, z);
            int top = s.gatePost() ? gateY() + 4 : g + s.height();
            long h = CampLayout.hash(plan.seed(), x, z);
            BlockState log = (h % 7L == 0L ? Blocks.STRIPPED_SPRUCE_LOG : h % 5L == 0L ? Blocks.DARK_OAK_LOG : Blocks.SPRUCE_LOG)
                    .defaultBlockState();
            for (int y = g + 1; y <= top; y++) {
                sink.set(x, y, z, log);
            }
            // Заострённые верхушки: местами поверх бревна торчит кол (забор).
            if (!s.gatePost() && h % 3L == 0L) {
                fence(x, top + 1, z, Blocks.SPRUCE_FENCE);
            }
            // Подсыпать землю под бревном, если колонка ниже соседей (столб не висит).
            sink.set(x, g, z, Blocks.COARSE_DIRT.defaultBlockState());
        }
    }

    private int gateY() {
        return heights[heights.length - 1];
    }

    /** Ворота: перекладина поверх проёма и фонарь под ней. */
    private void gate() {
        int y = gateY() + 5;
        double[] d = plan.gateDirection();
        // Перекладина вдоль касательной к кольцу.
        Direction.Axis axis = Math.abs(d[0]) > Math.abs(d[1]) ? Direction.Axis.Z : Direction.Axis.X;
        BlockState beam = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
        for (int[] c : plan.gateCells()) {
            int x = cx + c[0], z = cz + c[1];
            if (sink.owns(x, z)) {
                sink.set(x, y, z, beam);
                clearAbove(x, sink.ground(x, z) + 1, z, y - sink.ground(x, z) - 1);
            }
        }
        for (CampLayout.Stake s : plan.stakes()) {
            int x = cx + s.dx(), z = cz + s.dz();
            if (s.gatePost() && sink.owns(x, z)) {
                sink.set(x, y, z, beam);
            }
        }
        int[] mid = plan.gateCentre();
        int mx = cx + mid[0], mz = cz + mid[1];
        if (sink.owns(mx, mz) && sink.get(mx, y, mz).is(Blocks.SPRUCE_LOG)) {
            sink.set(mx, y - 1, mz, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
    }

    // ------------------------------------------------------------------ постройки

    /**
     * Костёр с вертелом: две жерди-рогатки, железный прут (цепь) над огнём и жареная птица на нём
     * (ItemDisplay, автор: «вертел читаемый»); брёвна-скамьи вокруг, камни очага.
     */
    private void fire(CampLayout.Spot s, int y) {
        int x = cx + s.dx(), z = cz + s.dz();
        footprint(s, y, -3, 3, -3, 3, 3, true);
        put(x, y + 1, z, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        put(x, y, z, Blocks.COBBLESTONE.defaultBlockState());
        // Вертел вдоль оси x: жерди по бокам, цепь — прут над огнём, на пруте — птица.
        fence(x - 1, y + 1, z, Blocks.SPRUCE_FENCE);
        fence(x - 1, y + 2, z, Blocks.SPRUCE_FENCE);
        fence(x + 1, y + 1, z, Blocks.SPRUCE_FENCE);
        fence(x + 1, y + 2, z, Blocks.SPRUCE_FENCE);
        put(x, y + 2, z, Blocks.CHAIN.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        if (sink.owns(x, z)) {
            sink.display(x + 0.5D, y + 2.42D, z + 0.5D, 0.0F, new ItemStack(Items.COOKED_CHICKEN));
        }
        // Камни очага и зола вокруг.
        for (int[] o : new int[][] {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}, {0, 1}, {0, -1}}) {
            put(x + o[0], y, z + o[1], (CampLayout.hash(plan.seed(), x + o[0], z + o[1]) & 1L) == 0L
                    ? Blocks.GRAVEL.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState());
        }
        // Скамьи-брёвна с трёх сторон, кроме стороны ворот (там тропа).
        double[] g = plan.gateDirection();
        int gateSide = CampLayout.facingTo(g[0], g[1]);
        for (int f = 0; f < 4; f++) {
            if (f == gateSide) {
                continue;
            }
            int[] st = CampLayout.step(f);
            Direction.Axis axis = st[0] != 0 ? Direction.Axis.Z : Direction.Axis.X;
            BlockState log = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
            for (int k = -1; k <= (f % 2 == 0 ? 1 : 0); k++) {
                int bx = x + st[0] * 3 + (st[0] == 0 ? k : 0);
                int bz = z + st[1] * 3 + (st[1] == 0 ? k : 0);
                put(bx, y + 1, bz, log);
            }
        }
    }

    /**
     * Шатёр-«домик» на жердях: скаты из ступеней полотна (45°), конёк — верхний полублок и жердь из
     * забора во всю длину на двух стойках спереди и сзади, глухая задняя стенка, открытый вход к костру,
     * колья-растяжки. Внутри — постели (циновка и светлое изголовье). {@code half} — полуширина
     * (2 — простой, 3 — главаря), {@code depth} — на сколько уходит назад от центра.
     */
    private void tent(CampLayout.Spot s, int y, int half, int depth, Canvas canvas, Block carpet, boolean chief) {
        int front = 2;
        footprint(s, y, -depth - 1, front + 1, -half, half, half + 2, false);
        Direction toPlus = dir(s.facing() + 1);
        Direction toMinus = dir(s.facing() + 3);
        BlockState full = canvas.full().defaultBlockState();
        BlockState ridgeSlab = canvas.slab().defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
        for (int a = -depth; a <= front; a++) {
            for (int side = -half; side <= half; side++) {
                int[] c = rel(s, a, side);
                if (side == 0) {
                    put(c, y + half, a == -depth ? full : ridgeSlab);
                } else {
                    // Скат: ступень поднимается к коньку.
                    int lvl = half - Math.abs(side);
                    put(c, y + 1 + lvl, canvas.stairs().defaultBlockState()
                            .setValue(StairBlock.FACING, side < 0 ? toPlus : toMinus)
                            .setValue(StairBlock.HALF, Half.BOTTOM));
                }
                if (a == -depth) {
                    // Задняя стенка: треугольник под скатами.
                    int top = side == 0 ? half - 1 : half - Math.abs(side);
                    for (int k = 1; k <= top; k++) {
                        put(c, y + k, full);
                    }
                }
            }
        }
        // Конёк-жердь во всю длину и две стойки под ней (спереди и сзади, на шаг за полотном).
        for (int a = -depth - 1; a <= front + 1; a++) {
            fence(rel(s, a, 0), y + half + 1, Blocks.SPRUCE_FENCE);
        }
        for (int a : new int[] {-depth - 1, front + 1}) {
            for (int k = 1; k <= half; k++) {
                fence(rel(s, a, 0), y + k, Blocks.SPRUCE_FENCE);
            }
        }
        // Колья-растяжки у входа (по краям ската, на шаг впереди).
        for (int side : new int[] {-half, half}) {
            fence(rel(s, front + 1, side), y + 1, Blocks.SPRUCE_FENCE);
        }
        // Постели вдоль скатов: изголовье у задней стенки светлое, дальше циновка.
        int[] beds = chief ? new int[] {1, 2} : new int[] {-1, 1};
        for (int side : beds) {
            for (int a = -depth + 1; a <= front - 1; a++) {
                put(rel(s, a, side), y + 1, (a == -depth + 1 ? Blocks.WHITE_CARPET : carpet).defaultBlockState());
            }
        }
        if (chief) {
            // Сундук главаря у задней стенки, бочка с фонарём, два знамени у входа.
            int[] c = rel(s, -depth + 1, 0);
            put(c, y + 1, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, dir(s.facing())));
            if (sink.owns(c[0], c[1])) {
                sink.loot(c[0], y + 1, c[1], LOOT_CHIEF);
            }
            put(rel(s, -depth + 1, -1), y + 1, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
            put(rel(s, -depth + 1, -1), y + 2, Blocks.LANTERN.defaultBlockState());
            int rot = (s.facing() & 3) * 4;
            put(rel(s, front + 1, -(half - 1)), y + 1, Blocks.RED_BANNER.defaultBlockState().setValue(BannerBlock.ROTATION, rot));
            put(rel(s, front + 1, half - 1), y + 1, Blocks.BLACK_BANNER.defaultBlockState().setValue(BannerBlock.ROTATION, rot));
        } else {
            // Свёрток с пожитками у задней стенки.
            put(rel(s, -depth + 1, 0), y + 1, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        }
    }

    /**
     * Навес: задняя стенка из полотна на двух брёвнах, крыша скатом к костру (ступень — полублок —
     * ступень — полублок, с 4 до 3 блоков), передние жерди, внутри две постели и бочка.
     */
    private void leanTo(CampLayout.Spot s, int y) {
        footprint(s, y, -2, 3, -3, 3, 5, false);
        Canvas canvas = TENT_CANVAS[1];
        Direction back = dir(s.facing() + 2);
        BlockState stair = canvas.stairs().defaultBlockState().setValue(StairBlock.FACING, back).setValue(StairBlock.HALF, Half.BOTTOM);
        BlockState slab = canvas.slab().defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        for (int side = -2; side <= 2; side++) {
            for (int k = 1; k <= 3; k++) {
                put(rel(s, -1, side), y + k, Math.abs(side) == 2
                        ? Blocks.SPRUCE_LOG.defaultBlockState() : canvas.full().defaultBlockState());
            }
            put(rel(s, -1, side), y + 4, stair);
            put(rel(s, 0, side), y + 4, slab);
            put(rel(s, 1, side), y + 3, stair);
            put(rel(s, 2, side), y + 3, slab);
        }
        for (int side : new int[] {-2, 2}) {
            fence(rel(s, 2, side), y + 1, Blocks.SPRUCE_FENCE);
            fence(rel(s, 2, side), y + 2, Blocks.SPRUCE_FENCE);
        }
        for (int side : new int[] {-1, 1}) {
            put(rel(s, 0, side), y + 1, Blocks.WHITE_CARPET.defaultBlockState());
            put(rel(s, 1, side), y + 1, Blocks.BROWN_CARPET.defaultBlockState());
        }
        put(rel(s, 0, 0), y + 1, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
    }

    /** Дозорная вышка: четыре столба, площадка 3×3 на высоте 5, перила, лестница, фонарь. */
    private void tower(CampLayout.Spot s, int y) {
        footprint(s, y, -2, 2, -2, 2, 8, true);
        int top = y + 5;
        for (int a = -1; a <= 1; a += 2) {
            for (int side = -1; side <= 1; side += 2) {
                for (int k = 1; k <= 5; k++) {
                    put(rel(s, a, side), y + k, Blocks.SPRUCE_LOG.defaultBlockState());
                }
            }
        }
        for (int a = -1; a <= 1; a++) {
            for (int side = -1; side <= 1; side++) {
                if (Math.abs(a) == 1 && Math.abs(side) == 1) {
                    continue;
                }
                put(rel(s, a, side), top, Blocks.SPRUCE_PLANKS.defaultBlockState());
            }
        }
        // Перила по краю площадки, кроме места выхода лестницы.
        for (int a = -1; a <= 1; a++) {
            for (int side = -1; side <= 1; side++) {
                boolean edge = Math.abs(a) == 1 || Math.abs(side) == 1;
                if (edge && !(a == 1 && side == 1)) {
                    fence(rel(s, a, side), top + 1, Blocks.SPRUCE_FENCE);
                }
            }
        }
        // Лестница снаружи на столбе (a=1, side=1), лицом к костру.
        Direction face = dir(s.facing());
        for (int k = 1; k <= 5; k++) {
            int[] l = rel(s, 2, 1);
            put(l, y + k, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, face));
        }
        put(rel(s, -1, -1), top + 1, Blocks.LANTERN.defaultBlockState());
    }

    /** Телега с награбленным: кузов из полублоков, колёса-торцы брёвен, оглобли, груз и сундук. */
    private void cart(CampLayout.Spot s, int y) {
        footprint(s, y, -2, 3, -2, 2, 3, true);
        for (int a = -1; a <= 1; a++) {
            for (int side = 0; side <= 1; side++) {
                put(rel(s, a, side), y + 1, Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
            }
        }
        // Колёса: торцы тёмных брёвен по бокам кузова — кольца на срезе читаются как колесо
        // (люки не читались, разбор codex 04.10).
        BlockState wheel = Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axisAcross(s.facing()));
        put(rel(s, 0, -1), y + 1, wheel);
        put(rel(s, 0, 2), y + 1, wheel);
        // Оглобли вперёд.
        fence(rel(s, 2, 0), y + 1, Blocks.SPRUCE_FENCE);
        fence(rel(s, 2, 1), y + 1, Blocks.SPRUCE_FENCE);
        // Груз: сундук с добычей, бочка, тюк сена, рулоны ткани.
        int[] c = rel(s, -1, 0);
        put(c, y + 2, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, dir(s.facing() + 1)));
        if (sink.owns(c[0], c[1])) {
            sink.loot(c[0], y + 2, c[1], LOOT_CART);
        }
        put(rel(s, -1, 1), y + 2, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, dir(s.facing())));
        put(rel(s, 0, 1), y + 2, Blocks.HAY_BLOCK.defaultBlockState());
        put(rel(s, 0, 0), y + 2, Blocks.WHITE_WOOL.defaultBlockState());
        put(rel(s, 1, 0), y + 2, Blocks.YELLOW_CARPET.defaultBlockState());
    }

    /** Стойка с оружием: дощатая стенка, на ней рамки с мечами и луком; точило рядом. */
    private void rack(CampLayout.Spot s, int y) {
        footprint(s, y, -1, 2, -2, 2, 3, true);
        for (int side = -1; side <= 1; side++) {
            put(rel(s, 0, side), y + 1, Blocks.SPRUCE_PLANKS.defaultBlockState());
            put(rel(s, 0, side), y + 2, Blocks.SPRUCE_SLAB.defaultBlockState());
        }
        Direction face = dir(s.facing());
        ItemStack[] items = {new ItemStack(Items.STONE_SWORD), new ItemStack(Items.BOW), new ItemStack(Items.IRON_SWORD)};
        for (int side = -1; side <= 1; side++) {
            int[] f = rel(s, 1, side);
            if (sink.owns(f[0], f[1])) {
                sink.frame(f[0], y + 1, f[1], face, items[side + 1]);
            }
        }
        put(rel(s, 0, 2), y + 1, Blocks.GRINDSTONE.defaultBlockState()
                .setValue(GrindstoneBlock.FACE, AttachFace.FLOOR).setValue(GrindstoneBlock.FACING, face));
    }

    /** Склад: ящик с добычей (бочка-ящик или сундук), рядом пустые бочки и компостер-короб. */
    private void crate(CampLayout.Spot s, int y) {
        footprint(s, y, -1, 1, -2, 2, 3, true);
        int[] c = rel(s, 0, 0);
        if (s.variant() == 0) {
            put(c, y + 1, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, dir(s.facing())));
        } else {
            put(c, y + 1, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        }
        if (sink.owns(c[0], c[1])) {
            sink.loot(c[0], y + 1, c[1], LOOT_CRATE);
        }
        put(rel(s, 0, -1), y + 1, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, dir(s.facing() + 1)));
        put(rel(s, -1, -1), y + 1, Blocks.COMPOSTER.defaultBlockState());
        put(rel(s, -1, -1), y + 2, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        put(rel(s, 0, 1), y + 1, Blocks.HAY_BLOCK.defaultBlockState());
    }

    // ------------------------------------------------------------------ опора постройки

    /**
     * Опора под постройку в прямоугольнике (вдоль: a0..a1, поперёк: s0..s1): ниже опорной высоты —
     * подсыпать, выше — срезать склон на {@code clear} блоков над полом. Верх подсыпки — утоптанная
     * земля, если {@code trample}. Край подсыпки — сухая кладка из булыжника, по верху — лежачее
     * бревно-брус: на склоне видна подпорная стенка террасы, а не бок земляной горки (замечание 04.10).
     */
    private void footprint(CampLayout.Spot s, int y, int a0, int a1, int s0, int s1, int clear, boolean trample) {
        for (int a = a0; a <= a1; a++) {
            for (int side = s0; side <= s1; side++) {
                int[] p = rel(s, a, side);
                if (!sink.owns(p[0], p[1])) {
                    continue;
                }
                int g = sink.ground(p[0], p[1]);
                if (g > y + 3 || g < y - 4) {
                    // Обрыв или скала: не строить фундамент-колонну, пусть край висит над склоном.
                    continue;
                }
                boolean edge = a == a0 || a == a1 || side == s0 || side == s1;
                for (int k = g + 1; k < y; k++) {
                    sink.set(p[0], k, p[1], edge ? rubble(p[0], k, p[1]) : Blocks.DIRT.defaultBlockState());
                }
                if (edge && g < y) {
                    // Брус по краю террасы: вдоль края.
                    boolean alongSide = a == a0 || a == a1;
                    Direction.Axis axis = alongSide ? axisAlong(s.facing() + 1) : axisAlong(s.facing());
                    sink.set(p[0], y, p[1], Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis));
                } else if (g <= y) {
                    // Верх опоры — тропа или глина: на земле и грубой земле проросли бы деревья
                    // (котёл кадров 04.10: дерево посреди лагеря у костра).
                    sink.set(p[0], y, p[1], trample ? Blocks.DIRT_PATH.defaultBlockState() : Blocks.PACKED_MUD.defaultBlockState());
                } else {
                    sink.set(p[0], y, p[1], trample ? Blocks.DIRT_PATH.defaultBlockState() : Blocks.PACKED_MUD.defaultBlockState());
                }
                clearAbove(p[0], y + 1, p[1], Math.max(clear, g - y));
            }
        }
    }

    /** Сухая кладка: булыжник с мшистым и кое-где бревном (по хэшу клетки). */
    private BlockState rubble(int x, int y, int z) {
        long h = CampLayout.hash(plan.seed() ^ y, x, z) % 10L;
        return (h < 6 ? Blocks.COBBLESTONE : h < 9 ? Blocks.MOSSY_COBBLESTONE : Blocks.ANDESITE).defaultBlockState();
    }

    /** Ось вдоль направления {@code facing} (0 юг, 1 запад, 2 север, 3 восток). */
    private static Direction.Axis axisAlong(int facing) {
        return (facing & 1) == 0 ? Direction.Axis.Z : Direction.Axis.X;
    }

    // ------------------------------------------------------------------ земля у ворот и на тропе

    /** Полублоки-ступени на тропе: булыжник, мшистый по хэшу. */
    private boolean stepSlab(BlockState st) {
        return st.is(Blocks.COBBLESTONE_SLAB) || st.is(Blocks.MOSSY_COBBLESTONE_SLAB);
    }

    /** Клетка тропы (двор от ворот к костру, проём ворот, тропа в лес) и не кол частокола. */
    private boolean walkway(int dx, int dz) {
        if (stake(dx, dz)) {
            return false;
        }
        return (plan.inside(dx, dz) && plan.onPath(dx, dz)) || (!plan.inside(dx, dz) && plan.onTrail(dx, dz)) || gateCell(dx, dz);
    }

    private boolean stake(int dx, int dz) {
        for (CampLayout.Stake st : plan.stakes()) {
            if (st.dx() == dx && st.dz() == dz) {
                return true;
            }
        }
        return false;
    }

    private boolean gateCell(int dx, int dz) {
        for (int[] c : plan.gateCells()) {
            if (c[0] == dx && c[1] == dz) {
                return true;
            }
        }
        return false;
    }

    /** Проём ворот и по два шага тропы по обе стороны — выровнены на высоту ворот. */
    private boolean gateApron(int dx, int dz) {
        int[] g = plan.gateCentre();
        double ddx = dx - g[0], ddz = dz - g[1];
        return walkway(dx, dz) && ddx * ddx + ddz * ddz <= 2.6D * 2.6D;
    }

    /**
     * Высота тропы в клетке без учёта своих ступеней: у ворот — высота ворот, иначе — рельеф.
     * {@code Integer.MIN_VALUE} — под ногами не земля тропы (листва, бревно, вода).
     */
    private int walkHeight(int x, int z) {
        int dx = x - cx, dz = z - cz;
        if (gateApron(dx, dz)) {
            return gateY();
        }
        int g = sink.ground(x, z);
        BlockState top = sink.get(x, g, z);
        if (stepSlab(top)) {
            return g - 1;
        }
        boolean soil = top.is(Blocks.DIRT_PATH) || top.is(Blocks.GRAVEL) || top.is(Blocks.PACKED_MUD) || top.is(Blocks.GRASS_BLOCK)
                || top.is(Blocks.DIRT) || top.is(Blocks.COARSE_DIRT) || top.is(Blocks.PODZOL) || top.is(Blocks.MYCELIUM);
        return soil ? g : Integer.MIN_VALUE;
    }

    /**
     * Ровная земля у ворот (автор: «ступенчатая земля у ворот»): проём ворот и два шага тропы с каждой
     * стороны выровнены на высоту ворот (подсыпка — земля под тропой, срез — убрать лишнее). Дальше по тропе
     * уступ в один блок сглажен полублоком-ступенью из булыжника на нижней клетке.
     */
    private void gateGround() {
        int r = plan.radius() + CampLayout.TRAIL_LENGTH + 1;
        int gy = gateY();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = cx + dx, z = cz + dz;
                if (!sink.owns(x, z) || !gateApron(dx, dz)) {
                    continue;
                }
                int g = sink.ground(x, z);
                if (g < gy - 3 || g > gy + 3) {
                    continue;
                }
                for (int k = g + 1; k < gy; k++) {
                    sink.set(x, k, z, Blocks.DIRT.defaultBlockState());
                }
                sink.set(x, gy, z, Blocks.DIRT_PATH.defaultBlockState());
                clearAbove(x, gy + 1, z, Math.max(4, g - gy));
            }
        }
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = cx + dx, z = cz + dz;
                if (!sink.owns(x, z) || !walkway(dx, dz) || gateApron(dx, dz)) {
                    continue;
                }
                int h = walkHeight(x, z);
                if (h == Integer.MIN_VALUE) {
                    continue;
                }
                boolean up = false;
                for (int f = 0; f < 4 && !up; f++) {
                    int[] st = CampLayout.step(f);
                    if (walkway(dx + st[0], dz + st[1])) {
                        up = walkHeight(x + st[0], z + st[1]) == h + 1;
                    }
                }
                if (up && sink.get(x, h + 1, z).isAir()) {
                    Block slab = (CampLayout.hash(plan.seed(), x, z) & 3L) == 0L ? Blocks.MOSSY_COBBLESTONE_SLAB : Blocks.COBBLESTONE_SLAB;
                    sink.set(x, h + 1, z, slab.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                }
            }
        }
    }

    private void clearAbove(int x, int from, int z, int n) {
        for (int k = 0; k < n; k++) {
            BlockState st = sink.get(x, from + k, z);
            if (!st.isAir() && !st.is(Blocks.WATER)) {
                sink.set(x, from + k, z, Blocks.AIR.defaultBlockState());
            }
        }
    }

    // ------------------------------------------------------------------ помощники

    /** Мировые x, z точки «вдоль a, поперёк side» от постройки (вдоль — к входу, к костру). */
    private int[] rel(CampLayout.Spot s, int a, int side) {
        int[] f = CampLayout.step(s.facing());
        int[] r = CampLayout.step(s.facing() + 1);
        return new int[] {cx + s.dx() + f[0] * a + r[0] * side, cz + s.dz() + f[1] * a + r[1] * side};
    }

    private void put(int[] xz, int y, BlockState st) {
        put(xz[0], y, xz[1], st);
    }

    private void put(int x, int y, int z, BlockState st) {
        if (sink.owns(x, z)) {
            sink.set(x, y, z, st);
        }
    }

    private void fence(int[] xz, int y, Block fence) {
        fence(xz[0], y, xz[1], fence);
    }

    private void fence(int x, int y, int z, Block fence) {
        put(x, y, z, fence.defaultBlockState());
    }

    private static Direction dir(int facing) {
        return Direction.from2DDataValue(facing & 3);
    }

    private static Direction.Axis axisAcross(int facing) {
        return (facing & 1) == 0 ? Direction.Axis.X : Direction.Axis.Z;
    }

    /**
     * Заборы, лестницы, цепи и фонари досчитывают форму по соседям при доводке чанка — как
     * {@code StructurePiece#placeBlock} (API: reference/minecraft-src/.../structure/StructurePiece.java#SHAPE_CHECK_BLOCKS).
     */
    public static boolean shapeChecked(BlockState st) {
        return st.is(Blocks.SPRUCE_FENCE) || st.is(Blocks.LADDER) || st.hasProperty(BlockStateProperties.NORTH);
    }
}
