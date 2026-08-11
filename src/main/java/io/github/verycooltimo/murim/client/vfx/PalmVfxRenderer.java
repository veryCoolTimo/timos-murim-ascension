package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniquePhase;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ядовитая ладонь — эффект, собранный по разбору референсов.
 *
 * <p>Отдельный рендерер, а не расширение общей схемы дуги: у ладони другой язык. Там взмах
 * с траекторией острия, здесь сбор энергии в точку и выброс вперёд. Натягивать одно на другое
 * означало бы получить схему, которая плохо описывает оба случая. Если появится вторая техника
 * такого рода, слои имеет смысл обобщить — до тех пор обобщение преждевременно.
 *
 * <p><b>Слои, найденные на референсах</b> (docs/design/reference/palm-1.png и palm-2.png):
 * холодное бело-голубое ядро, немного зелёных прядей, тёмные штрихи поверх свечения,
 * россыпь мелкой белой пыли, чёрные угловатые осколки на ударе, стекающие потёки с каплей
 * на сборе, и корона из неровных шипов вокруг ладони.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class PalmVfxRenderer {

    /** Зелёных прядей немного — четыре-восемь, как на референсах. Не десятки. */
    private static final int STRANDS = 7;

    /** Тёмных штрихов больше, чем светящихся прядей: они и создают фактуру движения. */
    private static final int DARK_STRANDS = 14;

    /** Белой пыли много и она мелкая. */
    private static final int DUST = 56;

    /** Осколков немного, но они крупные и жёсткие. */
    private static final int SHARDS = 12;

    /** Шипов короны вокруг ладони. */
    private static final int CORONA_SPIKES = 16;

    /**
     * Семя беспорядка техники. Постоянное, а не случайное: эффект должен выглядеть
     * одинаково при каждом применении, иначе игрок не запомнит его силуэт, а отладка
     * по кадрам станет невоспроизводимой.
     */
    private static final long SEED = 0x5EED0FA1L;

    /** Ленты, стекающие с ладони на сборе. Главный элемент первой панели референса. */
    private static final int GATHER_RIBBONS = 7;

    /**
     * Крупные ленты — «большая форма» эффекта.
     *
     * <p>Плотность добирается слоями РАЗНОГО масштаба, а не количеством одинаковых точек:
     * две-три крупные ленты, несколько средних дуг, десятки мелких искр и мягкий туман.
     * Полсотни одинаковых белых точек дают шум, а не насыщенность.
     */
    private static final int BIG_RIBBONS = 3;

    /** Клубы мягкого зелёного тумана вокруг эффекта. */
    private static final int FOG_PUFFS = 5;

    /**
     * Куда «смотрит» веер прядей выброса.
     *
     * <p>Постоянная, а не случайная: силуэт техники должен запоминаться. Значение выбрано
     * так, чтобы веер уходил вниз-вбок от ладони, как на второй панели референса.
     *
     * <p>Раствор веера намеренно широкий. Узкий сектор (раствор около 200°) поднял
     * радиальную неравномерность с 0.78 до 0.86 при пределе 0.62: метрика штрафует
     * сжатие энергии в клин, а не симметрию. Нужен перекос, а не конус.
     */
    private static final float SECTOR_CENTRE = -0.7F;

    private static final Map<Integer, State> ACTIVE = new ConcurrentHashMap<>();

    /**
     * Возраст, С КОТОРЫМ КАДР БЫЛ РЕАЛЬНО НАРИСОВАН, включая дробную часть тика.
     *
     * <p>Телеметрия пишется в обработчике тика, а снимок берёт последний отрисованный кадр —
     * его дробная доля тика произвольна. Из-за этого два прогона одного билда расходились
     * ровно на тик, и метрики по одному кадру мерили дрожание выборки, а не эффект.
     */
    private static final Map<Integer, Float> DRAWN_AGE = new ConcurrentHashMap<>();

    /**
     * МИРОВАЯ точка, в которой эффект реально поставил ладонь в этом кадре.
     *
     * <p>Пишется в телеметрию рядом с позицией кости. Без этого нельзя отличить ошибку
     * привязки от ошибки измерения: обе выглядят как «эффект не там, где кость».
     */
    private static final Map<Integer, Vec3> DRAWN_PALM = new ConcurrentHashMap<>();

    /** Где эффект поставил ладонь, или {@code null}. */
    public static Vec3 drawnPalm(int entityId) {
        return DRAWN_PALM.get(entityId);
    }

    private static int clientTicks;

    private record State(int startTick, TechniqueDefinition definition) {
        float ageAt(float partial) {
            return (clientTicks - startTick) + partial;
        }
    }

    public static void start(int entityId, TechniqueDefinition definition) {
        if (definition == null) {
            return;
        }
        ACTIVE.put(entityId, new State(clientTicks, definition));
    }

    public static void cancel(int entityId) {
        ACTIVE.remove(entityId);
        forget(entityId);
    }

    /**
     * Забыть отладочные следы техники.
     *
     * <p>Вызывается на КАЖДОМ пути завершения, включая штатное истечение. Иначе записи
     * переживают выход из мира: в новом мире тот же идентификатор принадлежит другому
     * существу, и телеметрия получает координаты из прошлой сессии как «где эффект
     * нарисовал ладонь сейчас».
     */
    private static void forget(int entityId) {
        DRAWN_AGE.remove(entityId);
        DRAWN_PALM.remove(entityId);
    }

    /**
     * Возраст техники в тиках и тик удара — для отладочной съёмки.
     *
     * <p>Нужно потому, что кадр съёмки и возраст эффекта НЕ совпадают: съёмка стартует в тот
     * тик, когда запрос уходит на сервер, а эффект начинается только после ответа. Задержка
     * плавает между прогонами, и привязка метрик к номеру кадра сравнивала разные фазы.
     *
     * @return {@code {возраст, тик удара}} или {@code null}, если техника не активна
     */
    public static float[] captureAgeOf(int entityId) {
        State state = ACTIVE.get(entityId);
        if (state == null) {
            return null;
        }
        return new float[] {
                DRAWN_AGE.getOrDefault(entityId, (float) (clientTicks - state.startTick())),
                state.definition().startTickOf(TechniquePhase.IMPACT) - 1.0F
        };
    }

    public static void clear() {
        ACTIVE.clear();
        DRAWN_AGE.clear();
        DRAWN_PALM.clear();
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        clientTicks++;
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
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        PoseStack poseStack = event.getPoseStack();
        Camera camera = event.getCamera();

        ACTIVE.entrySet().removeIf(entry -> {
            State state = entry.getValue();
            float age = state.ageAt(partial);
            if (age > state.definition.totalTicks()) {
                forget(entry.getKey());
                return true;
            }
            Entity entity = minecraft.level.getEntity(entry.getKey());
            if (entity == null) {
                forget(entry.getKey());
                return true;
            }
            if (entity == minecraft.player && minecraft.options.getCameraType().isFirstPerson()) {
                return false;
            }
            render(poseStack, buffers, camera, entity, state.definition, age, partial);
            DRAWN_AGE.put(entry.getKey(), age);
            return false;
        });

    }

    private static void render(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                               Camera camera, Entity entity, TechniqueDefinition definition,
                               float age, float partial) {
        float impactAge = definition.startTickOf(TechniquePhase.IMPACT) - 1.0F;
        float windupAge = definition.startTickOf(TechniquePhase.WINDUP) - 1.0F;

        Vec3 feet = new Vec3(
                Mth.lerp(partial, entity.xOld, entity.getX()),
                Mth.lerp(partial, entity.yOld, entity.getY()),
                Mth.lerp(partial, entity.zOld, entity.getZ()));
        float bodyYaw = entity instanceof LivingEntity living
                ? Mth.rotLerp(partial, living.yBodyRotO, living.yBodyRot)
                : Mth.rotLerp(partial, entity.yRotO, entity.getYRot());
        Vec3 cameraPos = camera.getPosition();
        Vec3 cameraLocal = toLocal(cameraPos.subtract(feet), bodyYaw);

        poseStack.pushPose();
        try {
            poseStack.translate(feet.x - cameraPos.x, feet.y - cameraPos.y, feet.z - cameraPos.z);
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-bodyYaw));
            PoseStack.Pose pose = poseStack.last();

            Vec3 palm = palmLocal(entity, feet, bodyYaw);
            if (palm == null) {
                return;
            }
            // Точка берётся из ТОЙ ЖЕ матрицы, которой рисуется геометрия, а не обратным
            // преобразованием моих же функций. Прежний вариант считал
            // toWorld(toLocal(кость)) и по построению всегда возвращал ровно кость —
            // проверка не могла разойтись и не доказывала ничего.
            Vector4f drawn = pose.pose().transform(
                    new Vector4f((float) palm.x, (float) palm.y, (float) palm.z, 1.0F));
            DRAWN_PALM.put(entity.getId(), new Vec3(drawn.x() + cameraPos.x,
                                                    drawn.y() + cameraPos.y,
                                                    drawn.z() + cameraPos.z));
            if (age < impactAge) {
                gather(buffers, pose, cameraLocal, palm, age, windupAge, impactAge);
            } else {
                release(buffers, pose, cameraLocal, palm, age - impactAge);
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Точка ладони — из КОСТИ РУКИ, а не из чисел в коде.
     *
     * <p>Раньше здесь стояли литералы в системе «ступни плюс поворот корпуса». Такая точка
     * не следует за анимацией, приседанием и покачиванием, поэтому эффект отрывался от руки.
     * Измерение показало смещение 66 пикселей при пределе 18.
     *
     * <p>Позиция приходит из {@link BoneAnchorLayer}, который читает её внутри слоя рендера
     * игрока, где кости уже позированы анимацией — включая правки Player Animation Library.
     *
     * @return точка в СИСТЕМЕ ИГРОКА или {@code null}, если кость в этом кадре недоступна
     */
    private static Vec3 palmLocal(Entity entity, Vec3 feet, float bodyYaw) {
        if (!(entity instanceof net.minecraft.client.player.AbstractClientPlayer player)) {
            return null;
        }
        Vec3 world = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.RIGHT_HAND);
        if (world == null) {
            // Игрок не рисовался в этом кадре: вне поля зрения, невидим, первое лицо.
            // Рисовать эффект вслепую нельзя — он окажется не там.
            return null;
        }
        return toLocal(world.subtract(feet), bodyYaw);
    }

    private static void gather(MultiBufferSource.BufferSource buffers, PoseStack.Pose pose,
                               Vec3 cameraLocal, Vec3 palm, float age, float windupAge,
                               float impactAge) {
        float charge = Mth.clamp((age - windupAge) / Math.max(1.0F, impactAge - windupAge), 0.0F, 1.0F);
        if (charge <= 0.0F) {
            return;
        }
        // Сжатие перед ударом: последние тики сбора всё ГАСНЕТ и стягивается к ладони.
        // Без паузы кульминация не читается как кульминация — нечему контрастировать.
        // Совет из разбора: на импакте должны расти все параметры сразу, а до него —
        // падать. Раньше сбор разгорался до самого удара и был ярче самого удара.
        float squash = charge > 0.86F ? Mth.clamp((1.0F - charge) / 0.14F, 0.0F, 1.0F) : 1.0F;
        charge *= squash;
        if (charge <= 0.01F) {
            return;
        }

        // ВАЖНО: слои рисуются строго по одному. Общий источник буферов строит только один
        // тип за раз, и запрос второго молча закрывает первый — запись в удержанную ссылку
        // после этого падает с «Not building!». Поймано на первом же прогоне ладони.
        VertexConsumer glow = buffers.getBuffer(MurimRenderTypes.impactCore());

        // ВСЕ яркости сбора умножаются на заряд, без постоянного слагаемого.
        //
        // Раньше у тумана, зелёного и холодного ядра был пол (0.045, 0.144 и 0.35), не
        // зависящий от заряда: эффект вспыхивал на полную с первого тика и сорок тиков
        // стоял ровно. Измерение показало 74% энергии уже на четвёртом тике при заряде
        // 0.125 — то есть фазы у эффекта не было вовсе. Это прямой стоп-сигнал «дешёвого»
        // эффекта из правила 04, и он же был причиной, по которой кульминация оказывалась
        // слабее подготовки: сбор набирал всю энергию мгновенно и держал её до удара.
        //
        // Яркость на пике сохранена прежней, изменилось только начало.
        // Холодное ядро в ладони. Растёт кубически: сила должна набираться заметным всплеском.
        float core = charge * charge * charge;
        // Соотношение цветов перевёрнуто относительно первой версии. На референсе зелёное
        // занимает почти всю площадь, а холодное — только маленькое ядро в центре. У меня
        // было наоборот: белая вспышка на весь кадр и почти без зелени.
        // Мягкий зелёный туман: самая широкая и самая прозрачная масса. Она и создаёт
        // ощущение плотности, не засвечивая силуэт.
        for (int i = 0; i < FOG_PUFFS; i++) {
            double angle = i * 2.399D + age * 0.02D;
            double drift = 0.09D + 0.06D * Math.sin(age * 0.05D + i);
            Vec3 puff = palm.add(new Vec3(Math.cos(angle) * drift, Math.sin(angle) * drift * 0.7D,
                    Math.sin(angle * 1.3D) * drift));
            // Радиусы поджаты к ладони. Прежние полтора блока растаскивали центр свечения
            // на полкорпуса от руки: якорь был верным, а геометрия вокруг него — нет.
            billboard(glow, pose, puff, cameraLocal, 0.26D + 0.34D * core,
                      (charge * 0.075F + 0.063F * core), 0.26F, 0.95F, 0.42F);
        }
        billboard(glow, pose, palm, cameraLocal, 0.15D + 0.24D * core,
                  charge * 0.240F + 0.234F * core, 0.36F, 1.0F, 0.52F);
        // Белое ТОЛЬКО ядром и небольшое: у референса холодного мало, оно плотное и в центре.
        billboard(glow, pose, palm, cameraLocal, 0.055D + 0.10D * core,
                  charge * 0.50F + 0.55F * core, 0.88F, 0.99F, 1.0F);

        // Частицы СТЯГИВАЮТСЯ к ладони по спирали — направление читается с первой панели.
        for (int i = 0; i < DUST; i++) {
            float cycle = ((age * 0.05F) + i / (float) DUST) % 1.0F;
            double angle = i * 2.399D + age * 0.06D;
            double radius = (0.40D - 0.34D * cycle) * (1.0D - 0.25D * charge);
            double lift = Math.sin(angle * 1.3D + i) * 0.22D * (1.0D - cycle);
            Vec3 point = palm.add(new Vec3(Math.cos(angle) * radius, lift, Math.sin(angle) * radius));
            float alpha = charge * cycle * 0.9F;
            // Крупнее и с чередованием холодных и зелёных: на референсе частицы разного
            // размера и не все белые.
            // Холодной остаётся лишь треть искр. Прежде было наоборот, и белая масса
            // забивала зелёную — тот самый перекос, который назвали и я, и codex.
            boolean cold = (i % 3) == 0;
            billboard(glow, pose, point, cameraLocal, 0.030D + 0.030D * cycle, alpha,
                      cold ? 0.88F : 0.42F, 1.0F, cold ? 0.94F : 0.55F);
        }
        buffers.endBatch(MurimRenderTypes.impactCore());

        VertexConsumer strands = buffers.getBuffer(MurimRenderTypes.strand());

        // Длинные ленты, стекающие вниз от ладони. На референсе именно они занимают панель,
        // а не точки: без них сбор читается как искра, а не как поток силы.
        for (int i = 0; i < GATHER_RIBBONS; i++) {
            double sway = Math.sin(age * 0.06D + i * 1.7D);
            double side = (i - (GATHER_RIBBONS - 1) / 2.0D) * 0.16D;
            Vec3 top = palm.add(new Vec3(side * 0.4D, 0.10D, side * 0.3D));
            int segments = 5;
            Vec3 previous = top;
            for (int seg = 1; seg <= segments; seg++) {
                double t = seg / (double) segments;
                Vec3 point = top.add(new Vec3(
                        side + sway * 0.18D * t,
                        -(0.16D + 0.52D * charge) * t,
                        sway * 0.12D * t));
                strandQuad(strands, pose, previous, point, cameraLocal,
                           0.075D * (1.0D - 0.55D * t),
                           charge * 0.675F * (1.0F - 0.5F * (float) t), 0.34F, 1.0F, 0.5F);
                previous = point;
            }
        }

        // Крупные ленты — большая форма, которой не хватало сильнее всего.
        for (int b = 0; b < BIG_RIBBONS; b++) {
            double base = b * (Math.PI * 2.0D / BIG_RIBBONS) + age * 0.018D;
            Vec3 previous = palm;
            for (int seg = 1; seg <= 7; seg++) {
                double t = seg / 7.0D;
                double angle = base + t * Math.PI * 1.1D;
                double radius = (0.14D + 0.34D * charge) * Math.sin(Math.PI * t * 0.85D);
                Vec3 point = palm.add(new Vec3(Math.cos(angle) * radius,
                        0.30D * charge * Math.sin(Math.PI * t) - 0.25D * t,
                        Math.sin(angle) * radius * 0.7D));
                strandQuad(strands, pose, previous, point, cameraLocal,
                           0.15D * (1.0D - 0.4D * t), charge * 0.6F, 0.30F, 1.0F, 0.46F);
                previous = point;
            }
        }

        for (int i = 0; i < CORONA_SPIKES; i++) {
            double angle = i * (Math.PI * 2.0D / CORONA_SPIKES) + age * 0.03D;
            double jitter = 0.55D + 0.45D * Math.abs(Math.sin(i * 2.399D));
            double radius = (0.22D + 0.30D * core) * jitter;
            Vec3 tip = palm.add(new Vec3(Math.cos(angle) * radius, Math.sin(angle) * radius * 0.8D,
                    Math.sin(angle * 1.7D) * radius * 0.35D));
            strandQuad(strands, pose, palm, tip, cameraLocal, 0.022D,
                       0.28F * core, 0.40F, 1.0F, 0.58F);
        }
        buffers.endBatch(MurimRenderTypes.strand());

        VertexConsumer drips = buffers.getBuffer(MurimRenderTypes.drip());
        // Потёки с каплей: стекают с ладони вниз. На панели сбора они есть, на панели удара нет.
        for (int i = 0; i < 5; i++) {
            double phase = (age * 0.03F + i * 0.21D) % 1.0D;
            Vec3 top = palm.add(new Vec3(-0.08D + 0.05D * i, -0.05D, -0.04D + 0.03D * i));
            Vec3 bottom = top.add(new Vec3(0.0D, -0.20D - 0.28D * phase, 0.0D));
            strandQuad(drips, pose, top, bottom, cameraLocal, 0.075D,
                       (float) (charge * (1.0D - phase) * 1.0D), 0.72F, 1.0F, 0.78F);
        }
        buffers.endBatch(MurimRenderTypes.drip());

        // Тёмные штрихи поверх свечения — именно они дают фактуру смазанного движения.
        VertexConsumer dark = buffers.getBuffer(MurimRenderTypes.shard());
        for (int i = 0; i < DARK_STRANDS / 2; i++) {
            double angle = i * 1.7D + age * 0.02D;
            Vec3 a = palm.add(new Vec3(Math.cos(angle) * 0.5D, 0.25D * Math.sin(angle), Math.sin(angle) * 0.5D));
            Vec3 b = a.add(new Vec3(Math.cos(angle + 0.6D) * 0.45D, -0.12D, Math.sin(angle + 0.6D) * 0.45D));
            strandQuad(dark, pose, a, b, cameraLocal, 0.03D, 0.5F * charge, 0.06F, 0.09F, 0.07F);
        }
        buffers.endBatch(MurimRenderTypes.shard());
    }

    private static void release(MultiBufferSource.BufferSource buffers, PoseStack.Pose pose,
                                Vec3 cameraLocal, Vec3 palm, float since) {
        float life = Mth.clamp(since / 26.0F, 0.0F, 1.0F);
        float fade = 1.0F - life;
        if (fade <= 0.0F) {
            return;
        }
        Vec3 forward = new Vec3(0.0D, 0.0D, 1.0D);
        // Вынос НАЧИНАЕТСЯ У ЛАДОНИ и разгоняется, а не появляется сразу в метре впереди.
        // Прежняя формула давала на нулевом тике вынос 1.2 блока: центр эффекта прыгал
        // на 48 px за кадр, и удар читался как отдельная вспышка, оторванная от сбора.
        // Показатель степени <1 держит рывок быстрым, но непрерывным.
        // РАЗГОН, а не телепорт. Показатель 0.55 давал на первом же тике вынос с 0.15
        // до 2.0 блока — почти два блока за кадр. Сзади это движение идёт вдоль оси
        // взгляда и незаметно, сбоку выглядит скачком: замер с поворотом 90° показал
        // 377 px/тик при медиане 6. Сглаживающая кривая стартует медленно и ускоряется,
        // при этом полный вынос достигается за те же пять тиков.
        double launch = Math.min(1.0F, since / 5.0F);
        double ramp = launch * launch * (3.0D - 2.0D * launch);
        double reach = 0.15D + 4.45D * ramp;

        // Вспышка контакта: живёт три тика, но в них самая высокая яркость всей техники.
        // Именно она делает удар пиком, а не продолжением сбора.
        if (since < 3.5F) {
            float flash = 1.0F - since / 3.5F;
            VertexConsumer burst = buffers.getBuffer(MurimRenderTypes.impactCore());
            billboard(burst, pose, palm.add(forward.scale(0.45D)), cameraLocal,
                      0.30D + 0.85D * (1.0F - flash), flash * 1.6F, 0.92F, 1.0F, 0.98F);
            billboard(burst, pose, palm.add(forward.scale(0.45D)), cameraLocal,
                      0.55D + 1.30D * (1.0F - flash), flash * 0.7F, 0.40F, 1.0F, 0.55F);
            buffers.endBatch(MurimRenderTypes.impactCore());
        }

        VertexConsumer glow = buffers.getBuffer(MurimRenderTypes.impactCore());

        // Ядро: широкая холодная лента вперёд, слегка волнистая. Самый яркий элемент.
        int steps = 10;
        Vec3 previous = palm;
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            double wobble = Math.sin(t * 5.0D + since * 0.35D) * 0.16D * t;
            Vec3 point = palm.add(forward.scale(reach * t)).add(new Vec3(wobble, wobble * 0.6D, 0.0D));
            strandQuad(glow, pose, previous, point, cameraLocal, 0.34D * (1.0D - 0.45D * t),
                       fade * 1.42F, 0.85F, 0.98F, 1.0F);
            previous = point;
        }

        // Туман вдоль канала: собирает отдельные линии в один плотный импульс.
        for (int i = 0; i < FOG_PUFFS + 3; i++) {
            double t = (i + 0.5D) / (FOG_PUFFS + 3);
            Vec3 puff = palm.add(forward.scale(reach * t));
            billboard(glow, pose, puff, cameraLocal, 0.52D + 0.40D * Math.sin(Math.PI * t),
                      fade * 0.22F, 0.24F, 0.95F, 0.40F);
        }

        // Белая пыль по всей зоне: мелкая, резкая, холодная.
        for (int i = 0; i < DUST; i++) {
            double t = ((i * 0.137D) + since * 0.05D) % 1.0D;
            double angle = i * 2.399D;
            double radius = 0.15D + 0.7D * Math.sin(Math.PI * t) * (0.4D + 0.6D * ((i % 5) / 4.0D));
            Vec3 point = palm.add(forward.scale(reach * t))
                    .add(new Vec3(Math.cos(angle) * radius, Math.sin(angle) * radius, 0.0D));
            billboard(glow, pose, point, cameraLocal, 0.020D + 0.026D * ((i % 3) / 2.0D),
                      fade * 0.95F, 0.94F, 1.0F, 0.98F);
        }
        buffers.endBatch(MurimRenderTypes.impactCore());

        VertexConsumer strands = buffers.getBuffer(MurimRenderTypes.strand());
        // Пряди строятся хаосом, а не равномерным углом. Прежняя раскладка
        // phase = s * 2π / N по построению давала правильный многоугольник — тот самый
        // «ураган в руке». Теперь у каждой пряди своя фаза из хеша, своё направление
        // закрутки и свой асимметричный профиль ширины.
        long seed = SEED;
        for (int strand = 0; strand < STRANDS; strand++) {
            // Пряди занимают СЕКТОР, а не полный круг. Равномерное кольцо в лоб читается
            // ровным диском: замер спереди дал радиальную равномерность 0.78 при пределе
            // 0.62, и это ровно то, что названо «ураганом в руке». Сектор около 200°
            // оставляет разброс, но даёт направление.
            float phase = SECTOR_CENTRE
                    + (Chaos.unit(strand, seed) - 0.5F) * (float) (Math.PI * 1.75D);
            float spin = Chaos.spin(strand, seed, 0.3F);
            // Плотность растёт к ладони: пряди, тяготеющие к средоточию, идут по меньшему
            // радиусу и держатся ближе к руке — как на референсе.
            float bias = Chaos.densityBias(strand, STRANDS, seed);
            float turns = Chaos.range(strand, seed ^ 0x1F, 1.2F, 2.4F);
            float peak = Chaos.range(strand, seed ^ 0x2F, 0.68F, 0.9F);
            double maxRadius = 0.10D + 0.34D * bias;
            double length = reach * (0.55D + 0.45D * bias);

            Vec3[] points = new Vec3[steps + 1];
            double[] widths = new double[steps + 1];
            float[] alphas = new float[steps + 1];
            for (int i = 0; i <= steps; i++) {
                float t = i / (float) steps;
                double angle = phase + spin * t * Math.PI * turns;
                double radius = maxRadius * Math.sin(Math.PI * Math.pow(t, 0.7D));
                points[i] = palm.add(forward.scale(length * t))
                        .add(new Vec3(Math.cos(angle) * radius, Math.sin(angle) * radius, 0.0D));
                widths[i] = 0.105D * Chaos.widthProfile(t, peak);
                alphas[i] = fade * 1.36F * Chaos.widthProfile(t, peak);
            }
            RibbonMesher.draw(strands, pose, points, widths, alphas, cameraLocal,
                              0.38F, 1.0F, 0.52F);
        }
        buffers.endBatch(MurimRenderTypes.strand());

        VertexConsumer dark = buffers.getBuffer(MurimRenderTypes.shard());
        // Чёрные осколки: жёсткие, угловатые, только на ударе. Рисуются НЕаддитивно.
        for (int i = 0; i < SHARDS; i++) {
            double t = 0.25D + 0.7D * ((i * 0.113D + since * 0.03D) % 1.0D);
            double angle = i * 1.94D + since * 0.05D;
            double radius = 0.28D + 0.55D * Math.abs(Math.sin(i * 1.7D));
            Vec3 point = palm.add(forward.scale(reach * t))
                    .add(new Vec3(Math.cos(angle) * radius, Math.sin(angle) * radius * 0.8D, 0.0D));
            // Осколки крупнее и непрозрачнее: в первой версии они терялись на фоне
            // свечения и на кадрах их не было видно вовсе.
            billboard(dark, pose, point, cameraLocal, 0.13D + 0.11D * ((i % 4) / 3.0D),
                      fade * 0.95F, 0.04F, 0.07F, 0.05F);
        }

        // Тёмные штрихи поверх свечения.
        for (int i = 0; i < DARK_STRANDS; i++) {
            double t0 = ((i * 0.09D) + since * 0.04D) % 0.9D;
            // Тёмные штрихи держатся того же сектора, иначе они возвращают кольцо.
            double angle = SECTOR_CENTRE + (Chaos.unit(i, SEED ^ 0x7AD) - 0.5F) * Math.PI * 1.9D;
            double radius = 0.2D + 0.45D * Math.abs(Math.cos(i * 1.3D));
            Vec3 a = palm.add(forward.scale(reach * t0))
                    .add(new Vec3(Math.cos(angle) * radius, Math.sin(angle) * radius, 0.0D));
            Vec3 b = a.add(forward.scale(reach * 0.22D)).add(new Vec3(0.05D, -0.04D, 0.0D));
            strandQuad(dark, pose, a, b, cameraLocal, 0.025D, fade * 0.6F, 0.05F, 0.08F, 0.06F);
        }
        buffers.endBatch(MurimRenderTypes.shard());
    }

    /** Четырёхугольник вдоль отрезка, развёрнутый шириной к камере. */
    private static void strandQuad(VertexConsumer consumer, PoseStack.Pose pose, Vec3 from, Vec3 to,
                                   Vec3 cameraLocal, double halfWidth, float alpha,
                                   float red, float green, float blue) {
        if (alpha <= 0.0F) {
            return;
        }
        Vec3 axis = to.subtract(from);
        if (axis.lengthSqr() < 1.0E-9D) {
            return;
        }
        Vec3 mid = from.add(to).scale(0.5D);
        Vec3 toCamera = cameraLocal.subtract(mid).normalize();
        Vec3 side = axis.normalize().cross(toCamera);
        if (side.lengthSqr() < 1.0E-6D) {
            return;
        }
        Vec3 offset = side.normalize().scale(halfWidth);
        Vec3 normal = toCamera;

        vertex(consumer, pose, from.subtract(offset), normal, 0.0F, 0.0F, alpha, red, green, blue);
        vertex(consumer, pose, to.subtract(offset), normal, 1.0F, 0.0F, alpha, red, green, blue);
        vertex(consumer, pose, to.add(offset), normal, 1.0F, 1.0F, alpha, red, green, blue);
        vertex(consumer, pose, from.add(offset), normal, 0.0F, 1.0F, alpha, red, green, blue);
    }

    private static void billboard(VertexConsumer consumer, PoseStack.Pose pose, Vec3 centre,
                                  Vec3 cameraLocal, double size, float alpha,
                                  float red, float green, float blue) {
        if (alpha <= 0.0F || size <= 0.0D) {
            return;
        }
        Vec3 forward = cameraLocal.subtract(centre).normalize();
        Vec3 reference = Math.abs(forward.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D)
                                                     : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = forward.cross(reference).normalize().scale(size);
        Vec3 up = right.normalize().cross(forward).normalize().scale(size);

        vertex(consumer, pose, centre.subtract(right).subtract(up), forward, 0.0F, 0.0F, alpha, red, green, blue);
        vertex(consumer, pose, centre.subtract(right).add(up), forward, 0.0F, 1.0F, alpha, red, green, blue);
        vertex(consumer, pose, centre.add(right).add(up), forward, 1.0F, 1.0F, alpha, red, green, blue);
        vertex(consumer, pose, centre.add(right).subtract(up), forward, 1.0F, 0.0F, alpha, red, green, blue);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, Vec3 position,
                               Vec3 normal, float u, float v, float alpha,
                               float red, float green, float blue) {
        // Альфа ОБЯЗАНА быть ограничена здесь.
        //
        // setColor(float…) в 1.21.1 делает (int)(a * 255) без всякого ограничения
        // (проверено дизассемблером: fmul, f2i), а дальше значение пишется байтом.
        // Значит alpha = 1.6 даёт 408 и после усечения 152, то есть 0.60 — ярче единицы
        // не становится, а СКАЧКОМ ТЕМНЕЕТ. Вспышка контакта была из-за этого
        // перевёрнута: самый яркий кадр техники оказывался тусклым, а пик приходился
        // на середину затухания.
        // API: com.mojang.blaze3d.vertex.VertexConsumer#setColor(float,float,float,float)
        consumer.addVertex(pose.pose(), (float) position.x, (float) position.y, (float) position.z)
                .setColor(Mth.clamp(red, 0.0F, 1.0F), Mth.clamp(green, 0.0F, 1.0F),
                          Mth.clamp(blue, 0.0F, 1.0F), Mth.clamp(alpha, 0.0F, 1.0F))
                .setUv(u, v)
                .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(0x00F000F0)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
    }

    private static Vec3 toLocal(Vec3 delta, float bodyYaw) {
        double radians = Math.toRadians(bodyYaw);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3(delta.x * cos + delta.z * sin, delta.y, -delta.x * sin + delta.z * cos);
    }

    private PalmVfxRenderer() {
    }
}
