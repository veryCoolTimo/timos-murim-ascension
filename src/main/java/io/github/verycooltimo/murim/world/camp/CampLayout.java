package io.github.verycooltimo.murim.world.camp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * План лагеря бандитов (docs/design/24-bandit-camp.md §2) — без мира: из зерна лагеря получаются
 * частокол, костёр, шатры, вышка, телега, стойка с оружием, ящики и посты бандитов. Координаты
 * локальные: (0, 0) — центр лагеря, x — восток, z — юг. Высоты ставит кусок структуры.
 *
 * <p>Один и тот же план строится заново из зерна и при генерации, и при заселении лагеря, поэтому
 * он обязан быть детерминированным: только {@link Random} с зерном лагеря и хэши координат.
 *
 * <p>Раскладка «по циферблату»: ворота на случайном угле, всё остальное — на своих секторах от
 * ворот, лицом к костру. Направления шатров округлены до сторон света — блоки не умеют косо.
 */
public final class CampLayout {

    /** Что стоит в лагере. */
    public enum Kind { FIRE, TENT, CHIEF_TENT, LEAN_TO, TOWER, CART, RACK, CRATE }

    /**
     * Предмет плана.
     *
     * @param facing  куда смотрит вход/лицо: 0 — юг (+z), 1 — запад (−x), 2 — север (−z), 3 — восток (+x)
     *                (как {@code Direction#get2DDataValue})
     * @param variant цвет шатра, тип ящика и т. п.
     */
    public record Spot(Kind kind, int dx, int dz, int facing, int variant) {
    }

    /** Пост бандита: где стоит без боя; {@code onTower} — наверху вышки. */
    public record Post(int dx, int dz, boolean onTower, boolean chief) {
    }

    /** Ячейка частокола: высота бревна над землёй; {@code gatePost} — столб ворот. */
    public record Stake(int dx, int dz, int height, boolean gatePost) {
    }

    /** Радиус частокола: 13–14 блоков (лагерь ~29 блоков в поперечнике). */
    public static final int MIN_RADIUS = 13;
    public static final int MAX_RADIUS = 14;

    /** Ширина ворот в ячейках частокола (по дуге), градусов. */
    static final double GATE_HALF_DEG = 7.5D;


    private final long seed;
    private int radius;
    private double gateAngle;
    private List<Spot> spots;
    private List<Stake> stakes;
    private final List<Post> posts;
    private List<int[]> gateCells;
    private int fireDx;
    private int fireDz;

