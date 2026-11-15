package io.github.verycooltimo.murim.client.boss;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.boss.BossMove;
import io.github.verycooltimo.murim.entity.boss.BossRules;
import io.github.verycooltimo.murim.entity.boss.FortressMaster;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;

/**
 * Частицы хозяина крепости (docs/design/26-boss.md §6): аура по фазам — тёмный дым, нефритовое
 * пламя, красная аура с белым паром; удары — пыль приземления, волна рыка, осколки раскола,
 * вихрь. Всё по видимому состоянию сущности, без пакетов (как client/bandit/BanditFx).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class BossFx {

    // Три палитры, по одной на приём (автор 9.11: одна палитра, до трёх слоёв, без белых искр, ничего на голове).
    // Сталь (клинок: блик, связка, вихрь): светлый и тёмный оттенок.
    private static final DustParticleOptions STEEL_L = new DustParticleOptions(new Vector3f(0.66F, 0.74F, 0.82F), 2.0F);
    private static final DustParticleOptions STEEL_D = new DustParticleOptions(new Vector3f(0.34F, 0.42F, 0.52F), 2.3F);
    // Пыль (приземление, таран, ноги, вдох рыка): два тона охры.
    private static final DustParticleOptions DUST_L = new DustParticleOptions(new Vector3f(0.66F, 0.58F, 0.44F), 1.9F);
    private static final DustParticleOptions DUST_D = new DustParticleOptions(new Vector3f(0.46F, 0.39F, 0.30F), 2.1F);
    // Угли (кипящая кровь, трещины раскола, искры приземления).
    private static final DustParticleOptions EMBER = new DustParticleOptions(new Vector3f(0.95F, 0.40F, 0.14F), 1.6F);
    private static final DustParticleOptions EMBER_D = new DustParticleOptions(new Vector3f(0.62F, 0.18F, 0.08F), 1.5F);
    // Аура фазы 1–2: тёмный дым и нефрит у плеч (фаза 2), не выше груди.
    private static final DustParticleOptions SMOKE = new DustParticleOptions(new Vector3f(0.12F, 0.23F, 0.17F), 1.6F);
    private static final DustParticleOptions JADE = new DustParticleOptions(new Vector3f(0.25F, 0.64F, 0.42F), 1.2F);
    private static final DustParticleOptions MARK = new DustParticleOptions(new Vector3f(0.88F, 0.28F, 0.17F), 1.2F);

    private BossFx() {
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) {
            return;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof FortressMaster b && b.isAlive()) {
                tick(b, mc.level);
            }
        }
    }

    private static void tick(FortressMaster b, Level level) {
        RandomSource r = b.getRandom();
        int phase = b.phase();
        int state = b.state();
        int age = (int) b.stateAge(0.0F);
        BossMove m = b.move();
        double x = b.getX(), y = b.getY(), z = b.getZ();
        if (state != FortressMaster.SIT) {
            aura(b, level, r, phase);
        }
        Vector3f mk = b.mark();
        Vector3f yard = b.yard();
        boolean blade = m == BossMove.CHAIN || m == BossMove.WHIRL || m == BossMove.POUNCE;
        if (state == FortressMaster.WINDUP && blade && age >= Math.max(1, m.windup() - 6)) {
            // Блик на дао: одна полоса, поднимается вдоль клинка от рукояти к острию; светлая сердцевина и тёмная кромка.
            double yaw = Math.toRadians(b.yBodyRot);
            double rx = -Math.cos(yaw) * 0.9D, rz = -Math.sin(yaw) * 0.9D;
            double h = 1.4D + 0.55D * (age - (m.windup() - 6));
            level.addParticle(STEEL_L, x + rx, y + h, z + rz, 0.0D, 0.05D, 0.0D);
            level.addParticle(STEEL_D, x + rx * 1.08D, y + h - 0.3D, z + rz * 1.08D, 0.0D, 0.05D, 0.0D);
        }
        if (state == FortressMaster.WINDUP && m != BossMove.ROAR && m != BossMove.SPLIT && age % 4 == 0) {
            // Накат: два клуба пыли у ног, к телу.
            double a = (b.tickCount * 0.5D) % (Math.PI * 2);
            for (int i = 0; i < 2; i++) {
                double aa = a + i * Math.PI;
                level.addParticle(DUST_L, x + Math.cos(aa) * 1.8D, y + 0.12D, z + Math.sin(aa) * 1.8D, -Math.cos(aa) * 0.08D, 0.02D, -Math.sin(aa) * 0.08D);
            }
        }
        if (state == FortressMaster.RECOVER && m == BossMove.POUNCE && age <= 14) {
            landing(b, level, r, yard.y, age);
        }
        phaseFlare(b, level, r);
        if (state == FortressMaster.STRIKE && m == BossMove.ROAR && age <= 1) {
            // Рык: одна кольцевая волна пыли по земле.
            shockRing(level, DUST_L, x, y + 0.3D, z, 40, 0.5D);
            shockRing(level, DUST_D, x, y + 0.2D, z, 20, 0.32D);
        }
        if (state == FortressMaster.WINDUP && m == BossMove.ROAR && age % 4 == 0) {
            // Вдох: пыль тянется к ногам.
            for (int i = 0; i < 3; i++) {
                double a = r.nextDouble() * Math.PI * 2, d = 3.0D + r.nextDouble() * 1.5D;
                level.addParticle(DUST_L, x + Math.cos(a) * d, y + 0.2D, z + Math.sin(a) * d, -Math.cos(a) * 0.14D, 0.02D, -Math.sin(a) * 0.14D);
            }
        }
        if (m == BossMove.SPLIT && state == FortressMaster.WINDUP && age > BossMove.SPLIT_PLANT && age % 3 == 0) {
            // Трещины тлеют: угли вдоль линий.
            for (double yaw : BossRules.splitYaws(mk.z)) {
                double[] f = BossRules.forward(yaw);
                double d = 1.0D + r.nextDouble() * (BossMove.SPLIT_LENGTH - 1.0D);
                level.addParticle(EMBER, x + f[0] * d, yard.y + 0.1D, z + f[1] * d, 0.0D, 0.03D, 0.0D);
            }
        }
        if (m == BossMove.SPLIT && state == FortressMaster.STRIKE) {
            // Фронт волны: пыль и угли по линии, по два шара.
            double front = BossMove.SPLIT_LENGTH * Math.min(1.0D, (age + 1.0D) / m.strike());
            for (double yaw : BossRules.splitYaws(mk.z)) {
                double[] f = BossRules.forward(yaw);
                double d = front - r.nextDouble() * 1.2D;
                level.addParticle(DUST_D, x + f[0] * d, yard.y + 0.2D, z + f[1] * d, (r.nextDouble() - 0.5D) * 0.1D, 0.12D, (r.nextDouble() - 0.5D) * 0.1D);
                level.addParticle(EMBER, x + f[0] * front, yard.y + 0.2D, z + f[1] * front, 0.0D, 0.1D, 0.0D);
            }
        }
        if (m == BossMove.WHIRL && state == FortressMaster.STRIKE) {
            // Полоса стали на уровне пояса: четырнадцать шаров по кольцу, два оттенка, бегут по касательной; пыль у ног.
            double base = b.tickCount * 0.9D;
            for (int i = 0; i < 14; i++) {
                double a = base + i * Math.PI / 7;
                level.addParticle(i % 2 == 0 ? STEEL_L : STEEL_D, x + Math.cos(a) * 2.0D, y + 1.1D, z + Math.sin(a) * 2.0D,
                        -Math.sin(a) * 0.35D, 0.0D, Math.cos(a) * 0.35D);
            }
            if (age % 2 == 0) {
                level.addParticle(DUST_L, x + (r.nextDouble() - 0.5D) * 3.0D, y + 0.15D, z + (r.nextDouble() - 0.5D) * 3.0D, 0.0D, 0.05D, 0.0D);
            }
        }
        if (m == BossMove.RAM && state == FortressMaster.STRIKE) {
            // Таран: пыль только по бокам, у земли.
            double yaw = Math.toRadians(b.yBodyRot);
            for (int i = 0; i < 2; i++) {
                double side = i == 0 ? -1.1D : 1.1D;
                level.addParticle(i == 0 ? DUST_L : DUST_D, x + Math.cos(yaw) * side, y + 0.15D, z + Math.sin(yaw) * side,
                        Math.cos(yaw) * side * 0.12D, 0.05D, Math.sin(yaw) * side * 0.12D);
            }
        }
        if (state == FortressMaster.BOIL && age % 2 == 0) {
            // Кровь кипит: четыре угля поднимаются вокруг корпуса, ниже плеч.
            for (int i = 0; i < 6; i++) {
                double a = r.nextDouble() * Math.PI * 2, d = 0.8D + r.nextDouble() * 0.5D;
                level.addParticle(i % 2 == 0 ? EMBER : EMBER_D, x + Math.cos(a) * d, y + 0.5D + r.nextDouble() * 1.2D, z + Math.sin(a) * d, 0.0D, 0.08D, 0.0D);
            }
        }
        if (b.arena() && b.tickCount % 6 == 0) {
            // Барьер: искры красной ци вдоль края плаца рядом с игроком.
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                double h = FortressMaster.YARD_HALF;
                for (int i = 0; i < 2; i++) {
                    double t = (r.nextDouble() * 2 - 1) * h;
                    int side = r.nextInt(4);
                    double px = side == 0 ? yard.x - h : side == 1 ? yard.x + h : yard.x + t;
                    double pz = side == 2 ? yard.z - h : side == 3 ? yard.z + h : yard.z + t;
                    if (mc.player.distanceToSqr(px, yard.y, pz) < 18 * 18) {
                        level.addParticle(MARK, px, yard.y + 0.2D + r.nextDouble() * 2.0D, pz, 0.0D, 0.03D, 0.0D);
                    }
                }
            }
        }
    }

    /** Аура по фазам, тихая: ф.1 — пыль у ног; ф.2 — тёмный дым у ног и нефрит у плеч; ф.3 — угли и тёмный дым у ног. Выше груди ничего. */
    private static void aura(FortressMaster b, Level level, RandomSource r, int phase) {
        double x = b.getX(), y = b.getY(), z = b.getZ();
        if (phase == 1) {
            if (b.tickCount % 8 == 0) {
                level.addParticle(DUST_D, x + (r.nextDouble() - 0.5D) * 1.6D, y + 0.05D, z + (r.nextDouble() - 0.5D) * 1.6D, 0.0D, 0.015D, 0.0D);
            }
            return;
        }
        if (phase == 2) {
            if (b.tickCount % 3 == 0) {
                double side = r.nextBoolean() ? 1.0D : -1.0D;
                double yaw = Math.toRadians(b.yBodyRot);
                level.addParticle(JADE, x + Math.cos(yaw) * 0.9D * side, y + 1.5D + r.nextDouble() * 0.4D, z + Math.sin(yaw) * 0.9D * side, 0.0D, 0.05D, 0.0D);
            }
            if (b.tickCount % 6 == 0) {
                level.addParticle(SMOKE, x + (r.nextDouble() - 0.5D) * 1.4D, y + 0.1D, z + (r.nextDouble() - 0.5D) * 1.4D, 0.0D, 0.02D, 0.0D);
            }
            return;
        }
        if (b.tickCount % 3 == 0) {
            double a = r.nextDouble() * Math.PI * 2;
            level.addParticle(EMBER, x + Math.cos(a) * 0.9D, y + 0.4D + r.nextDouble() * 1.0D, z + Math.sin(a) * 0.9D, 0.0D, 0.06D, 0.0D);
        }
        if (b.tickCount % 6 == 0) {
            level.addParticle(EMBER_D, x + (r.nextDouble() - 0.5D) * 1.4D, y + 0.1D, z + (r.nextDouble() - 0.5D) * 1.4D, 0.0D, 0.03D, 0.0D);
        }
    }

    // Приземление: крупная пыль (три слоя) и осколки пола; обе пыли светлее и темнее охры, размер ×1,5 против обычной.
    private static final DustParticleOptions BIG_L = new DustParticleOptions(new Vector3f(0.86F, 0.79F, 0.64F), 3.2F);
    private static final DustParticleOptions BIG_D = new DustParticleOptions(new Vector3f(0.62F, 0.56F, 0.46F), 3.6F);
    private static final java.util.Map<Integer, Integer> LANDED = new java.util.HashMap<>();
    private static final java.util.Map<Integer, Integer> PHASES = new java.util.HashMap<>();

    /**
     * Приземление прыжка (автор 14.11: «не хватает масштаба, разрушений, пыли»). Пик в первые тики (до 15 % жизни):
     * тик 0 — кольца пыли (три скорости), столб пыли, осколки пола (частицы блока с его же текстурой), тряска камеры;
     * тики 1–14 — докатывающийся вал пыли по земле. Блоки не ломаются.
     */
    private static void landing(FortressMaster b, Level level, RandomSource r, double floorY, int age) {
        double x = b.getX(), z = b.getZ(), y = floorY + 0.15D;
        Integer last = LANDED.get(b.getId());
        boolean fresh = last == null || b.tickCount - last >= 30;
        if (!(fresh && age <= 3)) {
            if (age >= 1 && age <= 10 && last != null && b.tickCount - last < 30) {
                // Вал: нарастающий радиус, скорость падает; пыль идёт наружу и чуть вверх.
                double rad = 1.8D + age * 0.55D;
                for (int i = 0; i < 10; i++) {
                    double a = r.nextDouble() * Math.PI * 2;
                    double sp = 0.16D * (1.0D - age / 14.0D);
                    level.addParticle(i % 2 == 0 ? BIG_L : BIG_D, x + Math.cos(a) * rad, y + r.nextDouble() * 0.3D, z + Math.sin(a) * rad,
                            Math.cos(a) * sp, 0.03D + r.nextDouble() * 0.05D, Math.sin(a) * sp);
                }
            }
            return;
        }
        LANDED.put(b.getId(), b.tickCount);
        // Слой 1: кольца пыли, три скорости, с разбросом.
        double[] speeds = {0.55D, 0.36D, 0.2D};
        int[] counts = {44, 36, 28};
        for (int k = 0; k < speeds.length; k++) {
            for (int i = 0; i < counts[k]; i++) {
                double a = Math.PI * 2 * (i + r.nextDouble() * 0.8D) / counts[k];
                double sp = speeds[k] * (0.8D + 0.4D * r.nextDouble());
                level.addParticle(k % 2 == 0 ? BIG_L : BIG_D, x + Math.cos(a) * 0.9D, y + 0.1D * k, z + Math.sin(a) * 0.9D,
                        Math.cos(a) * sp, 0.05D + 0.05D * k + r.nextDouble() * 0.06D, Math.sin(a) * sp);
            }
        }
        // Слой 2: столб пыли вверх (грудь босса и выше).
        for (int i = 0; i < 34; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = r.nextDouble() * 1.6D;
            level.addParticle(i % 2 == 0 ? BIG_D : BIG_L, x + Math.cos(a) * d, y, z + Math.sin(a) * d,
                    Math.cos(a) * 0.05D, 0.22D + r.nextDouble() * 0.38D, Math.sin(a) * 0.05D);
        }
        // Слой 2б: ваниль, заметная ночью: белые клубы по земле и вверх, серый дым столбом.
        for (int i = 0; i < 36; i++) {
            double a = Math.PI * 2 * i / 36 + r.nextDouble() * 0.2D, sp = 0.25D + r.nextDouble() * 0.3D;
            level.addParticle(net.minecraft.core.particles.ParticleTypes.POOF, x + Math.cos(a) * 1.0D, y + 0.1D, z + Math.sin(a) * 1.0D,
                    Math.cos(a) * sp, 0.04D + r.nextDouble() * 0.08D, Math.sin(a) * sp);
        }
        for (int i = 0; i < 14; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = r.nextDouble() * 1.3D;
            level.addParticle(net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE, x + Math.cos(a) * d, y + 0.2D, z + Math.sin(a) * d,
                    Math.cos(a) * 0.02D, 0.06D + r.nextDouble() * 0.06D, Math.sin(a) * 0.02D);
        }
        // Слой 3: осколки пола с его же текстурой, летят вверх и наружу.
        int thrown = 0;
        for (int i = 0; i < 90 && thrown < 56; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = 0.6D + r.nextDouble() * BossMove.POUNCE_RADIUS;
            double px = x + Math.cos(a) * d, pz = z + Math.sin(a) * d;
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(net.minecraft.core.BlockPos.containing(px, floorY - 0.5D, pz));
            if (st.isAir()) {
                continue;
            }
            thrown++;
            double up = 0.35D + r.nextDouble() * 0.55D, out = 0.1D + r.nextDouble() * 0.28D;
            level.addParticle(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, st),
                    px, floorY + 0.2D, pz, Math.cos(a) * out, up, Math.sin(a) * out);
        }
        // Угли оставлены от прежнего приземления: пять, низко.
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2 * i / 6 + r.nextDouble() * 0.4D;
            level.addParticle(EMBER, x + Math.cos(a) * 0.9D, y + 0.1D, z + Math.sin(a) * 0.9D, Math.cos(a) * 0.18D, 0.16D + r.nextDouble() * 0.1D, Math.sin(a) * 0.18D);
        }
        // Тряска для игроков рядом: сила спадает с расстоянием; ползунок настроек и режим съёмки учитывает сам обработчик.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            double dist = mc.player.distanceTo(b);
            if (dist < 24.0D) {
                io.github.verycooltimo.murim.client.CameraShakeHandler.quake((float) (0.55D * (1.0D - dist / 24.0D) + 0.1D), 14);
            }
        }
    }

    /** Переход в новую фазу: вспышка ауры (угли и пыль вокруг корпуса) и небольшая тряска; рык идёт следом своим ходом. */
    private static void phaseFlare(FortressMaster b, Level level, RandomSource r) {
        Integer was = PHASES.put(b.getId(), b.phase());
        if (was == null || b.phase() <= was) {
            return;
        }
        double x = b.getX(), y = b.getY(), z = b.getZ();
        shockRing(level, BIG_L, x, y + 0.2D, z, 36, 0.4D);
        for (int i = 0; i < 40; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = 0.5D + r.nextDouble() * 0.8D;
            level.addParticle(i % 2 == 0 ? EMBER : EMBER_D, x + Math.cos(a) * d, y + 0.2D + r.nextDouble() * 1.6D, z + Math.sin(a) * d,
                    Math.cos(a) * 0.05D, 0.12D + r.nextDouble() * 0.15D, Math.sin(a) * 0.05D);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.distanceTo(b) < 24.0D) {
            io.github.verycooltimo.murim.client.CameraShakeHandler.quake(0.3F, 10);
        }
    }

    /** Расходящееся кольцо крупных шаров по земле: волна удара, летит наружу и чуть вверх. */
    private static void shockRing(Level level, DustParticleOptions o, double x, double y, double z, int n, double speed) {
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            level.addParticle(o, x + Math.cos(a) * 0.8D, y, z + Math.sin(a) * 0.8D, Math.cos(a) * speed, 0.04D, Math.sin(a) * speed);
        }
    }
}
