package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniquePhase;
import io.github.verycooltimo.murim.combat.Techniques;
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
     * Тайминги слоёв выводятся из данных техники, а не зашиты числами.
     *
     * <p>Раньше они были константами, и добавление фазы ритуала сдвинуло бы удар на три
     * секунды, оставив все эффекты на прежних тиках. Привязка к {@code startTickOf} делает
     * шкалу единственным источником истины: правится длительность фазы — эффекты едут следом.
     *
     * <p>Поправка на тик: лента запускается по пакету от сервера, который приходит на тик
     * позже начала техники, поэтому возраст ленты равен номеру тика минус один.
     */
    private static final float IMPACT_AGE =
            Techniques.CEREMONIAL_DRAW.startTickOf(TechniquePhase.IMPACT) - 1.0F;

    private static final float WINDUP_AGE =
            Techniques.CEREMONIAL_DRAW.startTickOf(TechniquePhase.WINDUP) - 1.0F;

    private static final float RITUAL_LENGTH =
            Techniques.CEREMONIAL_DRAW.ticksOf(TechniquePhase.RITUAL);

    /** Начало прохода дуги — совпадает с моментом резолва урона. */
    private static final float SWEEP_START_TICK = IMPACT_AGE;

    /** Дуга пройдена целиком через три тика после начала удара. */
    private static final float SWEEP_END_TICK = IMPACT_AGE + 3.0F;

    /**
     * Затухание после взмаха. Короткое намеренно: правило 04 требует отношения удара
     * к рассеиванию не хуже 1:3, но долгий шлейф превращает след в висящее пятно.
     */
    private static final float FADE_TICKS = 5.0F;

    /** Сегментов вдоль дуги при высшем качестве. Больше — глаже кромка, дороже буфер. */
    private static final int MAX_SEGMENTS = 28;

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

    /**
     * Серп — второй слой удара. Стартует на тик позже ленты: правило 04 требует разносить
     * старты слоёв минимум на 16 мс, иначе три слоя сливаются в один и эффект читается плоско.
     */
    private static final float CRESCENT_START_TICK = IMPACT_AGE + 1.0F;

    /** Живёт 3 тика — 150 мс. Дольше держать нельзя: серп начинает читаться как висящее пятно. */
    private static final float CRESCENT_LIFE = 3.0F;

    /**
     * Пиковая непрозрачность серпа.
     *
     * <p>Заметно ниже единицы намеренно. Слой аддитивный, и при альфе около единицы он
     * мгновенно уходит в насыщение: три слоя сливаются в сплошное белое пятно, в котором
     * не видно ни формы серпа, ни ленты под ним. Поймано на кадрах 2026-08-10.
     */
    private static final float CRESCENT_PEAK_ALPHA = 0.48F;

    /** Максимальная полуширина серпа в блоках. Заметно шире ленты: это тело удара. */
    private static final double CRESCENT_HALF_WIDTH = 0.21D;

    /** Вспышка ядра — третий слой, ещё на полтика позже серпа. */
    private static final float CORE_START_TICK = IMPACT_AGE + 1.5F;

    private static final float CORE_LIFE = 3.0F;

    /** Пиковая непрозрачность ядра — по той же причине, что и у серпа. */
    private static final float CORE_PEAK_ALPHA = 0.58F;

    // Не середина дуги: там точка приходится игроку на голову, и вспышка читается как
    // светящееся лицо. Ближе к концу взмаха — это уже вынесенный вперёд клинок.
    private static final double CORE_ARC_POSITION = 0.74D;

    /**
     * Замах — самая длинная фаза после ритуала, и до сих пор он был полностью пуст.
     * Свечение у клинка и сбор энергии превращают паузу перед ударом в подготовку.
     */
    private static final float WINDUP_START_TICK = WINDUP_AGE + 3.0F;

    /** Конец накопления совпадает с началом взмаха: энергия срывается вместе с клинком. */
    private static final float WINDUP_END_TICK = IMPACT_AGE;

    /** Сколько искр сходится к лезвию. Немного: важна читаемость, а не густота. */
    private static final int MOTE_COUNT = 7;

    /** Искры ритуала. Больше, чем на замахе: три секунды пустого круга выглядели бы бедно. */
    private static final int RITUAL_MOTES = 12;

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
        renderRitual(poseStack, buffers, camera, entity, age, partialTick);
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
     * Ритуал перед техникой: круги энергии у ног, восходящие искры и свечение даньтяня.
     *
     * <p>Художественный прототип, а не система: настоящий ритуал даньтяня появится этапом
     * позже, а здесь проверяется постановка. Игрок с первой минуты видит, чем мод обещает
     * быть, и при этом проверяется всё тот же боевой пайплайн.
     *
     * <p>Три составляющих намеренно живут в разном темпе: круги вращаются медленно и ровно,
     * искры поднимаются рывками, свечение нарастает монотонно. Одинаковый темп читался бы
     * как один анимированный объект вместо сходящейся к телу силы.
     */
    private static void renderRitual(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                     Camera camera, Entity entity, float age, float partialTick) {
        if (RITUAL_LENGTH <= 0.0F) {
            return;
        }
        float progress = age / RITUAL_LENGTH;
        if (progress < 0.0F || progress > 1.0F) {
            return;
        }
        // Плавный вход и такой же выход: резкое появление кругов выглядит как ошибка отрисовки,
        // а резкий обрыв в конце — как потеря кадра.
        float envelope = Mth.clamp(progress / 0.15F, 0.0F, 1.0F)
                * Mth.clamp((1.0F - progress) / 0.12F, 0.0F, 1.0F);
        if (envelope <= 0.0F) {
            return;
        }

        Frame frame = frame(entity, camera, partialTick);
        poseStack.pushPose();
        try {
            applyFrame(poseStack, frame);
            com.mojang.blaze3d.vertex.PoseStack.Pose pose = poseStack.last();

            VertexConsumer rings = buffers.getBuffer(MurimRenderTypes.bladeCrescent());
            // Два кольца навстречу друг другу: одинаковое вращение читается как один диск.
            // Заметнее, чем у слоёв удара: в этой фазе кольца ничем не перекрываются
            // и не складываются с другими аддитивными слоями, риска пересвета нет.
            ring(rings, pose, 1.75D - 0.45D * progress, 0.075D, age * 0.035F,
                 0.34F * envelope);
            ring(rings, pose, 1.15D - 0.35D * progress, 0.045D, -age * 0.055F,
                 0.44F * envelope);

            VertexConsumer motes = buffers.getBuffer(MurimRenderTypes.impactCore());
            for (int i = 0; i < RITUAL_MOTES; i++) {
                // Каждая искра идёт по своему циклу подъёма, сдвинутому по фазе.
                float cycle = ((age * 0.045F) + i / (float) RITUAL_MOTES) % 1.0F;
                double angle = i * (Math.PI * 2.0D / RITUAL_MOTES) + age * 0.02D;
                double radius = (1.5D - 1.25D * cycle) * (1.0D - 0.35D * progress);
                double height = 0.05D + 1.35D * cycle;
                Vec3 point = new Vec3(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
                // Гаснут к верхней точке: искра втягивается в тело, а не улетает.
                float alpha = envelope * (1.0F - cycle) * 1.05F;
                billboard(motes, pose, point, frame.cameraLocal.subtract(point).normalize(),
                          0.055D + 0.04D * (1.0F - cycle), alpha);
            }

            // Даньтянь: центр тяжести всей сцены, поэтому нарастает монотонно до самого конца.
            Vec3 dantian = new Vec3(0.0D, 1.02D, 0.0D);
            billboard(motes, pose, dantian, frame.cameraLocal.subtract(dantian).normalize(),
                      0.16D + 0.26D * progress, envelope * (0.18F + 0.42F * progress));
        } finally {
            poseStack.popPose();
        }
    }

    /** Плоское кольцо в горизонтальной плоскости у ног игрока. */
    private static void ring(VertexConsumer consumer, com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                             double radius, double halfWidth, float rotation, float alpha) {
        if (alpha <= 0.0F) {
            return;
        }
        final int segments = 32;
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        for (int i = 0; i < segments; i++) {
            double a0 = rotation + i * (Math.PI * 2.0D / segments);
            double a1 = rotation + (i + 1) * (Math.PI * 2.0D / segments);
            double y = 0.03D;
            Vec3 inner0 = new Vec3(Math.cos(a0) * (radius - halfWidth), y, Math.sin(a0) * (radius - halfWidth));
            Vec3 outer0 = new Vec3(Math.cos(a0) * (radius + halfWidth), y, Math.sin(a0) * (radius + halfWidth));
            Vec3 outer1 = new Vec3(Math.cos(a1) * (radius + halfWidth), y, Math.sin(a1) * (radius + halfWidth));
            Vec3 inner1 = new Vec3(Math.cos(a1) * (radius - halfWidth), y, Math.sin(a1) * (radius - halfWidth));
            float u0 = i / (float) segments;
            float u1 = (i + 1) / (float) segments;
            vertex(consumer, pose, inner0, up, u0, 0.0F, alpha);
            vertex(consumer, pose, outer0, up, u0, 1.0F, alpha);
            vertex(consumer, pose, outer1, up, u1, 1.0F, alpha);
            vertex(consumer, pose, inner1, up, u1, 0.0F, alpha);
        }
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
