package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Дождь на горе (автор 05.10: «под дождь уходят»): дело под открытым небом прерывается — строй, поединки, столбы,
 * хозяйство, трапеза во дворе. Человек уходит под крышу рядом с местом дела: любой блок над головой, который держит дождь
 * (постройки автора, навес, крона дерева); нет крыши — к стене. Дело под крышей (зал автора, кровать в общежитии) идёт как
 * обычно. Кончился дождь — все возвращаются сами: распорядок пересчитывается раз в секунду.
 *
 * <p>Крыша — то же условие, по которому ваниль решает, идёт ли дождь в точке: верх карты высот {@code MOTION_BLOCKING}
 * выше головы. API: reference/minecraft-src/net/minecraft/world/level/Level.java#isRainingAt.
 *
 * <p>Поиск дорогой (до {@value #SEARCH} блоков вокруг), поэтому место запоминается у человека и ищется заново, только
 * если сменилось место дела; не нашлось — повтор через 10 секунд.
 */
public final class SectWeather {

    /** Радиус поиска укрытия по горизонтали, блоков. */
    public static final int SEARCH = 18;
    /** Укрытие ищется на столько блоков выше и ниже места дела (пол постройки, ступени). */
    static final int RISE = 4;
    /** Не нашлось укрытия — повтор через столько тиков. */
    static final int RETRY = 200;
    /** Двое не встают ближе этого. */
    static final double PERSONAL = 0.9D;

    private SectWeather() {
    }

    /** Идёт ли дождь вокруг человека (GameTest задаёт сам, иначе погода мира; снег на горе — тоже повод уйти под крышу). */
    public static boolean wet(SectDisciple npc) {
        Boolean o = npc.rainOverride();
        return o != null ? o : npc.level().isRaining();
    }

    /** Над головой стоящего в {@code feet} есть крыша (блок, который держит дождь). */
    public static boolean covered(Level level, BlockPos feet) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, feet.getX(), feet.getZ()) > feet.getY() + 1;
    }

    /** Дела, которые дождь не прерывает: пост, встреча у ворот, лечение, доклад главе. */
    static boolean stays(SectSchedule.Kind k) {
        return k == SectSchedule.Kind.GUARD || k == SectSchedule.Kind.GREET || k == SectSchedule.Kind.TREAT
                || k == SectSchedule.Kind.WAIT_TREAT || k == SectSchedule.Kind.REPORT || k == SectSchedule.Kind.SHELTER;
    }

    /**
     * Распорядок с поправкой на дождь: место дела под открытым небом — укрытие рядом. Сидячее дело (трапеза, вечерний
     * круг, сон без кровати, совет) идёт сидя под крышей; остальное прерывается — человек стоит под крышей
     * ({@link SectSchedule.Kind#SHELTER}) лицом к месту дела.
     */
    public static SectLife.Resolved apply(SectDisciple npc, SectLife.Resolved r) {
        if (r == null) {
            return null;
        }
        if (!wet(npc)) {
            // Дождь кончился: укрытие свободно для других в следующий дождь.
            if (npc.shelterFor() != null) {
                npc.setShelter(null, null, Long.MIN_VALUE);
            }
            return r;
        }
        // Смотр идёт и в дождь: боец сетки остаётся в ринге.
        if (stays(r.task().kind()) || SectReview.fighterTask(npc) != null) {
            return r;
        }
        Level level = npc.level();
        BlockPos feet = BlockPos.containing(r.spot());
        if (!level.isLoaded(feet) || covered(level, feet)) {
            return r;
        }
        Vec3 cover = shelter(npc, r.spot());
        SectSchedule.Task t = r.task();
        if (cover == null) {
            // Ни крыши, ни стены рядом: дело всё равно прервано (поединок, строй), стоит на месте и мокнет.
            return t.kind().seated() ? r
                    : new SectLife.Resolved(new SectSchedule.Task(SectSchedule.Kind.SHELTER, t.zone(), t.du(), t.dv(), t.faceU(), t.faceV()),
                    r.spot(), r.yaw());
        }
        Vec3 out = r.spot().subtract(cover);
        float yaw = out.horizontalDistanceSqr() < 0.04D ? r.yaw() : SectLayout.yawOf(out.x, out.z);
        if (t.kind().seated()) {
            return new SectLife.Resolved(t, cover, yaw);
        }
        return new SectLife.Resolved(new SectSchedule.Task(SectSchedule.Kind.SHELTER, t.zone(), t.du(), t.dv(), t.faceU(), t.faceV(),
                t.partner()), cover, yaw);
    }

    /** Укрытие для места дела {@code from}: запомненное или новое. */
    static Vec3 shelter(SectDisciple npc, Vec3 from) {
        Vec3 known = npc.shelterFor();
        long now = npc.level().getGameTime();
        if (known != null && known.distanceToSqr(from) < 1.0D) {
            Vec3 s = npc.shelterSpot();
            if (s != null || now < npc.shelterRetry()) {
                return s;
            }
        }
        Vec3 found = find(npc, from);
        npc.setShelter(from, found, now + RETRY);
        io.github.verycooltimo.murim.MurimMod.LOGGER.info("Секта: дождь — {} {}", npc.memberKey(), found == null ? "мокнет на месте"
                : covered(npc.level(), BlockPos.containing(found)) ? "под крышу в " + String.format("%.1f", found.distanceTo(from)) + " бл."
                : "к стене в " + String.format("%.1f", found.distanceTo(from)) + " бл.");
        return found;
    }

    /**
     * Ближайшее к {@code from} место под крышей, где можно стоять и которое не заняли другие; нет крыши — ближайшее место у
     * стены (сплошной блок на уровне головы рядом). Кольцами от центра: первое кольцо с местом — ближайшее в нём.
     */
    static Vec3 find(SectDisciple npc, Vec3 from) {
        Level level = npc.level();
        BlockPos c = BlockPos.containing(from);
        List<Vec3> taken = new ArrayList<>();
        for (SectDisciple d : level.getEntitiesOfClass(SectDisciple.class, npc.getBoundingBox().inflate(SEARCH + 24.0D),
                d -> d != npc && d.isAlive())) {
            if (d.shelterSpot() != null) {
                taken.add(d.shelterSpot());
            }
        }
        Vec3 wall = null;
        double wallD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int r = 0; r <= SEARCH; r++) {
            Vec3 best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int x = c.getX() + dx;
                    int z = c.getZ() + dz;
                    p.set(x, c.getY(), z);
                    if (!level.isLoaded(p)) {
                        continue;
                    }
                    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                    for (int dy = -RISE; dy <= RISE; dy++) {
                        int y = c.getY() + dy;
                        p.set(x, y, z);
                        if (!SectLife.standable(level, p)) {
                            continue;
                        }
                        Vec3 spot = new Vec3(x + 0.5D, y, z + 0.5D);
                        if (occupied(taken, spot)) {
                            continue;
                        }
                        double d = spot.distanceToSqr(from) + dy * dy * 2.0D;
                        if (top > y + 1) {
                            if (d < bestD) {
                                bestD = d;
                                best = spot;
                            }
                        } else if (best == null && d < wallD && byWall(level, p)) {
                            wallD = d;
                            wall = spot;
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return wall;
    }

    private static boolean occupied(List<Vec3> taken, Vec3 spot) {
        for (Vec3 t : taken) {
            if (t.distanceToSqr(spot) < PERSONAL * PERSONAL) {
                return true;
            }
        }
        return false;
    }

    /** Рядом на уровне головы — сплошной блок (стена, ствол). */
    static boolean byWall(Level level, BlockPos feet) {
        BlockPos head = feet.above();
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos n = head.relative(d);
            if (level.getBlockState(n).isCollisionShapeFullBlock(level, n)) {
                return true;
            }
        }
        return false;
    }
}
