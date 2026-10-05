package io.github.verycooltimo.murim.world.fortress;

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
        Ground ground = Ground.of(context);
        int[] sorted = probe(ground, x, z);
        if (sorted == null || sorted[sorted.length - 1] - sorted[0] > MAX_RELIEF
                || sorted[0] <= context.chunkGenerator().getSeaLevel() || sorted[sorted.length - 1] > 200) {
            return Optional.empty();
        }
        // Пол плаца — по медиане рельефа: на холме верх срезан, низ поднят цоколем.
        int y0 = sorted[sorted.length / 2] + 1;
        long seed = context.random().nextLong();
        // Крепость, захваченная автором (docs/design/28-location-capture.md): поворот шаблона — лишний бросок
        // только при шаблоне.
        CapturedLocation tpl = LocationTemplates.get(ModLocations.FORTRESS);
        Rotation rot = tpl == null ? Rotation.NONE : Rotation.getRandom(context.random());
        return Optional.of(new GenerationStub(new BlockPos(x, y0, z),
                builder -> pieces(seed, x, z, y0, sorted[0], ground, tpl, rot).forEach(builder::addPiece)));
    }

    /** Высоты рельефа (над верхним твёрдым блоком) в 12 пробах, по возрастанию; null — вода под площадкой. */
    static int[] probe(Ground ground, int x, int z) {
        int[][] probes = {{0, 0}, {14, 0}, {-14, 0}, {0, 14}, {0, -16}, {0, 28}, {12, 26}, {-12, 26}, {14, -16}, {-14, -16}, {12, 12}, {-12, 12}};
        int[] h = new int[probes.length];
        for (int i = 0; i < probes.length; i++) {
            // Поворот неизвестен до зерна — пробы симметричны по осям, хватает грубой оценки.
            if (!ground.dry(x + probes[i][0], z + probes[i][1])) {
                return null;
            }
            h[i] = ground.floor(x + probes[i][0], z + probes[i][1]) + 1;
        }
        Arrays.sort(h);
        return h;
    }

    /**
     * Куски крепости с плацем в (x, z): процедурный кусок или шаблон автора плюс оболочка (плац, поворот и зерно
     * для жизни крепости — кресло хозяина, сокровищница, дверь там же, где были при захвате).
     */
    public static List<StructurePiece> pieces(long seed, int x, int z, int y0, int lowest, Ground ground,
            CapturedLocation tpl, Rotation rot) {
        if (tpl != null) {
            Ground.Placement p = Ground.place(tpl, x, z, rot, ground);
            LocationTemplatePiece piece = new LocationTemplatePiece(tpl, p.origin(), rot);
            BlockPos yard = CapturedLocation.world(p.origin(), rot, tpl.anchor());
            Rotation fortressRot = Rotation.values()[Math.floorMod(tpl.anchorRot(), 4)].getRotated(rot);
            long fortressSeed = tpl.seed() != 0L ? tpl.seed() : seed;
            return List.of(piece, FortressPiece.shell(fortressSeed, yard, fortressRot, piece.getBoundingBox()));
        }
        int R = 34;
        return List.of(new FortressPiece(seed, x, y0, z,
                new BoundingBox(x - R, lowest - FortressBuilder.MAX_FOUNDATION, z - R, x + R, y0 + FortressBuilder.CLEAR, z + R)));
    }

    /** Шаблон тянет рельеф к своему уровню земли; процедурный кусок — нет (см. лагерь). */
    @Override
    public TerrainAdjustment terrainAdaptation() {
        return LocationTemplates.get(ModLocations.FORTRESS) != null ? TerrainAdjustment.BEARD_THIN : super.terrainAdaptation();
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
