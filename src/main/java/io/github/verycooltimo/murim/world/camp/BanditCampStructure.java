package io.github.verycooltimo.murim.world.camp;

import com.mojang.serialization.MapCodec;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import io.github.verycooltimo.murim.world.location.CapturedLocation;
import io.github.verycooltimo.murim.world.location.Ground;
import io.github.verycooltimo.murim.world.location.LocationTemplatePiece;
import io.github.verycooltimo.murim.world.location.LocationTemplates;
import io.github.verycooltimo.murim.world.location.ModLocations;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Лагерь бандитов как структура мира (docs/design/24-bandit-camp.md §2): находится {@code /locate
 * structure murim:bandit_camp}, редкий (structure_set: сетка 34 чанка), только в лесах, тайге и
 * холмах (тег {@code murim:has_structure/bandit_camp}), не у горы Хуашань и не рядом с деревнями.
 *
 * <p>Почему процедурный кусок, а не jigsaw/NBT: лагерь обязан лечь на рельеф без плиты. Шаблон
 * NBT ставится одной коробкой на одну высоту (rigid) или гнёт каждый блок по поверхности
 * (terrain_matching — шатры и вышка тогда ломаются). Здесь частокол идёт по высоте каждой колонки,
 * а постройки стоят на своей опорной высоте с подсыпкой только под собой. Плюс зерно лагеря даёт
 * свою раскладку каждый раз, без набора вручную собранных шаблонов.
 *
 * <p>Место отбирается дёшево (9 точек высот): {@code /locate} перебирает много кандидатов и
 * вызывает только эту проверку; полные высоты построек считаются уже при сборке куска.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/structure/Structure.java
 * (#findGenerationPoint, #simpleCodec, GenerationStub), .../structures/SwampHutStructure.java (образец),
 * reference/minecraft-src/net/minecraft/world/level/chunk/ChunkGenerator.java#getBaseHeight.
 */
public class BanditCampStructure extends Structure {

    public static final MapCodec<BanditCampStructure> CODEC = simpleCodec(BanditCampStructure::new);

    /** Перепад высот под лагерем, больше которого место отвергается: склон, а не площадка. */
    static final int MAX_RELIEF = 7;

    /** Сколько блоков держаться от коробки массива Хуашань. */
    static final int HUA_MARGIN = 128;

    public BanditCampStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        int x = context.chunkPos().getMiddleBlockX();
        int z = context.chunkPos().getMiddleBlockZ();
        if (nearMountHua(x, z)) {
            return Optional.empty();
        }
        Ground ground = Ground.of(context);
        int centreY = site(ground, x, z, context.chunkGenerator().getSeaLevel());
        if (centreY == Integer.MIN_VALUE) {
            return Optional.empty();
        }
        long seed = context.random().nextLong();
        // Захваченный автором лагерь (docs/design/28-location-capture.md): поворот — лишний бросок только при
        // шаблоне, процедурные лагеря того же зерна мира не сдвигаются.
        CapturedLocation tpl = LocationTemplates.get(ModLocations.CAMP);
        Rotation rot = tpl == null ? Rotation.NONE : Rotation.getRandom(context.random());
        return Optional.of(new GenerationStub(new BlockPos(x, centreY, z),
                builder -> pieces(seed, x, z, ground, tpl, rot).forEach(builder::addPiece)));
    }

    /**
     * Проверка места (9 точек): вода, перепад, высота. Возвращает высоту центра (над верхним твёрдым блоком)
     * или {@link Integer#MIN_VALUE}, если место не годится.
     */
    static int site(Ground ground, int x, int z, int seaLevel) {
        int r = CampLayout.MAX_RADIUS + 1;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int[][] probes = {{0, 0}, {r, 0}, {-r, 0}, {0, r}, {0, -r}, {r * 7 / 10, r * 7 / 10},
                {-r * 7 / 10, r * 7 / 10}, {r * 7 / 10, -r * 7 / 10}, {-r * 7 / 10, -r * 7 / 10}};
        for (int[] p : probes) {
            if (!ground.dry(x + p[0], z + p[1])) {
                // Вода под лагерем: озеро, река или болото.
                return Integer.MIN_VALUE;
            }
            int floor = ground.floor(x + p[0], z + p[1]) + 1;
            min = Math.min(min, floor);
            max = Math.max(max, floor);
        }
        if (max - min > MAX_RELIEF || min <= seaLevel || max > 170) {
            return Integer.MIN_VALUE;
        }
        return ground.floor(x, z) + 1;
    }

    /**
     * Куски лагеря с центром в (x, z): процедурный кусок или, если автор захватил лагерь, его шаблон плюс
     * «оболочка» — тот же кусок без блоков, по которому жизнь лагеря находит центр, зерно и посты.
     * Общий для генерации и мира-витрины ({@link Ground#live}).
     */
    public static List<StructurePiece> pieces(long seed, int x, int z, Ground ground, CapturedLocation tpl, Rotation rot) {
        if (tpl != null) {
            Ground.Placement p = Ground.place(tpl, x, z, rot, ground);
            LocationTemplatePiece piece = new LocationTemplatePiece(tpl, p.origin(), rot);
            BlockPos centre = CapturedLocation.world(p.origin(), rot, tpl.anchor());
            long campSeed = tpl.seed() != 0L ? tpl.seed() : seed;
            // Поворот оболочки — поворот раскладки зерна: какой была у захваченного лагеря плюс поворот шаблона.
            Rotation layout = Rotation.values()[Math.floorMod(tpl.anchorRot(), 4)].getRotated(rot);
            return List.of(piece, BanditCampPiece.shell(campSeed, centre, layout, piece.getBoundingBox()));
        }
        CampLayout plan = CampLayout.plan(seed);
        int[] heights = new int[plan.spots().size() + 1];
        for (int i = 0; i < plan.spots().size(); i++) {
            CampLayout.Spot s = plan.spots().get(i);
            heights[i] = median(ground, x + s.dx(), z + s.dz());
        }
        int[] g = plan.gateCentre();
        heights[heights.length - 1] = median(ground, x + g[0], z + g[1]);
        int centreY = ground.floor(x, z) + 1;
        int lo = Arrays.stream(heights).min().orElse(centreY) - 8;
        int hi = Arrays.stream(heights).max().orElse(centreY) + 12;
        int R = plan.radius() + CampLayout.TRAIL_LENGTH + 2;
        return List.of(new BanditCampPiece(seed, x, z, heights, new BoundingBox(x - R, lo, z - R, x + R, hi, z + R)));
    }

    /** Опорная высота постройки — медиана пяти точек рельефа (верхний твёрдый блок). */
    private static int median(Ground ground, int x, int z) {
        int[] v = new int[5];
        int[][] o = {{0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int i = 0; i < 5; i++) {
            v[i] = ground.floor(x + o[i][0], z + o[i][1]);
        }
        Arrays.sort(v);
        return v[2];
    }

    /**
     * Шаблон автора подтягивает рельеф вокруг себя (beard_thin на куске шаблона); процедурные куски этого
     * не просят ({@link BanditCampPiece} отвечает NONE). Без шаблона — как в данных структуры.
     */
    @Override
    public TerrainAdjustment terrainAdaptation() {
        return LocationTemplates.get(ModLocations.CAMP) != null ? TerrainAdjustment.BEARD_THIN : super.terrainAdaptation();
    }

    /**
     * Не у горы Хуашань: место массива выбирается при старте сервера ({@link MountHuaSites}); до
     * старта генерируются только чанки у спавна, а гора от него не ближе 2700 блоков.
     * API: reference/neoforge-src/net/neoforged/neoforge/server/ServerLifecycleHooks.java#getCurrentServer
     */
    static boolean nearMountHua(int x, int z) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        MountHuaSite site = server == null ? null : MountHuaSites.get(server);
        return site != null && site.near(x, z, HUA_MARGIN);
    }

    @Override
    public StructureType<?> type() {
        return BanditCamp.STRUCTURE_TYPE.get();
    }
}
