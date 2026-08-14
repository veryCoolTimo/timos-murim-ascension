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

    /** Радиус свечения у ладони. Поджат: полтора блока растаскивали центр от руки. */
    private static final double GLOW_RADIUS = 0.26D;

    /** Радиус кольца искр вокруг ладони до сжатия зарядом. */
    private static final double DUST_RADIUS = 0.40D;

    /** Клубов тумана вдоль канала выброса: они собирают линии в один импульс. */
    private static final int CHANNEL_PUFFS = 8;

    /** Откуда яд стягивается к ладони на сборе: примерно локоть от кисти. */
    private static final double GATHER_REACH = 0.55D;

    /** Вынос выброса: длина вытянутой руки, а не дистанция снаряда. */
    private static final double PALM_REACH = 1.05D;

    /** Волн, расходящихся по телу цели. На референсе они считаются по одной. */
    private static final int SPLASH_WAVES = 5;

    /** Капель зелёного всплеска под ладонью. */
    private static final int SPLASH_DROPS = 26;

    /** Чёрных брызг. */
    private static final int SPLASH_SHARDS = 10;

    /** Сколько тиков живёт вспышка контакта. */
    private static final float FLASH_TICKS = 3.5F;

    private static final VfxColour DUST_WARM = new VfxColour(0.42F, 1.0F, 0.55F);
    private static final VfxColour DUST_COLD = new VfxColour(0.88F, 1.0F, 0.94F);
    private static final VfxColour FLASH_CORE = new VfxColour(0.92F, 1.0F, 0.98F);

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

    /**
     * Куда пришёлся удар, если он состоялся.
     *
     * <p>Брызги яда рисуются В ТОЧКЕ КОНТАКТА и только при попадании. Прежде выброс
     * возникал всегда и уходил вперёд на пять блоков независимо от того, задел ли он
     * кого-нибудь: «трейл берётся из воздуха», как это назвал автор.
     */
    private record Hit(Vec3 at, float height, int tick) {
    }

    private static final Map<Integer, Hit> HITS = new ConcurrentHashMap<>();

    /** Принимает точку контакта с сервера. */
    public static void recordHit(int sourceId, double x, double y, double z, float height) {
        HITS.put(sourceId, new Hit(new Vec3(x, y, z), height, clientTicks));
    }

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
        HITS.remove(entityId);
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
        HITS.clear();
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
                release(buffers, pose, cameraLocal, palm, age - impactAge,
                        HITS.get(entity.getId()), feet, bodyYaw);
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
        // Радиус поджат к ладони. Прежние полтора блока растаскивали центр свечения
        // на полкорпуса от руки: якорь был верным, а геометрия вокруг него — нет.
        CoreGlow.draw(glow, pose, palm, cameraLocal, age, GLOW_RADIUS, charge,
                      VfxColour.VENOM_DEEP, VfxColour.COLD_CORE);

        // Искры СТЯГИВАЮТСЯ к ладони по спирали — направление читается с первой панели.
        // Кольцо ещё и сжимается по мере набора силы, поэтому радиус зависит от заряда.
        BillboardBurst.inward(glow, pose, palm, cameraLocal, DUST, age,
                              DUST_RADIUS * (1.0D - 0.25D * charge), charge,
                              DUST_WARM, DUST_COLD);
        buffers.endBatch(MurimRenderTypes.impactCore());

        VertexConsumer strands = buffers.getBuffer(MurimRenderTypes.strand());

        // Ленты СТЯГИВАЮТСЯ ИЗ ВОЗДУХА В ЛАДОНЬ, а не стекают вниз.
        //
        // Прямое замечание автора по кадру: «эффекты идут вниз, а не к руке». Прежняя
        // версия вела ленты от ладони вниз на полблока, и сбор читался как утечка,
        // а не как набор силы. По референсу яд собирается ИЗ ВОЗДУХА к отведённой кисти.
        for (int i = 0; i < GATHER_RIBBONS; i++) {
            float pick = Chaos.unit(i, SEED ^ 0x6A7L);
            double around = i * (Math.PI * 2.0D / GATHER_RIBBONS) + age * 0.05D;
            double lift = (pick - 0.35D) * 0.9D;
            // Дальний конец ленты: чем больше заряд, тем ближе он подтянут к ладони.
            double far = GATHER_REACH * (1.0D - 0.45D * charge);
            Vec3 outer = palm.add(new Vec3(Math.cos(around) * far,
                                           lift * far,
                                           Math.sin(around) * far * 0.8D));
            int segments = 6;
            Vec3 previous = outer;
            for (int seg = 1; seg <= segments; seg++) {
                double t = seg / (double) segments;
                // Лента идёт К ладони, слегка закручиваясь по пути.
                double twist = around + t * Math.PI * 0.55D * (i % 2 == 0 ? 1.0D : -1.0D);
                double radius = far * (1.0D - t) * 0.75D;
                Vec3 point = palm.add(new Vec3(Math.cos(twist) * radius,
                                               lift * far * (1.0D - t),
                                               Math.sin(twist) * radius * 0.8D));
                // Яркость растёт К ЛАДОНИ: видно, куда течёт, а не откуда.
                VfxDraw.segment(strands, pose, previous, point, cameraLocal,
                           0.045D + 0.055D * t,
                           charge * (0.30F + 0.60F * (float) t), 0.34F, 1.0F, 0.5F);
                previous = point;
            }
        }

        // Крупные ленты — большая форма, которой не хватало сильнее всего.
        for (int b = 0; b < BIG_RIBBONS; b++) {
            double base = b * (Math.PI * 2.0D / BIG_RIBBONS) + age * 0.018D;
            // Крупные ленты тоже идут ИЗВНЕ ВНУТРЬ: спираль сходится к ладони.
            double start = GATHER_REACH * 0.85D * (1.0D - 0.35D * charge);
            Vec3 previous = palm.add(new Vec3(Math.cos(base) * start, 0.12D * start,
                                              Math.sin(base) * start * 0.7D));
            for (int seg = 1; seg <= 7; seg++) {
                double t = seg / 7.0D;
                double angle = base + t * Math.PI * 1.3D;
                double radius = start * (1.0D - t);
                Vec3 point = palm.add(new Vec3(Math.cos(angle) * radius,
                        0.12D * start * (1.0D - t) + 0.10D * charge * Math.sin(Math.PI * t),
                        Math.sin(angle) * radius * 0.7D));
                VfxDraw.segment(strands, pose, previous, point, cameraLocal,
                           0.09D + 0.07D * t, charge * (0.35F + 0.45F * (float) t),
                           0.30F, 1.0F, 0.46F);
                previous = point;
            }
        }

        for (int i = 0; i < CORONA_SPIKES; i++) {
            double angle = i * (Math.PI * 2.0D / CORONA_SPIKES) + age * 0.03D;
            double jitter = 0.55D + 0.45D * Math.abs(Math.sin(i * 2.399D));
            double radius = (0.22D + 0.30D * core) * jitter;
            Vec3 tip = palm.add(new Vec3(Math.cos(angle) * radius, Math.sin(angle) * radius * 0.8D,
                    Math.sin(angle * 1.7D) * radius * 0.35D));
            VfxDraw.segment(strands, pose, palm, tip, cameraLocal, 0.022D,
                       0.28F * core, 0.40F, 1.0F, 0.58F);
        }
        buffers.endBatch(MurimRenderTypes.strand());

        VertexConsumer drips = buffers.getBuffer(MurimRenderTypes.drip());
        // Потёки с каплей: стекают с ладони вниз. На панели сбора они есть, на панели удара нет.
        for (int i = 0; i < 5; i++) {
            double phase = (age * 0.03F + i * 0.21D) % 1.0D;
            Vec3 top = palm.add(new Vec3(-0.08D + 0.05D * i, -0.05D, -0.04D + 0.03D * i));
            Vec3 bottom = top.add(new Vec3(0.0D, -0.20D - 0.28D * phase, 0.0D));
            VfxDraw.segment(drips, pose, top, bottom, cameraLocal, 0.075D,
                       (float) (charge * (1.0D - phase) * 1.0D), 0.72F, 1.0F, 0.78F);
        }
        buffers.endBatch(MurimRenderTypes.drip());

        // Тёмные штрихи поверх свечения — именно они дают фактуру смазанного движения.
        VertexConsumer dark = buffers.getBuffer(MurimRenderTypes.shard());
        for (int i = 0; i < DARK_STRANDS / 2; i++) {
            double angle = i * 1.7D + age * 0.02D;
            Vec3 a = palm.add(new Vec3(Math.cos(angle) * 0.5D, 0.25D * Math.sin(angle), Math.sin(angle) * 0.5D));
            Vec3 b = a.add(new Vec3(Math.cos(angle + 0.6D) * 0.45D, -0.12D, Math.sin(angle + 0.6D) * 0.45D));
            VfxDraw.segment(dark, pose, a, b, cameraLocal, 0.03D, 0.5F * charge, 0.06F, 0.09F, 0.07F);
        }
        buffers.endBatch(MurimRenderTypes.shard());
    }

    /**
     * Удар: короткий выброс У ЛАДОНИ и брызги ПО ЦЕЛИ.
     *
     * <p>Переписано по разбору кадров автором. Прежняя версия гнала волну на четыре с
     * половиной блока вперёд независимо от попадания — «ходукен», возникающий из воздуха.
     * По референсу и по описанию должно быть иначе: яд срывается с ладони на расстояние
     * вытянутой руки, а на контакте расплёскивается по телу цели отдельными волнами,
     * зелёным всплеском под ладонью и чёрными брызгами.
     *
     * @param hit точка контакта или {@code null}, если удар прошёл мимо
     */
    private static void release(MultiBufferSource.BufferSource buffers, PoseStack.Pose pose,
                                Vec3 cameraLocal, Vec3 palm, float since,
                                Hit hit, Vec3 feet, float bodyYaw) {
        // Жизнь выброса укорочена до длины анимации: раньше эффект доигрывал тридцать
        // тиков над уже опущенной рукой.
        float life = Mth.clamp(since / 14.0F, 0.0F, 1.0F);
        float fade = 1.0F - life;
        if (fade <= 0.0F) {
            return;
        }
        Vec3 forward = new Vec3(0.0D, 0.0D, 1.0D);
        // Вынос — на длину вытянутой руки, а не через полполя.
        double reach = PALM_REACH * (0.35D + 0.65D * Mth.clamp(since / 3.0F, 0.0F, 1.0F));

        if (since < FLASH_TICKS) {
            VertexConsumer burst = buffers.getBuffer(MurimRenderTypes.impactCore());
            ImpactFlash.draw(burst, pose, palm.add(forward.scale(0.25D)), cameraLocal,
                             since / FLASH_TICKS, 0.22D, FLASH_CORE, VfxColour.VENOM);
            buffers.endBatch(MurimRenderTypes.impactCore());
        }

        // Пряди срываются с ладони вперёд, но коротко: это выхлоп удара, а не снаряд.
        VertexConsumer strands = buffers.getBuffer(MurimRenderTypes.strand());
        RibbonTrail.draw(strands, pose, palm, forward, cameraLocal, STRANDS, reach,
                         0.085D, fade * 0.9F, (float) (Math.PI * 1.75D), SECTOR_CENTRE,
                         SEED, VfxColour.VENOM);
        buffers.endBatch(MurimRenderTypes.strand());

        if (hit != null) {
            splash(buffers, pose, cameraLocal, hit, feet, bodyYaw, since, fade);
        }
    }

    /**
     * Брызги по цели: волны, всплеск и чёрные капли.
     *
     * <p>Точка контакта приходит с сервера в мировых координатах, а рисуем мы в системе
     * игрока — поэтому её надо перевести. Рисовать брызги в мировой системе поверх
     * повёрнутого стека нельзя: они уедут вместе с поворотом корпуса.
     */
    private static void splash(MultiBufferSource.BufferSource buffers, PoseStack.Pose pose,
                               Vec3 cameraLocal, Hit hit, Vec3 feet, float bodyYaw,
                               float since, float fade) {
        Vec3 at = toLocal(hit.at().subtract(feet), bodyYaw);
        float spread = Mth.clamp(since / 5.0F, 0.0F, 1.0F);
        double body = Math.max(0.6D, hit.height());

        // ВОЛНЫ: пять отдельных дуг, расходящихся по телу цели сверху вниз.
        // Их считанное число — не украшение: на референсе они читаются по одной.
        VertexConsumer strands = buffers.getBuffer(MurimRenderTypes.strand());
        for (int wave = 0; wave < SPLASH_WAVES; wave++) {
            float phase = Chaos.unit(wave, SEED ^ 0x5A11L);
            float delay = wave * 0.12F;
            float grown = Mth.clamp((spread - delay) / (1.0F - delay), 0.0F, 1.0F);
            if (grown <= 0.0F) {
                continue;
            }
            int steps = 10;
            Vec3[] path = new Vec3[steps + 1];
            double[] widths = new double[steps + 1];
            float[] alphas = new float[steps + 1];
            for (int i = 0; i <= steps; i++) {
                float t = (i / (float) steps) * grown;
                double angle = (phase - 0.5F) * Math.PI * 1.4D + t * Math.PI * 0.9D
                        * (wave % 2 == 0 ? 1.0D : -1.0D);
                // Волна обтекает тело: расходится вбок и стекает вниз.
                double side = Math.sin(angle) * body * 0.42D * t;
                double drop = -body * 0.62D * t * t;
                double out = Math.cos(angle) * body * 0.18D;
                path[i] = at.add(side, drop, out);
                float profile = Chaos.widthProfile(t, 0.35F);
                widths[i] = 0.055D * profile;
                alphas[i] = fade * profile;
            }
            RibbonMesher.draw(strands, pose, path, widths, alphas, cameraLocal,
                              VfxColour.VENOM.red(), VfxColour.VENOM.green(),
                              VfxColour.VENOM.blue());
        }
        buffers.endBatch(MurimRenderTypes.strand());

        // ВСПЛЕСК под ладонью: густая масса, растекающаяся как вылитая вода.
        VertexConsumer glow = buffers.getBuffer(MurimRenderTypes.impactCore());
        for (int i = 0; i < SPLASH_DROPS; i++) {
            float u = Chaos.unit(i, SEED ^ 0x0DD5L);
            double angle = i * 2.399D;
            double run = body * 0.55D * spread * (0.35D + 0.65D * u);
            Vec3 point = at.add(Math.cos(angle) * run * 0.6D,
                                -body * 0.5D * spread * (0.4D + 0.6D * u),
                                Math.sin(angle) * run * 0.35D);
            VfxDraw.billboard(glow, pose, point, cameraLocal,
                              0.035D + 0.05D * (1.0F - spread), fade * (0.5F + 0.5F * u),
                              VfxColour.VENOM.red(), VfxColour.VENOM.green(),
                              VfxColour.VENOM.blue());
        }
        VfxDraw.billboard(glow, pose, at, cameraLocal, 0.16D + 0.30D * spread,
                          fade * 0.85F, FLASH_CORE.red(), FLASH_CORE.green(), FLASH_CORE.blue());
        buffers.endBatch(MurimRenderTypes.impactCore());

        // ЧЁРНЫЕ БРЫЗГИ. Их автор назвал верными и нужными; на программном рендере
        // моего стенда этот слой не рисуется вовсе, поэтому судить о нём я могу
        // только по кадрам автора.
        VertexConsumer dark = buffers.getBuffer(MurimRenderTypes.shard());
        for (int i = 0; i < SPLASH_SHARDS; i++) {
            float u = Chaos.unit(i, SEED ^ 0x51A5L);
            double angle = i * 1.94D;
            double run = body * 0.6D * spread * (0.3D + 0.7D * u);
            Vec3 point = at.add(Math.cos(angle) * run * 0.7D,
                                -body * 0.45D * spread * u,
                                Math.sin(angle) * run * 0.4D);
            VfxDraw.billboard(dark, pose, point, cameraLocal, 0.05D + 0.06D * u,
                              fade * 0.9F, VfxColour.SOOT.red(), VfxColour.SOOT.green(),
                              VfxColour.SOOT.blue());
        }
        buffers.endBatch(MurimRenderTypes.shard());
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