    private CampLayout(long seed) {
        this.seed = seed;
        // Раскладка ищется перебором мест; если что-то обязательное не поместилось, пробуется
        // следующая производная раскладка того же зерна (детерминированно).
        for (int attempt = 0; ; attempt++) {
            taken.clear();
            Random r = new Random(seed + attempt * 0x9E3779B97F4A7C15L);
            this.radius = MIN_RADIUS + r.nextInt(MAX_RADIUS - MIN_RADIUS + 1);
            this.gateAngle = r.nextDouble() * Math.PI * 2.0D;
            double phaseA = r.nextDouble() * Math.PI * 2.0D;
            double phaseB = r.nextDouble() * Math.PI * 2.0D;
            this.fireDx = r.nextInt(3) - 1;
            this.fireDz = r.nextInt(3) - 1;

            // Частокол: замкнутое кольцо ячеек с лёгкой неровностью радиуса, ворота — разрыв.
            Set<Long> seen = new LinkedHashSet<>();
            List<Stake> ring = new ArrayList<>();
            List<int[]> gap = new ArrayList<>();
            int steps = 720;
            for (int i = 0; i < steps; i++) {
                double a = i * Math.PI * 2.0D / steps;
                double rr = radius + 0.7D * Math.sin(3.0D * a + phaseA) + 0.4D * Math.sin(5.0D * a + phaseB);
                int x = (int) Math.round(Math.cos(a) * rr);
                int z = (int) Math.round(Math.sin(a) * rr);
                if (!seen.add(key(x, z))) {
                    continue;
                }
                double off = Math.toDegrees(angleDiff(Math.atan2(z, x), gateAngle));
                if (Math.abs(off) < GATE_HALF_DEG) {
                    gap.add(new int[] {x, z});
                    continue;
                }
                boolean gatePost = Math.abs(off) < GATE_HALF_DEG + 5.0D;
                int h = gatePost ? 5 : 3 + (int) (hash(seed, x, z) & 1L);
                ring.add(new Stake(x, z, h, gatePost));
            }
            this.stakes = List.copyOf(ring);
            this.gateCells = List.copyOf(gap);

            // Постройки: по очереди, каждая — на первое свободное место из своих секторов (от ворот),
            // не на тропе ворота—костёр, не ближе клетки к соседям и к частоколу.
            List<Spot> s = new ArrayList<>();
            Spot fire = new Spot(Kind.FIRE, fireDx, fireDz, 0, 0);
            s.add(fire);
            occupy(fire, 0);
            add(s, place(Kind.CHIEF_TENT, r.nextInt(2), new int[] {180, 165, 195, 150, 210, 135, 225}, 4.5D, radius - 3.0D, 1));
            List<Integer> tentAngles = new ArrayList<>();
            for (int v : shuffled(r, 60, 305, 15)) {
                tentAngles.add(v);
            }
            int tents = 2 + r.nextInt(2);
            int placedTents = 0;
            for (int i = 0; i < tentAngles.size() && placedTents < tents; i++) {
                boolean leanTo = placedTents == 0 && r.nextBoolean();
                Spot t = place(leanTo ? Kind.LEAN_TO : Kind.TENT, r.nextInt(4), new int[] {tentAngles.get(i)}, 4.0D, radius - 2.0D, 1);
                if (t != null) {
                    s.add(t);
                    placedTents++;
                }
            }
            // Тесно — второй проход без отступа между шатрами: меньше двух простых шатров не бывает.
            for (int i = 0; i < tentAngles.size() && placedTents < 2; i++) {
                Spot t = place(Kind.TENT, r.nextInt(4), new int[] {tentAngles.get(i)}, 3.0D, radius - 2.0D, 0);
                if (t != null) {
                    s.add(t);
                    placedTents++;
                }
            }
            int sign = r.nextBoolean() ? 1 : -1;
            add(s, place(Kind.TOWER, 0, new int[] {40 * sign, 55 * sign, -40 * sign, -55 * sign, 70 * sign, -70 * sign, 90 * sign,
                    -90 * sign, 110 * sign, -110 * sign}, 4.0D, radius - 2.0D, 1));
            add(s, place(Kind.CART, r.nextInt(2), new int[] {300, 60, 315, 45, 280, 80, 250, 110, 330, 30, 230, 130}, 4.0D, radius - 2.0D, 0));
            add(s, place(Kind.RACK, 0, shuffled(r, 0, 360, 30), 3.5D, 9.0D, 0));
            add(s, place(Kind.CRATE, 0, shuffled(r, 20, 340, 20), 4.0D, radius - 2.5D, 0));
            add(s, place(Kind.CRATE, 1, shuffled(r, 20, 340, 20), 4.0D, radius - 2.5D, 0));
            this.spots = List.copyOf(s);
            if (complete() || attempt >= 24) {
                break;
            }
        }

        // Посты: главарь у своего шатра, лучник на вышке, привратник, двое у костра, у шатров, у телеги.
        List<Post> p = new ArrayList<>();
        Spot chief = findOrNull(Kind.CHIEF_TENT);
        int[] chiefFront = chief == null ? new int[] {fireDx, fireDz - 2} : front(chief, 4);
        p.add(new Post(chiefFront[0], chiefFront[1], false, true));
        Spot tower = findOrNull(Kind.TOWER);
        int[] gate = polar(0, radius - 3.0D);
        p.add(tower == null ? new Post(gate[0], gate[1], false, false) : new Post(tower.dx(), tower.dz(), true, false));
        p.add(new Post(gate[0], gate[1], false, false));
        p.add(new Post(fireDx + 2, fireDz + 1, false, false));
        p.add(new Post(fireDx - 2, fireDz - 1, false, false));
        for (Spot t : spots) {
            if (t.kind() == Kind.TENT || t.kind() == Kind.LEAN_TO) {
                int[] f = front(t, t.kind() == Kind.TENT ? 5 : 4);
                p.add(new Post(f[0], f[1], false, false));
            }
        }
        Spot cartSpot = findOrNull(Kind.CART);
        if (cartSpot != null) {
            int[] cart = front(cartSpot, 4);
            p.add(new Post(cart[0], cart[1], false, false));
        }
        this.posts = List.copyOf(p);
    }

