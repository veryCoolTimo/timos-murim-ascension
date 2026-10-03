package io.github.verycooltimo.murim.client.bandit;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.BanditMove;
import io.github.verycooltimo.murim.entity.BanditSwordsman;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;

/**
 * Простые эффекты бандита-мечника (автор: «без сложных эффектов»): блик на клинке за 4 тика до
 * удара — момент «сейчас ударит»; у элитного — ци стекает на клинок в замахе и голубой след
 * рывка-разреза. Всё по видимому состоянию бандита, без пакетов.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class BanditFx {

    private static final Vector3f QI_BLUE = new Vector3f(0.45F, 0.80F, 1.0F);

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) {
            return;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof BanditSwordsman b && b.isAlive()) {
                tick(b, mc.level);
            }
        }
    }

    private static void tick(BanditSwordsman b, Level level) {
        int s = b.state();
        BanditMove m = b.move();
        int age = (int) b.stateAge(0.0F);
        RandomSource r = b.getRandom();
        double yaw = Math.toRadians(b.yBodyRot);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        if (s == Bandit.WINDUP && age == Math.max(1, m.windup() - 4)) {
            Vec3 tip = blade(b, m, fx, fz, rx, rz);
            for (int i = 0; i < 5; i++) {
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, tip.x, tip.y, tip.z,
                        (r.nextDouble() - 0.5D) * 0.25D, (r.nextDouble() - 0.5D) * 0.25D, (r.nextDouble() - 0.5D) * 0.25D);
            }
        }
        if (m == BanditMove.QI_DASH && s == Bandit.WINDUP) {
            Vec3 tip = blade(b, m, fx, fz, rx, rz);
            int n = 1 + age / 4;
            for (int i = 0; i < n; i++) {
                double k = r.nextDouble();
                level.addParticle(new DustParticleOptions(QI_BLUE, 0.8F),
                        Mth.lerp(k, b.getX() + rx * 0.4D, tip.x), Mth.lerp(k, b.getY() + 0.9D, tip.y), Mth.lerp(k, b.getZ() + rz * 0.4D, tip.z),
                        0.0D, 0.02D, 0.0D);
            }
        }
        if (m == BanditMove.QI_DASH && s == Bandit.STRIKE && age <= BanditMove.DASH_TICKS + 1) {
            for (int i = 0; i < 8; i++) {
                double k = i / 8.0D;
                double x = Mth.lerp(k, b.xo, b.getX());
                double y = Mth.lerp(k, b.yo, b.getY()) + 0.75D + r.nextDouble() * 0.55D;
                double z = Mth.lerp(k, b.zo, b.getZ());
                level.addParticle(new DustParticleOptions(QI_BLUE, 1.2F), x + rx * 0.3D, y, z + rz * 0.3D, 0.0D, 0.0D, 0.0D);
            }
            level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, b.getX(), b.getY() + 1.0D, b.getZ(), 0.0D, 0.01D, 0.0D);
        }
    }

    /** Примерное место клинка в конце замаха — по позе клипа {@code windup*}. */
    private static Vec3 blade(BanditSwordsman b, BanditMove m, double fx, double fz, double rx, double rz) {
        if (m == BanditMove.SWEEP) {
            return new Vec3(b.getX() + rx * 1.1D - fx * 0.3D, b.getY() + 1.45D, b.getZ() + rz * 1.1D - fz * 0.3D);
        }
        if (m == BanditMove.THRUST || m == BanditMove.QI_DASH) {
            return new Vec3(b.getX() + rx * 0.6D - fx * 0.9D, b.getY() + 0.9D, b.getZ() + rz * 0.6D - fz * 0.9D);
        }
        return new Vec3(b.getX() + rx * 0.3D - fx * 0.85D, b.getY() + 2.45D, b.getZ() + rz * 0.3D - fz * 0.85D);
    }

    private BanditFx() {
    }
}
