package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.world.hua.MountHuaPlan;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Где на горе площадки секты в мировых координатах. Распорядок ({@link SectSchedule}) говорит
 * «площадка + смещение в локальной рамке горы»; раскладка переводит это в точку мира и поворот.
 * Гора — {@link #hua}; GameTest подставляет свою раскладку на маленькой площадке.
 */
public interface SectLayout {

    /** Точка мира: смещение {@code (du, dv)} от центра площадки; {@code y} — высота площадки. null — площадки нет. */
    Vec3 at(String zone, double du, double dv);

    /** Мировой поворот (yaw) для локального направления {@code (du, dv)}. */
    float yaw(double du, double dv);

    /** Половины размеров площадки по {@code u} и {@code v}; null — площадки нет. */
    double[] half(String zone);

    /** Смещение точки мира от центра площадки в локальной рамке {@code (du, dv)}; null — площадки нет. */
    double[] local(String zone, Vec3 pos);

    /** Точка внутри площадки (с запасом {@code margin}) — только по горизонтали. */
    default boolean inside(String zone, Vec3 pos, double margin) {
        double[] l = local(zone, pos);
        double[] h = half(zone);
        return l != null && h != null && Math.abs(l[0]) <= h[0] + margin && Math.abs(l[1]) <= h[1] + margin;
    }

    /** Раскладка горы Хуа. */
    static SectLayout hua(MountHuaSite site) {
        return new Hua(site);
    }

    /**
     * Не площадка, а последний пролёт тропы под воротами секты: {@code at(STAIR, t, 0)} — точка на доле {@code t}
     * пути от верхнего конца тропы к Золотому Замку (носильщики поднимают груз по ступеням).
     */
    String STAIR = "stair";

    /** Площадки секты — всё, что на полке перед Южным пиком (без нижних ворот и беседок на пиках). */
    List<String> SECT_ZONES = List.of("sect_gate", "training", "bell", "mentor", "sparring", "main_hall", "ancestors",
            "scriptures", "elders", "treasury", "camp", "dorm_2nd", "dorm_3rd", "dining", "poles", "alchemy", "grove",
            "vault", "penance");

    /** Раскладка по плану горы: только чтение {@link MountHuaPlan#ZONES} и {@link MountHuaSite}. */
    final class Hua implements SectLayout {

        private final MountHuaSite site;
        private final Map<String, MountHuaPlan.Zone> zones = new HashMap<>();

        Hua(MountHuaSite site) {
            this.site = site;
            for (MountHuaPlan.Zone z : MountHuaPlan.ZONES) {
                zones.put(z.id(), z);
            }
        }

        public MountHuaSite site() {
            return site;
        }

        @Override
        public Vec3 at(String zone, double du, double dv) {
            if (STAIR.equals(zone)) {
                // Последний пролёт тропы: от точки у ворот к Золотому Замку, высота — по тропе.
                List<MountHuaPlan.TrailPoint> trail = MountHuaPlan.TRAIL;
                MountHuaPlan.TrailPoint top = trail.get(trail.size() - 1);
                MountHuaPlan.TrailPoint lock = trail.get(trail.size() - 2);
                double t = Math.max(0.0D, Math.min(1.0D, du));
                int[] w = site.toWorld(top.u() + (lock.u() - top.u()) * t, top.v() + (lock.v() - top.v()) * t);
                return new Vec3(w[0] + 0.5D, Math.round(site.worldY(top.y() + (lock.y() - top.y()) * t)) + 1.0D, w[1] + 0.5D);
            }
            MountHuaPlan.Zone z = zones.get(zone);
            if (z == null) {
                return null;
            }
            // Центр блока: toWorld округляет вниз до сетки.
            int[] w = site.toWorld(z.u() + du, z.v() + dv);
            return new Vec3(w[0] + 0.5D, Math.round(site.worldY(z.y())) + 1.0D, w[1] + 0.5D);
        }

        @Override
        public float yaw(double du, double dv) {
            double dx;
            double dz;
            switch (site.rotation()) {
                case 1 -> {
                    dx = -dv;
                    dz = du;
                }
                case 2 -> {
                    dx = -du;
                    dz = -dv;
                }
                case 3 -> {
                    dx = dv;
                    dz = -du;
                }
                default -> {
                    dx = du;
                    dz = dv;
                }
            }
            return yawOf(dx, dz);
        }

        @Override
        public double[] half(String zone) {
            MountHuaPlan.Zone z = zones.get(zone);
            return z == null ? null : new double[] {z.width() / 2.0D, z.depth() / 2.0D};
        }

        @Override
        public double[] local(String zone, Vec3 pos) {
            MountHuaPlan.Zone z = zones.get(zone);
            if (z == null) {
                return null;
            }
            // Центр блока площадки — та же сетка, что в at(): toWorld округляет вниз.
            return new double[] {site.localU(pos.x - 0.5D, pos.z - 0.5D) - z.u(), site.localV(pos.x - 0.5D, pos.z - 0.5D) - z.v()};
        }
    }

    /** Поворот Minecraft: 0 — на юг (+z), 90 — на запад (−x). */
    static float yawOf(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
