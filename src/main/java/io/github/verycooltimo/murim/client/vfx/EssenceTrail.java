package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Шлейф эссенции за кистью на ударе.
 *
 * <p>Описание автора: над ладонью тянется шлейф — те самые дуги, что собирались на первой
 * картинке. Поэтому шлейф рисуется тем же материалом, что и дуги сбора: мягкий ореол,
 * светлое ядро и широкий бледный проход, а нитей несколько, со своим шумом у каждой.
 *
 * <p>Путь берётся из истории положений кисти за последние тики: шлейф идёт ровно там,
 * где прошла рука, а не по заранее заданной кривой.
 */
public final class EssenceTrail {

    /**
     * Рисует шлейф.
     *
     * @param path      точки пути кисти от старой к новой, в системе вызывающего
     * @param strands   число нитей
     * @param width     полуширина ореола у кисти, блоки
     * @param intensity общий множитель яркости
     * @param since     тиков с удара: шум течёт во времени
     */
    public static void draw(VertexConsumer consumer, PoseStack.Pose pose, List<Vec3> path,
                            Vec3 cameraLocal, int strands, double width, float intensity,
                            float since, long seed, VfxColour halo, VfxColour core) {
        int n = path.size();
        if (n < 3 || intensity <= 0.0F) {
            return;
        }
        for (int s = 0; s < strands; s++) {
            double amp = Chaos.range(s, seed ^ 0x7A1L, 0.04F, 0.14F);
            double freq = Chaos.range(s, seed ^ 0x7A2L, 1.0F, 2.6F);
            double phase = Chaos.range(s, seed ^ 0x7A3L, 0.0F, (float) (Math.PI * 2.0D));
            float share = s == 0 ? 1.0F : 0.6F;
            Vec3[] points = new Vec3[n];
            double[] haloW = new double[n];
            double[] coreW = new double[n];
            double[] bloomW = new double[n];
            float[] haloA = new float[n];
            float[] coreA = new float[n];
            float[] bloomA = new float[n];
            for (int i = 0; i < n; i++) {
                // t: 0 — хвост, 1 — кисть.
                double t = i / (double) (n - 1);
                Vec3 tangent = path.get(Math.min(n - 1, i + 1)).subtract(path.get(Math.max(0, i - 1)));
                Vec3 side = tangent.lengthSqr() < 1.0E-8D ? new Vec3(0.0D, 1.0D, 0.0D)
                        : tangent.cross(new Vec3(0.0D, 1.0D, 0.0D));
                if (side.lengthSqr() < 1.0E-8D) {
                    side = new Vec3(1.0D, 0.0D, 0.0D);
                }
                side = side.normalize();
                Vec3 up = side.cross(tangent.lengthSqr() < 1.0E-8D ? new Vec3(0, 0, 1) : tangent.normalize());
                // Нити расходятся к хвосту и сходятся у кисти.
                double spread = amp * (1.0D - t);
                double wave = t * freq * Math.PI * 2.0D + phase + since * 0.25D;
                points[i] = path.get(i)
                        .add(side.scale(Math.sin(wave) * spread))
                        .add(up.scale(Math.cos(wave * 0.8D) * spread));
                double body = Chaos.widthProfile((float) Math.max(0.02D, Math.min(0.98D, t)), 0.8F);
                haloW[i] = width * share * body;
                coreW[i] = width * 0.24D * share * body;
                bloomW[i] = haloW[i] * 1.8D;
                float a = intensity * share * (float) (0.15D + 0.85D * t);
                haloA[i] = a * 0.55F;
                coreA[i] = a * 0.95F;
                bloomA[i] = a * 0.2F;
            }
            RibbonMesher.draw(consumer, pose, points, bloomW, bloomA, cameraLocal,
                              halo.red(), halo.green(), halo.blue());
            RibbonMesher.draw(consumer, pose, points, haloW, haloA, cameraLocal,
                              halo.red(), halo.green(), halo.blue());
            RibbonMesher.draw(consumer, pose, points, coreW, coreA, cameraLocal,
                              core.red(), core.green(), core.blue());
        }
    }

    private EssenceTrail() {
    }
}
