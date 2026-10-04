package io.github.verycooltimo.murim.world.camp;

import com.mojang.serialization.MapCodec;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Arrays;
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
        ChunkGenerator gen = context.chunkGenerator();
        int r = CampLayout.MAX_RADIUS + 1;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int[][] probes = {{0, 0}, {r, 0}, {-r, 0}, {0, r}, {0, -r}, {r * 7 / 10, r * 7 / 10},
                {-r * 7 / 10, r * 7 / 10}, {r * 7 / 10, -r * 7 / 10}, {-r * 7 / 10, -r * 7 / 10}};
        for (int[] p : probes) {
            int surface = gen.getBaseHeight(x + p[0], z + p[1], Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
            int floor = gen.getBaseHeight(x + p[0], z + p[1], Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState());
            if (surface != floor) {
                // Вода под лагерем: озеро, река или болото.
                return Optional.empty();
            }
            min = Math.min(min, floor);
            max = Math.max(max, floor);
        }
        if (max - min > MAX_RELIEF || min <= gen.getSeaLevel() || max > 170) {
            return Optional.empty();
        }
        int centreY = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState());
        long seed = context.random().nextLong();
        return Optional.of(new GenerationStub(new BlockPos(x, centreY, z), builder -> {
            CampLayout plan = CampLayout.plan(seed);
            int[] heights = new int[plan.spots().size() + 1];
            for (int i = 0; i < plan.spots().size(); i++) {
                CampLayout.Spot s = plan.spots().get(i);
                heights[i] = median(gen, context, x + s.dx(), z + s.dz());
            }
            int[] g = plan.gateCentre();
            heights[heights.length - 1] = median(gen, context, x + g[0], z + g[1]);
            int lo = Arrays.stream(heights).min().orElse(centreY) - 8;
            int hi = Arrays.stream(heights).max().orElse(centreY) + 12;
            int R = plan.radius() + 3;
            builder.addPiece(new BanditCampPiece(seed, x, z, heights, new BoundingBox(x - R, lo, z - R, x + R, hi, z + R)));
        }));
    }

    /** Опорная высота постройки — медиана пяти точек рельефа (верхний твёрдый блок). */
    private static int median(ChunkGenerator gen, GenerationContext context, int x, int z) {
        int[] v = new int[5];
        int[][] o = {{0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int i = 0; i < 5; i++) {
            v[i] = gen.getBaseHeight(x + o[i][0], z + o[i][1], Heightmap.Types.OCEAN_FLOOR_WG,
                    context.heightAccessor(), context.randomState()) - 1;
        }
        Arrays.sort(v);
        return v[2];
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
