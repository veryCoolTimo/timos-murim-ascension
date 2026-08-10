package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Лента следа клинка.
 *
 * <p>Закрывает разрыв, который иначе виден на срыве замаха: взмах занимает три тика, и между
 * верхней и нижней позой клинка нет ни одного промежуточного положения — движение читается
 * как склейка. Лента показывает пройденную дугу и делает удар непрерывным.
 *
 * <p>Стадия {@code AFTER_PARTICLES} выбрана как стадия по умолчанию для мировых эффектов:
 * {@code PoseStack} там живой, и отрисовка идёт после полупрозрачного слоя в обоих режимах
 * графики. Обоснование — правило 04.
 *
 * <p>Состояние по игрокам, а не в статическом поле одного игрока: технику может применить
 * и наблюдаемый игрок. Ключ — идентификатор сущности; запись снимается сама по истечении срока.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class BladeTrailRenderer {

    /**
     * Окно взмаха в тиках техники. Границы согласованы с ключевыми кадрами анимации:
     * клинок идёт от верхней позы к нижней между 0.70 и 0.84 секунды, то есть тики 14–17.
     * Растянутое окно даёт видимое отставание следа от меча (поймано на кадрах 2026-08-10).
     */
    private static final float SWEEP_START_TICK = 13.0F;

    // Окно на тик короче номинального: лента стартует по пакету от сервера, то есть на тик
    // позже начала техники, и без поправки след тянется за клинком с видимым отставанием.
    private static final float SWEEP_END_TICK = 16.0F;

    /**
     * Затухание после взмаха. Короткое намеренно: правило 04 требует отношения удара
     * к рассеиванию не хуже 1:3, но долгий шлейф превращает след в висящее пятно.
     */
    private static final float FADE_TICKS = 5.0F;

    /**
     * Длина светящегося хвоста в долях дуги. Хвост короче самой дуги: иначе лента
     * висит целиком и превращается в статичную полосу вместо следа.
     */
    private static final double TAIL_LENGTH = 0.72D;

    /**
     * Полная ширина ленты в блоках. Ширина в целый блок превращает след в веер:
     * лента должна быть заметно длиннее, чем шире, иначе не читается как след клинка.
     */
    private static final double RIBBON_WIDTH = 0.46D;

    /** Сегментов вдоль дуги. Больше — глаже кромка, дороже буфер. */
    private static final int SEGMENTS = 28;

    private static final Map<Integer, Trail> ACTIVE = new ConcurrentHashMap<>();

    /**
     * Клиентские тики с загрузки. Возраст ленты считается по нему, а не по игровому времени:
     * игровое время замирает при hit stop, а лента должна доигрывать — иначе она застынет
     * ровно в момент удара, то есть там, где нужна больше всего.
     */
    private static int clientTicks;

    /**
     * Состояние одной ленты. Хранится тик запуска, а не накопленный возраст: так возраст
     * не разъезжается, если кадры пропущены.
     */
    private static final class Trail {
        private final int startTick;

        private Trail(int startTick) {
            this.startTick = startTick;
        }

        private float ageAt(float partialTick) {
            return (clientTicks - startTick) + partialTick;
        }
    }

    /**
     * Мир, для которого накоплено состояние. Слабая ссылка: держать выгруженный уровень
     * из-за списка эффектов нельзя — это утечка на всю сессию.
     */
    private static java.lang.ref.WeakReference<net.minecraft.client.multiplayer.ClientLevel> level =
            new java.lang.ref.WeakReference<>(null);

    @SubscribeEvent
    static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        clientTicks++;
        // Смена мира не должна тянуть за собой чужие ленты: идентификаторы сущностей
        // в новом мире принадлежат другим существам, и след появился бы на постороннем.
        net.minecraft.client.multiplayer.ClientLevel current = Minecraft.getInstance().level;
        if (level.get() != current) {
            clear();
            level = new java.lang.ref.WeakReference<>(current);
        }
    }

    /** Запускает ленту у игрока. Вызывается по серверному событию начала техники. */
    public static void start(int entityId) {
        ACTIVE.put(entityId, new Trail(clientTicks));
    }

    /** Снимает ленту досрочно — например, когда технику прервали. */
    public static void cancel(int entityId) {
        ACTIVE.remove(entityId);
    }

    /** Сбрасывает всё состояние: смена мира не должна тянуть за собой чужие эффекты. */
    public static void clear() {
        ACTIVE.clear();
    }

    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ACTIVE.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        // Ловушка сигнатуры: getPartialTick отдаёт DeltaTracker, а не float (правило 04).
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Camera camera = event.getCamera();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(MurimRenderTypes.bladeTrail());
        PoseStack poseStack = event.getPoseStack();

        ACTIVE.entrySet().removeIf(entry -> {
            float age = entry.getValue().ageAt(partialTick);
            if (age > SWEEP_END_TICK + FADE_TICKS) {
                return true;
            }
            Entity entity = minecraft.level.getEntity(entry.getKey());
            if (entity == null) {
                return true;
            }
            renderTrail(poseStack, consumer, camera, entity, age, partialTick);
            return false;
        });

        // Буфер сбрасывается сразу: эффект живёт один кадр, накопление здесь не нужно.
        buffers.endBatch(MurimRenderTypes.bladeTrail());
    }

    private static void renderTrail(PoseStack poseStack, VertexConsumer consumer, Camera camera,
                                    Entity entity, float age, float partialTick) {
        float head = Mth.clamp((age - SWEEP_START_TICK) / (SWEEP_END_TICK - SWEEP_START_TICK), 0.0F, 1.0F);
        if (head <= 0.0F) {
            return;
        }
        float fade = age <= SWEEP_END_TICK
                ? 1.0F
                : Mth.clamp(1.0F - (age - SWEEP_END_TICK) / FADE_TICKS, 0.0F, 1.0F);
        if (fade <= 0.0F) {
            return;
        }

        Vec3 feet = new Vec3(
                Mth.lerp(partialTick, entity.xo, entity.getX()),
                Mth.lerp(partialTick, entity.yo, entity.getY()),
                Mth.lerp(partialTick, entity.zo, entity.getZ()));
        Vec3 cameraPos = camera.getPosition();
        float bodyYaw = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());

        poseStack.pushPose();
        try {
            poseStack.translate(feet.x - cameraPos.x, feet.y - cameraPos.y, feet.z - cameraPos.z);
            // Рысканье игрока: локальная дуга описана для взгляда на юг, как у ванильной модели.
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-bodyYaw));

            BladeArc arc = BladeArc.CEREMONIAL_DRAW;
            org.joml.Matrix4f pose = poseStack.last().pose();
            org.joml.Matrix3f normal = poseStack.last().normal();

            // Камера в системе координат игрока. Нужна, чтобы развернуть ленту шириной к зрителю:
            // плоскость рубящего взмаха содержит направление «вперёд», и при виде спереди или
            // сзади лента, построенная в этой плоскости, видна с ребра — вместо дуги остаётся
            // тонкая нить. Поймано на кадрах 2026-08-10. Поэтому лента строится билбордом
            // вдоль траектории острия, а не по двум радиусам дуги.
            Vec3 toCameraLocal = toLocal(cameraPos.subtract(feet), bodyYaw);

            double tailStart = Math.max(0.0D, head - TAIL_LENGTH);
            // Ширина берётся константой, а не из радиусов дуги: радиусы задают траекторию
            // острия, а толщина следа — вопрос читаемости, и связывать их незачем.
            double width = RIBBON_WIDTH;

            Vec3 prevLeft = null;
            Vec3 prevRight = null;
            float prevAlpha = 0.0F;
            float prevU = 0.0F;

            for (int i = 0; i <= SEGMENTS; i++) {
                float along = (float) i / SEGMENTS;
                double t = tailStart + (head - tailStart) * along;
                Vec3 point = arc.tipAt(t);

                // Касательная берётся по соседним точкам дуги: на краях — односторонняя разность.
                double step = 1.0D / (SEGMENTS * 2.0D);
                Vec3 tangent = arc.tipAt(Math.min(1.0D, t + step))
                        .subtract(arc.tipAt(Math.max(0.0D, t - step)));
                if (tangent.lengthSqr() < 1.0E-12D) {
                    continue;
                }

                Vec3 toCamera = toCameraLocal.subtract(point);
                Vec3 side = tangent.cross(toCamera);
                if (side.lengthSqr() < 1.0E-12D) {
                    continue;
                }
                // Хвост сужается: постоянная ширина читается как лента ткани, а не как след клинка.
                double halfWidth = width * 0.5D * (0.15D + 0.85D * along);
                side = side.normalize().scale(halfWidth);

                Vec3 left = point.add(side);
                Vec3 right = point.subtract(side);
                float alpha = fade * (0.30F + 0.70F * along * along);

                if (prevLeft != null) {
                    quad(consumer, pose, normal, prevRight, prevLeft, left, right,
                         prevAlpha, alpha, prevU, along);
                }
                prevLeft = left;
                prevRight = right;
                prevAlpha = alpha;
                prevU = along;
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Переводит смещение из мировых координат в систему игрока — обратное преобразование
     * к повороту {@code Axis.YP.rotationDegrees(-bodyYaw)}, применённому к матрице.
     */
    private static Vec3 toLocal(Vec3 delta, float bodyYaw) {
        double radians = Math.toRadians(bodyYaw);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3(delta.x * cos + delta.z * sin, delta.y, -delta.x * sin + delta.z * cos);
    }

    private static void quad(VertexConsumer consumer, org.joml.Matrix4f pose, org.joml.Matrix3f normal,
                             Vec3 innerA, Vec3 outerA, Vec3 outerB, Vec3 innerB,
                             float alphaA, float alphaB, float uA, float uB) {
        vertex(consumer, pose, normal, innerA, uA, 0.0F, alphaA);
        vertex(consumer, pose, normal, outerA, uA, 1.0F, alphaA);
        vertex(consumer, pose, normal, outerB, uB, 1.0F, alphaB);
        vertex(consumer, pose, normal, innerB, uB, 0.0F, alphaB);
    }

    private static void vertex(VertexConsumer consumer, org.joml.Matrix4f pose, org.joml.Matrix3f normal,
                               Vec3 position, float u, float v, float alpha) {
        // Формат NEW_ENTITY требует все элементы: цвет, uv, overlay, свет и нормаль.
        // Пропуск любого из них даёт мусор в буфере, а не ошибку компиляции.
        consumer.addVertex(pose, (float) position.x, (float) position.y, (float) position.z)
                .setColor(0.86F, 0.94F, 1.0F, alpha)
                .setUv(u, v)
                .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(0x00F000F0)
                .setNormal(0.0F, 1.0F, 0.0F);
    }

    private BladeTrailRenderer() {
    }
}
