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

    /**
     * The built sect as data (docs/design/38-sect-ai-locations.md): rooms, beds, spots and the waypoint graph.
     * Null: this layout has none (test yards, foreign worlds); the plan zones above stay the fallback.
     */
    default SectLayoutFile file() {
        return null;
    }

    /** Teaching context for this layout; isolated yards can supply their own transient session. */
    default SectLessons lessons(net.minecraft.server.level.ServerLevel level) {
        return SectLessons.of(level);
    }

    /** Раскладка горы Хуа. */
    static SectLayout hua(MountHuaSite site) {
        return new Hua(site);
    }

    /** Площадки секты — всё, что на полке перед Южным пиком (без нижних ворот и беседок на пиках). */
    List<String> SECT_ZONES = List.of("sect_gate", "training", "poles", "bell", "mentor", "arch", "main_hall", "ancestors",
            "scriptures", "elders", "treasury", "camp", "dorm_2nd", "dorm_3rd", "dining", "alchemy", "grove",
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

        /** The built sect exists only on the canonical mountain it was baked on; an old world without the bake has none. */
        @Override
        public SectLayoutFile file() {
            return site.canonical() ? SectLayoutFile.anchored(site) : null;
        }

        /**
         * Places the author built himself are read from the layout file when it has them, not from the plan: the penance cave
         * stands where the extractor found its seal (author 18.10: the plan put it outside the mountain, unbuilt).
         */
        private static final java.util.Set<String> BUILT_ZONES = java.util.Set.of("penance");

        private SectLayoutFile.Zone built(String zone) {
            if (!BUILT_ZONES.contains(zone)) {
                return null;
            }
            SectLayoutFile f = file();
            return f == null ? null : f.zone(zone);
        }

        private double builtCentreX(SectLayoutFile.Zone d) {
            return (d.rect().x0() + d.rect().x1() + 1) / 2.0D;
        }

        private double builtCentreZ(SectLayoutFile.Zone d) {
            return (d.rect().z0() + d.rect().z1() + 1) / 2.0D;
        }

        @Override
        public Vec3 at(String zone, double du, double dv) {
            SectLayoutFile.Zone d = built(zone);
            if (d != null) {
                // The local offset turns with the site like a direction does (see yaw); y is the feet level of the floor.
                double[] w = worldDelta(du, dv);
                return new Vec3(builtCentreX(d) + w[0], d.y(), builtCentreZ(d) + w[1]);
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

        /** A local direction (du, dv) as a world (dx, dz) vector for this site's rotation. */
        private double[] worldDelta(double du, double dv) {
            return switch (site.rotation()) {
                case 1 -> new double[] {-dv, du};
                case 2 -> new double[] {-du, -dv};
                case 3 -> new double[] {dv, -du};
                default -> new double[] {du, dv};
            };
        }

        @Override
        public double[] half(String zone) {
            SectLayoutFile.Zone d = built(zone);
            if (d != null) {
                double w = d.rect().x1() - d.rect().x0() + 1;
                double dp = d.rect().z1() - d.rect().z0() + 1;
                return (site.rotation() & 1) == 0 ? new double[] {w / 2.0D, dp / 2.0D} : new double[] {dp / 2.0D, w / 2.0D};
            }
            MountHuaPlan.Zone z = zones.get(zone);
            return z == null ? null : new double[] {z.width() / 2.0D, z.depth() / 2.0D};
        }

        @Override
        public double[] local(String zone, Vec3 pos) {
            SectLayoutFile.Zone d = built(zone);
            if (d != null) {
                double dx = pos.x - builtCentreX(d);
                double dz = pos.z - builtCentreZ(d);
                return switch (site.rotation()) {
                    case 1 -> new double[] {dz, -dx};
                    case 2 -> new double[] {-dx, -dz};
                    case 3 -> new double[] {-dz, dx};
                    default -> new double[] {dx, dz};
                };
            }
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
