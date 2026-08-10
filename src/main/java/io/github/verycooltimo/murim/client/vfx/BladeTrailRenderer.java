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

    /** Сегментов вдоль дуги при высшем качестве. Больше — глаже кромка, дороже буфер. */
    private static final int MAX_SEGMENTS = 28;

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
            // Дуга проходит через точку глаза (1.62 блока), поэтому в первом лице лента
            // размазывается по экрану крупными пятнами. Съёмка этого не показывала:
            // DevCaptureHandler принудительно ставит третье лицо.
            if (entity == minecraft.player && minecraft.options.getCameraType().isFirstPerson()) {
                return false;
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

        // xOld, а не xo: в штатном тике они совпадают, но при коррекции позиции сервером
        // правится только одно из полей, и лента на кадр уезжает от модели. Ванильные
        // рендереры интерполируют именно по xOld.
        Vec3 feet = new Vec3(
                Mth.lerp(partialTick, entity.xOld, entity.getX()),
                Mth.lerp(partialTick, entity.yOld, entity.getY()),
                Mth.lerp(partialTick, entity.zOld, entity.getZ()));
        Vec3 cameraPos = camera.getPosition();
        // Рысканье КОРПУСА, а не головы: модель игрока рисуется по yBodyRot, который догоняет
        // взгляд с задержкой. По getYRot лента отрывалась от клинка на десятки градусов
        // при резком повороте мыши.
        float bodyYaw = entity instanceof net.minecraft.world.entity.LivingEntity living
                ? Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot)
                : Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());

        poseStack.pushPose();
        try {
            poseStack.translate(feet.x - cameraPos.x, feet.y - cameraPos.y, feet.z - cameraPos.z);
            // Рысканье игрока: локальная дуга описана для взгляда на юг, как у ванильной модели.
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-bodyYaw));

            BladeArc arc = BladeArc.CEREMONIAL_DRAW;
            com.mojang.blaze3d.vertex.PoseStack.Pose pose = poseStack.last();

            // Камера в системе координат игрока. Нужна, чтобы развернуть ленту шириной к зрителю:
            // плоскость рубящего взмаха содержит направление «вперёд», и при виде спереди или
            // сзади лента, построенная в этой плоскости, видна с ребра — вместо дуги остаётся
            // тонкая нить. Поймано на кадрах 2026-08-10. Поэтому лента строится билбордом
            // вдоль траектории острия, а не по двум радиусам дуги.
            Vec3 toCameraLocal = toLocal(cameraPos.subtract(feet), bodyYaw);

            int segments = Math.min(MAX_SEGMENTS,
                    io.github.verycooltimo.murim.client.ClientConfig.trailSegments());
            double tailStart = Math.max(0.0D, head - TAIL_LENGTH);
            // Ширина берётся константой, а не из радиусов дуги: радиусы задают траекторию
            // острия, а толщина следа — вопрос читаемости, и связывать их незачем.
            double width = RIBBON_WIDTH;

            Vec3 fallbackSide = arc.planeNormal();
            Vec3 prevLeft = null;
            Vec3 prevRight = null;
            Vec3 prevNormal = null;
            float prevAlpha = 0.0F;
            float prevU = 0.0F;

            for (int i = 0; i <= segments; i++) {
                float along = (float) i / segments;
                double t = tailStart + (head - tailStart) * along;
                Vec3 point = arc.tipAt(t);
                Vec3 tangent = arc.tangentAt(t).normalize();
                Vec3 toCamera = toCameraLocal.subtract(point).normalize();

                // Оба вектора нормализованы, поэтому длина произведения — это синус угла между
                // ними, и порог означает ровно «камера смотрит почти вдоль касательной».
                // На ненормализованных векторах прежний порог 1e-12 не достигался никогда,
                // а направление ширины при этом скачком переворачивалось между кадрами.
                Vec3 side = tangent.cross(toCamera);
                if (side.lengthSqr() < 1.0E-4D) {
                    // Не пропускаем сегмент: пропуск оставляет прежние кромки и следующий
                    // квад растягивается через дыру. Берём устойчивую опору — нормаль плоскости.
                    side = fallbackSide;
                }
                // Хвост сужается: постоянная ширина читается как лента ткани, а не как след клинка.
                double halfWidth = width * 0.5D * (0.15D + 0.85D * along);
                Vec3 unitSide = side.normalize();
                Vec3 offset = unitSide.scale(halfWidth);

                Vec3 left = point.add(offset);
                Vec3 right = point.subtract(offset);
                // Настоящая нормаль билборда. Ванильный шейдер её игнорирует, но Iris пишет
                // нормаль в G-буфер, и захардкоженная «вверх» дала бы неверное затенение.
                Vec3 faceNormal = unitSide.cross(tangent).normalize();
                float alpha = fade * (0.30F + 0.70F * along * along);

                if (prevLeft != null) {
                    quad(consumer, pose, prevRight, prevLeft, left, right,
                         prevNormal, faceNormal, prevAlpha, alpha, prevU, along);
                }
                prevLeft = left;
                prevRight = right;
                prevNormal = faceNormal;
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

    private static void quad(VertexConsumer consumer, com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                             Vec3 innerA, Vec3 outerA, Vec3 outerB, Vec3 innerB,
                             Vec3 normalA, Vec3 normalB,
                             float alphaA, float alphaB, float uA, float uB) {
        vertex(consumer, pose, innerA, normalA, uA, 0.0F, alphaA);
        vertex(consumer, pose, outerA, normalA, uA, 1.0F, alphaA);
        vertex(consumer, pose, outerB, normalB, uB, 1.0F, alphaB);
        vertex(consumer, pose, innerB, normalB, uB, 0.0F, alphaB);
    }

    private static void vertex(VertexConsumer consumer, com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                               Vec3 position, Vec3 normal, float u, float v, float alpha) {
        // Формат NEW_ENTITY требует все элементы: цвет, uv, overlay, свет и нормаль.
        // Пропуск любого даёт исключение при завершении вершины, а не ошибку компиляции.
        // Свет выставлен в максимум формально: слой идёт с NO_LIGHTMAP и шейдером без Sampler2.
        consumer.addVertex(pose.pose(), (float) position.x, (float) position.y, (float) position.z)
                .setColor(0.86F, 0.94F, 1.0F, alpha)
                .setUv(u, v)
                .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(0x00F000F0)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
    }

    private BladeTrailRenderer() {
    }
}
