package io.github.verycooltimo.murim.world.fortress;

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
 * Крепость Зелёного Леса как структура мира (docs/design/26-boss.md §2). Как лагерь — процедурный
 * кусок, а не NBT: площадка ложится на цоколь до земли, поворот и мелочи — из зерна.
 *
 * <p>Место: не ближе {@link #MIN_FROM_ORIGIN} блоков к началу мира (спавн рядом с ним), не у горы
 * Хуашань, без воды под площадкой, перепад рельефа не больше {@link #MAX_RELIEF}: склон холма даёт
 * видимый каменный цоколь, обрыв — нет.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/structure/Structure.java#findGenerationPoint,
 * образец — world/camp/BanditCampStructure.
 */
public class FortressStructure extends Structure {

    public static final MapCodec<FortressStructure> CODEC = simpleCodec(FortressStructure::new);

    static final int MAX_RELIEF = 14;
    static final int MIN_FROM_ORIGIN = 700;
    static final int HUA_MARGIN = 160;

    public FortressStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        int x = context.chunkPos().getMiddleBlockX();
        int z = context.chunkPos().getMiddleBlockZ();
        if ((long) x * x + (long) z * z < (long) MIN_FROM_ORIGIN * MIN_FROM_ORIGIN || nearMountHua(x, z)) {
            return Optional.empty();
        }
        ChunkGenerator gen = context.chunkGenerator();
        int[][] probes = {{0, 0}, {14, 0}, {-14, 0}, {0, 14}, {0, -16}, {0, 28}, {12, 26}, {-12, 26}, {14, -16}, {-14, -16}, {12, 12}, {-12, 12}};
        int[] h = new int[probes.length];
        for (int i = 0; i < probes.length; i++) {
            // Поворот неизвестен до зерна — пробы симметричны по осям, хватает грубой оценки.
            int surface = gen.getBaseHeight(x + probes[i][0], z + probes[i][1], Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
            int floor = gen.getBaseHeight(x + probes[i][0], z + probes[i][1], Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState());
            if (surface != floor) {
                return Optional.empty();
            }
            h[i] = floor;
        }
        int[] sorted = h.clone();
        Arrays.sort(sorted);
        if (sorted[sorted.length - 1] - sorted[0] > MAX_RELIEF || sorted[0] <= gen.getSeaLevel() || sorted[sorted.length - 1] > 200) {
            return Optional.empty();
        }
        // Пол плаца — по медиане рельефа: на холме верх срезан, низ поднят цоколем.
        int y0 = sorted[sorted.length / 2] + 1;
        long seed = context.random().nextLong();
        return Optional.of(new GenerationStub(new BlockPos(x, y0, z), builder -> {
            int R = 34;
            builder.addPiece(new FortressPiece(seed, x, y0, z,
                    new BoundingBox(x - R, sorted[0] - FortressBuilder.MAX_FOUNDATION, z - R, x + R, y0 + FortressBuilder.CLEAR, z + R)));
        }));
    }

    /** Не у горы Хуашань (как лагерь): место массива выбирается при старте сервера. */
    static boolean nearMountHua(int x, int z) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        MountHuaSite site = server == null ? null : MountHuaSites.get(server);
        return site != null && site.near(x, z, HUA_MARGIN);
    }

    @Override
    public StructureType<?> type() {
        return Fortress.STRUCTURE_TYPE.get();
    }
}
