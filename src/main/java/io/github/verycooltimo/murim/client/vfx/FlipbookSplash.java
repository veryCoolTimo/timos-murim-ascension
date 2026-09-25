package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Всплеск яда — отрендеренный заранее флипбук из трёх слоёв на нескольких плоскостях.
 *
 * <p>Решение автора (2026-09-24): плоский отрендеренный эффект, три слоя — светлый, тёмный
 * и чёрный — вставленный несколько раз для объёма, чтобы ничего не нагружать. Процедурные
 * частицы до этого читались «взрывом больших партиклов», потом пузырями.
 *
 * <p>Кадры считает {@code tools/splash_flipbook.py}: атласы 4x4 {@code splash_light.png},
 * {@code splash_dark.png}, {@code splash_black.png}. Здесь только проигрывание:
 * <ul>
 *   <li>главная плоскость по поверхности цели и две слабые под ±50°, у каждой своё зеркало,
 *       масштаб и задержка — иначе одинаковые картинки читаются копиями;</li>
 *   <li>плоскость, повёрнутая к камере ребром, гаснет: с ребра она видна полоской;</li>
 *   <li>соседние кадры смешиваются по дробной части: 16 кадров на 13 тиков иначе дёргаются.</li>
 * </ul>
 * Порядок слоёв: чёрный и тёмный — обычное смешивание, светлый — аддитивно, поверх.
 */
public final class FlipbookSplash {

    /** Атлас 4x4. */
    private static final int COLS = 4;
    private static final int FRAMES = COLS * COLS;

    /** Тиков на кадр. 16 кадров — около 0.65 с: всплеск, а не облако. */
    private static final float TICKS_PER_FRAME = 1.0F;

    /** Полуразмер плоскости в блоках. */
    private static final double HALF = 0.95D;

    /**
     * Где в кадре точка удара, по вертикали от верха (как в генераторе, CENTRE[1]).
     * Плоскость сдвигается так, чтобы эта точка легла на контакт.
     */
    private static final double CONTACT_V = 0.56D;

    /**
     * Плоскости: поворот вокруг вертикали от «наружу», масштаб, задержка в тиках, зеркало,
     * яркость.
     *
     * <p>Главная плоскость лежит ПО ПОВЕРХНОСТИ ЦЕЛИ, поперёк удара (угол 90° от «наружу»):
     * яд расходится по телу противника, как на palm-2, где смотрим из-за спины бьющего.
     * Развёрнутая к камере плоскость сбоку давала разлив вбок, вдоль удара (отзыв автора).
     * Две боковые плоскости под ±50° слабее — они держат объём при взгляде сбоку, когда
     * главная видна с ребра.
     */
    private static final double[] PLANE_ANGLE = {90.0D, 40.0D, 140.0D};
    private static final double[] PLANE_SCALE = {1.0D, 0.8D, 0.75D};
    private static final float[] PLANE_DELAY = {0.0F, 1.0F, 2.0F};
    private static final boolean[] PLANE_MIRROR = {false, true, false};
    private static final float[] PLANE_ALPHA = {1.0F, 0.6F, 0.5F};

    /**
     * @param at    точка контакта в системе вызывающего
     * @param away  направление наружу от поверхности цели, горизонтальное
     * @param since тиков с прихода попадания
     */
    public static void draw(MultiBufferSource.BufferSource buffers, PoseStack.Pose pose,
                            Vec3 at, Vec3 away, Vec3 cameraLocal, float since) {
        if (since < 0.0F || since > FRAMES * TICKS_PER_FRAME + PLANE_DELAY[2]) {
            return;
        }
        layer(buffers, MurimRenderTypes.splashBlack(), pose, at, away, cameraLocal, since);
        layer(buffers, MurimRenderTypes.splashDark(), pose, at, away, cameraLocal, since);
        layer(buffers, MurimRenderTypes.splashLight(), pose, at, away, cameraLocal, since);
    }

    private static void layer(MultiBufferSource.BufferSource buffers, RenderType type,
                              PoseStack.Pose pose, Vec3 at, Vec3 away, Vec3 cameraLocal,
                              float since) {
        VertexConsumer consumer = buffers.getBuffer(type);
        double base = Math.atan2(away.z, away.x);
        for (int p = 0; p < PLANE_ANGLE.length; p++) {
            float local = (since - PLANE_DELAY[p]) / TICKS_PER_FRAME;
            if (local < 0.0F || local >= FRAMES) {
                continue;
            }
            Vec3 view = cameraLocal.subtract(at);
            if (view.lengthSqr() < 1.0E-6D) {
                continue;
            }
            double angle = base + Math.toRadians(PLANE_ANGLE[p]);
            // Ось ширины плоскости; нормаль — перпендикуляр к ней в горизонтали.
            Vec3 right = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
            Vec3 normal = new Vec3(-right.z, 0.0D, right.x);
            // С ребра плоскость — полоска: гасим её по мере разворота.
            float facing = PLANE_ALPHA[p] * (float) smooth(0.25D, 0.7D,
                    Math.abs(normal.dot(view.normalize())));
            if (facing <= 0.0F) {
                continue;
            }
            int frame = Mth.floor(local);
            float blend = local - frame;
            double half = HALF * PLANE_SCALE[p];
            quad(consumer, pose, at, right, normal, half, frame, (1.0F - blend) * facing,
                 PLANE_MIRROR[p]);
            if (frame + 1 < FRAMES) {
                quad(consumer, pose, at, right, normal, half, frame + 1, blend * facing,
                     PLANE_MIRROR[p]);
            }
        }
        buffers.endBatch(type);
    }

    private static void quad(VertexConsumer consumer, PoseStack.Pose pose, Vec3 at, Vec3 right,
                             Vec3 normal, double half, int frame, float alpha, boolean mirror) {
        if (alpha <= 0.001F) {
            return;
        }
        float u0 = (frame % COLS) / (float) COLS;
        float v0 = (frame / COLS) / (float) COLS;
        float u1 = u0 + 1.0F / COLS;
        float v1 = v0 + 1.0F / COLS;
        if (mirror) {
            float t = u0;
            u0 = u1;
            u1 = t;
        }
        // Точка удара в кадре ниже середины: сдвигаем плоскость вверх на эту разницу.
        Vec3 centre = at.add(0.0D, (CONTACT_V - 0.5D) * 2.0D * half, 0.0D);
        Vec3 r = right.scale(half);
        Vec3 up = new Vec3(0.0D, half, 0.0D);
        VfxDraw.vertex(consumer, pose, centre.subtract(r).add(up), normal, u0, v0, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(consumer, pose, centre.subtract(r).subtract(up), normal, u0, v1, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(consumer, pose, centre.add(r).subtract(up), normal, u1, v1, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(consumer, pose, centre.add(r).add(up), normal, u1, v0, alpha, 1.0F, 1.0F, 1.0F);
    }

    private static double smooth(double edge0, double edge1, double x) {
        double t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private FlipbookSplash() {
    }
}
