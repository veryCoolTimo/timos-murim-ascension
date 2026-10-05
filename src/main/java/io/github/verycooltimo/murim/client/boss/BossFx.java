package io.github.verycooltimo.murim.client.boss;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.boss.BossMove;
import io.github.verycooltimo.murim.entity.boss.BossRules;
import io.github.verycooltimo.murim.entity.boss.FortressMaster;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
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

    private static final DustParticleOptions SMOKE = new DustParticleOptions(new Vector3f(0.12F, 0.23F, 0.17F), 1.6F);
    private static final DustParticleOptions OCHRE = new DustParticleOptions(new Vector3f(0.55F, 0.42F, 0.23F), 1.0F);
    private static final DustParticleOptions JADE = new DustParticleOptions(new Vector3f(0.25F, 0.64F, 0.42F), 1.4F);
    private static final DustParticleOptions BLOOD = new DustParticleOptions(new Vector3f(0.70F, 0.15F, 0.12F), 1.5F);
    private static final DustParticleOptions INK = new DustParticleOptions(new Vector3f(0.16F, 0.05F, 0.05F), 1.8F);
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
        if (state == FortressMaster.WINDUP && age == Math.max(1, m.windup() - 4)) {
            // Блик на дао перед ударом.
            for (int i = 0; i < 6; i++) {
                level.addParticle(ParticleTypes.CRIT, x + (r.nextDouble() - 0.5D) * 1.2D, y + 2.2D + r.nextDouble(), z + (r.nextDouble() - 0.5D) * 1.2D,
                        0.0D, 0.05D, 0.0D);
            }
        }
        if (state == FortressMaster.RECOVER && m == BossMove.POUNCE && age <= 1) {
            // Приземление: пыль и осколки кольцом.
            level.addParticle(ParticleTypes.EXPLOSION, x, y + 0.5D, z, 0.0D, 0.0D, 0.0D);
            ringParticles(level, r, x, yard.y + 0.1D, z, BossMove.POUNCE_RADIUS, 40, 0.25D);
        }
        if (state == FortressMaster.STRIKE && m == BossMove.ROAR && age <= 1) {
            level.addParticle(ParticleTypes.SONIC_BOOM, x, y + 1.8D, z, 0.0D, 0.0D, 0.0D);
            for (int i = 0; i < 48; i++) {
                double a = Math.PI * 2 * i / 48;
                level.addParticle(ParticleTypes.POOF, x, y + 0.4D, z, Math.cos(a) * 0.55D, 0.02D, Math.sin(a) * 0.55D);
                level.addParticle(JADE, x + Math.cos(a), y + 1.0D, z + Math.sin(a), Math.cos(a) * 0.4D, 0.05D, Math.sin(a) * 0.4D);
            }
        }
        if (state == FortressMaster.WINDUP && m == BossMove.ROAR && age % 3 == 0) {
            // Вдох: воздух и пыль тянутся к груди.
            for (int i = 0; i < 6; i++) {
                double a = r.nextDouble() * Math.PI * 2, d = 3.0D + r.nextDouble() * 2.0D;
                level.addParticle(ParticleTypes.CLOUD, x + Math.cos(a) * d, y + 0.3D, z + Math.sin(a) * d, -Math.cos(a) * 0.18D, 0.04D, -Math.sin(a) * 0.18D);
            }
        }
        if (m == BossMove.SPLIT && state == FortressMaster.WINDUP && age == BossMove.SPLIT_PLANT) {
            level.addParticle(ParticleTypes.EXPLOSION, x, y + 0.2D, z, 0.0D, 0.0D, 0.0D);
        }
        if (m == BossMove.SPLIT && state == FortressMaster.WINDUP && age > BossMove.SPLIT_PLANT && age % 2 == 0) {
            // Трещины тлеют: искры вдоль линий.
            for (double yaw : BossRules.splitYaws(mk.z)) {
                double[] f = BossRules.forward(yaw);
                double d = 1.0D + r.nextDouble() * (BossMove.SPLIT_LENGTH - 1.0D);
                level.addParticle(ParticleTypes.SMALL_FLAME, x + f[0] * d, yard.y + 0.1D, z + f[1] * d, 0.0D, 0.02D, 0.0D);
            }
        }
        if (m == BossMove.SPLIT && state == FortressMaster.STRIKE) {
            // Фронт волны: камень лопается, осколки и пламя.
            double front = BossMove.SPLIT_LENGTH * Math.min(1.0D, (age + 1.0D) / m.strike());
            BlockParticleOption stone = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE_BRICKS.defaultBlockState());
            for (double yaw : BossRules.splitYaws(mk.z)) {
                double[] f = BossRules.forward(yaw);
                for (int i = 0; i < 6; i++) {
                    double d = front - r.nextDouble() * 1.5D;
                    double px = x + f[0] * d + (r.nextDouble() - 0.5D) * 0.8D, pz = z + f[1] * d + (r.nextDouble() - 0.5D) * 0.8D;
                    level.addParticle(stone, px, yard.y + 0.2D, pz, (r.nextDouble() - 0.5D) * 0.2D, 0.35D + r.nextDouble() * 0.3D, (r.nextDouble() - 0.5D) * 0.2D);
                }
                level.addParticle(ParticleTypes.LAVA, x + f[0] * front, yard.y + 0.2D, z + f[1] * front, 0.0D, 0.0D, 0.0D);
                level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, x + f[0] * front, yard.y + 0.2D, z + f[1] * front, 0.0D, 0.04D, 0.0D);
            }
        }
        if (m == BossMove.WHIRL && state == FortressMaster.STRIKE && age % 2 == 0) {
            for (int i = 0; i < 3; i++) {
                double a = r.nextDouble() * Math.PI * 2;
                level.addParticle(ParticleTypes.SWEEP_ATTACK, x + Math.cos(a) * 1.4D, y + 1.0D + r.nextDouble(), z + Math.sin(a) * 1.4D, 0.0D, 0.0D, 0.0D);
            }
        }
        if (m == BossMove.RAM && state == FortressMaster.STRIKE) {
            level.addParticle(ParticleTypes.CLOUD, x, y + 0.2D, z, 0.0D, 0.05D, 0.0D);
        }
        if (state == FortressMaster.BOIL && age % 2 == 0) {
            for (int i = 0; i < 8; i++) {
                level.addParticle(ParticleTypes.WHITE_SMOKE, x + (r.nextDouble() - 0.5D) * 1.4D, y + 0.8D + r.nextDouble() * 1.8D, z + (r.nextDouble() - 0.5D) * 1.4D,
                        (r.nextDouble() - 0.5D) * 0.05D, 0.12D, (r.nextDouble() - 0.5D) * 0.05D);
            }
        }
        if (b.arena() && b.tickCount % 3 == 0) {
            // Барьер: искры красной ци вдоль края плаца рядом с игроком.
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                double h = FortressMaster.YARD_HALF;
                for (int i = 0; i < 4; i++) {
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

    /** Аура по фазам: ф.1 — тихий тёмный дым с охрой у ног; ф.2 — нефрит; ф.3 — кровь, тушь и пар. */
    private static void aura(FortressMaster b, Level level, RandomSource r, int phase) {
        double x = b.getX(), y = b.getY(), z = b.getZ();
        if (phase == 1) {
            if (b.tickCount % 4 == 0) {
                level.addParticle(SMOKE, x + (r.nextDouble() - 0.5D) * 1.2D, y + 0.1D, z + (r.nextDouble() - 0.5D) * 1.2D, 0.0D, 0.02D, 0.0D);
                level.addParticle(OCHRE, x + (r.nextDouble() - 0.5D) * 1.6D, y + 0.05D, z + (r.nextDouble() - 0.5D) * 1.6D, 0.0D, 0.01D, 0.0D);
            }
            return;
        }
        if (phase == 2) {
            for (int i = 0; i < 2; i++) {
                double side = r.nextBoolean() ? 1.0D : -1.0D;
                double yaw = Math.toRadians(b.yBodyRot);
                double sx = Math.cos(yaw) * 0.55D * side, sz = Math.sin(yaw) * 0.55D * side;
                level.addParticle(JADE, x + sx, y + 2.2D + r.nextDouble() * 0.3D, z + sz, (r.nextDouble() - 0.5D) * 0.03D, 0.09D, (r.nextDouble() - 0.5D) * 0.03D);
            }
            if (b.tickCount % 3 == 0) {
                level.addParticle(SMOKE, x + (r.nextDouble() - 0.5D) * 1.0D, y + 2.0D, z + (r.nextDouble() - 0.5D) * 1.0D, 0.0D, 0.08D, 0.0D);
            }
            return;
        }
        for (int i = 0; i < 2; i++) {
            level.addParticle(BLOOD, x + (r.nextDouble() - 0.5D) * 1.2D, y + 0.5D + r.nextDouble() * 1.8D, z + (r.nextDouble() - 0.5D) * 1.2D, 0.0D, 0.06D, 0.0D);
        }
        if (b.tickCount % 2 == 0) {
            level.addParticle(INK, x + (r.nextDouble() - 0.5D) * 1.4D, y + 0.2D, z + (r.nextDouble() - 0.5D) * 1.4D, 0.0D, 0.04D, 0.0D);
            // Белый пар с тела: внешнее тело в полную силу.
            level.addParticle(ParticleTypes.WHITE_SMOKE, x + (r.nextDouble() - 0.5D) * 0.9D, y + 1.4D + r.nextDouble() * 1.0D, z + (r.nextDouble() - 0.5D) * 0.9D,
                    (r.nextDouble() - 0.5D) * 0.02D, 0.07D, (r.nextDouble() - 0.5D) * 0.02D);
        }
    }

    private static void ringParticles(Level level, RandomSource r, double x, double y, double z, double radius, int n, double speed) {
        BlockParticleOption stone = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE_BRICKS.defaultBlockState());
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            level.addParticle(stone, x + Math.cos(a) * radius * 0.5D, y, z + Math.sin(a) * radius * 0.5D,
                    Math.cos(a) * speed, 0.3D + r.nextDouble() * 0.2D, Math.sin(a) * speed);
            if (i % 2 == 0) {
                level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, x + Math.cos(a) * radius, y, z + Math.sin(a) * radius, 0.0D, 0.02D, 0.0D);
            }
        }
    }
}
