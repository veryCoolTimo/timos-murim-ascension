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
     * Серп — второй слой удара. Стартует на тик позже ленты: правило 04 требует разносить
     * старты слоёв минимум на 16 мс, иначе три слоя сливаются в один и эффект читается плоско.
     */
    private static final float CRESCENT_START_TICK = 14.0F;

    /** Живёт 3 тика — 150 мс. Дольше держать нельзя: серп начинает читаться как висящее пятно. */
    private static final float CRESCENT_LIFE = 3.0F;

    /**
     * Пиковая непрозрачность серпа.
     *
     * <p>Низкая намеренно. Слой аддитивный, и при альфе около единицы он мгновенно уходит
     * в насыщение: три слоя сливаются в сплошное белое пятно, в котором не видно ни формы
     * серпа, ни ленты под ним. Поймано на кадрах 2026-08-10 — первая версия с альфой 1.0
     * читалась как приклеенный к голове факел.
     */
    private static final float CRESCENT_PEAK_ALPHA = 0.48F;

    /** Максимальная полуширина серпа в блоках. Заметно шире ленты: это тело удара. */
    private static final double CRESCENT_HALF_WIDTH = 0.21D;

    /** Вспышка ядра — третий слой, ещё на полтика позже серпа. */
    private static final float CORE_START_TICK = 14.5F;

    private static final float CORE_LIFE = 3.0F;

    /** Пиковая непрозрачность ядра — по той же причине, что и у серпа. */
    private static final float CORE_PEAK_ALPHA = 0.58F;

    /** Доля дуги, где вспыхивает ядро: середина взмаха, то есть точка контакта. */
    // Не середина дуги: там точка приходится игроку на голову, и вспышка читается как
    // светящееся лицо. Ближе к концу взмаха — это уже вынесенный вперёд клинок.
    private static final double CORE_ARC_POSITION = 0.74D;

    /**
     * Замах — самая длинная фаза техники и до сих пор полностью пустая: почти секунда,
     * за которую не происходило ничего. Именно из-за неё техника читалась как «мало эффектов»,
     * хотя сам удар насыщен. Свечение у клинка и сбор энергии заполняют эту паузу
     * и превращают её из ожидания в подготовку.
     */
    private static final float WINDUP_START_TICK = 3.0F;

    /** Конец накопления совпадает с началом взмаха: энергия срывается вместе с клинком. */
    private static final float WINDUP_END_TICK = 13.0F;

    /** Сколько искр сходится к лезвию. Немного: важна читаемость, а не густота. */
    private static final int MOTE_COUNT = 7;

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
            renderLayers(poseStack, buffers, camera, entity, age, partialTick);
            return false;
        });

        // Буферы сбрасываются сразу: эффект живёт один кадр, накопление здесь не нужно.
        // Порядок сброса задаёт порядок отрисовки слоёв — ядро поверх серпа, серп поверх ленты.
        buffers.endBatch(MurimRenderTypes.bladeTrail());
        buffers.endBatch(MurimRenderTypes.bladeCrescent());
        buffers.endBatch(MurimRenderTypes.impactCore());
    }

    /**
     * Три слоя одного удара: тянущаяся лента, серп и вспышка ядра. Общая для них подготовка
     * матрицы вынесена сюда, чтобы не повторять поворот и перенос трижды за кадр.
     */
    private static void renderLayers(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                     Camera camera, Entity entity, float age, float partialTick) {
        renderWindup(poseStack, buffers.getBuffer(MurimRenderTypes.impactCore()), camera,
                     entity, age, partialTick);
        renderTrail(poseStack, buffers.getBuffer(MurimRenderTypes.bladeTrail()), camera,
                    entity, age, partialTick);
        renderCrescent(poseStack, buffers.getBuffer(MurimRenderTypes.bladeCrescent()), camera,
                       entity, age, partialTick);
        renderCore(poseStack, buffers.getBuffer(MurimRenderTypes.impactCore()), camera,
                   entity, age, partialTick);
    }

    /**
     * Накопление энергии на замахе: свечение у лезвия и сходящиеся к нему искры.
     *
     * <p>Растёт к концу замаха, а не держится ровно: зритель должен видеть, что сила
     * набирается, иначе пауза перед ударом читается как задержка, а не как подготовка.
     */
    private static void renderWindup(PoseStack poseStack, VertexConsumer consumer, Camera camera,
                                     Entity entity, float age, float partialTick) {
        float charge = (age - WINDUP_START_TICK) / (WINDUP_END_TICK - WINDUP_START_TICK);
        if (charge < 0.0F || charge > 1.0F) {
            return;
        }
        // Кубический рост: почти незаметно в начале, заметный всплеск перед самым срывом.
        float intensity = charge * charge * charge;

        BladeArc arc = BladeArc.CEREMONIAL_DRAW;
        Frame frame = frame(entity, camera, partialTick);
        // Точка у лезвия в занесённом положении — это начало будущей дуги.
        Vec3 blade = arc.tipAt(0.0D).add(arc.pivot()).scale(0.5D);

        poseStack.pushPose();
        try {
            applyFrame(poseStack, frame);
            com.mojang.blaze3d.vertex.PoseStack.Pose pose = poseStack.last();
            Vec3 toBlade = frame.cameraLocal.subtract(blade).normalize();

            // Свечение у самого клинка.
            billboard(consumer, pose, blade, toBlade,
                      0.18D + 0.34D * intensity, 0.15F + 0.45F * intensity);

            // Искры сходятся по спирали: радиус падает, вращение продолжается.
            double radius = 1.5D * (1.0D - charge) + 0.12D;
            float spin = age * 0.28F;
            for (int i = 0; i < MOTE_COUNT; i++) {
                double angle = spin + i * (Math.PI * 2.0D / MOTE_COUNT);
                // Разная высота у искр: плоское кольцо читается как декорация, а не как сбор силы.
                double lift = Math.sin(angle * 1.7D + i) * 0.35D * (1.0D - charge);
                Vec3 offset = new Vec3(Math.cos(angle) * radius, lift, Math.sin(angle) * radius);
                Vec3 point = blade.add(offset);
                billboard(consumer, pose, point, frame.cameraLocal.subtract(point).normalize(),
                          0.05D + 0.07D * intensity, 0.20F + 0.55F * intensity);
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** Квад, развёрнутый к камере. Общая заготовка для точечных вспышек. */
    private static void billboard(VertexConsumer consumer, com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                                  Vec3 centre, Vec3 forward, double size, float alpha) {
        if (alpha <= 0.0F || size <= 0.0D) {
            return;
        }
        Vec3 reference = Math.abs(forward.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D)
                                                     : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = forward.cross(reference).normalize().scale(size);
        Vec3 up = right.normalize().cross(forward).normalize().scale(size);

        vertex(consumer, pose, centre.subtract(right).subtract(up), forward, 0.0F, 0.0F, alpha);
        vertex(consumer, pose, centre.subtract(right).add(up), forward, 0.0F, 1.0F, alpha);
        vertex(consumer, pose, centre.add(right).add(up), forward, 1.0F, 1.0F, alpha);
        vertex(consumer, pose, centre.add(right).subtract(up), forward, 1.0F, 0.0F, alpha);
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
     * Серп удара: тело взмаха, а не его след.
     *
     * <p>В отличие от ленты не тянется за клинком, а вспыхивает целиком на всю дугу
     * и сразу гаснет. Ширина сходит к нулю на обоих концах — это и даёт форму серпа,
     * а не полосы одинаковой толщины.
     */
    private static void renderCrescent(PoseStack poseStack, VertexConsumer consumer, Camera camera,
                                       Entity entity, float age, float partialTick) {
        float life = (age - CRESCENT_START_TICK) / CRESCENT_LIFE;
        if (life < 0.0F || life > 1.0F) {
            return;
        }
        // Пик в первые проценты жизни, дальше только спад — требование правила 04.
        float alpha = life < 0.12F
                ? Mth.clamp(life / 0.12F, 0.0F, 1.0F)
                : (1.0F - (life - 0.12F) / 0.88F);
        alpha = Mth.clamp(alpha, 0.0F, 1.0F) * CRESCENT_PEAK_ALPHA;
        if (alpha <= 0.0F) {
            return;
        }

        BladeArc arc = BladeArc.CEREMONIAL_DRAW;
        Frame frame = frame(entity, camera, partialTick);
        int segments = Math.min(MAX_SEGMENTS,
                io.github.verycooltimo.murim.client.ClientConfig.trailSegments());

        poseStack.pushPose();
        try {
            applyFrame(poseStack, frame);
            com.mojang.blaze3d.vertex.PoseStack.Pose pose = poseStack.last();
            Vec3 fallbackSide = arc.planeNormal();

            Vec3 prevLeft = null;
            Vec3 prevRight = null;
            Vec3 prevNormal = null;
            float prevAlpha = 0.0F;
            float prevU = 0.0F;

            for (int i = 0; i <= segments; i++) {
                float along = (float) i / segments;
                double t = 0.04D + 0.92D * along;
                Vec3 point = arc.tipAt(t);
                Vec3 tangent = arc.tangentAt(t).normalize();
                Vec3 toCamera = frame.cameraLocal.subtract(point).normalize();

                Vec3 side = tangent.cross(toCamera);
                if (side.lengthSqr() < 1.0E-4D) {
                    side = fallbackSide;
                }
                Vec3 unitSide = side.normalize();
                // Синус даёт сходящиеся острия на концах; степень меньше единицы удерживает
                // середину широкой, иначе серп выглядит вялым веретеном.
                double halfWidth = CRESCENT_HALF_WIDTH * Math.pow(Math.sin(Math.PI * along), 0.55D);
                Vec3 offset = unitSide.scale(halfWidth);

                Vec3 left = point.add(offset);
                Vec3 right = point.subtract(offset);
                Vec3 faceNormal = unitSide.cross(tangent).normalize();

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
     * Вспышка ядра в точке контакта — квад, развёрнутый к камере.
     *
     * <p>Самый короткий из трёх слоёв и самый яркий. Растёт рывком в первые проценты жизни,
     * дальше только гаснет: отношение удара к рассеиванию держится не хуже 1:3.
     */
    private static void renderCore(PoseStack poseStack, VertexConsumer consumer, Camera camera,
                                   Entity entity, float age, float partialTick) {
        float life = (age - CORE_START_TICK) / CORE_LIFE;
        if (life < 0.0F || life > 1.0F) {
            return;
        }
        float rise = Mth.clamp(life / 0.10F, 0.0F, 1.0F);
        float fall = 1.0F - Mth.clamp((life - 0.10F) / 0.90F, 0.0F, 1.0F);
        float alpha = rise * fall * fall * CORE_PEAK_ALPHA;
        if (alpha <= 0.0F) {
            return;
        }
        double size = 0.28D + 0.45D * life;

        Frame frame = frame(entity, camera, partialTick);
        Vec3 centre = BladeArc.CEREMONIAL_DRAW.tipAt(CORE_ARC_POSITION);

        poseStack.pushPose();
        try {
            applyFrame(poseStack, frame);
            com.mojang.blaze3d.vertex.PoseStack.Pose pose = poseStack.last();

            Vec3 forward = frame.cameraLocal.subtract(centre).normalize();
            // Опорный вектор выбирается не параллельным взгляду, иначе базис вырождается
            // при взгляде строго сверху.
            Vec3 reference = Math.abs(forward.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D)
                                                         : new Vec3(0.0D, 1.0D, 0.0D);
            Vec3 right = forward.cross(reference).normalize().scale(size);
            Vec3 up = right.normalize().cross(forward).normalize().scale(size);

            Vec3 a = centre.subtract(right).subtract(up);
            Vec3 b = centre.subtract(right).add(up);
            Vec3 c = centre.add(right).add(up);
            Vec3 d = centre.add(right).subtract(up);

            vertex(consumer, pose, a, forward, 0.0F, 0.0F, alpha);
            vertex(consumer, pose, b, forward, 0.0F, 1.0F, alpha);
            vertex(consumer, pose, c, forward, 1.0F, 1.0F, alpha);
            vertex(consumer, pose, d, forward, 1.0F, 0.0F, alpha);
        } finally {
            poseStack.popPose();
        }
    }

    /** Положение и разворот игрока на текущем кадре — общая подготовка для всех трёх слоёв. */
    private record Frame(Vec3 feet, Vec3 cameraPos, Vec3 cameraLocal, float bodyYaw) {
    }

    private static Frame frame(Entity entity, Camera camera, float partialTick) {
        Vec3 feet = new Vec3(
                Mth.lerp(partialTick, entity.xOld, entity.getX()),
                Mth.lerp(partialTick, entity.yOld, entity.getY()),
                Mth.lerp(partialTick, entity.zOld, entity.getZ()));
        float bodyYaw = entity instanceof net.minecraft.world.entity.LivingEntity living
                ? Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot)
                : Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
        Vec3 cameraPos = camera.getPosition();
        return new Frame(feet, cameraPos, toLocal(cameraPos.subtract(feet), bodyYaw), bodyYaw);
    }

    private static void applyFrame(PoseStack poseStack, Frame frame) {
        poseStack.translate(frame.feet.x - frame.cameraPos.x,
                            frame.feet.y - frame.cameraPos.y,
                            frame.feet.z - frame.cameraPos.z);
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-frame.bodyYaw));
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