    // ------------------------------------------------------------------ расстановка

    /** Клетки, занятые постройками (с отступом), — для проверки следующих. */
    private final Set<Long> taken = new java.util.HashSet<>();

    /**
     * Занятые блоками клетки постройки: вдоль a0..a1 (к костру), поперёк s0..s1 — так их строит
     * {@link CampBuilder} (колья у входа и перила включены).
     */
    public static int[] extent(Kind kind) {
        return switch (kind) {
            case FIRE -> new int[] {-3, 3, -3, 3};
            case TENT -> new int[] {-2, 3, -2, 2};
            case CHIEF_TENT -> new int[] {-3, 3, -3, 3};
            case LEAN_TO -> new int[] {-1, 2, -2, 2};
            case TOWER -> new int[] {-1, 2, -1, 1};
            case CART -> new int[] {-1, 2, -1, 2};
            case RACK -> new int[] {0, 1, -1, 2};
            case CRATE -> new int[] {-1, 0, -1, 1};
        };
    }

    /** Клетки постройки в координатах лагеря. */
    public static List<int[]> cells(Spot s) {
        int[] e = extent(s.kind());
        int[] f = step(s.facing());
        int[] rt = step(s.facing() + 1);
        List<int[]> out = new ArrayList<>();
        for (int a = e[0]; a <= e[1]; a++) {
            for (int side = e[2]; side <= e[3]; side++) {
                out.add(new int[] {s.dx() + f[0] * a + rt[0] * side, s.dz() + f[1] * a + rt[1] * side});
            }
        }
        return out;
    }

    private Spot place(Kind kind, int variant, int[] anglesDeg, double rMin, double rMax, int margin) {
        for (int angle : anglesDeg) {
            for (double rr = rMax; rr >= rMin; rr -= 0.5D) {
                int[] xz = polar(angle, rr);
                Spot s = new Spot(kind, xz[0], xz[1], facingTo(fireDx - xz[0], fireDz - xz[1]), variant);
                if (fits(s, margin)) {
                    occupy(s, margin);
                    return s;
                }
            }
        }
        return null;
    }

    private boolean fits(Spot s, int margin) {
        double limit = radius - 1.8D;
        for (int[] c : cells(s)) {
            if (Math.sqrt(c[0] * c[0] + c[1] * c[1]) > limit || pathCell(c[0], c[1]) || taken.contains(key(c[0], c[1]))) {
                return false;
            }
        }
        return true;
    }

    private void occupy(Spot s, int margin) {
        for (int[] c : cells(s)) {
            for (int dx = -margin; dx <= margin; dx++) {
                for (int dz = -margin; dz <= margin; dz++) {
                    taken.add(key(c[0] + dx, c[1] + dz));
                }
            }
        }
    }

    /** Тропа ворота—костёр с запасом: на ней ничего не ставится. */
    private boolean pathCell(int dx, int dz) {
        int[] gate = polar(0, radius);
        double ax = gate[0], az = gate[1], bx = fireDx, bz = fireDz;
        double vx = bx - ax, vz = bz - az;
        double t = Math.max(0.0D, Math.min(1.0D, ((dx - ax) * vx + (dz - az) * vz) / (vx * vx + vz * vz)));
        double px = ax + vx * t - dx, pz = az + vz * t - dz;
        return px * px + pz * pz <= 4.0D && t < 0.85D;
    }

    /** Всё обязательное на месте: шатёр главаря, два простых шатра, вышка, телега, стойка, два ящика. */
    private boolean complete() {
        long tents = spots.stream().filter(t -> t.kind() == Kind.TENT || t.kind() == Kind.LEAN_TO).count();
        long crates = spots.stream().filter(t -> t.kind() == Kind.CRATE).count();
        return tents >= 2 && crates == 2 && findOrNull(Kind.CHIEF_TENT) != null && findOrNull(Kind.TOWER) != null
                && findOrNull(Kind.CART) != null && findOrNull(Kind.RACK) != null;
    }

    private static void add(List<Spot> list, Spot s) {
        if (s != null) {
            list.add(s);
        }
    }

    private static int[] shuffled(Random r, int from, int to, int step) {
        List<Integer> a = new ArrayList<>();
        for (int v = from; v < to; v += step) {
            a.add(v);
        }
        java.util.Collections.shuffle(a, r);
        return a.stream().mapToInt(Integer::intValue).toArray();
    }

