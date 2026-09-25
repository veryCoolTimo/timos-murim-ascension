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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
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
 * <p><b>Что рисуется</b> — по описанию автора, docs/design/techniques/demon-palm.md.
 * Сбор: мягкие дуги-эссенции ({@link EssenceArc}) закручиваются к светящейся кисти, вокруг
 * белые точки. Удар: всплеск яда во все стороны от точки контакта и шлейф над ладонью.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class PalmVfxRenderer {

    /** Белых точек вокруг дуг. */
    private static final int DUST = 40;

    /**
     * Семя беспорядка техники. Постоянное, а не случайное: эффект должен выглядеть
     * одинаково при каждом применении, иначе игрок не запомнит его силуэт, а отладка
     * по кадрам станет невоспроизводимой.
     */
    private static final long SEED = 0x5EED0FA1L;

    /** Дуг-эссенций на сборе: автор насчитал на референсе шесть. */
    private static final int ESSENCE_ARCS = 6;

    /** Тиков между появлением соседних дуг: «по одной, но быстро». */
    private static final float ARC_STAGGER_TICKS = 1.6F;

    /** За сколько тиков дуга дорастает до кисти. */
    private static final float ARC_GROW_TICKS = 3.0F;

    /** Светлое ядро дуги: бледно-зелёное, почти белое. */
    private static final VfxColour ESSENCE_CORE = new VfxColour(0.80F, 1.0F, 0.86F);

    /**
     * Разновидность дуг. Пока автор не выбрал, её можно задать переменной окружения
     * {@code MURIM_ARC_STYLE} (a, b, c) — съёмочный стенд снимает все три подряд.
     */
    private static final EssenceArc.Style ARC_STYLE =
            EssenceArc.Style.byName(System.getenv("MURIM_ARC_STYLE"));

    /** Радиус облака белых точек вокруг ладони до сжатия зарядом. */
    private static final double DUST_RADIUS = 0.85D;

    /** Сколько тиков живёт шлейф после удара. */
    private static final float TRAIL_TICKS = 10.0F;

    /** Нитей в шлейфе: как у дуг варианта C, основная и спутницы. */
    private static final int TRAIL_STRANDS = 3;

    /** За сколько тиков истории строится шлейф. */
    private static final float TRAIL_HISTORY_TICKS = 8.0F;

    /** Ближе этого кисть считается у цели, и всплеск идёт прямо из неё. */
    private static final double PALM_TOUCH = 0.9D;

    /** Квадрат наименьшего шага между узлами шлейфа: 0.04 блока. */
    private static final double TRAIL_MIN_STEP_SQR = 0.04D * 0.04D;

    /** Полуширина цели: всплеск ставится на её поверхность. */
    private static final double TARGET_HALF_WIDTH = 0.3D;

    private static final VfxColour DUST_COLD = new VfxColour(0.88F, 1.0F, 0.94F);

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

    /** Точка кисти в мире в один момент: из истории строится шлейф удара. */
    private record Sample(float age, Vec3 world) {
    }

    /** История кисти за последние тики, от старой к новой. */
    private static final Map<Integer, ArrayDeque<Sample>> PALM_PATH = new ConcurrentHashMap<>();

    /** Мировая точка всплеска, выбранная в первый кадр после попадания. */
    private static final Map<Integer, Vec3> SPLASH_ORIGIN = new ConcurrentHashMap<>();

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
        PALM_PATH.remove(entityId);
        SPLASH_ORIGIN.remove(entityId);
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
        PALM_PATH.clear();
        SPLASH_ORIGIN.clear();
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
            // Не рисуем только тогда, когда смотрим ГЛАЗАМИ этой сущности: при съёмке сбоку
            // камера стоит у другой сущности, и первое лицо не значит, что игрока не видно.
            if (entity == minecraft.getCameraEntity() && minecraft.options.getCameraType().isFirstPerson()) {
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
            Vec3 palmWorld = new Vec3(drawn.x() + cameraPos.x,
                                      drawn.y() + cameraPos.y,
                                      drawn.z() + cameraPos.z);
            DRAWN_PALM.put(entity.getId(), palmWorld);
            List<Vec3> trail = remember(entity.getId(), age, palmWorld, feet, bodyYaw);
            // Время всплеска считается от ПРИХОДА попадания, а не от расчётного тика удара:
            // пакет с сервера приходит на пару тиков позже, и всплеск появлялся уже
            // наполовину отыгранным, без вспышки контакта.
            Hit hit = HITS.get(entity.getId());
            Vec3 splashAt = null;
            Vec3 away = new Vec3(0.0D, 0.0D, -1.0D);
            float hitSince = -1.0F;
            if (hit != null) {
                hitSince = clientTicks - hit.tick() + partial;
                Vec3 origin = SPLASH_ORIGIN.computeIfAbsent(entity.getId(),
                        id -> splashOrigin(hit, palmWorld));
                splashAt = toLocal(origin.subtract(feet), bodyYaw);
                // Наружу от поверхности цели — туда жидкость и выплёскивается.
                Vec3 target = toLocal(hit.at().subtract(feet), bodyYaw);
                Vec3 out = new Vec3(splashAt.x - target.x, 0.0D, splashAt.z - target.z);
                if (out.lengthSqr() > 1.0E-6D) {
                    away = out.normalize();
                }
            }
            if (age < impactAge) {
                gather(buffers, pose, cameraLocal, palm, age, windupAge, impactAge);
            } else {
                release(buffers, pose, cameraLocal, age - impactAge, splashAt, away, hitSince, trail);
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Запоминает кисть и возвращает путь за последние тики в системе игрока.
     *
     * <p>Храним МИРОВЫЕ точки и переводим их в систему игрока на каждом кадре: корпус
     * поворачивается во время удара, и путь в старой системе уехал бы вместе с ним.
     */
    private static List<Vec3> remember(int entityId, float age, Vec3 world, Vec3 feet,
                                       float bodyYaw) {
        ArrayDeque<Sample> path = PALM_PATH.computeIfAbsent(entityId, id -> new ArrayDeque<>());
        Sample last = path.peekLast();
        if (last == null || age - last.age() >= 0.2F) {
            path.addLast(new Sample(age, world));
        }
        while (!path.isEmpty() && age - path.peekFirst().age() > TRAIL_HISTORY_TICKS) {
            path.removeFirst();
        }
        // Прореживание по РАССТОЯНИЮ: пока кисть стоит на сборе, в одну точку набиваются
        // десятки совпадающих узлов, их квады складываются в яркое пятно позади игрока,
        // а касательная между ними не определена.
        List<Vec3> local = new ArrayList<>(path.size() + 1);
        Vec3 now = toLocal(world.subtract(feet), bodyYaw);
        for (Sample sample : path) {
            Vec3 point = toLocal(sample.world().subtract(feet), bodyYaw);
            if (local.isEmpty() || point.distanceToSqr(local.get(local.size() - 1)) > TRAIL_MIN_STEP_SQR) {
                local.add(point);
            }
        }
        if (local.isEmpty() || now.distanceToSqr(local.get(local.size() - 1)) > TRAIL_MIN_STEP_SQR) {
            local.add(now);
        } else {
            local.set(local.size() - 1, now);
        }
        return local;
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
        float span = Math.max(1.0F, impactAge - windupAge);
        float since = age - windupAge;
        float charge = Mth.clamp(since / span, 0.0F, 1.0F);
        if (since <= 0.0F) {
            return;
        }
        // Сбор по описанию автора (docs/design/techniques/demon-palm.md): мягкие дуги-эссенции
        // закручиваются к кисти, появляются по одной, но быстро; вокруг белые точки; кисть
        // светится. Прежние слои — корона шипов, потёки, тёмные штрихи, зелёный туман —
        // убраны: в описании их нет, а туман автор прямо назвал лишним.
        //
        // ВАЖНО: слои рисуются строго по одному. Общий источник буферов строит только один
        // тип за раз, и запрос второго молча закрывает первый — запись в удержанную ссылку
        // после этого падает с «Not building!».
        VertexConsumer essence = buffers.getBuffer(MurimRenderTypes.essence());
        EssenceArc.gather(essence, pose, palm, cameraLocal, ARC_STYLE, ESSENCE_ARCS, since,
                          charge, ARC_STAGGER_TICKS, ARC_GROW_TICKS, 1.0F, SEED,
                          VfxColour.VENOM, ESSENCE_CORE);
        buffers.endBatch(MurimRenderTypes.essence());

        VertexConsumer glow = buffers.getBuffer(MurimRenderTypes.mote());
        // Кисть светится и разгорается к удару.
        float hand = charge * charge;
        VfxDraw.billboard(glow, pose, palm, cameraLocal, 0.10D + 0.14D * hand, 0.25F + 0.45F * hand,
                          VfxColour.VENOM.red(), VfxColour.VENOM.green(), VfxColour.VENOM.blue());
        VfxDraw.billboard(glow, pose, palm, cameraLocal, 0.05D + 0.06D * hand, 0.35F + 0.6F * hand,
                          ESSENCE_CORE.red(), ESSENCE_CORE.green(), ESSENCE_CORE.blue());
        // Белые точки вокруг дуг, втягиваются к кисти вместе с потоком.
        BillboardBurst.inward(glow, pose, palm, cameraLocal, DUST, age,
                              DUST_RADIUS * (1.0D - 0.25D * charge),
                              Mth.clamp(since / 4.0F, 0.0F, 1.0F),
                              DUST_COLD, VfxColour.COLD_CORE);
        buffers.endBatch(MurimRenderTypes.mote());
    }

    /**
     * Удар по описанию автора (docs/design/techniques/demon-palm.md).
     *
     * <p>Дуги сбора не летят в цель: ладонь бьёт сама, над ней тянется шлейф из тех же
     * дуг, а из-под ладони во все стороны выплёскивается яд — светлый и тёмно-зелёный,
     * ниже чёрные капли. Дыма нет. Прежние слои — веер прядей вперёд, волны по телу цели,
     * большая белая вспышка — убраны: в описании их нет.
     *
     * @param hit точка контакта или {@code null}, если удар прошёл мимо
     */
    private static void release(MultiBufferSource.BufferSource buffers, PoseStack.Pose pose,
                                Vec3 cameraLocal, float since, Vec3 splashAt, Vec3 away,
                                float hitSince, List<Vec3> trail) {
        float fade = 1.0F - Mth.clamp(since / TRAIL_TICKS, 0.0F, 1.0F);
        if (fade > 0.0F) {
            VertexConsumer essence = buffers.getBuffer(MurimRenderTypes.essence());
            EssenceTrail.draw(essence, pose, trail, cameraLocal, TRAIL_STRANDS, 0.20D,
                              fade, since, SEED, VfxColour.VENOM, ESSENCE_CORE);
            buffers.endBatch(MurimRenderTypes.essence());
        }
        if (splashAt != null) {
            FlipbookSplash.draw(buffers, pose, splashAt, away, cameraLocal, hitSince);
        }
    }

    /**
     * Откуда бьёт всплеск — «из-под ладони».
     *
     * <p>Берётся кисть в момент прихода попадания, если она у цели. Если кисть не дотянулась
     * (дальность удара больше длины руки), — поверхность цели со стороны кисти, а не центр:
     * из центра тела всплеск наполовину прятался в модели. Точка запоминается один раз:
     * после удара рука уходит назад, а всплеск остаётся там, где был контакт.
     */
    private static Vec3 splashOrigin(Hit hit, Vec3 palmWorld) {
        Vec3 toPalm = new Vec3(palmWorld.x - hit.at().x, 0.0D, palmWorld.z - hit.at().z);
        if (toPalm.lengthSqr() < 1.0E-6D) {
            return hit.at();
        }
        Vec3 surface = hit.at().add(toPalm.normalize().scale(TARGET_HALF_WIDTH));
        return palmWorld.distanceTo(surface) < PALM_TOUCH ? palmWorld : surface;
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
