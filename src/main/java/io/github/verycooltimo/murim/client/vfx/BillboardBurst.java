package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

/**
 * Россыпь мелких искр по спирали — внутрь к средоточию или наружу от него.
 *
 * <p>Извлечено из сбора ладони. Направление движения читается с первого взгляда и
 * отличает сбор силы от её выброса: на референсе первой панели искры втягиваются
 * в ладонь, на второй — разлетаются.
 *
 * <p>Искры мелкие и разного размера. Полсотни одинаковых точек дают шум, а не
 * насыщенность (ADR-85); масштаб задаётся вместе с фазой жизни каждой искры.
 */
public final class BillboardBurst {

    private static final double GOLDEN_ANGLE = 2.399D;

    /**
     * Искры, втягивающиеся к точке.
     *
     * <p>Яркость растёт по мере приближения: искра разгорается, входя в средоточие,
     * и гаснет в нуле цикла. Затухание в точке перехода обязательно — иначе искры
     * мигают, появляясь на полном радиусе уже яркими.
     */
    public static void inward(VertexConsumer consumer, PoseStack.Pose pose, Vec3 centre,
                              Vec3 cameraLocal, int count, float age, double radius,
                              float intensity, VfxColour warm, VfxColour cold) {
        draw(consumer, pose, centre, cameraLocal, count, age, radius, intensity,
             warm, cold, true);
    }

    /** Искры, разлетающиеся от точки. */
    public static void outward(VertexConsumer consumer, PoseStack.Pose pose, Vec3 centre,
                               Vec3 cameraLocal, int count, float age, double radius,
                               float intensity, VfxColour warm, VfxColour cold) {
        draw(consumer, pose, centre, cameraLocal, count, age, radius, intensity,
             warm, cold, false);
    }

    private static void draw(VertexConsumer consumer, PoseStack.Pose pose, Vec3 centre,
                             Vec3 cameraLocal, int count, float age, double radius,
                             float intensity, VfxColour warm, VfxColour cold, boolean inward) {
        if (count <= 0 || intensity <= 0.0F || radius <= 0.0D) {
            return;
        }
        for (int i = 0; i < count; i++) {
            float cycle = ((age * 0.05F) + i / (float) count) % 1.0F;
            double phase = inward ? cycle : 1.0F - cycle;
            double angle = i * GOLDEN_ANGLE + age * 0.06D;
            double distance = radius * (1.0D - 0.85D * phase);
            double lift = Math.sin(angle * 1.3D + i) * radius * 0.55D * (1.0D - phase);
            Vec3 point = centre.add(new Vec3(
                    Math.cos(angle) * distance, lift, Math.sin(angle) * distance));

            // Треть искр холодные. Прежде было наоборот, и белая масса забивала цвет —
            // перекос, названный и человеком, и вторым мнением.
            boolean isCold = (i % 3) == 0;
            VfxColour tint = isCold ? cold : warm;
            VfxDraw.billboard(consumer, pose, point, cameraLocal,
                              radius * (0.075D + 0.075D * cycle),
                              intensity * cycle * 0.9F,
                              tint.red(), tint.green(), tint.blue());
        }
    }

    private BillboardBurst() {
    }
}