    public static CampLayout plan(long seed) {
        return new CampLayout(seed);
    }

    public long seed() {
        return seed;
    }

    public int radius() {
        return radius;
    }

    /** Угол ворот, радианы (x = cos, z = sin). */
    public double gateAngle() {
        return gateAngle;
    }

    public List<Spot> spots() {
        return spots;
    }

    public List<Stake> stakes() {
        return stakes;
    }

    public List<Post> posts() {
        return posts;
    }

    /** Ячейки проёма ворот (под перекладиной). */
    public List<int[]> gateCells() {
        return gateCells;
    }

    /** Середина ворот: по ней берётся высота перекладины. */
    public int[] gateCentre() {
        return polar(0, radius);
    }

    public Spot findOrNull(Kind kind) {
        for (Spot s : spots) {
            if (s.kind() == kind) {
                return s;
            }
        }
        return null;
    }

    public Spot find(Kind kind) {
        for (Spot s : spots) {
            if (s.kind() == kind) {
                return s;
            }
        }
        throw new IllegalStateException("В плане нет " + kind);
    }

    /** Внутри ли ограды (с запасом в полблока от частокола). */
    public boolean inside(int dx, int dz) {
        return Math.sqrt(dx * dx + dz * dz) < radius - 1.2D;
    }

    /** Точка на тропе от ворот к костру: тропа утоптана сильнее двора. */
    public boolean onPath(int dx, int dz) {
        int[] gate = polar(0, radius);
        double ax = gate[0], az = gate[1], bx = fireDx, bz = fireDz;
        double vx = bx - ax, vz = bz - az;
        double t = Math.max(0.0D, Math.min(1.0D, ((dx - ax) * vx + (dz - az) * vz) / (vx * vx + vz * vz)));
        double px = ax + vx * t - dx, pz = az + vz * t - dz;
        return px * px + pz * pz <= 1.6D;
    }

    /** Сколько блоков тропа тянется от ворот наружу. */
    public static final int TRAIL_LENGTH = 10;

    /** Тропа от ворот в лес: банда ходит на дорогу (и подход к лагерю не упирается в стволы). */
    public boolean onTrail(int dx, int dz) {
        double[] g = gateDirection();
        double along = dx * g[0] + dz * g[1];
        double across = -dx * g[1] + dz * g[0];
        double wobble = 0.8D * Math.sin(along * 0.45D + (seed & 7));
        return along > radius - 1.5D && along < radius + TRAIL_LENGTH && Math.abs(across - wobble) <= 1.2D;
    }

    /** Направление ворот наружу: x, z на единичной окружности. */
    public double[] gateDirection() {
        return new double[] {Math.cos(gateAngle), Math.sin(gateAngle)};
    }

    private int[] polar(int slotDeg, double dist) {
        double a = gateAngle + Math.toRadians(slotDeg);
        return new int[] {(int) Math.round(Math.cos(a) * dist), (int) Math.round(Math.sin(a) * dist)};
    }

    /** Точка в {@code dist} блоках перед входом. */
    public static int[] front(Spot s, int dist) {
        int[] f = step(s.facing());
        return new int[] {s.dx() + f[0] * dist, s.dz() + f[1] * dist};
    }

    /** Шаг по стороне света: 0 юг (+z), 1 запад (−x), 2 север (−z), 3 восток (+x). */
    public static int[] step(int facing) {
        return switch (facing & 3) {
            case 0 -> new int[] {0, 1};
            case 1 -> new int[] {-1, 0};
            case 2 -> new int[] {0, -1};
            default -> new int[] {1, 0};
        };
    }

    /** Ближайшая сторона света к направлению (dx, dz). */
    public static int facingTo(double dx, double dz) {
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? 3 : 1;
        }
        return dz >= 0 ? 0 : 2;
    }

    static double angleDiff(double a, double b) {
        double d = a - b;
        while (d > Math.PI) {
            d -= Math.PI * 2.0D;
        }
        while (d < -Math.PI) {
            d += Math.PI * 2.0D;
        }
        return d;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** Детерминированный хэш позиции: декор не зависит от порядка генерации чанков. */
    public static long hash(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h & Long.MAX_VALUE;
    }
}
