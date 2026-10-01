package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientAuraState;
import io.github.verycooltimo.murim.client.ClientProfileState;
import io.github.verycooltimo.murim.combat.AuraPressure;
import io.github.verycooltimo.murim.combat.AuraState;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Симуляция ауры: частицы с состоянием, а не функции времени (автор 01.10: «нужно симулировать
 * лучше», карточки с картинками — «просто картинки»).
 *
 * <p>У каждой частицы — положение, скорость, возраст и след из последних положений. На неё
 * действуют подъёмная сила (жар поднимается), вихревое поле (пламя закручивается, языки
 * рвутся по-разному), сопротивление воздуха и стягивание к оси над головой — колонна сужается
 * кверху сама. Языки пламени рисуются по следу частицы, поэтому их форма рождается из
 * движения: ни один не повторяет другой и не зацикливается.
 *
 * <p>Виды частиц:
 * <ul>
 *   <li>{@code FLAME} — светлый язык, след в 8 точек;</li>
 *   <li>{@code INK} — чёрный язык туши: медленнее, шире, живёт дольше;</li>
 *   <li>{@code SPARK} — искра или уголь: лёгкая, быстрая, короткий росчерк;</li>
 *   <li>{@code FLOW} — поток давления: вылетает из противника к придавленному игроку и
 *       закручивается по пути;</li>
 *   <li>{@code DEBRIS} — обломок пола: давление поднимает его, гравитация роняет обратно.</li>
 * </ul>
 *
 * <p>Симуляция тикает 20 раз в секунду; отрисовка интерполирует голову частицы между тиками.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class AuraSim {

    public enum Kind { FLAME, INK, SPARK, FLOW, DEBRIS }

    public static final int TRAIL = 8;

    /** Предел частиц на одно существо: страховка от толпы сильных противников. */
    private static final int CAP = 520;

    /** Сила ауры по рангу. */
    static final float[] INTENSITY = {0.0F, 0.35F, 0.6F, 0.82F, 1.0F, 1.18F, 1.35F};
    /** Доля чёрных языков по рангу. */
    static final float[] INK_SHARE = {0.0F, 0.0F, 0.0F, 0.3F, 0.6F, 0.72F, 0.8F};
    /** Языков в тик по рангу. */
    private static final float[] FLAME_RATE = {0.0F, 0.0F, 3.0F, 5.0F, 8.0F, 10.0F, 13.0F};

    public static final class Particle {
        public final Kind kind;
        public double x, y, z;
        public double px, py, pz;
        double vx, vy, vz;
        public final double[] trail = new double[TRAIL * 3];
        public int trailCount;
        public int age;
        public final int life;
        public final float size;
        public final float seed;

        Particle(Kind kind, double x, double y, double z, double vx, double vy, double vz, int life, float size, float seed) {
            this.kind = kind;
            this.x = this.px = x;
            this.y = this.py = y;
            this.z = this.pz = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.life = life;
            this.size = size;
            this.seed = seed;
            pushTrail();
        }

        void pushTrail() {
            System.arraycopy(trail, 0, trail, 3, (TRAIL - 1) * 3);
            trail[0] = x;
            trail[1] = y;
            trail[2] = z;
            trailCount = Math.min(TRAIL, trailCount + 1);
        }

        /** 0 в начале жизни, 1 в конце. */
        public float progress(float partial) {
            return Math.min(1.0F, (age + partial) / life);
        }

        public Vec3 head(float partial) {
            return new Vec3(px + (x - px) * partial, py + (y - py) * partial, pz + (z - pz) * partial);
        }
    }

    /** Источник частиц одного существа. */
    public static final class Emitter {
        public final List<Particle> particles = new ArrayList<>();
        float flameDebt;
        float sparkDebt;
        float flowDebt;
        float debrisDebt;
        int lastSeen;
        final Random random;

        Emitter(int id) {
            this.random = new Random(id * 0x9E3779B97F4A7C15L);
        }
    }

    private static final Int2ObjectMap<Emitter> EMITTERS = new Int2ObjectOpenHashMap<>();
    private static int ticks;

    public static Emitter of(int entityId) {
        return EMITTERS.get(entityId);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            EMITTERS.clear();
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }
        ticks++;
        for (Int2ObjectMap.Entry<AuraState> entry : ClientAuraState.all().int2ObjectEntrySet()) {
            Entity entity = minecraft.level.getEntity(entry.getIntKey());
            if (!(entity instanceof LivingEntity living) || !living.isAlive()
                    || entity.distanceToSqr(minecraft.gameRenderer.getMainCamera().getPosition()) > 64.0D * 64.0D) {
                continue;
            }
            Emitter emitter = EMITTERS.computeIfAbsent(entity.getId(), Emitter::new);
            emitter.lastSeen = ticks;
            spawn(emitter, living, entry.getValue(), minecraft);
        }
        var it = EMITTERS.int2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            Emitter emitter = entry.getValue();
            Entity entity = minecraft.level.getEntity(entry.getIntKey());
            step(emitter, entity);
            // Аура пропала — частицы догорают, потом источник убирается.
            if (emitter.particles.isEmpty() && ticks - emitter.lastSeen > 2) {
                it.remove();
            }
        }
    }

    private static void spawn(Emitter e, LivingEntity entity, AuraState aura, Minecraft minecraft) {
        int rank = aura.rank();
        if (rank < 2 || e.particles.size() >= CAP) {
            return;
        }
        Random r = e.random;
        float intensity = INTENSITY[rank];
        double h = entity.getBbHeight();
        double radius = entity.getBbWidth() * 0.5D + 0.05D;
        Vec3 feet = entity.position();

        // Языки: рождаются на поверхности тела, гуще у плеч и головы.
        e.flameDebt += FLAME_RATE[rank];
        while (e.flameDebt >= 1.0F) {
            e.flameDebt -= 1.0F;
            boolean ink = r.nextFloat() < (aura.demonic() ? Math.max(0.4F, INK_SHARE[rank]) : INK_SHARE[rank]);
            double angle = r.nextDouble() * Math.PI * 2.0D;
            double rr = radius * (0.7D + 0.5D * r.nextDouble());
            double y = h * Math.pow(r.nextDouble(), 0.7D);
            double ox = Math.cos(angle), oz = Math.sin(angle);
            double up = (0.05D + 0.05D * r.nextDouble()) * (0.7D + 0.5D * intensity) * (ink ? 0.75D : 1.0D);
            double out = (0.012D + 0.02D * r.nextDouble()) * intensity;
            int life = (int) ((ink ? 18 : 13) + r.nextInt(ink ? 12 : 9) + 6 * intensity);
            float size = (float) ((ink ? 0.16D : 0.12D) + 0.08D * r.nextDouble()) * (0.6F + 0.5F * intensity) * (float) (h / 2.0D);
            e.particles.add(new Particle(ink ? Kind.INK : Kind.FLAME, feet.x + ox * rr, feet.y + y, feet.z + oz * rr,
                    ox * out, up, oz * out, life, size, r.nextFloat()));
        }

        // Искры и угли.
        e.sparkDebt += 0.25F * rank * (aura.demonic() ? 1.6F : 1.0F);
        while (e.sparkDebt >= 1.0F) {
            e.sparkDebt -= 1.0F;
            double angle = r.nextDouble() * Math.PI * 2.0D;
            double rr = radius * (0.6D + 1.2D * r.nextDouble());
            e.particles.add(new Particle(Kind.SPARK, feet.x + Math.cos(angle) * rr, feet.y + h * r.nextDouble(),
                    feet.z + Math.sin(angle) * rr, (r.nextDouble() - 0.5D) * 0.03D, 0.03D + 0.04D * r.nextDouble(),
                    (r.nextDouble() - 0.5D) * 0.03D, 16 + r.nextInt(14), 0.03F + 0.03F * r.nextFloat(), r.nextFloat()));
        }

        // Обломки: с Пика давление поднимает куски пола вокруг.
        if (rank >= 4) {
            e.debrisDebt += 0.15F * (rank - 3);
            while (e.debrisDebt >= 1.0F) {
                e.debrisDebt -= 1.0F;
                double angle = r.nextDouble() * Math.PI * 2.0D;
                double rr = 0.6D + 2.0D * r.nextDouble();
                e.particles.add(new Particle(Kind.DEBRIS, feet.x + Math.cos(angle) * rr, feet.y + 0.02D,
                        feet.z + Math.sin(angle) * rr, Math.cos(angle) * 0.01D, 0.05D + 0.05D * r.nextDouble(),
                        Math.sin(angle) * 0.01D, 30 + r.nextInt(30), 0.03F + 0.06F * r.nextFloat(), r.nextFloat()));
            }
        }

        // Поток давления к своему игроку, если давит именно эта аура.
        if (minecraft.player != null && ClientAuraState.sourceId() == entity.getId()) {
            float p = AuraPressure.of(rank, ClientProfileState.profile().rank(), minecraft.player.distanceTo(entity));
            if (p > 0.2F) {
                e.flowDebt += 1.0F + 3.0F * p;
                Vec3 target = minecraft.player.position().add(0.0D, minecraft.player.getBbHeight() * 0.55D, 0.0D);
                while (e.flowDebt >= 1.0F) {
                    e.flowDebt -= 1.0F;
                    Vec3 from = feet.add((r.nextDouble() - 0.5D) * radius * 2.0D, h * (0.2D + 0.7D * r.nextDouble()),
                            (r.nextDouble() - 0.5D) * radius * 2.0D);
                    Vec3 dir = target.subtract(from);
                    double dist = dir.length();
                    if (dist < 1.5D) {
                        continue;
                    }
                    double speed = 0.22D + 0.12D * r.nextDouble();
                    Vec3 v = dir.normalize().scale(speed);
                    int life = (int) Math.max(6.0D, (dist - 1.0D) / speed);
                    e.particles.add(new Particle(Kind.FLOW, from.x, from.y, from.z, v.x, v.y, v.z, life,
                            (float) (0.12D + 0.1D * r.nextDouble()) * (0.6F + 0.6F * p), r.nextFloat()));
                }
            }
        }
    }

    private static void step(Emitter e, Entity entity) {
        double cx = entity != null ? entity.getX() : Double.NaN;
        double cz = entity != null ? entity.getZ() : Double.NaN;
        double top = entity != null ? entity.getY() + entity.getBbHeight() : Double.NaN;
        float t = ticks;
        var it = e.particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.px = p.x;
            p.py = p.y;
            p.pz = p.z;
            if (++p.age >= p.life) {
                it.remove();
                continue;
            }
            double s = p.seed * 10.0D;
            // Вихревое поле: сумма несоизмеримых синусов по координатам и времени.
            double tx = Math.sin(p.y * 1.7D + t * 0.11D + s) + Math.sin(p.z * 1.3D - t * 0.07D);
            double ty = Math.sin(p.x * 1.1D + t * 0.09D + s);
            double tz = Math.sin(p.x * 1.9D - t * 0.13D + s) + Math.sin(p.y * 1.5D + t * 0.05D);
            switch (p.kind) {
                case FLAME, INK -> {
                    double k = p.kind == Kind.INK ? 0.006D : 0.008D;
                    p.vx = p.vx * 0.9D + tx * k;
                    p.vy = p.vy * 0.93D + 0.009D + ty * 0.002D;
                    p.vz = p.vz * 0.9D + tz * k;
                    // Над головой колонна сужается: частицы стягиваются к оси.
                    if (!Double.isNaN(cx) && p.y > top - 0.2D) {
                        p.vx -= (p.x - cx) * 0.02D;
                        p.vz -= (p.z - cz) * 0.02D;
                    }
                }
                case SPARK -> {
                    p.vx = p.vx * 0.96D + tx * 0.006D;
                    p.vy = p.vy * 0.97D + 0.002D;
                    p.vz = p.vz * 0.96D + tz * 0.006D;
                }
                case FLOW -> {
                    p.vx += tx * 0.012D;
                    p.vy += ty * 0.006D;
                    p.vz += tz * 0.012D;
                }
                case DEBRIS -> {
                    p.vx *= 0.97D;
                    p.vz *= 0.97D;
                    // Первую треть жизни давление держит обломок, потом он падает.
                    p.vy = p.age < p.life / 3 ? p.vy * 0.9D + 0.002D : p.vy - 0.012D;
                    if (!Double.isNaN(cx) && p.y < entity.getY() + 0.02D && p.vy < 0.0D) {
                        p.y = entity.getY() + 0.02D;
                        p.vy = 0.0D;
                    }
                }
            }
            p.x += p.vx;
            p.y += p.vy;
            p.z += p.vz;
            p.pushTrail();
        }
    }

    private AuraSim() {
    }
}
