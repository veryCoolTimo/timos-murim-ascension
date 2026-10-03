package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientMeditationState;
import io.github.verycooltimo.murim.cultivation.MeditationService;
import io.github.verycooltimo.murim.network.SyncMeditationPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Медитация в мире: тепло у пупка, кольцо ци, семя даньтяня.
 *
 * <p>Каждый такт docs/design/19 §3а обязан читаться на теле, а не полоской интерфейса:
 * <ol>
 *   <li>первое ощущение — тепло разгорается под пупком и рассыпается искрами;</li>
 *   <li>удержание — вокруг пояса собирается кольцо: удерживаемое — ровное и яркое,
 *       отпущенное — дрожит, тускнеет и теряет куски;</li>
 *   <li>семя — кольцо стягивается в точку, к ней по телу сходятся жилы (референс
 *       docs/design/reference/dantian-1.png), затем сцена «внутреннего взгляда».</li>
 * </ol>
 *
 * <p>Кольцо рисуется с проверкой глубины: задняя половина уходит за корпус, и кольцо
 * читается как опоясывающее тело, а не как нарисованное поверх. Жилы, наоборот, без
 * глубины — они светят сквозь одежду, как на референсе.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class MeditationVfxRenderer {

    /** Холодная палитра референса: глубокий синий ореол, циановая сердцевина. */
    private static final VfxColour HALO = new VfxColour(0.16F, 0.42F, 0.95F);
    private static final VfxColour DEEP = new VfxColour(0.12F, 0.30F, 0.85F);
    private static final VfxColour CORE = new VfxColour(0.45F, 0.88F, 1.0F);
    /** Сорвавшееся кольцо теряет цвет: тусклый серо-синий. */
    private static final VfxColour FRAYED = new VfxColour(0.42F, 0.50F, 0.78F);

    private static final int RING_POINTS = 56;

    private static final boolean DEPTH_DEBUG = "1".equals(System.getenv("MURIM_VEIN_DEPTH"));

    /** Насколько жилы вынесены от оси части тела: полутолщина модели 0.125 плюс зазор. */
    private static final double SKIN_TORSO = 0.16D;
    private static final double SKIN_LIMB = 0.16D;
    private static final double SKIN_HEAD = 0.28D;

    /**
     * Положение семени на экране в долях ширины и высоты, или {@code null}.
     *
     * <p>Нужно интерфейсу: в сцене семени экран затемняется целиком, и затемнение гасит
     * мировое свечение вместе с остальным. Поэтому семя дорисовывается поверх затемнения
     * в интерфейсе — ровно в той точке, где оно стоит в мире.
     */
    private static float[] seedOnScreen;

    public static float[] seedOnScreen() {
        return seedOnScreen;
    }
    private static final long SEED = 0x5EEDDA17L;

    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        SyncMeditationPayload state = ClientMeditationState.state();
        int sceneAge = ClientMeditationState.seedSceneAge();
        int rankUpAge = ClientMeditationState.rankUpAge();
        ClientMeditationState.Aftermath aftermath = ClientMeditationState.aftermath();
        int stainAge = ClientMeditationState.stainAge();
        if (stainAge >= 0 && ClientMeditationState.stainOrigin() != null) {
            float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            Vec3 cam = event.getCamera().getPosition();
            PoseStack ps = event.getPoseStack();
            ps.pushPose();
            try {
                ps.translate(-cam.x, -cam.y, -cam.z);
                drawStains(Minecraft.getInstance().renderBuffers().bufferSource(), ps.last(),
                           ClientMeditationState.stainOrigin(), stainAge + pt);
            } finally {
                ps.popPose();
            }
        }
        if (!state.active() && sceneAge < 0 && rankUpAge < 0
                && aftermath == ClientMeditationState.Aftermath.NONE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !(minecraft.player instanceof AbstractClientPlayer player)) {
            return;
        }
        // Кость приходит из слоя рендера игрока. В первом лице тело не рисуется,
        // и ставить эффект вслепую нельзя — тогда в мире ничего нет, остаётся HUD.
        Vec3 dantian = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.DANTIAN);
        Vec3 chest = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.CHEST);
        if (dantian == null || chest == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cameraPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        try {
            poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
            PoseStack.Pose pose = poseStack.last();
            // Кольцо — на оси корпуса, на высоте пупка: кость даньтяня лежит на передней
            // поверхности живота, и кольцо вокруг неё съезжало бы вперёд.
            Vec3 axis = new Vec3(chest.x, dantian.y, chest.z);
            // Ядро выносится к поверхности живота СО СТОРОНЫ КАМЕРЫ: в самой кости оно
            // лежит внутри торса, и проверка глубины срезала его целиком — на первых
            // кадрах центрального источника, главного на референсе, не было вовсе.
            Vec3 toCamera = cameraPos.subtract(dantian);
            Vec3 core = dantian.add(0.0D, 0.03D, 0.0D)
                    .add(toCamera.lengthSqr() > 1.0E-6D ? toCamera.normalize().scale(0.2D) : Vec3.ZERO);
            // «Вперёд» тела — по повороту корпуса: жилы и потоки кладутся на его переднюю
            // поверхность и поворачиваются вместе с телом, а не с камерой.
            float bodyYaw = Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot);
            Vec3 facing = Vec3.directionFromRotation(0.0F, bodyYaw);
            Scene scene = new Scene(minecraft, player, pose, cameraPos, axis, core, facing);

            seedOnScreen = null;
            int breakAge = ClientMeditationState.breakthroughAge();
            int warnAge = ClientMeditationState.warningAge();
            if (warnAge >= 0) {
                // Предупреждение: ядро бьётся вместе со стуком сердца.
                float wa = warnAge + partial;
                float period = wa < io.github.verycooltimo.murim.cultivation.Realm.WARNING_TICKS / 2.0F ? 20.0F : 12.0F;
                float pulse = (float) Math.exp(-((wa % period) / period) * 10.0D);
                VertexConsumer glow = scene.buffers().getBuffer(MurimRenderTypes.impactCore());
                CoreGlow.draw(glow, scene.pose(), scene.core(), scene.camera(), wa, 0.06D + 0.06D * pulse,
                              0.5F + 0.5F * pulse, DEEP, CORE);
                scene.buffers().endBatch(MurimRenderTypes.impactCore());
            }
            if (rankUpAge >= 0) {
                rankUp(scene, rankUpAge + partial);
            }
            if (breakAge >= 0) {
                breakthrough(scene, breakAge + partial);
            } else if (sceneAge >= 0) {
                seedOnScreen = project(core, cameraPos, event.getModelViewMatrix(), event.getProjectionMatrix());
                seedScene(scene, sceneAge + partial);
            } else if (state.active()) {
                // Итог прошлого такта доигрывается поверх начала следующего: сидение
                // непрерывно, и послесвечению больше некуда деваться.
                if (aftermath != ClientMeditationState.Aftermath.NONE) {
                    float age = ClientMeditationState.AFTERMATH_TICKS
                            - ClientMeditationState.aftermathTicks() + partial;
                    afterSession(scene, aftermath, ClientMeditationState.aftermathBeats(), age);
                }
                float t = ClientMeditationState.sessionTicks() + partial;
                if (ClientMeditationState.minigame()) {
                    minigameRing(scene, t, state.beats(), ClientMeditationState.ring(partial));
                } else if (state.beats() == 0) {
                    firstFeeling(scene, t);
                } else if (state.beats() >= 3) {
                    seededBreath(scene, t);
                }
            } else {
                float length = aftermath == ClientMeditationState.Aftermath.BACKLASH
                        ? ClientMeditationState.BACKLASH_TICKS : ClientMeditationState.AFTERMATH_TICKS;
                float age = length - ClientMeditationState.aftermathTicks() + partial;
                afterSession(scene, aftermath, ClientMeditationState.aftermathBeats(), age);
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Мировая точка в доли экрана.
     * API: reference/neoforge-src/net/neoforged/neoforge/client/event/RenderLevelStageEvent.java
     * #getModelViewMatrix / #getProjectionMatrix
     */
    private static float[] project(Vec3 point, Vec3 camera, org.joml.Matrix4f view, org.joml.Matrix4f projection) {
        org.joml.Vector4f v = new org.joml.Vector4f((float) (point.x - camera.x),
                (float) (point.y - camera.y), (float) (point.z - camera.z), 1.0F);
        view.transform(v);
        projection.transform(v);
        if (v.w() <= 1.0E-4F) {
            return null;
        }
        float x = v.x() / v.w() * 0.5F + 0.5F;
        float y = 1.0F - (v.y() / v.w() * 0.5F + 0.5F);
        return new float[] {x, y};
    }

    /** Всё, что нужно для рисования одного кадра. */
    private record Scene(Minecraft minecraft, AbstractClientPlayer player, PoseStack.Pose pose,
                         Vec3 camera, Vec3 axis, Vec3 core, Vec3 facing) {

        MultiBufferSource.BufferSource buffers() {
            return minecraft.renderBuffers().bufferSource();
        }
    }

    // ------------------------------------------------------------------ такт 1

    /**
     * Первое ощущение: тепло разгорается, затем начинает дрожать и рассыпается.
     * Неудача по замыслу — игрок должен увидеть, что ци есть, но держаться ей не на чем.
     */
    private static void firstFeeling(Scene s, float t) {
        float grow = Mth.clamp(t / 180.0F, 0.0F, 1.0F);
        // С десятой секунды тепло «не держится»: мерцает всё сильнее.
        float unrest = Mth.clamp((t - 200.0F) / 100.0F, 0.0F, 1.0F);
        float flicker = 1.0F - unrest * (0.5F + 0.5F * Mth.sin(t * 0.9F) * Mth.sin(t * 0.37F));
        float intensity = (0.25F + 0.55F * grow) * flicker;

        VertexConsumer glow = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        // Тепло должно быть заметным: на первых кадрах оно терялось до пары искр,
        // и игрок не понял бы, что вообще что-то произошло.
        CoreGlow.draw(glow, s.pose(), s.core(), s.camera(), t, 0.08D + 0.10D * grow,
                      Math.min(1.0F, intensity + 0.15F), DEEP, CORE);
        VfxDraw.billboard(glow, s.pose(), s.core(), s.camera(), 0.2D + 0.2D * grow,
                          0.16F * grow * flicker, HALO.red(), HALO.green(), HALO.blue());
        BillboardBurst.inward(glow, s.pose(), s.core(), s.camera(), 14, t, 0.38D,
                              0.45F * grow * (1.0F - unrest), HALO, CORE);
        if (unrest > 0.0F) {
            BillboardBurst.outward(glow, s.pose(), s.core(), s.camera(), 10, t, 0.30D,
                                   0.5F * unrest, HALO, CORE);
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    // ------------------------------------------------------------------ такты 2 и 3

    /** Сорвавшееся кольцо: красный, как на круге мини-игры. */
    private static final VfxColour STRAIN = new VfxColour(1.0F, 0.26F, 0.18F);

    /**
     * Кольцо на теле повторяет кольцо мини-игры: тот же радиус, та же дрожь, тот же
     * красный при промахе. На такте сжатия к нему добавляются жилы и потоки к семени —
     * их сила растёт с устойчивостью.
     */
    private static void minigameRing(Scene s, float t, int beat, SyncMeditationPayload.Ring ring) {
        float miss = ring.miss();
        boolean off = miss != 0.0F;
        double radius = 0.07D + 0.40D * ring.radius();
        float wobble = off && miss > 0.0F ? 0.8F : 0.0F;
        float breaks = off ? (miss < 0.0F ? 0.35F : 0.25F) : 0.0F;
        float alpha = off ? 0.75F : 1.0F;
        VfxColour line = off ? STRAIN : HALO;
        float stability = Mth.clamp(ring.stability(), 0.0F, 1.0F);
        drawRing(s, t, radius, wobble, alpha, breaks, line, stability);

        if (beat >= 2) {
            drawVeins(s, stability, 0.5F * stability);
            drawStreams(s, t, stability);
        }
        VertexConsumer glow = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        // Чем туже кольцо и выше устойчивость, тем плотнее ядро.
        float dense = (float) (1.0D - ring.radius()) * 0.5F + stability * 0.5F;
        CoreGlow.draw(glow, s.pose(), s.core(), s.camera(), t, 0.05D + 0.06D * dense,
                      0.4F + 0.5F * dense, DEEP, CORE);
        if (!off) {
            // В полосе ци стекает из кольца в пупок.
            BillboardBurst.inward(glow, s.pose(), s.core(), s.camera(), 14, t, radius, 0.6F, HALO, CORE);
        } else {
            // Вне полосы кольцо теряет искры наружу.
            BillboardBurst.outward(glow, s.pose(), s.axis(), s.camera(), 10, t, radius + 0.1D,
                                   0.5F + 0.5F * ring.strain(), STRAIN, CORE);
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }


    /**
     * Кольцо из точек вокруг оси корпуса.
     *
     * @param wobble  0 — ровная окружность, 1 — рваная и плавающая
     * @param breaks  доля выпавших кусков
     * @param lit     доля окружности, горящая ярче (засчитанное удержание)
     */
    private static void drawRing(Scene s, float t, double radius, float wobble, float alpha,
                                 float breaks, VfxColour line, float lit) {
        if (alpha <= 0.01F) {
            return;
        }
        List<Vec3> points = new ArrayList<>(RING_POINTS + 1);
        for (int i = 0; i <= RING_POINTS; i++) {
            double a = i * (Math.PI * 2.0D / RING_POINTS) + t * 0.02D;
            double noise = Math.sin(a * 3.0D + t * 0.23D) * 0.6D + Math.sin(a * 7.0D - t * 0.31D) * 0.4D;
            double r = radius * (1.0D + 0.18D * wobble * noise);
            double y = 0.05D * wobble * Math.sin(a * 2.0D + t * 0.17D);
            points.add(s.axis().add(Math.cos(a) * r, y, Math.sin(a) * r));
        }

        VertexConsumer strand = s.buffers().getBuffer(MurimRenderTypes.strand());
        for (int i = 0; i < RING_POINTS; i++) {
            // Выпадающие куски — по устойчивому шуму, чтобы дыры не мигали каждый кадр.
            double gap = 0.5D + 0.5D * Math.sin(i * 1.7D + Math.floor(t / 6.0D) * 0.9D + SEED % 7);
            if (gap < breaks) {
                continue;
            }
            float share = i / (float) RING_POINTS;
            float bright = share < lit ? 1.0F : 0.7F;
            // Три слоя: широкий мягкий ореол, полоса цвета, тонкая светлая сердцевина.
            VfxDraw.segment(strand, s.pose(), points.get(i), points.get(i + 1), s.camera(),
                            0.06D, alpha * bright * 0.25F, DEEP.red(), DEEP.green(), DEEP.blue());
            VfxDraw.segment(strand, s.pose(), points.get(i), points.get(i + 1), s.camera(),
                            0.025D, alpha * bright * 0.8F, line.red(), line.green(), line.blue());
            VfxDraw.segment(strand, s.pose(), points.get(i), points.get(i + 1), s.camera(),
                            0.008D, alpha * bright, CORE.red(), CORE.green(), CORE.blue());
        }
        s.buffers().endBatch(MurimRenderTypes.strand());

        // Мягкий ореол по кольцу: без него линия читается как проволока, а не как ци.
        VertexConsumer glow = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        for (int i = 0; i < RING_POINTS; i += 4) {
            VfxDraw.billboard(glow, s.pose(), points.get(i), s.camera(), 0.12D,
                              alpha * 0.18F, line.red(), line.green(), line.blue());
        }
        // Бегущие сгустки: ци в кольце течёт. Ровное кольцо гонит их быстро и ярко,
        // рваное — медленно, и они вываливаются наружу.
        int motes = 7;
        double speed = 0.09D - 0.06D * wobble;
        for (int m = 0; m < motes; m++) {
            double a = m * (Math.PI * 2.0D / motes) + t * speed;
            double drift = 1.0D + wobble * 0.5D * (0.5D + 0.5D * Math.sin(t * 0.2D + m * 2.1D));
            Vec3 at = s.axis().add(Math.cos(a) * radius * drift, 0.0D, Math.sin(a) * radius * drift);
            VfxDraw.billboard(glow, s.pose(), at, s.camera(), 0.05D, alpha * (1.0F - 0.6F * wobble),
                              CORE.red(), CORE.green(), CORE.blue());
            VfxDraw.billboard(glow, s.pose(), at, s.camera(), 0.11D, alpha * 0.35F * (1.0F - 0.6F * wobble),
                              line.red(), line.green(), line.blue());
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    // ------------------------------------------------------------------ семя

    /**
     * «Внутренний взгляд»: вспышка, жилы по всему телу, пульс семени, затухание до
     * мягкого свечения. Затемнение краёв экрана рисует {@code MeditationHud}.
     */
    private static void seedScene(Scene s, float age) {
        float total = ClientMeditationState.SEED_SCENE_TICKS;
        float flash = Mth.clamp(1.0F - age / 16.0F, 0.0F, 1.0F);
        // Жилы вспыхивают разом и медленно гаснут к концу сцены.
        float veins = age < 20.0F ? age / 20.0F : Mth.clamp(1.0F - (age - (total - 80.0F)) / 70.0F, 0.0F, 1.0F);
        float fadeOut = Mth.clamp((total - age) / 50.0F, 0.0F, 1.0F);
        // Пульс сердца: двойной удар раз в полторы секунды.
        float beat = (age % 30.0F) / 30.0F;
        float pulse = (float) (Math.exp(-beat * 18.0D) + 0.6D * Math.exp(-Math.abs(beat - 0.22D) * 18.0D));

        drawVeins(s, 1.0F, veins * 0.7F);
        drawStreams(s, age, veins);

        VertexConsumer glow = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        // Семя — главный объект сцены: плотная точка, бьющаяся как сердце. На прошлых
        // кадрах после вспышки оно гасло до невидимого, и сцена оставалась без центра.
        float life = Math.max(fadeOut, 0.4F);
        CoreGlow.draw(glow, s.pose(), s.core(), s.camera(), age,
                      0.24D + 0.10D * pulse + 0.20D * flash,
                      Math.min(1.0F, (0.8F + 0.2F * pulse) * life + 0.4F * flash), DEEP, CORE);
        VfxDraw.billboard(glow, s.pose(), s.core(), s.camera(), 0.05D + 0.03D * pulse,
                          life, 0.85F, 0.97F, 1.0F);
        // Широкий мягкий ореол: на референсе свет семени заливает живот и руки.
        VfxDraw.billboard(glow, s.pose(), s.core(), s.camera(), 0.45D + 0.08D * pulse,
                          0.22F * life, HALO.red(), HALO.green(), HALO.blue());
        if (age < 30.0F) {
            BillboardBurst.outward(glow, s.pose(), s.core(), s.camera(), 36, age, 0.6D,
                                   1.0F - age / 30.0F, HALO, CORE);
        } else {
            BillboardBurst.inward(glow, s.pose(), s.core(), s.camera(), 12, age, 0.3D,
                                  0.35F * fadeOut, HALO, CORE);
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    // ------------------------------------------------------------------ прорыв

    /** Кости: тёплый белый, как раскалённое; ядро почти белое. */
    private static final VfxColour BONE = new VfxColour(1.0F, 0.72F, 0.3F);
    private static final VfxColour BONE_CORE = new VfxColour(1.0F, 0.93F, 0.72F);
    /** Аура после прорыва — светлее и холоднее ци семени. */
    private static final VfxColour AURA = new VfxColour(0.6F, 0.88F, 1.0F);

    /**
     * Прорыв в ранг (docs/design/19 §3е, решение автора 01.10): ци собирается к центру,
     * кости светятся сквозь тело, по меридианам идёт волна, тёмные сгустки примесей выходят
     * наружу и падают пятнами. Выход ауры в конце — {@link #rankUp}.
     *
     * <p>Фазы по 12 секундам сцены: 0–2 с — сбор; 2–10 с — кости и волна; 3,5–10 с — примеси;
     * 10–12 с — всё стягивается в центр перед выходом ауры.
     */
    private static final VfxColour GOLD = new VfxColour(1.0F, 0.62F, 0.18F);
    private static final VfxColour GOLD_CORE = new VfxColour(1.0F, 0.95F, 0.75F);
    private static final VfxColour PINK = new VfxColour(0.95F, 0.42F, 0.62F);
    private static final VfxColour PINK_CORE = new VfxColour(1.0F, 0.88F, 0.94F);

    /** Цвета ранга: третий — синий, второй — золото, первый — слива, Пик — белое золото. */
    private static VfxColour[] palette(int rank) {
        return switch (rank) {
            case 2 -> new VfxColour[] {GOLD, GOLD_CORE};
            case 3 -> new VfxColour[] {PINK, PINK_CORE};
            case 4 -> new VfxColour[] {BONE, BONE_CORE};
            default -> new VfxColour[] {HALO, CORE};
        };
    }

    /**
     * Прорыв: у каждого ранга своя сцена (решение автора 01.10, концепты —
     * docs/design/reference/breakthrough-concepts/). Общее — стягивание в центр в последние
     * две секунды, затем выход ауры ({@link #rankUp}).
     */
    private static void breakthrough(Scene s, float age) {
        int target = ClientMeditationState.breakthroughTarget();
        switch (target) {
            case 2 -> circuitScene(s, age);
            case 3 -> formScene(s, age);
            case 4 -> rebirthScene(s, age);
            default -> purificationScene(s, age);
        }
        if (target == 4) {
            return;
        }
        float total = io.github.verycooltimo.murim.cultivation.Realm.BREAKTHROUGH_TICKS;
        float gather = Mth.clamp(age / 40.0F, 0.0F, 1.0F);
        float squeeze = Mth.clamp((age - (total - 40.0F)) / 40.0F, 0.0F, 1.0F);
        VfxColour[] pal = palette(target);
        float breath = 0.5F + 0.5F * Mth.sin(age * 0.25F);
        VertexConsumer glow = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        CoreGlow.draw(glow, s.pose(), s.core(), s.camera(), age,
                      0.07D + 0.04D * gather + 0.32D * squeeze * squeeze + 0.02D * breath,
                      Math.min(1.0F, 0.45F * gather + 0.55F * squeeze + 0.12F * breath), pal[0], pal[1]);
        BillboardBurst.inward(glow, s.pose(), s.core(), s.camera(), 20, age, 0.7D,
                              0.3F * gather + 0.5F * squeeze, pal[0], pal[1]);
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    /**
     * Гладкая линия по точкам текстурой ленты, с общими боковыми векторами на стыках (урок
     * Пика: отрезки с круглой текстурой давали пунктир, отдельные повороты — «гребёнку»).
     * Свечение шире и слабее, ядро узкое; {@code taper} — сужение к обоим концам.
     *
     * @param upTo доля пути, уже прорисованная (рост от начала к концу)
     */
    private static void smoothLine(VertexConsumer c, Scene s, Vec3[] pts, float upTo, double glowW, double coreW,
                                   float alpha, VfxColour glow, VfxColour core, boolean taper) {
        int n = pts.length - 1;
        if (n < 1 || upTo <= 0.0F || alpha <= 0.0F) {
            return;
        }
        Vec3[] sides = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            Vec3 tan = pts[Math.min(n, i + 1)].subtract(pts[Math.max(0, i - 1)]);
            Vec3 sd = tan.cross(s.camera().subtract(pts[i]));
            sides[i] = sd.lengthSqr() < 1.0E-12D ? new Vec3(0.0D, 1.0D, 0.0D) : sd.normalize();
        }
        float total = n * Math.min(1.0F, upTo);
        for (int i = 0; i < n && i < total; i++) {
            float f = Math.min(1.0F, total - i);
            Vec3 a = pts[i];
            Vec3 b = a.lerp(pts[i + 1], f);
            double ua = i / (double) n, ub = (i + f) / (double) n;
            double ta = taper ? Math.pow(Math.sin(Math.PI * ua), 0.7D) : 1.0D;
            double tb = taper ? Math.pow(Math.sin(Math.PI * ub), 0.7D) : 1.0D;
            ribbonQuad(c, s, a, b, sides[i], sides[i + 1], glowW * ta, glowW * tb, 0.4F * alpha, 0.4F * alpha, glow, glow);
            ribbonQuad(c, s, a, b, sides[i], sides[i + 1], coreW * ta, coreW * tb, alpha, alpha, core, core);
        }
    }

    /** Точки окружности/эллипса в плоскости (u, v) вокруг центра. */
    private static Vec3[] loop(Vec3 centre, Vec3 u, Vec3 v, double ru, double rv, double from, double span, int n) {
        Vec3[] pts = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            double a = from + span * i / n;
            pts[i] = centre.add(u.scale(Math.cos(a) * ru)).add(v.scale(Math.sin(a) * rv));
        }
        return pts;
    }

    /** Квадратные искры, отходящие от тела наружу с подъёмом. */
    private static void outwardSparks(Scene s, float age, float strength, int count, double reach, VfxColour a, VfxColour b, long salt) {
        if (strength <= 0.0F) {
            return;
        }
        VertexConsumer c = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        for (int i = 0; i < count; i++) {
            java.util.Random r = rng(salt, i);
            float life = 16.0F + r.nextFloat() * 14.0F;
            float t = ((age + r.nextFloat() * life) % life) / life;
            double ang = r.nextDouble() * Math.PI * 2.0D;
            double h = 0.2D + r.nextDouble() * 1.1D;
            Vec3 at = new Vec3(s.axis().x + Math.cos(ang) * (0.2D + reach * t), ground(s) + h + 0.3D * t, s.axis().z + Math.sin(ang) * (0.2D + reach * t));
            VfxColour col = r.nextBoolean() ? a : b;
            VfxDraw.billboard(c, s.pose(), at, s.camera(), 0.015D + r.nextDouble() * 0.03D,
                              (float) Math.sin(t * Math.PI) * strength, col.red(), col.green(), col.blue());
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    // --------------------------------------------------- третий ранг: очищение

    private static final VfxColour VEIN_CORE = hex(0xD8FAFF);
    private static final VfxColour VEIN_LINE = hex(0x56CFFF);
    private static final VfxColour VEIN_HALO = hex(0x287BFF);

    /**
     * Очищение (Хуашань гл. 41–42) — по разбору codex кадров и концепта 01.10. Фазы:
     * 0–35 тонкая сеть и центр; 35–100 тёмный пот и нарастающий пар; 100–165 каналы
     * расширяются, импульсы ускоряются, искры наружу; 165–200 выброс — кольца по полу,
     * перекрёстный луч, радиальные ленты; 200–240 гаснет, остаются пар и пятна.
     */
    private static void purificationScene(Scene s, float age) {
        float net = Mth.clamp(age / 35.0F, 0.0F, 1.0F);
        float sweat = Mth.clamp((age - 15.0F) / 20.0F, 0.0F, 1.0F) * (1.0F - Mth.clamp((age - 165.0F) / 30.0F, 0.0F, 1.0F));
        float widen = Mth.clamp((age - 100.0F) / 65.0F, 0.0F, 1.0F);
        float fade = 1.0F - Mth.clamp((age - 200.0F) / 35.0F, 0.0F, 1.0F);
        float pulse = 0.5F + 0.5F * Mth.sin(age * 0.25F);
        drawVeins(s, net, (0.25F + 0.45F * widen + 0.15F * pulse * widen) * fade, 0.006D + 0.014D * widen, VEIN_HALO, VEIN_LINE);
        drawStreams(s, age * (1.0F + 0.8F * widen), (0.4F + 0.8F * widen) * net * fade, VEIN_LINE, VEIN_CORE);
        drawSweat(s, age, sweat);
        drawSteam(s, age, Mth.clamp((age - 25.0F) / 40.0F, 0.0F, 1.0F) * (1.0F - 0.6F * Mth.clamp((age - 135.0F) / 85.0F, 0.0F, 1.0F)));
        drawMist(s, age, net * 0.7F, HALO);
        outwardSparks(s, age, Mth.clamp((age - 100.0F) / 20.0F, 0.0F, 1.0F) * (1.0F - Mth.clamp((age - 190.0F) / 20.0F, 0.0F, 1.0F)),
                      18, 0.55D, hex(0xA0EAFF), hex(0x448FFF), 301);
        float stains = Mth.clamp((age - 55.0F) / 115.0F, 0.0F, 1.0F);
        if (stains > 0.0F) {
            VertexConsumer dark = s.buffers().getBuffer(MurimRenderTypes.impurity());
            stainSet(dark, s.pose(), new Vec3(s.axis().x, ground(s) + 0.01D, s.axis().z), 0.85F, stains);
            s.buffers().endBatch(MurimRenderTypes.impurity());
        }
        // Выброс 165–200: кольца по полу, перекрёстный луч, радиальные ленты.
        float burst = Mth.clamp((age - 165.0F) / 25.0F, 0.0F, 1.0F);
        if (age >= 165.0F && age <= 205.0F) {
            Vec3 floor = new Vec3(s.axis().x, ground(s) + 0.04D, s.axis().z);
            Vec3 side = s.facing().cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
            float br = (float) Math.sin(Math.min(1.0D, burst) * Math.PI) ;
            VertexConsumer c = s.buffers().getBuffer(MurimRenderTypes.ribbon());
            for (int k = 0; k < 3; k++) {
                double rr = 0.35D + (1.5D + 0.2D * k) * Math.sqrt(Mth.clamp(burst - k * 0.12F, 0.0F, 1.0F));
                smoothLine(c, s, loop(floor.add(0.0D, 0.01D * k, 0.0D), side, s.facing(), rr, rr, 0.0D, Math.PI * 2.0D, 40),
                           1.0F, 0.08D, 0.02D, 0.8F * br, hex(0x409CFF), hex(0xC7F7FF), false);
            }
            float beam = Mth.clamp((age - 165.0F) / 5.0F, 0.0F, 1.0F) * (1.0F - Mth.clamp((age - 182.0F) / 16.0F, 0.0F, 1.0F));
            Vec3 b0 = floor, b1 = floor.add(0.0D, 3.2D, 0.0D);
            smoothLine(c, s, new Vec3[] {b0, b0.lerp(b1, 0.5D), b1}, 1.0F, 0.3D, 0.1D, beam, hex(0x409CFF), hex(0xC7F7FF), true);
            for (int k = 0; k < 10; k++) {
                java.util.Random r = rng(307, k);
                double a = r.nextDouble() * Math.PI * 2.0D;
                Vec3 dir = new Vec3(Math.cos(a), 0.15D + r.nextDouble() * 0.5D, Math.sin(a)).normalize();
                Vec3 from = s.core().add(dir.scale(0.3D + 0.5D * burst));
                Vec3 to = from.add(dir.scale(0.6D));
                smoothLine(c, s, new Vec3[] {from, from.lerp(to, 0.5D), to}, 1.0F, 0.05D, 0.015D, br, hex(0x448FFF), hex(0xC7F7FF), true);
            }
            s.buffers().endBatch(MurimRenderTypes.ribbon());
        }
    }


    /**
     * Лёгкая дымка вокруг тела во всех сценах прорыва (автор 01.10: «как от пота, менее
     * заметно, как туман»): крупные бледные клубы медленно поднимаются и тают.
     */
    private static void drawMist(Scene s, float age, float strength, VfxColour tint) {
        if (strength <= 0.0F) {
            return;
        }
        double feet = ground(s);
        VertexConsumer c = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        for (int i = 0; i < 22; i++) {
            java.util.Random r = rng(89, i);
            float t = ((age * 0.6F + r.nextFloat() * 120.0F) % 120.0F) / 120.0F;
            double a = r.nextDouble() * Math.PI * 2.0D;
            double rad = 0.25D + r.nextDouble() * 0.55D + 0.3D * t;
            Vec3 at = new Vec3(s.axis().x + Math.cos(a) * rad, feet + 0.2D + 1.6D * t, s.axis().z + Math.sin(a) * rad);
            float fade = (float) Math.sin(t * Math.PI);
            VfxDraw.billboard(c, s.pose(), at, s.camera(), 0.35D + 0.4D * t, 0.07F * strength * fade,
                              0.6F + 0.4F * tint.red(), 0.6F + 0.4F * tint.green(), 0.6F + 0.4F * tint.blue());
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    /** Пятна примесей на полу после очищения: тёмные кляксы вокруг места, где сидел игрок. */
    private static void drawStains(MultiBufferSource.BufferSource buffers, PoseStack.Pose pose, Vec3 origin, float age) {
        float fade = Mth.clamp((ClientMeditationState.STAIN_TICKS - age) / 120.0F, 0.0F, 1.0F);
        VertexConsumer dark = buffers.getBuffer(MurimRenderTypes.impurity());
        stainSet(dark, pose, origin.add(0.0D, 0.03D, 0.0D), 0.85F * fade, 1.0F);
        if (((int) age) % 100 == 0) MurimMod.LOGGER.info("DEBUG пятна: возраст {} центр {}", age, origin);
        buffers.endBatch(MurimRenderTypes.impurity());
    }

    /** Набор клякс: кольцом вокруг тела, крупные и мелкие брызги; {@code grow} — доля проявления. */
    private static void stainSet(VertexConsumer dark, PoseStack.Pose pose, Vec3 centre, float alpha, float grow) {
        for (int i = 0; i < 22; i++) {
            java.util.Random r = rng(97, i);
            double a = r.nextDouble() * Math.PI * 2.0D;
            double rad = 0.3D + r.nextDouble() * 0.6D;
            Vec3 at = centre.add(Math.cos(a) * rad, 0.0D, Math.sin(a) * rad);
            float own = Mth.clamp(grow * 22.0F - i, 0.0F, 1.0F);
            if (own <= 0.0F) {
                continue;
            }
            // Клякса — два наложенных пятна: мягкий край текстуры съедает половину радиуса.
            double big = (0.16D + r.nextDouble() * 0.18D) * own;
            flatSpot(dark, pose, at, big, alpha);
            flatSpot(dark, pose, at.add(0.0D, 0.001D, 0.0D), big * 0.6D, alpha);
            for (int k = 0; k < 4; k++) {
                double b = r.nextDouble() * Math.PI * 2.0D;
                double d = big * (1.1D + r.nextDouble());
                flatSpot(dark, pose, at.add(Math.cos(b) * d, 0.002D, Math.sin(b) * d), 0.05D * own, alpha);
            }
        }
    }

    /** Точка на коже спереди: кости лежат на оси, кожа — на 0,17 блока ближе к зрителю. */
    private static Vec3 skin(Scene s, Vec3 bone, double depth) {
        return bone == null ? null : bone.add(s.facing().scale(depth));
    }

    private static Vec3[] skinAnchors(Scene s) {
        AbstractClientPlayer p = s.player();
        Vec3 head = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.HEAD);
        return new Vec3[] {
            skin(s, BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.CHEST), 0.2D),
            skin(s, s.axis(), 0.2D),
            skin(s, BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.RIGHT_SHOULDER), 0.19D),
            skin(s, BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.LEFT_SHOULDER), 0.19D),
            skin(s, BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.RIGHT_HAND), 0.19D),
            skin(s, BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.LEFT_HAND), 0.19D),
            head == null ? null : head.add(s.facing().scale(0.29D)).add(0.0D, -0.05D, 0.0D),
            skin(s, BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.RIGHT_KNEE), 0.19D),
            skin(s, BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.LEFT_KNEE), 0.19D)};
    }

    private static double ground(Scene s) {
        AbstractClientPlayer p = s.player();
        return Mth.lerp(s.minecraft().getTimer().getGameTimeDeltaPartialTick(false), p.yOld, p.getY()) + 0.02D;
    }

    /** Тёмные капли пота: выступают на коже, сползают, срываются и оставляют пятна на полу. */
    private static void drawSweat(Scene s, float age, float strength) {
        if (strength <= 0.0F) {
            return;
        }
        Vec3[] anchors = skinAnchors(s);
        Vec3 side = s.facing().cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        double ground = ground(s);
        VertexConsumer dark = s.buffers().getBuffer(MurimRenderTypes.impuritySkin());
        for (int i = 0; i < 70; i++) {
            java.util.Random r = rng(17, i);
            Vec3 base = anchors[r.nextInt(anchors.length)];
            if (base == null) {
                continue;
            }
            Vec3 at0 = base.add(side.scale((r.nextDouble() - 0.5D) * 0.24D)).add(0.0D, (r.nextDouble() - 0.5D) * 0.18D, 0.0D);
            float born = 45.0F + r.nextFloat() * 120.0F;
            float t = (age - born) / 55.0F;
            if (t < 0.0F) {
                continue;
            }
            double size = 0.06D + r.nextDouble() * 0.05D;
            if (t < 1.0F) {
                // Выступает (растёт), медленно сползает, к концу срывается вниз.
                double grow = Math.min(1.0D, t * 4.0D);
                double slide = 0.12D * t * t;
                double fall = Math.max(0.0D, t - 0.75D) * 4.0D;
                Vec3 at = at0.add(0.0D, -slide - 1.2D * fall * fall, 0.0D);
                if (at.y < ground) {
                    at = new Vec3(at.x, ground + size, at.z);
                }
                VfxDraw.billboard(dark, s.pose(), at, s.camera(), size * grow, 0.95F * strength,
                                  0.16F, 0.09F, 0.1F);
                // Блик мокрой капли.
                VfxDraw.billboard(dark, s.pose(), at.add(0.0D, size * 0.3D, 0.0D).add(s.facing().scale(0.01D)),
                                  s.camera(), size * 0.25D * grow, 0.7F * strength, 0.75F, 0.7F, 0.72F);
                // Вытянутый след капли по коже.
                VfxDraw.segment(dark, s.pose(), at0, at, s.camera(), size * 0.35D * grow, 0.5F * strength,
                                0.12F, 0.07F, 0.08F);
            }

        }
        s.buffers().endBatch(MurimRenderTypes.impuritySkin());
    }

    /** Пар от пота: 22 клуба от предплечий, плеч и висков, растут и поднимаются, светлые серо-голубые. */
    private static void drawSteam(Scene s, float age, float strength) {
        if (strength <= 0.0F) {
            return;
        }
        Vec3[] anchors = skinAnchors(s);
        int[] from = {0, 2, 3, 4, 5, 6};
        VertexConsumer puff = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        for (int i = 0; i < 22; i++) {
            java.util.Random r = rng(29, i);
            Vec3 base = anchors[from[r.nextInt(from.length)]];
            if (base == null) {
                continue;
            }
            float life = 30.0F + r.nextFloat() * 15.0F;
            float t = ((age + r.nextFloat() * life) % life) / life;
            Vec3 at = base.add((r.nextDouble() - 0.5D) * 0.25D, 0.05D + 0.55D * t, (r.nextDouble() - 0.5D) * 0.25D);
            VfxDraw.billboard(puff, s.pose(), at, s.camera(), 0.12D + 0.3D * t, 0.13F * strength * (float) Math.sin(t * Math.PI),
                              0.69F, 0.73F, 0.8F);
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }


    // ------------------------------------------ второй ранг: малый небесный круг

    /**
     * Малый небесный круг (Absolute Regression гл. 119; Myst гл. 165) — по разбору codex 01.10.
     * 0–39 сбор в даньтяне и кольцо на поясе; 40–99 спираль вокруг корпуса и импульс вверх
     * по позвоночнику; 100–118 прорыв макушки — звезда и радиальные лучи; 118–140 спуск по
     * передней линии, точки раскрываются, ветвление по рукам и ногам; 140–209 устойчивый
     * круг и дуга за головой с бегущим ярким участком; 210–240 угасание.
     */
    private static void circuitScene(Scene s, float age) {
        AbstractClientPlayer p = s.player();
        Vec3 chest = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.CHEST);
        Vec3 head = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.HEAD);
        if (chest == null || head == null) {
            return;
        }
        drawMist(s, age, Mth.clamp(age / 40.0F, 0.0F, 1.0F) * 0.7F, GOLD);
        Vec3 side = s.facing().cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 back = s.facing().scale(-0.15D), front = s.facing().scale(0.17D);
        Vec3 core = s.core();
        Vec3 sacrum = new Vec3(s.axis().x, s.axis().y - 0.2D, s.axis().z).add(back);
        Vec3 crown = head.add(0.0D, 0.32D, 0.0D);
        Vec3 brow = head.add(s.facing().scale(0.27D)).add(0.0D, 0.06D, 0.0D);
        Vec3 throat = chest.lerp(head, 0.3D).add(front);
        Vec3 chestFront = chest.add(front);
        Vec3[] upPath = {core, sacrum, chest.add(back), chest.lerp(head, 0.35D).add(back), head.add(back).add(0.0D, 0.1D, 0.0D), crown};
        Vec3[] downPath = {crown, brow, throat, chestFront, core};
        float end = 1.0F - Mth.clamp((age - 210.0F) / 30.0F, 0.0F, 1.0F);
        float ascent = Mth.clamp((age - 40.0F) / 55.0F, 0.0F, 1.0F);
        float descent = Mth.clamp((age - 118.0F) / 22.0F, 0.0F, 1.0F);
        float steady = Mth.clamp((age - 140.0F) / 20.0F, 0.0F, 1.0F);
        VfxColour gl = hex(0xFFC13A), gc = hex(0xFFF1B0);

        // Каналы по телу: ветвление после прорыва — грудь, руки, ноги.
        float branch = Mth.clamp((age - 112.0F) / 38.0F, 0.0F, 1.0F);
        drawVeins(s, branch, (0.2F + 0.5F * branch) * end, 0.006D + 0.01D * branch, GOLD, GOLD_CORE);

        VertexConsumer c = s.buffers().getBuffer(MurimRenderTypes.ribbon());
        // Кольцо на поясе (16–59) и спираль вокруг корпуса (40–99).
        float ring = Mth.clamp((age - 16.0F) / 43.0F, 0.0F, 1.0F) * (1.0F - 0.6F * steady) * end;
        smoothLine(c, s, loop(core, side, s.facing(), 0.24D, 0.24D, age * 0.18D, Math.PI * 2.0D * ring, 36), 1.0F,
                   0.06D, 0.016D, 0.8F * Math.min(1.0F, ring * 3.0F), gl, gc, false);
        float spiral = Mth.clamp((age - 40.0F) / 59.0F, 0.0F, 1.0F) * (1.0F - Mth.clamp((age - 110.0F) / 30.0F, 0.0F, 1.0F));
        if (spiral > 0.0F) {
            Vec3[] sp = new Vec3[49];
            for (int i = 0; i <= 48; i++) {
                double u = i / 48.0D;
                double a = age * 0.17D + u * Math.PI * 2.0D * 1.75D;
                sp[i] = core.add(side.scale(Math.cos(a) * 0.3D)).add(s.facing().scale(Math.sin(a) * 0.3D)).add(0.0D, 0.75D * u, 0.0D);
            }
            smoothLine(c, s, sp, spiral, 0.06D, 0.016D, 0.75F, gl, gc, true);
        }
        // Канал вверх по позвоночнику и вниз по передней линии.
        smoothLine(c, s, upPath, ascent, 0.05D, 0.016D, end, gl, gc, false);
        smoothLine(c, s, upPath, ascent, 0.012D, 0.006D, end, gc, hex(0xFFFFFF), false);
        smoothLine(c, s, downPath, descent, 0.05D, 0.016D, end, gl, gc, false);
        // Дуга-эллипс за головой и плечами (140–209) с бегущим ярким участком.
        Vec3 ellC = chest.lerp(head, 0.6D).add(s.facing().scale(-0.3D));
        float ell = Mth.clamp((age - 140.0F) / 20.0F, 0.0F, 1.0F) * end;
        smoothLine(c, s, loop(ellC, side, up, 0.42D, 0.62D, 0.0D, Math.PI * 2.0D, 48), ell, 0.05D, 0.016D, 0.7F * ell, gl, hex(0xFFC744), false);
        s.buffers().endBatch(MurimRenderTypes.ribbon());

        VertexConsumer glow = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        // Бегущие импульсы по каналу: светлые узлы с шагом ~0,08 блока за фронтом.
        for (int k = 0; k < 14; k++) {
            float u = ascent - k * 0.035F;
            if (u > 0.0F && ascent < 1.0F) {
                Vec3 at = along(upPath, u);
                float a = 1.0F - k / 14.0F;
                VfxDraw.billboard(glow, s.pose(), at, s.camera(), 0.035D, 0.9F * a, gc.red(), gc.green(), gc.blue());
            }
        }
        if (ascent > 0.0F && ascent < 1.0F) {
            bead(glow, s, along(upPath, ascent));
        }
        if (descent > 0.0F && descent < 1.0F) {
            bead(glow, s, along(downPath, descent));
        }
        if (ell > 0.0F) {
            Vec3[] e = loop(ellC, side, up, 0.42D, 0.62D, 0.0D, Math.PI * 2.0D, 48);
            Vec3 run = along(e, (age % 54.0F) / 54.0F);
            VfxDraw.billboard(glow, s.pose(), run, s.camera(), 0.1D, 0.9F * ell, 1.0F, 0.95F, 0.75F);
        }
        // Звёзды на узлах: живот, грудь, лоб, макушка.
        Vec3[] nodes = {core, chestFront, brow, crown};
        float[] opens = {20.0F, 126.0F, 120.0F, 100.0F};
        for (int i = 0; i < nodes.length; i++) {
            float t = age - opens[i];
            if (t >= 0.0F) {
                float flash = (float) Math.exp(-t / 8.0F);
                star(glow, s, nodes[i], 0.07D + 0.1D * flash, (0.55F + 0.45F * flash) * end);
            }
        }
        // Прорыв макушки 100–118: звезда растёт и радиальные лучи.
        float cb = age >= 100.0F && age <= 118.0F ? (age < 105.0F ? (age - 100.0F) / 5.0F : 1.0F - (age - 105.0F) / 13.0F) : 0.0F;
        if (cb > 0.0F) {
            star(glow, s, crown, 0.08D + 0.27D * cb, cb);
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
        if (cb > 0.0F) {
            VertexConsumer rays = s.buffers().getBuffer(MurimRenderTypes.ribbon());
            for (int k = 0; k < 16; k++) {
                java.util.Random r = rng(311, k);
                double a = r.nextDouble() * Math.PI * 2.0D;
                Vec3 dir = side.scale(Math.cos(a)).add(up.scale(Math.sin(a) * 0.9D + 0.1D)).normalize();
                Vec3 to = crown.add(dir.scale(0.15D + 0.3D * r.nextDouble() + 0.2D * (1.0F - cb)));
                smoothLine(rays, s, new Vec3[] {crown, crown.lerp(to, 0.5D), to}, 1.0F, 0.03D, 0.01D, cb, hex(0xFFAA28), hex(0xFFFBE5), true);
            }
            s.buffers().endBatch(MurimRenderTypes.ribbon());
        }
        // Квадратные золотые искры: мало в начале, больше на прорыве и в финале.
        float sparkK = 0.35F + 0.65F * Mth.clamp((age - 95.0F) / 20.0F, 0.0F, 1.0F);
        outwardSparks(s, age, sparkK * end, 24, 0.6D, hex(0xFFD36B), hex(0xFFB43B), 313);
    }


    private static void shadowTrail(VertexConsumer c, Scene s, Vec3[] path, float upTo) {
        if (upTo <= 0.0F) {
            return;
        }
        int n = 24;
        Vec3 prev = path[0];
        for (int i = 1; i <= n; i++) {
            Vec3 at = along(path, upTo * i / n);
            VfxDraw.segment(c, s.pose(), prev, at, s.camera(), 0.07D, 0.45F, 0.06F, 0.04F, 0.03F);
            prev = at;
        }
    }

    /** Пройденная часть пути — золотая лента с белым ядром. */
    private static void trail(VertexConsumer c, Scene s, Vec3[] path, float upTo, float alpha) {
        if (upTo <= 0.0F) {
            return;
        }
        int n = 24;
        Vec3 prev = path[0];
        for (int i = 1; i <= n; i++) {
            float u = upTo * i / n;
            Vec3 at = along(path, u);
            VfxDraw.segment(c, s.pose(), prev, at, s.camera(), 0.05D, 0.6F * alpha, GOLD.red(), GOLD.green(), GOLD.blue());
            VfxDraw.segment(c, s.pose(), prev, at, s.camera(), 0.02D, alpha, GOLD_CORE.red(), GOLD_CORE.green(), GOLD_CORE.blue());
            prev = at;
        }
    }

    private static void bead(VertexConsumer c, Scene s, Vec3 at) {
        VfxDraw.billboard(c, s.pose(), at, s.camera(), 0.16D, 0.7F, GOLD.red(), GOLD.green(), GOLD.blue());
        VfxDraw.billboard(c, s.pose(), at, s.camera(), 0.06D, 1.0F, 1.0F, 0.98F, 0.9F);
    }

    /** Четырёхлучевая звезда: два скрещённых луча. */
    private static void star(VertexConsumer c, Scene s, Vec3 at, double size, float alpha) {
        Vec3 side = s.facing().cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        Vec3 upv = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.segment(c, s.pose(), at.subtract(upv.scale(size)), at.add(upv.scale(size)), s.camera(), size * 0.12D,
                        alpha, GOLD_CORE.red(), GOLD_CORE.green(), GOLD_CORE.blue());
        VfxDraw.segment(c, s.pose(), at.subtract(side.scale(size)), at.add(side.scale(size)), s.camera(), size * 0.12D,
                        alpha, GOLD_CORE.red(), GOLD_CORE.green(), GOLD_CORE.blue());
        VfxDraw.billboard(c, s.pose(), at, s.camera(), size * 0.5D, alpha * 0.6F, GOLD.red(), GOLD.green(), GOLD.blue());
    }

    // ------------------------------------------ первый ранг: ци держит форму

    /**
     * Ци держит форму (вики Myst, First Rate) — абстрактная фигура ци, как понравилось автору
     * («непонятно что, дерево, просто прикольно»), но по урокам Пика: гладкие связные ветви
     * растут от основания к концам, голубая спиральная аура в начале, пространственная орбита,
     * свободные лепестки, в конце — схождение лентами в грудь с пиком 202–210.
     */
    private static void formScene(Scene s, float age) {
        drawMist(s, age, Mth.clamp(age / 40.0F, 0.0F, 1.0F) * 0.7F, PINK);
        Vec3 side = s.facing().cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        Vec3 chest = BoneAnchorLayer.position(s.player(), BoneAnchorLayer.Bone.CHEST);
        Vec3 heart = chest == null ? s.core() : chest.add(s.facing().scale(0.15D));
        double feet = ground(s);
        // Голубая спиральная аура: 0–45 раскрытие, 45–170 фон, 190–230 снова у ног и плеч.
        float aura = Mth.clamp(age / 45.0F, 0.0F, 1.0F) * (age < 170.0F ? 1.0F - 0.65F * Mth.clamp((age - 45.0F) / 40.0F, 0.0F, 1.0F) : 0.35F + 0.5F * Mth.clamp((age - 190.0F) / 20.0F, 0.0F, 1.0F));
        VertexConsumer c = s.buffers().getBuffer(MurimRenderTypes.ribbon());
        for (int k = 0; k < 3; k++) {
            Vec3[] sp = new Vec3[49];
            for (int i = 0; i <= 48; i++) {
                double u = i / 48.0D;
                double a = age * (Math.PI * 2.0D / (55.0D + 10.0D * k)) + k * 2.1D + u * Math.PI * 2.2D;
                double rad = 0.65D + 0.3D * Math.sin(u * Math.PI + k);
                sp[i] = new Vec3(s.axis().x + Math.cos(a) * rad, feet + 2.0D * u, s.axis().z + Math.sin(a) * rad);
            }
            smoothLine(c, s, sp, 1.0F, 0.16D, 0.03D, 0.35F * aura, hex(0x83CFFF), hex(0xC2F0FF), true);
        }
        // Абстрактное дерево ци: растёт 40–100, держится до 170, 170–205 сходится в грудь.
        float grow = Mth.clamp((age - 40.0F) / 60.0F, 0.0F, 1.0F);
        float fold = Mth.clamp((age - 170.0F) / 35.0F, 0.0F, 1.0F);
        List<Vec3> tips = new ArrayList<>();
        if (grow > 0.0F) {
            Vec3 root = new Vec3(s.axis().x, feet + 0.15D, s.axis().z).add(s.facing().scale(-0.32D));
            treeBranch(c, s, root, new Vec3(0.0D, 1.0D, 0.0D).add(s.facing().scale(-0.08D)).normalize(), 0.9D, 0, grow, fold, heart, tips, side, 1L);
        }
        // Орбита 105–125 замыкается, вращается; наклон ~12°.
        float orb = Mth.clamp((age - 105.0F) / 20.0F, 0.0F, 1.0F) * (1.0F - fold);
        Vec3 tilt = new Vec3(0.0D, Math.sin(Math.toRadians(12.0D)), 0.0D).add(s.facing().scale(Math.cos(Math.toRadians(12.0D)))).normalize();
        Vec3 orbC = new Vec3(s.axis().x, s.core().y + 0.1D, s.axis().z);
        Vec3[] ring = loop(orbC, side, tilt, 1.45D, 1.45D, age * (Math.PI * 2.0D / 75.0D), Math.PI * 2.0D, 64);
        smoothLine(c, s, ring, orb, 0.07D, 0.02D, 0.7F * orb, hex(0xFFADD9), hex(0xFFE0F3), false);
        // Схождение 170–205: ленты от кончиков кроны дугой в грудь.
        if (fold > 0.0F && fold < 1.0F) {
            for (int k = 0; k < Math.min(8, tips.size()); k++) {
                Vec3 t0 = tips.get(k * Math.max(1, tips.size() / 8));
                Vec3 mid = t0.lerp(heart, 0.5D).add(0.0D, 0.4D, 0.0D);
                Vec3[] arc = {t0, t0.lerp(mid, 0.5D), mid, mid.lerp(heart, 0.5D), heart};
                smoothLine(c, s, arc, fold * 1.2F, 0.08D, 0.025D, 0.8F * (1.0F - fold), hex(0xFFB1DD), hex(0xFFF0FA), true);
            }
        }
        s.buffers().endBatch(MurimRenderTypes.ribbon());
        VertexConsumer glow = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        // Свечение на концах ветвей — абстрактные розовые огни, не цветы.
        float bloom = Mth.clamp((grow - 0.6F) / 0.4F, 0.0F, 1.0F) * (1.0F - fold);
        for (Vec3 tip : tips) {
            VfxDraw.billboard(glow, s.pose(), tip, s.camera(), 0.14D * bloom, 0.75F * bloom, PINK.red(), PINK.green(), PINK.blue());
            VfxDraw.billboard(glow, s.pose(), tip, s.camera(), 0.045D * bloom, bloom, 1.0F, 0.95F, 0.97F);
        }
        // Огни на орбите.
        for (int k = 0; k < 16 && orb > 0.0F; k++) {
            Vec3 at = ring[(k * 4) % ring.length];
            VfxDraw.billboard(glow, s.pose(), at, s.camera(), 0.1D, 0.7F * orb, PINK.red(), PINK.green(), PINK.blue());
        }
        // Свободные лепестки 85–210: медленно вокруг, к концу часть ускоряется к груди.
        float petals = Mth.clamp((age - 85.0F) / 25.0F, 0.0F, 1.0F) * (1.0F - Mth.clamp((age - 215.0F) / 25.0F, 0.0F, 1.0F));
        for (int i = 0; i < 34 && petals > 0.0F; i++) {
            java.util.Random r = rng(331, i);
            double a = r.nextDouble() * Math.PI * 2.0D + age * (0.01D + 0.015D * r.nextDouble());
            double rad = 0.8D + r.nextDouble() * 1.2D;
            Vec3 at = new Vec3(s.axis().x + Math.cos(a) * rad, feet + 0.3D + r.nextDouble() * 2.6D, s.axis().z + Math.sin(a) * rad);
            if (i % 2 == 0) {
                at = at.lerp(heart, fold);
            }
            VfxColour col = r.nextBoolean() ? hex(0xFF8FC8) : hex(0xFFC0E1);
            VfxDraw.billboard(glow, s.pose(), at, s.camera(), i < 5 ? 0.18D : 0.08D, 0.8F * petals, col.red(), col.green(), col.blue());
        }
        // Пик схождения в груди 202–210.
        float hp = Mth.clamp(1.0F - Math.abs(age - 206.0F) / 12.0F, 0.0F, 1.0F);
        if (hp > 0.0F) {
            VfxDraw.billboard(glow, s.pose(), heart, s.camera(), 0.42D * hp, 0.7F * hp, 1.0F, 0.69F, 0.87F);
            VfxDraw.billboard(glow, s.pose(), heart, s.camera(), 0.13D, hp, 1.0F, 0.94F, 0.98F);
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    /** Ветвь абстрактного дерева ци: гладкая, растёт от основания, дети — после родителя. */
    private static void treeBranch(VertexConsumer c, Scene s, Vec3 from, Vec3 dir, double length, int depth, float grow,
                                   float fold, Vec3 heart, List<Vec3> tips, Vec3 side, long seed) {
        float reveal = Mth.clamp(grow * 4.5F - depth, 0.0F, 1.0F);
        if (reveal <= 0.0F) {
            return;
        }
        java.util.Random r = rng(337, seed * 13L + depth);
        Vec3 bend = side.scale((r.nextDouble() - 0.5D) * 0.25D * length);
        Vec3 to = from.add(dir.scale(length));
        Vec3 mid = from.lerp(to, 0.5D).add(bend);
        Vec3[] pts = {from, from.lerp(mid, 0.5D), mid, mid.lerp(to, 0.5D), to};
        if (fold > 0.0F) {
            for (int i = 0; i < pts.length; i++) {
                pts[i] = pts[i].lerp(heart, fold * fold);
            }
        }
        double w = depth == 0 ? 0.1D : depth == 1 ? 0.055D : depth == 2 ? 0.032D : 0.018D;
        float bright = depth == 0 ? 1.0F : 0.75F;
        smoothLine(c, s, pts, reveal, w * 3.0D, w, bright * (1.0F - 0.5F * fold), hex(0xFFB0DF), hex(0xFFF1FC), depth >= 2);
        if (reveal < 1.0F) {
            return;
        }
        if (depth >= 3) {
            tips.add(pts[4]);
            return;
        }
        int kids = depth == 0 ? 3 : 3;
        for (int k = 0; k < kids; k++) {
            // Крона шире, чем выше: ветви в стороны, чтобы дерево умещалось в кадр крупного плана.
            double spread = (k - (kids - 1) / 2.0D) * (depth == 0 ? 1.9D : 1.25D) + (r.nextDouble() - 0.5D) * 0.4D;
            Vec3 nd = dir.add(side.scale(spread)).add(0.0D, depth == 0 ? 0.0D : 0.15D, 0.0D).add(s.facing().scale(-0.06D)).normalize();
            treeBranch(c, s, to, nd, length * (0.78D + r.nextDouble() * 0.12D), depth + 1, grow, fold, heart, tips, side, seed * 31L + k);
        }
    }


    /** Ветвь дерева ци: растёт по уровням, на концах — цветы. */
    private static void growBranch(VertexConsumer c, Scene s, Vec3 from, Vec3 dir, double length, int depth,
                                   float grow, float fold, List<Vec3> tips, long seed) {
        float reveal = Mth.clamp(grow * 5.0F - depth, 0.0F, 1.0F);
        if (reveal <= 0.0F) {
            return;
        }
        Vec3 to = from.add(dir.scale(length * reveal));
        Vec3 a = from.lerp(s.core(), fold);
        Vec3 b = to.lerp(s.core(), fold);
        double width = 0.08D * Math.pow(0.62D, depth);
        VfxDraw.segment(c, s.pose(), a, b, s.camera(), width * 2.2D, 0.35F, PINK.red(), PINK.green(), PINK.blue());
        VfxDraw.segment(c, s.pose(), a, b, s.camera(), width, 0.9F, PINK_CORE.red(), PINK_CORE.green(), PINK_CORE.blue());
        if (depth >= 4 || reveal < 1.0F) {
            if (depth >= 3) {
                tips.add(to);
            }
            return;
        }
        java.util.Random r = new java.util.Random(seed * 7919L + depth);
        Vec3 side = s.facing().cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        int kids = depth == 0 ? 3 : 2;
        for (int k = 0; k < kids; k++) {
            double spread = (k - (kids - 1) / 2.0D) * (depth == 0 ? 1.3D : 0.95D) + (r.nextDouble() - 0.5D) * 0.4D;
            Vec3 nd = dir.add(side.scale(spread)).add(0.0D, depth == 0 ? 0.05D : 0.25D, 0.0D).add(s.facing().scale(-0.08D)).normalize();
            growBranch(c, s, to, nd, length * (0.66D + r.nextDouble() * 0.12D), depth + 1, grow, fold, tips, seed * 31L + k);
        }
    }

    // ------------------------------------------------ Пик: перестройка тела

    /**
     * Перестройка тела, 환골탈태 (вики Nano Machine: тело испускает яркий свет, кости и мышцы
     * ломаются и собираются заново; «кожа трескается, как скорлупа, и осыпается пылью»;
     * Myst гл. 108: тело «рассыпается, как кора», под ним новая блестящая кожа).
     */
    private static final VfxColour CRACK_LIGHT = new VfxColour(0.82F, 0.94F, 1.0F);
    private static final VfxColour CRACK_BLUE = new VfxColour(0.35F, 0.7F, 1.0F);

    private static final VfxColour SEAM_CORE = new VfxColour(0.93F, 1.0F, 1.0F);
    private static final VfxColour SEAM_EDGE = new VfxColour(0.4F, 0.87F, 1.0F);
    private static final VfxColour BONE_GOLD = new VfxColour(1.0F, 0.72F, 0.29F);
    private static final VfxColour BONE_PALE = new VfxColour(1.0F, 0.94F, 0.74F);

    /** Часть тела для трещин: центр передней грани, полуразмеры по горизонтали и вертикали. */
    private record Part(Vec3 centre, double halfW, double halfH, int cracks, double minLen, double maxLen) {
    }

    private static List<Part> crackParts(Scene s) {
        AbstractClientPlayer p = s.player();
        List<Part> parts = new ArrayList<>();
        Vec3 head = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.HEAD);
        Vec3 chest = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.CHEST);
        if (head != null) parts.add(new Part(head.add(s.facing().scale(0.255D)), 0.22D, 0.22D, 5, 0.18D, 0.38D));
        if (chest != null) parts.add(new Part(chest.add(s.facing().scale(0.13D)).add(0.0D, -0.12D, 0.0D), 0.22D, 0.26D, 6, 0.25D, 0.5D));
        for (BoneAnchorLayer.Bone b : new BoneAnchorLayer.Bone[] {BoneAnchorLayer.Bone.RIGHT_SHOULDER, BoneAnchorLayer.Bone.LEFT_SHOULDER}) {
            Vec3 sh = BoneAnchorLayer.position(p, b);
            Vec3 hand = BoneAnchorLayer.position(p, b == BoneAnchorLayer.Bone.RIGHT_SHOULDER
                    ? BoneAnchorLayer.Bone.RIGHT_HAND : BoneAnchorLayer.Bone.LEFT_HAND);
            if (sh != null && hand != null) parts.add(new Part(sh.lerp(hand, 0.5D).add(s.facing().scale(0.13D)), 0.1D, 0.3D, 3, 0.18D, 0.36D));
        }
        for (BoneAnchorLayer.Bone b : new BoneAnchorLayer.Bone[] {BoneAnchorLayer.Bone.RIGHT_KNEE, BoneAnchorLayer.Bone.LEFT_KNEE}) {
            Vec3 k = BoneAnchorLayer.position(p, b);
            if (k != null) parts.add(new Part(k.add(s.facing().scale(0.13D)), 0.2D, 0.1D, 2, 0.18D, 0.32D));
        }
        return parts;
    }

    /** Сеть трещин: постоянная (от сида), растёт от начала к концу каждой трещины. */
    private record Crack(List<Vec3> points, float start, int part) {
    }

    private static List<Crack> crackNet(Scene s) {
        Vec3 side = s.facing().cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        List<Crack> net = new ArrayList<>();
        List<Part> parts = crackParts(s);
        int n = 0;
        for (int pi = 0; pi < parts.size(); pi++) {
            Part part = parts.get(pi);
            for (int c = 0; c < part.cracks(); c++, n++) {
                java.util.Random r = rng(131, n);
                Vec3 at = part.centre().add(side.scale((r.nextDouble() - 0.5D) * 2.0D * part.halfW() * 0.8D))
                        .add(up.scale((r.nextDouble() - 0.5D) * 2.0D * part.halfH() * 0.8D));
                double len = part.minLen() + r.nextDouble() * (part.maxLen() - part.minLen());
                double angle = r.nextDouble() * Math.PI;
                List<Vec3> pts = new ArrayList<>();
                pts.add(at);
                int steps = 5;
                for (int k = 0; k < steps; k++) {
                    angle += (r.nextDouble() - 0.5D) * 1.1D;
                    Vec3 step = side.scale(Math.cos(angle)).add(up.scale(Math.sin(angle))).scale(len / steps);
                    Vec3 next = at.add(step);
                    // Не выходить за грань части тела.
                    Vec3 rel = next.subtract(part.centre());
                    double sx = Mth.clamp(rel.dot(side), -part.halfW(), part.halfW());
                    double sy = Mth.clamp(rel.y, -part.halfH(), part.halfH());
                    next = part.centre().add(side.scale(sx)).add(0.0D, sy, 0.0D);
                    pts.add(next);
                    at = next;
                }
                // Голова и грудь трескаются первыми, руки и ноги — позже.
                float start = 28.0F + (pi * 11.0F) + r.nextFloat() * 30.0F;
                net.add(new Crack(pts, Math.min(start, 104.0F), pi));
            }
        }
        return net;
    }

    /**
     * Перестройка тела — по главному референсу автора (Мок Кён Ун, Myst гл. 107–108) и разбору
     * codex по кадрам 01.10: не частицы вокруг тела, а разрушение старой оболочки. Контрольные
     * состояния: тик 40 — свет внутри (золотой скелет), 90 — тело расколото сетью трещин,
     * 140 — пластины коры отходят и меняют силуэт, 190 — швы закрываются, 230 — цельное тело.
     */
    private static void rebirthScene(Scene s, float age) {
        drawMist(s, age, Mth.clamp(age / 40.0F, 0.0F, 1.0F), SEAM_EDGE);
        float bones = Mth.clamp(age / 24.0F, 0.0F, 1.0F) * (1.0F - Mth.clamp((age - 44.0F) / 32.0F, 0.0F, 1.0F));
        drawStreams(s, age, 0.4F * bones, BONE_GOLD, BONE_PALE);
        drawSkeleton(s, age, bones);
        List<Crack> net = crackNet(s);
        drawSeams(s, age, net);
        drawPlates(s, age, net);
        drawZonePulses(s, age);
        drawBodyAura(s, age);
        drawRainbowSparks(s, age);
        // Ленты — v7 (автор 01.10: «раньше цвета были куда лучше, верни старые толстые
        // полосы»), ореол — новый, по разбору codex.
        drawSpirals(s, age);
        drawHaloArc(s, age, Mth.clamp((age - 52.0F) / 40.0F, 0.0F, 1.0F) * (1.0F - Mth.clamp((age - 204.0F) / 30.0F, 0.0F, 1.0F)));
        // Один короткий удар волной по полу на пике, без постоянного кольца.
        float ring = Mth.clamp((age - 142.0F) / 10.0F, 0.0F, 1.0F);
        if (ring > 0.0F && ring < 1.0F) {
            drawGroundRing(s, new Vec3(s.axis().x, ground(s) + 0.03D, s.axis().z), 0.35D + 0.8D * ring, 0.025D,
                           0.7F * (1.0F - ring), new VfxColour(0.7F, 0.93F, 1.0F));
        }
    }

    /** Швы трещин: ядро #EEFFFF, кайма #65DFFF; растут 10–18 тиков, раскрываются к 160, закрываются к 204. */
    private static void drawSeams(Scene s, float age, List<Crack> net) {
        float open = Mth.clamp((age - 104.0F) / 56.0F, 0.0F, 1.0F);
        float close = Mth.clamp((age - 180.0F) / 24.0F, 0.0F, 1.0F);
        if (age < 28.0F || close >= 1.0F) {
            return;
        }
        VertexConsumer c = s.buffers().getBuffer(MurimRenderTypes.ribbon());
        for (Crack cr : net) {
            float grow = Mth.clamp((age - cr.start()) / 14.0F, 0.0F, 1.0F);
            if (grow <= 0.0F) {
                continue;
            }
            List<Vec3> pts = cr.points();
            int n = pts.size() - 1;
            float total = n * grow;
            double core = Mth.lerp(open, 0.006D, 0.02D) * (1.0D - close);
            float alpha = (0.75F + 0.25F * open) * (1.0F - close);
            // Трещина сужается к концам (замечание автора 01.10: широкая ровная линия —
            // «шрам», а не трещина): ширина по длине — sin, у кончиков ноль.
            Vec3[] sides = new Vec3[n + 1];
            for (int k = 0; k <= n; k++) {
                Vec3 tan = pts.get(Math.min(n, k + 1)).subtract(pts.get(Math.max(0, k - 1)));
                Vec3 sd = tan.cross(s.camera().subtract(pts.get(k)));
                sides[k] = sd.lengthSqr() < 1.0E-12D ? new Vec3(0.0D, 1.0D, 0.0D) : sd.normalize();
            }
            for (int k = 0; k < n && k < total; k++) {
                Vec3 a = pts.get(k);
                float f1 = Math.min(1.0F, total - k);
                Vec3 b = a.lerp(pts.get(k + 1), f1);
                double ua = k / (double) n, ub = (k + f1) / (double) n;
                double ta = Math.pow(Math.sin(Math.PI * ua), 0.8D), tb = Math.pow(Math.sin(Math.PI * ub), 0.8D);
                ribbonQuad(c, s, a, b, sides[k], sides[k + 1], core * 3.5D * ta, core * 3.5D * tb,
                           0.35F * alpha, 0.35F * alpha, SEAM_EDGE, SEAM_EDGE);
                ribbonQuad(c, s, a, b, sides[k], sides[k + 1], core * ta, core * tb,
                           alpha, alpha, SEAM_CORE, SEAM_CORE);
            }
        }
        s.buffers().endBatch(MurimRenderTypes.ribbon());
    }


    /**
     * Пластины коры: отходят от поверхности у трещин, приподнимаются со светом под краем
     * и отлетают наружу, вращаясь. Тёмная пластина — неаддитивный слой; светящийся край и
     * просвет под ней — аддитивные, их видно и на программном стенде.
     */
    private static void drawPlates(Scene s, float age, List<Crack> net) {
        Vec3 side = s.facing().cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        List<Vec3[]> dark = new ArrayList<>();
        List<Vec3[]> glowEdges = new ArrayList<>();
        List<double[]> glowSpots = new ArrayList<>();
        List<Vec3> spotPos = new ArrayList<>();
        List<Vec3[]> holes = new ArrayList<>();
        List<Float> edgeAlpha = new ArrayList<>();
        List<Float> holeAlpha = new ArrayList<>();
        int idx = 0;
        for (Crack cr : net) {
            for (int k = 1; k < cr.points().size(); k += 2, idx++) {
                java.util.Random r = rng(151, idx);
                float t0 = 80.0F + cr.part() * 8.0F + r.nextFloat() * 40.0F;
                float lift = Mth.clamp((age - t0) / 10.0F, 0.0F, 1.0F);
                float fly = Mth.clamp((age - t0 - 10.0F) / 26.0F, 0.0F, 1.0F);
                if (lift <= 0.0F || fly >= 1.0F) {
                    continue;
                }
                // Перед лицом — не больше 3–4 сколов: иначе лицо не читается (codex 01.10).
                if (r.nextFloat() < (cr.part() == 0 ? 0.7F : 0.35F)) {
                    continue;
                }
                double size = cr.part() == 1 ? 0.06D + r.nextDouble() * 0.06D : 0.04D + r.nextDouble() * 0.04D;
                Vec3 base = cr.points().get(k);
                Vec3 out = s.facing().add(side.scale((r.nextDouble() - 0.5D) * 1.4D)).add(0.0D, 0.15D + r.nextDouble() * 0.3D, 0.0D).normalize();
                double dist = 0.035D * lift + (0.25D + r.nextDouble() * 0.6D) * fly * fly;
                Vec3 centre = base.add(out.scale(dist)).add(0.0D, -0.4D * fly * fly * fly, 0.0D);
                double rot = Math.toRadians(15.0D * lift + (30.0D + r.nextDouble() * 70.0D) * fly) * (r.nextBoolean() ? 1 : -1);
                    Vec3 ax = side.scale(Math.cos(rot)).add(up.scale(Math.sin(rot)));
                Vec3 ay = up.scale(Math.cos(rot)).subtract(side.scale(Math.sin(rot)));
                // Скол — неровный угловатый многоугольник (5–6 вершин), а не квадрат с рамкой
                // (замечание автора 01.10: «контур светящийся странный, не похоже на сколы»).
                int verts = 5 + r.nextInt(2);
                Vec3[] poly = new Vec3[verts];
                double turn = r.nextDouble() * Math.PI * 2.0D;
                for (int v = 0; v < verts; v++) {
                    double ang = turn + (v + (r.nextDouble() - 0.5D) * 0.6D) / verts * Math.PI * 2.0D;
                    double rr = size * (0.55D + 0.5D * r.nextDouble());
                    poly[v] = centre.add(ax.scale(Math.cos(ang) * rr)).add(ay.scale(Math.sin(ang) * rr * 0.8D));
                }
                dark.add(poly);
                // Дыра на месте скола: та же форма на коже, светится ярче всего сразу после
                // отрыва и гаснет за ~2 с — «там кожа сильнее светится» (автор 01.10).
                float since = age - t0 - 10.0F;
                float hole = since < 0.0F ? 0.5F * lift : Mth.clamp(1.0F - since / 40.0F, 0.0F, 1.0F);
                if (hole > 0.0F) {
                    Vec3[] spot = new Vec3[verts];
                    for (int v = 0; v < verts; v++) {
                        spot[v] = poly[v].subtract(centre).add(base).add(s.facing().scale(0.004D));
                    }
                    holes.add(spot);
                    holeAlpha.add(hole);
                }
                float edge = (1.0F - fly) * (0.4F + 0.6F * lift);
                // Чёрный скол, светится щель вокруг — тонкий контур по всем краям, пока скол
                // у кожи; при отлёте гаснет (автор 01.10: «сколы чёрные, светится то, что между ними»).
                // Ободок шире скола и чуть позади него (к телу): иначе скол в той же плоскости
                // закрывал свет, и контура не было видно (кадры стенда 01.10).
                Vec3[] rim = new Vec3[verts];
                for (int v = 0; v < verts; v++) {
                    rim[v] = centre.add(poly[v].subtract(centre).scale(1.18D)).subtract(s.facing().scale(0.006D));
                }
                for (int v = 0; v < verts; v++) {
                    glowEdges.add(new Vec3[] {rim[v], rim[(v + 1) % verts]});
                    edgeAlpha.add(Mth.clamp(1.2F - fly, 0.0F, 1.0F) * (0.6F + 0.4F * lift));
                }
                glowSpots.add(new double[] {size * 1.1D, edge});
                spotPos.add(base.add(s.facing().scale(0.005D)));
            }
        }
        VertexConsumer d = s.buffers().getBuffer(MurimRenderTypes.impurity());
        Vec3 n = s.facing();
        for (Vec3[] q : dark) {
            // Заливка веером из четырёхугольников (последняя вершина повторяется) — UV в
            // центр текстуры, где она непрозрачна: края скола жёсткие, а не размытые.
            Vec3 c0 = q[0];
            for (int v = 1; v + 1 < q.length; v++) {
                VfxDraw.vertex(d, s.pose(), c0, n, 0.5F, 0.5F, 0.97F, 0.204F, 0.188F, 0.267F);
                VfxDraw.vertex(d, s.pose(), q[v], n, 0.5F, 0.5F, 0.97F, 0.443F, 0.404F, 0.49F);
                VfxDraw.vertex(d, s.pose(), q[v + 1], n, 0.5F, 0.5F, 0.97F, 0.09F, 0.106F, 0.176F);
                VfxDraw.vertex(d, s.pose(), q[v + 1], n, 0.5F, 0.5F, 0.97F, 0.09F, 0.106F, 0.176F);
            }
        }
        s.buffers().endBatch(MurimRenderTypes.impurity());
        VertexConsumer g = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        for (int i = 0; i < glowEdges.size(); i++) {
            float a = edgeAlpha.get(i);
            Vec3[] e = glowEdges.get(i);
            VfxDraw.segment(g, s.pose(), e[0], e[1], s.camera(), 0.02D, 0.4F * a, 0.4F, 0.87F, 1.0F);
            VfxDraw.segment(g, s.pose(), e[0], e[1], s.camera(), 0.009D, a, 0.93F, 1.0F, 1.0F);
        }
        for (int h = 0; h < holes.size(); h++) {
            Vec3[] q = holes.get(h);
            float ha = holeAlpha.get(h);
            Vec3 c0 = q[0];
            for (int v = 1; v + 1 < q.length; v++) {
                // UV в центр мягкой текстуры — заливка ровная, без пятна.
                VfxDraw.vertex(g, s.pose(), c0, n, 0.5F, 0.5F, 0.85F * ha, 0.75F, 0.95F, 1.0F);
                VfxDraw.vertex(g, s.pose(), q[v], n, 0.5F, 0.5F, 0.85F * ha, 0.75F, 0.95F, 1.0F);
                VfxDraw.vertex(g, s.pose(), q[v + 1], n, 0.5F, 0.5F, 0.85F * ha, 0.75F, 0.95F, 1.0F);
                VfxDraw.vertex(g, s.pose(), q[v + 1], n, 0.5F, 0.5F, 0.85F * ha, 0.75F, 0.95F, 1.0F);
            }
            for (int v = 0; v < q.length; v++) {
                VfxDraw.segment(g, s.pose(), q[v], q[(v + 1) % q.length], s.camera(), 0.006D, 0.9F * ha, 0.93F, 1.0F, 1.0F);
            }
            Vec3 mid = q[0].lerp(q[q.length / 2], 0.5D);
            VfxDraw.billboard(g, s.pose(), mid, s.camera(), 0.12D, 0.5F * ha, 0.4F, 0.85F, 1.0F);
        }
        // Просвет под отошедшей пластиной: держится 4–8 тиков после отлёта и гаснет.
        for (int i = 0; i < spotPos.size(); i++) {
            double[] gs = glowSpots.get(i);
            VfxDraw.billboard(g, s.pose(), spotPos.get(i), s.camera(), gs[0], (float) (0.45D * gs[1]),
                              SEAM_EDGE.red(), SEAM_EDGE.green(), SEAM_EDGE.blue());
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    /** Кульминация по зонам тела: грудь → плечи и голова → руки → ноги, импульсы по 8 тиков. */
    private static void drawZonePulses(Scene s, float age) {
        if (age < 112.0F || age > 172.0F) {
            return;
        }
        AbstractClientPlayer p = s.player();
        // Без головы: подсветка лица ложилась на глаза, а у каждого скина глаза на своём месте
        // (замечание автора 01.10).
        BoneAnchorLayer.Bone[] order = {BoneAnchorLayer.Bone.CHEST, BoneAnchorLayer.Bone.RIGHT_SHOULDER, BoneAnchorLayer.Bone.LEFT_SHOULDER,
            BoneAnchorLayer.Bone.RIGHT_HAND, BoneAnchorLayer.Bone.LEFT_HAND,
            BoneAnchorLayer.Bone.RIGHT_KNEE, BoneAnchorLayer.Bone.LEFT_KNEE};
        VertexConsumer g = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        for (int i = 0; i < order.length; i++) {
            Vec3 at = BoneAnchorLayer.position(p, order[i]);
            if (at == null) {
                continue;
            }
            at = at.add(s.facing().scale(order[i] == BoneAnchorLayer.Bone.HEAD ? 0.28D : 0.15D));
            for (int pulse = 0; pulse < 3; pulse++) {
                float t = (age - (112.0F + i * 3.0F + pulse * 16.0F)) / 8.0F;
                if (t < 0.0F || t > 1.0F) {
                    continue;
                }
                float k = (float) Math.sin(t * Math.PI);
                double size = order[i] == BoneAnchorLayer.Bone.HEAD ? 0.12D : 0.14D + 0.1D * k;
                VfxDraw.billboard(g, s.pose(), at, s.camera(), size, 0.65F * k, 0.38F, 0.85F, 1.0F);
                VfxDraw.billboard(g, s.pose(), at, s.camera(), size * 0.35D, 0.9F * k, 0.95F, 1.0F, 1.0F);
            }
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    private static VfxColour hex(int c) {
        return new VfxColour((c >> 16 & 255) / 255.0F, (c >> 8 & 255) / 255.0F, (c & 255) / 255.0F);
    }

    /**
     * Группы цветов лент по разбору codex кадров Мок Кён Уна (DESCRIPTIONS.md, 01.10):
     * ядро → середина → тёмный край дыма.
     */
    private static final VfxColour[][] RIBBON_GROUPS = {
        {hex(0xFFFAD9), hex(0xFFBB58), hex(0x8B3549)},   // тёплые: #FFFAD9 → #FFBB58 → #E84C79, дым #8B3549
        {hex(0xFFF0FF), hex(0xF285E6), hex(0x663D78)},   // розовые: #FFF0FF → #F285E6 → #8C4CAB, дым #663D78
        {hex(0xF0FFFF), hex(0x79E8FA), hex(0x246C83)}};  // холодные: #F0FFFF → #79E8FA → #4674D1, дым #246C83
    private static final VfxColour[] RIBBON_ACCENT = {hex(0xE84C79), hex(0x8C4CAB), hex(0x4674D1)};

    /** Точка i-й ленты на доле u: у основания огибает тело, выше расходится. */
    private static Vec3 ribbonPoint(Scene s, int k, double u, float age, double feet, double top, double base) {
        double phase = k * 1.13D;
        double a = base + k * (Math.PI * 2.0D / 6.0D) + 0.7D * Math.sin(phase) + u * (2.2D + 0.6D * Math.sin(phase * 2.0D))
                + age * 0.01D * (k % 2 == 0 ? 1.0D : -0.8D);
        // S-кривая: радиус сначала прижат к телу, потом уходит наружу с волной.
        double rad = 0.45D + 0.2D * Math.sin(phase) + 1.4D * u * u + 0.25D * Math.sin(u * Math.PI * 2.0D + phase + age * 0.05D);
        double y = feet + 0.1D + (top - feet) * u;
        return new Vec3(s.axis().x + Math.cos(a) * rad, y, s.axis().z + Math.sin(a) * rad);
    }

    /**
     * Потоки ци — широкие мягкие ленты, как на референсе (замечание автора 01.10: «полосы
     * тонкие, симметрия ненужная, цвета не те»). Две основные и одна тонкая, несимметрично:
     * выходят с одного бока снизу и закручиваются вверх вокруг головы; толще в середине,
     * тоньше к концам; белое ядро и переливающийся край (голубой → сиреневый → тёплый).
     */
    private static void drawSpirals(Scene s, float age) {
        float strength = Mth.clamp((age - 52.0F) / 40.0F, 0.0F, 1.0F) * (1.0F - Mth.clamp((age - 204.0F) / 30.0F, 0.0F, 1.0F));
        if (strength <= 0.0F) {
            return;
        }
        float peak = 1.0F + 0.5F * (float) Math.exp(-Math.pow((age - 136.0D) / 24.0D, 2.0D));
        Vec3 head = BoneAnchorLayer.position(s.player(), BoneAnchorLayer.Bone.HEAD);
        double feet = ground(s);
        double top = (head == null ? feet + 1.0D : head.y) + 1.1D;
        // Параметры лент: стартовый угол, радиус, ширина, сдвиг фазы, скорость; разные — без симметрии.
        // Одна большая S-лента через всё тело и три второстепенные на разной высоте и с разным
        // ходом (второе мнение codex 01.10: «не делать парные крылья»). Столбцы: угол старта,
        // радиус, ширина, фаза, скорость, доля высоты начала, доля высоты конца, закрутка.
        double[][] ribbons = {
            {-2.3D, 0.8D, 0.75D, 0.0D, 1.0D, 0.0D, 1.0D, 3.6D},
            {0.6D, 0.55D, 0.42D, 1.7D, 1.4D, 0.25D, 0.85D, -2.4D},
            {2.7D, 0.95D, 0.3D, 3.1D, 0.8D, 0.05D, 0.55D, 2.0D},
            {-0.9D, 0.45D, 0.22D, 4.4D, 1.7D, 0.5D, 1.15D, -3.0D}};
        // Ленты появляются по очереди и вырастают снизу вверх (автор 01.10: «не одновременно»).
        float[] starts = {52.0F, 74.0F, 96.0F, 118.0F};
        Vec3 side = s.facing().cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        double base = Math.atan2(side.z, side.x);
        VertexConsumer c = s.buffers().getBuffer(MurimRenderTypes.ribbon());
        for (int ri = 0; ri < ribbons.length; ri++) {
            double[] rb = ribbons[ri];
            float reveal = Mth.clamp((age - starts[ri]) / 26.0F, 0.0F, 1.0F);
            if (reveal <= 0.0F) {
                continue;
            }
            int n = 36;
            Vec3[] pts = new Vec3[n + 1];
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                double wob = 0.12D * Math.sin(age * 0.07D * rb[4] + rb[3] + u * 5.0D);
                double a = base + rb[0] + u * rb[7] + age * 0.012D * rb[4] + 0.4D * Math.sin(u * 3.0D + rb[3]);
                double rad = rb[1] * (1.0D - 0.35D * u) + wob;
                double h = Mth.lerp(u, rb[5], rb[6]);
                pts[i] = new Vec3(s.axis().x + Math.cos(a) * rad, feet + 0.1D + (top - feet) * h, s.axis().z + Math.sin(a) * rad);
            }
            // Боковой вектор считается в каждой точке по соседям и общий для стыкующихся
            // кусков: иначе на стыках щели и нахлёсты давали тёмную «гребёнку» по краю.
            Vec3[] sides = new Vec3[n + 1];
            for (int i = 0; i <= n; i++) {
                Vec3 tan = pts[Math.min(n, i + 1)].subtract(pts[Math.max(0, i - 1)]);
                Vec3 toCam = s.camera().subtract(pts[i]);
                Vec3 sd = tan.cross(toCam);
                sides[i] = sd.lengthSqr() < 1.0E-10D ? new Vec3(0.0D, 1.0D, 0.0D) : sd.normalize();
            }
            for (int i = 0; i < n; i++) {
                double u0 = i / (double) n, u1 = (i + 1) / (double) n;
                if (u0 > reveal) {
                    break;
                }
                double w0 = rb[2] * peak * Math.sin(Math.PI * Math.min(1.0D, u0 * 1.15D)), w1 = rb[2] * peak * Math.sin(Math.PI * Math.min(1.0D, u1 * 1.15D));
                float a0 = (float) Math.sin(Math.PI * u0) * strength, a1 = (float) Math.sin(Math.PI * u1) * strength;
                // Край — перелив по длине ленты; ядро — почти белое, уже вдвое.
                // Насыщенная радуга вдоль ленты (автор: «на референсе тоже радуга, просто она
                // цветастая, у нас тусклая»): широкий цветной слой дважды — для плотности
                // цвета — и узкое светлое ядро того же оттенка, а не чисто белое.
                VfxColour e0 = rainbow(u0 * 1.3D + rb[3] * 0.17D + age * 0.006D), e1 = rainbow(u1 * 1.3D + rb[3] * 0.17D + age * 0.006D);
                ribbonQuad(c, s, pts[i], pts[i + 1], sides[i], sides[i + 1], w0, w1, 0.55F * a0, 0.55F * a1, e0, e1);
                ribbonQuad(c, s, pts[i], pts[i + 1], sides[i], sides[i + 1], w0 * 0.6D, w1 * 0.6D, 0.75F * a0, 0.75F * a1, e0, e1);
                ribbonQuad(c, s, pts[i], pts[i + 1], sides[i], sides[i + 1], w0 * 0.18D, w1 * 0.18D, 0.8F * a0, 0.8F * a1, light(e0), light(e1));
            }
        }
        s.buffers().endBatch(MurimRenderTypes.ribbon());
    }    /** Радужные искры: мелкие яркие частицы поднимаются от тела и разлетаются наружу. */
    private static void drawRainbowSparks(Scene s, float age) {
        float strength = Mth.clamp((age - 56.0F) / 40.0F, 0.0F, 1.0F) * (1.0F - Mth.clamp((age - 205.0F) / 30.0F, 0.0F, 1.0F));
        if (strength <= 0.0F) {
            return;
        }
        double feet = ground(s);
        Vec3 head = BoneAnchorLayer.position(s.player(), BoneAnchorLayer.Bone.HEAD);
        double top = head == null ? feet + 1.2D : head.y + 0.2D;
        VertexConsumer c = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        for (int i = 0; i < 60; i++) {
            java.util.Random r = rng(233, i);
            float life = 30.0F + r.nextFloat() * 30.0F;
            float t = ((age + r.nextFloat() * life) % life) / life;
            double a = r.nextDouble() * Math.PI * 2.0D + age * 0.01D;
            double rad = 0.2D + r.nextDouble() * 0.3D + 0.9D * t;
            double y = feet + 0.2D + r.nextDouble() * (top - feet) + 0.8D * t;
            Vec3 at = new Vec3(s.axis().x + Math.cos(a) * rad, y, s.axis().z + Math.sin(a) * rad);
            VfxColour col = rainbow(r.nextDouble() + age * 0.01D);
            float fade = (float) Math.sin(t * Math.PI) * strength;
            VfxDraw.billboard(c, s.pose(), at, s.camera(), 0.05D, 0.8F * fade, col.red(), col.green(), col.blue());
            VfxDraw.billboard(c, s.pose(), at, s.camera(), 0.016D, fade, 1.0F, 1.0F, 1.0F);
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    private static VfxColour lerpC(VfxColour a, VfxColour b, float t) {
        return new VfxColour(Mth.lerp(t, a.red(), b.red()), Mth.lerp(t, a.green(), b.green()), Mth.lerp(t, a.blue(), b.blue()));
    }

    /** Нижний световой узел: три зоны у нижней части тела шириной с тело — слева бело-жёлтая, сзади голубая, справа зелёная. */
    private static void drawLowerNode(Scene s, float age, float strength) {
        Vec3 side = s.facing().cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        Vec3 low = new Vec3(s.axis().x, ground(s) + 0.45D, s.axis().z);
        float breath = 0.85F + 0.15F * Mth.sin(age * 0.2F);
        VertexConsumer c = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        Object[][] zones = {
            {low.add(side.scale(-0.45D)), hex(0xF2F89B), hex(0xFFFFF1)},
            {low.add(s.facing().scale(-0.35D)).add(0.0D, 0.15D, 0.0D), hex(0x77E9FF), hex(0xF0FFFF)},
            {low.add(side.scale(0.45D)).add(0.0D, -0.15D, 0.0D), hex(0x3AF39A), hex(0xD9FFE2)}};
        for (Object[] z : zones) {
            Vec3 at = (Vec3) z[0];
            VfxColour mid = (VfxColour) z[1], core = (VfxColour) z[2];
            VfxDraw.billboard(c, s.pose(), at, s.camera(), 0.75D, 0.2F * strength * breath, mid.red(), mid.green(), mid.blue());
            VfxDraw.billboard(c, s.pose(), at, s.camera(), 0.3D, 0.28F * strength * breath, core.red(), core.green(), core.blue());
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    /** Большая голубая дуга-ореол за спиной, почти замкнутая, диаметр ~1,8 высоты фигуры, край мягкий и неровный. */
    private static void drawHaloArc(Scene s, float age, float strength) {
        Vec3 side = s.facing().cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 centre = new Vec3(s.axis().x, ground(s) + 0.8D, s.axis().z).add(s.facing().scale(-0.6D));
        int n = 48;
        Vec3[] pts = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            // Неполная дуга с одной стороны (сверху-слева): полный обод читался как две
            // симметричные линии по бокам, а симметрию автор просил убрать.
            double a = Math.PI * 0.35D + Math.PI * 0.85D * i / n;
            double r = 1.35D + 0.08D * Math.sin(i * 0.7D + age * 0.04D);
            pts[i] = centre.add(side.scale(Math.cos(a) * r)).add(up.scale(Math.sin(a) * r * 0.95D));
        }
        Vec3[] sides = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            Vec3 tan = pts[Math.min(n, i + 1)].subtract(pts[Math.max(0, i - 1)]);
            Vec3 sd = tan.cross(s.camera().subtract(pts[i]));
            sides[i] = sd.lengthSqr() < 1.0E-10D ? up : sd.normalize();
        }
        VfxColour edge = hex(0x405CC4), mid = hex(0x72DFFF), core = hex(0xE9FFFF);
        VertexConsumer c = s.buffers().getBuffer(MurimRenderTypes.ribbon());
        for (int i = 0; i < n; i++) {
            float var = 0.6F + 0.4F * (float) Math.sin(i * 0.45D + age * 0.03D);
            float a = 0.32F * strength * var;
            ribbonQuad(c, s, pts[i], pts[i + 1], sides[i], sides[i + 1], 0.28D, 0.28D, 0.45F * a, 0.45F * a, edge, edge);
            ribbonQuad(c, s, pts[i], pts[i + 1], sides[i], sides[i + 1], 0.12D, 0.12D, a, a, mid, mid);
            ribbonQuad(c, s, pts[i], pts[i + 1], sides[i], sides[i + 1], 0.03D, 0.03D, 0.6F * a, 0.6F * a, core, core);
        }
        s.buffers().endBatch(MurimRenderTypes.ribbon());
    }

    /** Световые дуги и зелёно-голубая дымка у пола. */
    private static void drawFloorHaze(Scene s, float age, float strength) {
        double y = ground(s) + 0.05D;
        VertexConsumer c = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        for (int i = 0; i < 14; i++) {
            java.util.Random r = rng(251, i);
            double a = r.nextDouble() * Math.PI * 2.0D + age * 0.006D;
            double rad = 0.4D + r.nextDouble() * 0.9D;
            Vec3 at = new Vec3(s.axis().x + Math.cos(a) * rad, y + 0.1D, s.axis().z + Math.sin(a) * rad);
            VfxColour col = r.nextBoolean() ? hex(0x3AF39A) : hex(0x77E9FF);
            VfxDraw.billboard(c, s.pose(), at, s.camera(), 0.45D, 0.12F * strength, col.red(), col.green(), col.blue());
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
        drawGroundRing(s, new Vec3(s.axis().x, y, s.axis().z), 1.0D + 0.1D * Mth.sin(age * 0.05F), 0.05D, 0.35F * strength, hex(0x9AF3D0));
    }

    /** Сотни мелких искр вдоль лент: вверх и наружу, гуще у нижнего узла; белые → #C6F8F4 → #71AFD4, изредка розовые и жёлтые. */
    private static void drawStreamSparks(Scene s, float age, float strength, double feet, double top, double base) {
        VertexConsumer c = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        // 60% цветной пыли (второе мнение codex 01.10), остальное — белые и бирюзовые искры.
        VfxColour[] cols = {hex(0xFFFFFF), hex(0xC6F8F4), hex(0x71AFD4), hex(0xFF5ACD), hex(0x62F7FF), hex(0xF8E86B), hex(0x7DFF8A)};
        for (int i = 0; i < 220; i++) {
            java.util.Random r = rng(263, i);
            int k = r.nextInt(6);
            float life = 40.0F + r.nextFloat() * 40.0F;
            float t = ((age + r.nextFloat() * life) % life) / life;
            // Гуще у основания: доля высоты в квадрате.
            double u = Math.min(1.0D, Math.pow(r.nextDouble(), 1.8D) * 0.7D + t * 0.35D);
            Vec3 at = ribbonPoint(s, k, u, age, feet, top, base);
            Vec3 out = new Vec3(at.x - s.axis().x, 0.0D, at.z - s.axis().z);
            out = out.lengthSqr() < 1.0E-6D ? s.facing() : out.normalize();
            at = at.add(out.scale(0.3D * t + (r.nextDouble() - 0.5D) * 0.4D)).add(0.0D, (r.nextDouble() - 0.5D) * 0.3D, 0.0D);
            int ci = r.nextFloat() < 0.4F ? r.nextInt(3) : 3 + r.nextInt(4);
            VfxColour col = cols[ci];
            float fade = (float) Math.sin(t * Math.PI) * strength;
            float roll = r.nextFloat();
            double size = roll < 0.7F ? 0.035D : roll < 0.93F ? 0.08D : 0.16D;
            float alpha = roll < 0.7F ? 0.6F : roll < 0.93F ? 0.65F : 0.35F;
            VfxDraw.billboard(c, s.pose(), at, s.camera(), size, alpha * fade, col.red(), col.green(), col.blue());
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    /**
     * Радужное свечение от самого тела (автор 01.10): мягкий переливающийся ореол по силуэту —
     * плечи, голова, руки, колени, — пульсирует, отходит наружу, к кульминации ярче.
     */
    private static void drawBodyAura(Scene s, float age) {
        float strength = Mth.clamp((age - 60.0F) / 50.0F, 0.0F, 1.0F) * (1.0F - Mth.clamp((age - 200.0F) / 35.0F, 0.0F, 1.0F));
        if (strength <= 0.0F) {
            return;
        }
        float peak = 0.6F + 0.6F * (float) Math.exp(-Math.pow((age - 140.0D) / 30.0D, 2.0D));
        AbstractClientPlayer p = s.player();
        BoneAnchorLayer.Bone[] bones = {BoneAnchorLayer.Bone.HEAD, BoneAnchorLayer.Bone.CHEST, BoneAnchorLayer.Bone.RIGHT_SHOULDER,
            BoneAnchorLayer.Bone.LEFT_SHOULDER, BoneAnchorLayer.Bone.RIGHT_HAND, BoneAnchorLayer.Bone.LEFT_HAND,
            BoneAnchorLayer.Bone.RIGHT_KNEE, BoneAnchorLayer.Bone.LEFT_KNEE};
        VertexConsumer c = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        for (int b = 0; b < bones.length; b++) {
            Vec3 at = BoneAnchorLayer.position(p, bones[b]);
            if (at == null) {
                continue;
            }
            for (int k = 0; k < 3; k++) {
                java.util.Random r = rng(211, b * 7L + k);
                float cycle = ((age * (0.8F + 0.4F * r.nextFloat()) + r.nextFloat() * 50.0F) % 50.0F) / 50.0F;
                Vec3 out = at.subtract(s.axis().x, at.y, s.axis().z);
                out = new Vec3(out.x, 0.0D, out.z);
                out = out.lengthSqr() < 1.0E-6D ? s.facing() : out.normalize();
                Vec3 pos = at.add(out.scale(0.1D + 0.35D * cycle)).add(0.0D, 0.25D * cycle, 0.0D)
                        .add(s.facing().scale(-0.05D));
                VfxColour col = rainbow(b / 8.0D + k * 0.13D + age * 0.005D);
                float a = (float) Math.sin(cycle * Math.PI) * strength * peak;
                VfxDraw.billboard(c, s.pose(), pos, s.camera(), 0.32D + 0.28D * cycle, 0.4F * a, col.red(), col.green(), col.blue());
            }
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }
    /** Цвета финального взрыва по рангу: третий — голубые, второй — золото, первый — розовые. */
    private static VfxColour[] burstPalette(int rank) {
        return switch (rank) {
            case 2 -> new VfxColour[] {hex(0xFFD36B), hex(0xFFB43B), hex(0xFFF1B0), hex(0xFFAA28)};
            case 3 -> new VfxColour[] {hex(0xFF8FC8), hex(0xFFC0E1), hex(0xFFADD9), hex(0x83CFFF)};
            default -> new VfxColour[] {hex(0xA0EAFF), hex(0x448FFF), hex(0xD8FAFF), hex(0x56CFFF)};
        };
    }

    /** Радуга референса: розовый → красно-оранжевый → золотой → зелёный → бирюзовый → циан. */
    private static VfxColour rainbow(double t) {
        int[] hex = {0xFF5FB7, 0xFF5A2E, 0xFFD35A, 0x34F36F, 0x40F0C8, 0x55EAFF};
        double x = ((t % 1.0D) + 1.0D) % 1.0D * hex.length;
        int a = (int) Math.floor(x) % hex.length, b = (a + 1) % hex.length;
        float f = (float) (x - Math.floor(x));
        return new VfxColour(Mth.lerp(f, (hex[a] >> 16 & 255) / 255.0F, (hex[b] >> 16 & 255) / 255.0F),
                             Mth.lerp(f, (hex[a] >> 8 & 255) / 255.0F, (hex[b] >> 8 & 255) / 255.0F),
                             Mth.lerp(f, (hex[a] & 255) / 255.0F, (hex[b] & 255) / 255.0F));
    }

    private static VfxColour light(VfxColour c) {
        return new VfxColour(0.55F + 0.45F * c.red(), 0.55F + 0.45F * c.green(), 0.55F + 0.45F * c.blue());
    }

    /** Перелив края ленты: голубой → сиреневый → тёплый персиковый → голубой. */
    private static VfxColour shimmer(double t) {
        double x = ((t % 1.0D) + 1.0D) % 1.0D * 3.0D;
        VfxColour[] k = {new VfxColour(0.45F, 0.85F, 1.0F), new VfxColour(0.86F, 0.55F, 1.0F), new VfxColour(1.0F, 0.72F, 0.5F)};
        int a = (int) Math.floor(x) % 3;
        int b = (a + 1) % 3;
        float f = (float) (x - Math.floor(x));
        return new VfxColour(Mth.lerp(f, k[a].red(), k[b].red()), Mth.lerp(f, k[a].green(), k[b].green()), Mth.lerp(f, k[a].blue(), k[b].blue()));
    }

    /** Четырёхугольник ленты с разной шириной и цветом на концах, развёрнутый к камере. */
    private static void ribbonQuad(VertexConsumer c, Scene s, Vec3 a, Vec3 b, Vec3 sa, Vec3 sb, double wa, double wb,
                                   float alphaA, float alphaB, VfxColour ca, VfxColour cb) {
        Vec3 n = s.camera().subtract(a.add(b).scale(0.5D)).normalize();
        VfxDraw.vertex(c, s.pose(), a.subtract(sa.scale(wa)), n, 0.0F, 0.0F, alphaA, ca.red(), ca.green(), ca.blue());
        VfxDraw.vertex(c, s.pose(), b.subtract(sb.scale(wb)), n, 1.0F, 0.0F, alphaB, cb.red(), cb.green(), cb.blue());
        VfxDraw.vertex(c, s.pose(), b.add(sb.scale(wb)), n, 1.0F, 1.0F, alphaB, cb.red(), cb.green(), cb.blue());
        VfxDraw.vertex(c, s.pose(), a.add(sa.scale(wa)), n, 0.0F, 1.0F, alphaA, ca.red(), ca.green(), ca.blue());
    }


    /**
     * Генератор для i-го элемента эффекта. У java.util.Random соседние сиды дают почти
     * одинаковые первые числа — все пятна и капли ложились в одну кляксу (кадры Mac 01.10).
     * Сид перемешивается (SplitMix64), чтобы элементы расходились равномерно.
     */
    private static java.util.Random rng(long salt, long i) {
        long z = SEED + salt * 0x632BE59BD9B4E019L + i * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return new java.util.Random(z ^ (z >>> 31));
    }

    /** Точка на передней грани головы: лицо модели — на 0,26 блока от центра головы. */
    private static Vec3 face(Scene s) {
        Vec3 head = BoneAnchorLayer.position(s.player(), BoneAnchorLayer.Bone.HEAD);
        return head == null ? null : head.add(s.facing().scale(0.27D));
    }

    /**
     * Сеть трещин: тёмные линии (затемнение) с бело-голубым светом в швах. На голове гуще —
     * на референсах трещины прежде всего идут по лицу, свет бьёт из глаз.
     */
    private static void drawCrackNet(Scene s, float age, float strength, float climax) {
        if (strength <= 0.0F) {
            return;
        }
        Vec3[] anchors = skinAnchors(s);
        Vec3 faceAt = face(s);
        Vec3 side = s.facing().cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        int count = (int) (70 * strength);
        List<Vec3[]> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            java.util.Random r = rng(61, i);
            boolean onFace = faceAt != null && i % 3 == 0;
            Vec3 base = onFace ? faceAt : anchors[r.nextInt(anchors.length)];
            if (base == null) {
                continue;
            }
            double spread = onFace ? 0.24D : 0.26D;
            Vec3 prev = base.add(side.scale((r.nextDouble() - 0.5D) * spread)).add(up.scale((r.nextDouble() - 0.5D) * spread))
                    .add(s.facing().scale(0.012D));
            for (int k = 0; k < 4; k++) {
                Vec3 next = prev.add(side.scale((r.nextDouble() - 0.5D) * 0.1D)).add(up.scale((r.nextDouble() - 0.5D) * 0.1D));
                lines.add(new Vec3[] {prev, next});
                prev = next;
            }
        }
        VertexConsumer dark = s.buffers().getBuffer(MurimRenderTypes.impurity());
        for (Vec3[] l : lines) {
            VfxDraw.segment(dark, s.pose(), l[0], l[1], s.camera(), 0.016D, 0.85F * strength, 0.08F, 0.07F, 0.09F);
        }
        s.buffers().endBatch(MurimRenderTypes.impurity());
        float flicker = 0.55F + 0.45F * Mth.sin(age * 0.5F);
        float lit = strength * (0.35F + 0.25F * flicker + 0.6F * climax);
        VertexConsumer glow = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        for (Vec3[] l : lines) {
            VfxDraw.segment(glow, s.pose(), l[0], l[1], s.camera(), 0.006D + 0.006D * climax, lit,
                            CRACK_LIGHT.red(), CRACK_LIGHT.green(), CRACK_LIGHT.blue());
        }
        // Глаза: свет бьёт из глазниц, к кульминации — всё лицо бело-голубое.
        if (faceAt != null) {
            Vec3 eyes = faceAt.add(0.0D, 0.03D, 0.0D);
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                Vec3 eye = eyes.add(side.scale(sgn * 0.07D));
                VfxDraw.billboard(glow, s.pose(), eye, s.camera(), 0.05D + 0.08D * climax, strength * (0.6F + 0.4F * climax),
                                  CRACK_LIGHT.red(), CRACK_LIGHT.green(), CRACK_LIGHT.blue());
            }
            VfxDraw.billboard(glow, s.pose(), faceAt, s.camera(), 0.35D * climax, 0.55F * climax,
                              CRACK_BLUE.red(), CRACK_BLUE.green(), CRACK_BLUE.blue());
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    /** Цвет радуги по доле 0..1: красный → оранжевый → жёлтый → зелёный → голубой → фиолетовый. */
    private static VfxColour hue(double h) {
        double x = (h % 1.0D + 1.0D) % 1.0D * 6.0D;
        double f = x - Math.floor(x);
        float r, g, b;
        switch ((int) Math.floor(x)) {
            case 0 -> { r = 1.0F; g = (float) f; b = 0.0F; }
            case 1 -> { r = (float) (1.0D - f); g = 1.0F; b = 0.0F; }
            case 2 -> { r = 0.0F; g = 1.0F; b = (float) f; }
            case 3 -> { r = 0.0F; g = (float) (1.0D - f); b = 1.0F; }
            case 4 -> { r = (float) f; g = 0.0F; b = 1.0F; }
            default -> { r = 1.0F; g = 0.0F; b = (float) (1.0D - f); }
        }
        // Пастельнее: на референсах ленты светлые, с белым ядром.
        return new VfxColour(0.45F + 0.55F * r, 0.45F + 0.55F * g, 0.45F + 0.55F * b);
    }

    /**
     * Радужные потоки ци: ленты поднимаются от тела, извиваясь; холодная дуга у пола;
     * в кульминацию — вертикальный выброс сквозь тело.
     */
    private static void drawRainbow(Scene s, float age, float strength, float climax) {
        if (strength <= 0.0F) {
            return;
        }
        double feet = ground(s);
        Vec3 base = new Vec3(s.axis().x, feet, s.axis().z);
        VertexConsumer c = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        int ribbons = 7;
        for (int k = 0; k < ribbons; k++) {
            double a0 = k / (double) ribbons * Math.PI * 2.0D + age * 0.02D;
            VfxColour col = hue(k / (double) ribbons + age * 0.004D);
            Vec3 prev = null;
            for (int i = 0; i <= 14; i++) {
                double u = i / 14.0D;
                double rise = 0.1D + 3.2D * u * (0.6D + 0.4D * strength);
                double rad = (0.55D - 0.35D * u) + 0.08D * Math.sin(age * 0.12D + k + u * 6.0D);
                double a = a0 + u * 2.4D;
                Vec3 at = base.add(Math.cos(a) * rad, rise, Math.sin(a) * rad);
                if (prev != null) {
                    float fade = (float) (Math.sin(u * Math.PI)) * strength;
                    VfxDraw.segment(c, s.pose(), prev, at, s.camera(), 0.07D, 0.35F * fade, col.red(), col.green(), col.blue());
                    VfxDraw.segment(c, s.pose(), prev, at, s.camera(), 0.018D, 0.8F * fade, 1.0F, 1.0F, 1.0F);
                }
                prev = at;
            }
        }
        // Холодная дуга у пола.
        s.buffers().endBatch(MurimRenderTypes.impactCore());
        drawGroundRing(s, base.add(0.0D, 0.04D, 0.0D), 0.9D + 0.3D * climax, 0.18D, 0.55F * strength, CRACK_BLUE);
        // Вертикальный выброс сквозь тело.
        if (climax > 0.02F) {
            VertexConsumer p = s.buffers().getBuffer(MurimRenderTypes.impactCore());
            VfxDraw.segment(p, s.pose(), base, base.add(0.0D, 7.0D, 0.0D), s.camera(), 0.5D * climax, 0.6F * climax,
                            CRACK_BLUE.red(), CRACK_BLUE.green(), CRACK_BLUE.blue());
            VfxDraw.segment(p, s.pose(), base, base.add(0.0D, 7.0D, 0.0D), s.camera(), 0.15D * climax, climax, 1.0F, 1.0F, 1.0F);
            s.buffers().endBatch(MurimRenderTypes.impactCore());
        }
    }

    /** Чёрная кровь у рта: стекает по подбородку (Myst гл. 107). */
    private static void drawBlood(Scene s, float age, float strength) {
        Vec3 faceAt = face(s);
        if (strength <= 0.0F || faceAt == null) {
            return;
        }
        Vec3 mouth = faceAt.add(0.0D, -0.1D, 0.0D).add(s.facing().scale(0.01D));
        float run = Mth.clamp((age - 90.0F) / 50.0F, 0.0F, 1.0F);
        VertexConsumer dark = s.buffers().getBuffer(MurimRenderTypes.impurity());
        VfxDraw.segment(dark, s.pose(), mouth, mouth.add(0.0D, -0.22D * run, 0.0D), s.camera(), 0.02D, 0.95F * strength, 0.05F, 0.02F, 0.03F);
        VfxDraw.billboard(dark, s.pose(), mouth.add(0.0D, -0.22D * run, 0.0D), s.camera(), 0.025D, 0.9F * strength, 0.05F, 0.02F, 0.03F);
        s.buffers().endBatch(MurimRenderTypes.impurity());
    }

    /** Светящиеся трещины по коже: свет просачивается изнутри. */
    private static void drawCracks(Scene s, float age, float strength) {
        if (strength <= 0.0F) {
            return;
        }
        Vec3[] anchors = skinAnchors(s);
        Vec3 side = s.facing().cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        VertexConsumer c = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        int count = (int) (36 * strength);
        for (int i = 0; i < count; i++) {
            java.util.Random r = rng(53, i);
            Vec3 base = anchors[r.nextInt(anchors.length)];
            if (base == null) {
                continue;
            }
            Vec3 at = base.add(side.scale((r.nextDouble() - 0.5D) * 0.26D)).add(0.0D, (r.nextDouble() - 0.5D) * 0.24D, 0.0D)
                    .add(s.facing().scale(0.01D));
            Vec3 prev = at;
            for (int k = 0; k < 3; k++) {
                Vec3 next = prev.add(side.scale((r.nextDouble() - 0.5D) * 0.09D)).add(0.0D, (r.nextDouble() - 0.5D) * 0.09D, 0.0D);
                float flicker = 0.7F + 0.3F * Mth.sin(age * 0.6F + i);
                VfxDraw.segment(c, s.pose(), prev, next, s.camera(), 0.012D, strength * flicker, 1.0F, 0.97F, 0.86F);
                prev = next;
            }
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    /** Скорлупа: кожа трескается хлопьями, они отрываются, разлетаются и рассыпаются пылью. */
    private static void drawShell(Scene s, float age) {
        float start = 150.0F;
        if (age < start) {
            return;
        }
        // Два прохода: хлопья (тёмный слой) и швы (свет). Немедленный буфер закрывает первый
        // тип, когда запрашивают второй, и запись в оба сразу падала «Not building!».
        VertexConsumer flakes = s.buffers().getBuffer(MurimRenderTypes.impurity());
        shellPass(s, age, start, flakes, false);
        s.buffers().endBatch(MurimRenderTypes.impurity());
        VertexConsumer seams = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        shellPass(s, age, start, seams, true);
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    private static void shellPass(Scene s, float age, float start, VertexConsumer c, boolean light) {
        Vec3[] anchors = skinAnchors(s);
        Vec3 side = s.facing().cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        double ground = ground(s);
        for (int i = 0; i < 40; i++) {
            java.util.Random r = rng(71, i);
            Vec3 base = anchors[r.nextInt(anchors.length)];
            if (base == null) {
                continue;
            }
            Vec3 at0 = base.add(side.scale((r.nextDouble() - 0.5D) * 0.26D)).add(0.0D, (r.nextDouble() - 0.5D) * 0.22D, 0.0D);
            float t = (age - start - r.nextFloat() * 40.0F) / 45.0F;
            if (t < 0.0F || t > 1.0F) {
                continue;
            }
            Vec3 out = s.facing().add(side.scale((r.nextDouble() - 0.5D) * 1.6D)).normalize();
            Vec3 at = at0.add(out.scale(0.7D * t)).add(0.0D, 0.25D * t - 0.9D * t * t, 0.0D);
            if (at.y < ground) {
                at = new Vec3(at.x, ground + 0.02D, at.z);
            }
            double size = (0.05D + r.nextDouble() * 0.04D) * (1.0D - 0.7D * t);
            if (light) {
                VfxDraw.billboard(c, s.pose(), at, s.camera(), size * 0.6D, 0.6F * (1.0F - t), 1.0F, 0.9F, 0.65F);
            } else {
                VfxDraw.billboard(c, s.pose(), at, s.camera(), size, 0.9F * (1.0F - t * t), 0.43F, 0.35F, 0.29F);
                for (int d = 0; d < 3; d++) {
                    Vec3 dust = at.add((r.nextDouble() - 0.5D) * 0.3D * t, -0.1D * t * d, (r.nextDouble() - 0.5D) * 0.3D * t);
                    VfxDraw.billboard(c, s.pose(), dust, s.camera(), 0.015D, 0.6F * t * (1.0F - t), 0.55F, 0.48F, 0.42F);
                }
            }
        }
    }


    /**
     * Светящийся каркас: позвоночник, ключицы, руки, ноги, рёбра и череп. Кости лежат
     * внутри тела, поэтому рисуются слоем без проверки глубины — как рентген сквозь кожу.
     * Свет идёт от таза наружу; каждые полторы секунды каркас вспыхивает трещиной:
     * «кости ломаются и срастаются».
     */
    private static void drawSkeleton(Scene s, float age, float strength) {
        if (strength <= 0.0F) {
            return;
        }
        AbstractClientPlayer p = s.player();
        Vec3 pelvis = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.DANTIAN);
        Vec3 chest = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.CHEST);
        Vec3 head = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.HEAD);
        Vec3 rs = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.RIGHT_SHOULDER);
        Vec3 ls = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.LEFT_SHOULDER);
        Vec3 rh = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.RIGHT_HAND);
        Vec3 lh = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.LEFT_HAND);
        Vec3 rk = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.RIGHT_KNEE);
        Vec3 lk = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.LEFT_KNEE);
        Vec3 rf = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.RIGHT_FOOT);
        Vec3 lf = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.LEFT_FOOT);
        if (pelvis == null || chest == null) {
            return;
        }
        // Крестец — на оси тела и НИЖЕ пупка, у сиденья: кость даньтяня лежит на животе, и
        // ноги, выросшие из неё, шли палками от пупка к коленям через скрещённые ноги
        // (замечание автора 01.10: «всё, что ниже рёбер, — бред»).
        Vec3 hip = new Vec3(chest.x, pelvis.y - 0.2D, chest.z);
        Vec3 side = s.facing().cross(new Vec3(0.0D, 1.0D, 0.0D));
        side = side.lengthSqr() > 1.0E-6D ? side.normalize() : new Vec3(1.0D, 0.0D, 0.0D);
        // Тазобедренные суставы по бокам крестца; каждое колено берёт ближайший сустав,
        // а не общий центр, — так бедро идёт от своего бока.
        Vec3 hipA = hip.add(side.scale(0.13D)).add(0.0D, 0.03D, 0.0D);
        Vec3 hipB = hip.add(side.scale(-0.13D)).add(0.0D, 0.03D, 0.0D);
        Vec3 rHip = rk == null || rk.distanceToSqr(hipA) <= rk.distanceToSqr(hipB) ? hipA : hipB;
        Vec3 lHip = rHip == hipA ? hipB : hipA;
        // Позвоночник кончается шеей: кость головы стоит в центре головы, и линия до неё
        // шла прямо через лицо (кадры стенда 01.10).
        Vec3 neck = head != null ? chest.lerp(head, 0.12D) : chest.add(0.0D, 0.12D, 0.0D);
        List<Vec3[]> segments = new ArrayList<>();
        segments.add(new Vec3[] {hip, neck});
        // Таз: крестец к суставам и гребни подвздошных костей — дуга вверх и в стороны.
        segments.add(new Vec3[] {hip, hipA});
        segments.add(new Vec3[] {hip, hipB});
        for (Vec3 joint : new Vec3[] {hipA, hipB}) {
            Vec3 out = joint.subtract(hip).normalize();
            Vec3 crestMid = joint.add(out.scale(0.05D)).add(0.0D, 0.09D, 0.0D);
            Vec3 crestTop = hip.add(out.scale(0.08D)).add(0.0D, 0.16D, 0.0D);
            segments.add(new Vec3[] {joint, crestMid});
            segments.add(new Vec3[] {crestMid, crestTop});
        }
        for (Vec3[] limb : new Vec3[][] {{chest, rs, rh}, {chest, ls, lh}, {rHip, rk, rf}, {lHip, lk, lf}}) {
            for (int i = 0; i + 1 < limb.length; i++) {
                if (limb[i] != null && limb[i + 1] != null) {
                    segments.add(new Vec3[] {limb[i], limb[i + 1]});
                }
            }
        }
        // Рёбра: четыре пары ДУГ от грудины вбок и назад — дуга читается как ребро,
        // прямой отрезок — как прожилка (второе мнение по кадрам 01.10).
        {
            for (int i = 0; i < 4; i++) {
                Vec3 sternum = chest.add(0.0D, -0.04D - 0.065D * i, 0.0D).add(s.facing().scale(0.06D));
                double reach = 0.19D - 0.012D * i;
                for (int sign = -1; sign <= 1; sign += 2) {
                    Vec3 prev = sternum;
                    for (int k = 1; k <= 4; k++) {
                        double a = k / 4.0D * Math.PI * 0.5D;
                        Vec3 next = sternum.add(side.scale(sign * reach * Math.sin(a)))
                                .add(s.facing().scale(-0.12D * (1.0D - Math.cos(a))))
                                .add(0.0D, -0.035D * k / 4.0D, 0.0D);
                        segments.add(new Vec3[] {prev, next});
                        prev = next;
                    }
                }
            }
        }

        // Трещина: короткая яркая вспышка каждые 30 тиков, затухающая за 8.
        float crack = (float) Math.exp(-(age % 30.0F) / 4.0F);
        float lit = strength * (0.75F + 0.25F * crack);
        VertexConsumer bone = s.buffers().getBuffer(MurimRenderTypes.bodyGlow());
        for (Vec3[] seg : segments) {
            VfxDraw.segment(bone, s.pose(), seg[0], seg[1], s.camera(), 0.075D, 0.4F * lit,
                            BONE.red(), BONE.green(), BONE.blue());
            VfxDraw.segment(bone, s.pose(), seg[0], seg[1], s.camera(), 0.03D, 0.95F * lit,
                            BONE_CORE.red(), BONE_CORE.green(), BONE_CORE.blue());
        }
        // Позвонки — цепочка узлов по позвоночнику: вертикальная ось читается костью, а не лучом.
        for (int i = 0; i <= 9; i++) {
            Vec3 v = hip.lerp(neck, i / 9.0D);
            VfxDraw.billboard(bone, s.pose(), v, s.camera(), 0.045D, 0.85F * lit,
                              BONE_CORE.red(), BONE_CORE.green(), BONE_CORE.blue());
        }
        // Суставы и череп — светлые узлы.
        for (Vec3 joint : new Vec3[] {hipA, hipB, chest, rs, ls, rk, lk}) {
            if (joint != null) {
                VfxDraw.billboard(bone, s.pose(), joint, s.camera(), 0.05D, 0.8F * lit,
                                  BONE_CORE.red(), BONE_CORE.green(), BONE_CORE.blue());
            }
        }
        if (head != null) {
            VfxDraw.billboard(bone, s.pose(), head.add(0.0D, 0.08D, 0.0D), s.camera(), 0.13D, 0.5F * lit,
                              BONE.red(), BONE.green(), BONE.blue());
        }
        s.buffers().endBatch(MurimRenderTypes.bodyGlow());
    }

    /**
     * Примеси: тёмные сгустки выступают из тела, отрываются и падают, оставляя пятна у ног.
     * Положения считаются из номера сгустка, а не хранятся: сцена воспроизводима кадр в кадр.
     */
    private static void drawImpurities(Scene s, float age, float strength) {
        if (strength <= 0.0F) {
            return;
        }
        AbstractClientPlayer p = s.player();
        Vec3 chest = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.CHEST);
        Vec3[] anchors = {
            chest, s.core(),
            BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.RIGHT_SHOULDER),
            BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.LEFT_SHOULDER),
            BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.RIGHT_HAND),
            BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.LEFT_HAND),
            BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.RIGHT_KNEE),
            BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.LEFT_KNEE)};
        double ground = Mth.lerp(s.minecraft().getTimer().getGameTimeDeltaPartialTick(false), p.yOld, p.getY()) + 0.02D;
        VertexConsumer dark = s.buffers().getBuffer(MurimRenderTypes.impurity());
        int count = 26;
        for (int i = 0; i < count; i++) {
            java.util.Random r = rng(31, i);
            Vec3 from = anchors[r.nextInt(anchors.length)];
            if (from == null) {
                continue;
            }
            float born = 70.0F + r.nextFloat() * 120.0F;
            float t = (age - born) / 40.0F;
            if (t < 0.0F) {
                continue;
            }
            // Наружу от оси тела — сгусток выступает из кожи, а не висит внутри.
            Vec3 out = new Vec3(from.x - s.axis().x, 0.0D, from.z - s.axis().z);
            out = out.lengthSqr() > 1.0E-6D ? out.normalize() : s.facing();
            out = out.add((r.nextDouble() - 0.5D) * 0.6D, 0.0D, (r.nextDouble() - 0.5D) * 0.6D).normalize();
            double size = 0.05D + r.nextDouble() * 0.05D;
            float a = 0.85F * strength;
            if (t < 1.0F) {
                // Выступает на 0,15 блока и падает с ускорением.
                double push = 0.15D * Math.min(1.0D, t * 3.0D);
                double fall = Math.max(0.0D, t - 0.3D);
                Vec3 at = from.add(out.scale(0.12D + push)).add(0.0D, -1.4D * fall * fall, 0.0D);
                if (at.y < ground) {
                    at = new Vec3(at.x, ground + size, at.z);
                }
                VfxDraw.billboard(dark, s.pose(), at, s.camera(), size * (1.0D + 0.6D * t), a,
                                  0.07F, 0.04F, 0.06F);
            }
            // Пятно у ног: появляется при падении и держится до конца сцены.
            if (t > 0.6F) {
                Vec3 spot = new Vec3(from.x + out.x * 0.35D, ground, from.z + out.z * 0.35D);
                double rad = size * 1.8D * Math.min(1.0D, (t - 0.6D) * 2.5D);
                flatSpot(dark, s.pose(), spot, rad, 0.7F * strength);
            }
        }
        s.buffers().endBatch(MurimRenderTypes.impurity());
    }

    /** Горизонтальное пятно на земле. */
    private static void flatSpot(VertexConsumer c, PoseStack.Pose pose, Vec3 at, double r, float alpha) {
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, pose, at.add(-r, 0.0D, -r), up, 0.0F, 0.0F, alpha, 0.06F, 0.04F, 0.05F);
        VfxDraw.vertex(c, pose, at.add(-r, 0.0D, r), up, 0.0F, 1.0F, alpha, 0.06F, 0.04F, 0.05F);
        VfxDraw.vertex(c, pose, at.add(r, 0.0D, r), up, 1.0F, 1.0F, alpha, 0.06F, 0.04F, 0.05F);
        VfxDraw.vertex(c, pose, at.add(r, 0.0D, -r), up, 1.0F, 0.0F, alpha, 0.06F, 0.04F, 0.05F);
    }

    /**
     * Выход ауры после прорыва: вспышка из центра, кольцо по земле и столб света.
     * Тот же язык, что у давления сильного противника (§3д), — только наружу от себя.
     */
    private static void rankUp(Scene s, float age) {
        VfxColour[] pal = palette(ClientMeditationState.rankUpRank());
        VfxColour AURA = pal[1];
        float k = Mth.clamp(age / ClientMeditationState.RANK_UP_TICKS, 0.0F, 1.0F);
        float fade = (1.0F - k) * (1.0F - k);
        VertexConsumer glow = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        boolean peakRank = ClientMeditationState.rankUpRank() == io.github.verycooltimo.murim.cultivation.Realm.PEAK;
        // Взрыв в палитре сцены (урок Пика): цветные частицы разлетаются во все стороны.
        if (!peakRank && age < 36.0F) {
            float life = 1.0F - age / 36.0F;
            VfxColour[] cols = burstPalette(ClientMeditationState.rankUpRank());
            for (int i = 0; i < 110; i++) {
                java.util.Random r = rng(283, i);
                Vec3 dir = new Vec3(r.nextDouble() - 0.5D, r.nextDouble() * 0.9D - 0.2D, r.nextDouble() - 0.5D).normalize();
                double dist = (0.3D + r.nextDouble() * 2.8D) * (1.0D - Math.pow(1.0D - Math.min(1.0D, age / 22.0D), 3.0D));
                Vec3 at = s.core().add(dir.scale(dist)).add(0.0D, -0.35D * Math.pow(age / 36.0D, 2.0D), 0.0D);
                VfxColour col = cols[r.nextInt(cols.length)];
                double size = r.nextFloat() < 0.8F ? 0.045D : 0.1D;
                VfxDraw.billboard(glow, s.pose(), at, s.camera(), size, 0.85F * life, col.red(), col.green(), col.blue());
                VfxDraw.billboard(glow, s.pose(), at, s.camera(), size * 0.3D, life, 1.0F, 1.0F, 1.0F);
            }
        }
        // Пик: взрыв теми же радужными частицами, что и в сцене (замечание автора 01.10).
        if (peakRank && age < 40.0F) {
            float life = 1.0F - age / 40.0F;
            for (int i = 0; i < 140; i++) {
                java.util.Random r = rng(277, i);
                Vec3 dir = new Vec3(r.nextDouble() - 0.5D, r.nextDouble() * 0.9D - 0.2D, r.nextDouble() - 0.5D).normalize();
                double dist = (0.3D + r.nextDouble() * 3.2D) * (1.0D - Math.pow(1.0D - Math.min(1.0D, age / 25.0D), 3.0D));
                Vec3 at = s.core().add(dir.scale(dist)).add(0.0D, -0.4D * Math.pow(age / 40.0D, 2.0D), 0.0D);
                VfxColour col = rainbow(r.nextDouble() + age * 0.01D);
                double size = r.nextFloat() < 0.8F ? 0.05D : 0.11D;
                VfxDraw.billboard(glow, s.pose(), at, s.camera(), size, 0.85F * life, col.red(), col.green(), col.blue());
                VfxDraw.billboard(glow, s.pose(), at, s.camera(), size * 0.3D, life, 1.0F, 1.0F, 1.0F);
            }
        }
        // Пересвет силуэта в первые тики — пик выброса.
        float peak = Mth.clamp(1.0F - age / 8.0F, 0.0F, 1.0F);
        VfxDraw.billboard(glow, s.pose(), s.core().add(0.0D, 0.3D, 0.0D), s.camera(), 0.8D * peak + 0.01D,
                          0.55F * peak, 0.85F, 0.95F, 1.0F);
        CoreGlow.draw(glow, s.pose(), s.core(), s.camera(), age, 0.35D * fade + 0.06D, fade, AURA, CORE);
        // Столб: от ног вверх, быстро гаснет.
        AbstractClientPlayer p = s.player();
        double feet = Mth.lerp(s.minecraft().getTimer().getGameTimeDeltaPartialTick(false), p.yOld, p.getY());
        Vec3 base = new Vec3(s.axis().x, feet, s.axis().z);
        float pillar = Mth.clamp(1.0F - age / 18.0F, 0.0F, 1.0F);
        // Столб — через тело: от земли сквозь позвоночник и макушку вверх, канал прорыва.
        VfxDraw.segment(glow, s.pose(), base, base.add(0.0D, 6.0D, 0.0D), s.camera(), 0.45D * pillar + 0.05D,
                        0.8F * pillar, AURA.red(), AURA.green(), AURA.blue());
        VfxDraw.segment(glow, s.pose(), base, base.add(0.0D, 6.0D, 0.0D), s.camera(), 0.12D * pillar + 0.02D,
                        pillar, CORE.red(), CORE.green(), CORE.blue());
        s.buffers().endBatch(MurimRenderTypes.impactCore());
        // Кольцо по земле расходится на четыре блока.
        // Ударная волна: за полсекунды до семи блоков — резко, а не спокойным кругом.
        double radius = 0.4D + 6.6D * Math.min(1.0D, Math.sqrt(age / 10.0D));
        drawGroundRing(s, base.add(0.0D, 0.05D, 0.0D), radius, 0.12D + 0.2D * k, 0.8F * fade, AURA);
    }

    private static void drawGroundRing(Scene s, Vec3 centre, double radius, double width, float alpha, VfxColour AURA) {
        if (alpha <= 0.0F) {
            return;
        }
        VertexConsumer c = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        int n = 48;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2.0D * i / n;
            double a1 = Math.PI * 2.0D * (i + 1) / n;
            Vec3 i0 = centre.add(Math.cos(a0) * (radius - width), 0.0D, Math.sin(a0) * (radius - width));
            Vec3 o0 = centre.add(Math.cos(a0) * radius, 0.0D, Math.sin(a0) * radius);
            Vec3 o1 = centre.add(Math.cos(a1) * radius, 0.0D, Math.sin(a1) * radius);
            Vec3 i1 = centre.add(Math.cos(a1) * (radius - width), 0.0D, Math.sin(a1) * (radius - width));
            VfxDraw.vertex(c, s.pose(), i0, up, 0.5F, 0.5F, alpha * 0.2F, AURA.red(), AURA.green(), AURA.blue());
            VfxDraw.vertex(c, s.pose(), o0, up, 0.5F, 0.0F, alpha, AURA.red(), AURA.green(), AURA.blue());
            VfxDraw.vertex(c, s.pose(), o1, up, 0.5F, 0.0F, alpha, AURA.red(), AURA.green(), AURA.blue());
            VfxDraw.vertex(c, s.pose(), i1, up, 0.5F, 0.5F, alpha * 0.2F, AURA.red(), AURA.green(), AURA.blue());
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    // ------------------------------------------------------------------ после семени

    /** Медитация с семенем: ровное дыхание ядра и медленный приток искр. */
    private static void seededBreath(Scene s, float t) {
        float breath = 0.5F + 0.5F * Mth.sin(t * 0.08F);
        float rise = Mth.clamp(t / 40.0F, 0.0F, 1.0F);
        VertexConsumer glow = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        CoreGlow.draw(glow, s.pose(), s.core(), s.camera(), t, 0.05D + 0.015D * breath,
                      (0.4F + 0.2F * breath) * rise, DEEP, CORE);
        BillboardBurst.inward(glow, s.pose(), s.core(), s.camera(), 10, t * 0.6F, 0.5D,
                              0.35F * rise, HALO, CORE);
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    /** Послесвечение законченной сессии: осевшее кольцо или рассыпавшаяся ци. */
    private static void afterSession(Scene s, ClientMeditationState.Aftermath kind, int beats, float age) {
        float k = Mth.clamp(age / ClientMeditationState.AFTERMATH_TICKS, 0.0F, 1.0F);
        VertexConsumer glow = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        if (kind == ClientMeditationState.Aftermath.BACKLASH) {
            // Искажение ци: кольцо лопается красными осколками.
            float b = Mth.clamp(age / ClientMeditationState.BACKLASH_TICKS, 0.0F, 1.0F);
            BillboardBurst.outward(glow, s.pose(), s.axis(), s.camera(), 40, age, 0.9D,
                                   1.0F - b, STRAIN, CORE);
            CoreGlow.draw(glow, s.pose(), s.core(), s.camera(), age, 0.12D,
                          (1.0F - b) * (1.0F - b), STRAIN, CORE);
        } else if (kind == ClientMeditationState.Aftermath.SETTLE) {
            s.buffers().endBatch(MurimRenderTypes.impactCore());
            drawRing(s, age, 0.32D - 0.24D * k, 0.03F, 0.85F * (1.0F - k), 0.0F, HALO, 1.0F);
            glow = s.buffers().getBuffer(MurimRenderTypes.impactCore());
            CoreGlow.draw(glow, s.pose(), s.core(), s.camera(), age, 0.06D, 0.7F * (1.0F - k), DEEP, CORE);
        } else {
            // Тепло первого такта и не удержанное кольцо разлетаются одинаково.
            float strength = beats == 0 ? 0.8F : 0.6F;
            BillboardBurst.outward(glow, s.pose(), s.core(), s.camera(), 24, age, 0.55D,
                                   strength * (1.0F - k), beats == 0 ? HALO : FRAYED, CORE);
            CoreGlow.draw(glow, s.pose(), s.core(), s.camera(), age, 0.05D,
                          0.5F * (1.0F - k) * (1.0F - k), DEEP, CORE);
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    // ------------------------------------------------------------------ потоки к семени

    /**
     * Сгустки, бегущие по телу В СЕМЯ: от кистей, колен и головы через плечи и грудь.
     *
     * <p>Второе мнение по кадрам: жилы читались «случайными молниями на одежде», а по
     * референсу смысл в том, что ци тела стекается в одну точку. Направление видно
     * только в движении, поэтому потоку нужны бегущие сгустки, разгорающиеся у пупка.
     */
    private static void drawStreams(Scene s, float t, float strength) {
        drawStreams(s, t, strength, HALO, CORE);
    }

    private static void drawStreams(Scene s, float t, float strength, VfxColour halo, VfxColour coreColour) {
        if (strength <= 0.0F) {
            return;
        }
        AbstractClientPlayer p = s.player();
        Vec3 chest = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.CHEST);
        Vec3 head = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.HEAD);
        Vec3 rs = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.RIGHT_SHOULDER);
        Vec3 ls = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.LEFT_SHOULDER);
        Vec3 rh = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.RIGHT_HAND);
        Vec3 lh = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.LEFT_HAND);
        Vec3 rk = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.RIGHT_KNEE);
        Vec3 lk = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.LEFT_KNEE);
        Vec3 core = s.core();
        // Кости лежат на оси частей тела — внутри модели, и потоки по ним закрывал корпус.
        // Выносим точки на переднюю поверхность: торс толщиной ~0.25, конечности ~0.25.
        Vec3 skin = s.facing().scale(0.17D);
        chest = chest == null ? null : chest.add(skin);
        head = head == null ? null : head.add(s.facing().scale(0.27D));
        rs = rs == null ? null : rs.add(skin);
        ls = ls == null ? null : ls.add(skin);
        rh = rh == null ? null : rh.add(skin);
        lh = lh == null ? null : lh.add(skin);
        rk = rk == null ? null : rk.add(skin);
        lk = lk == null ? null : lk.add(skin);
        List<Vec3[]> paths = new ArrayList<>();
        if (rh != null && rs != null && chest != null) paths.add(new Vec3[] {rh, rs, chest, core});
        if (lh != null && ls != null && chest != null) paths.add(new Vec3[] {lh, ls, chest, core});
        if (head != null && chest != null) paths.add(new Vec3[] {head, chest, core});
        if (rk != null) paths.add(new Vec3[] {rk, core});
        if (lk != null) paths.add(new Vec3[] {lk, core});

        VertexConsumer glow = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        int perPath = 4;
        for (int pi = 0; pi < paths.size(); pi++) {
            Vec3[] path = paths.get(pi);
            for (int m = 0; m < perPath; m++) {
                float u = ((t * 0.018F) + m / (float) perPath + pi * 0.13F) % 1.0F;
                Vec3 at = along(path, u);
                // Разгорается к семени: у кисти еле видно, у пупка ярко.
                float a = strength * (0.25F + 0.75F * u * u);
                VfxDraw.billboard(glow, s.pose(), at, s.camera(), 0.03D + 0.02D * u, a,
                                  coreColour.red(), coreColour.green(), coreColour.blue());
                VfxDraw.billboard(glow, s.pose(), at, s.camera(), 0.08D, a * 0.3F,
                                  halo.red(), halo.green(), halo.blue());
            }
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    /** Точка на ломаной по доле длины. */
    private static Vec3 along(Vec3[] path, float u) {
        double total = 0.0D;
        for (int i = 0; i + 1 < path.length; i++) {
            total += path[i].distanceTo(path[i + 1]);
        }
        double left = total * Mth.clamp(u, 0.0F, 1.0F);
        for (int i = 0; i + 1 < path.length; i++) {
            double len = path[i].distanceTo(path[i + 1]);
            if (left <= len || i + 2 == path.length) {
                return path[i].lerp(path[i + 1], len < 1.0E-6D ? 0.0D : Mth.clamp(left / len, 0.0D, 1.0D));
            }
            left -= len;
        }
        return path[path.length - 1];
    }

    // ------------------------------------------------------------------ жилы

    /**
     * Жилы от конечностей и головы к средоточию, как на референсе: плотнее у живота.
     *
     * @param reach доля пути, пройденная светом от средоточия наружу
     */
    private static void drawVeins(Scene s, float reach, float alpha) {
        drawVeins(s, reach, alpha, 0.006D, HALO, CORE);
    }

    /**
     * @param width полуширина жилы: очищение меридиан показывается их расширением
     *              («из тонкого ручья — в реку», Хуашань гл. 42)
     */
    private static void drawVeins(Scene s, float reach, float alpha, double width, VfxColour halo, VfxColour coreColour) {
        if (reach <= 0.0F || alpha <= 0.0F) {
            return;
        }
        AbstractClientPlayer p = s.player();
        Vec3 chest = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.CHEST);
        Vec3 head = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.HEAD);
        Vec3 rs = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.RIGHT_SHOULDER);
        Vec3 ls = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.LEFT_SHOULDER);
        Vec3 rh = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.RIGHT_HAND);
        Vec3 lh = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.LEFT_HAND);
        Vec3 rk = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.RIGHT_KNEE);
        Vec3 lk = BoneAnchorLayer.position(p, BoneAnchorLayer.Bone.LEFT_KNEE);
        Vec3 core = s.core();

        // Глубина — НАД кожей, а не на ней. Полутолщина торса и конечностей модели 0.125:
        // прежние 0.05 у рук и ног клали линии внутрь конечности, 0.12 у торса — вровень
        // с поверхностью, и на живой игре тело их закрывало (замечание автора 30.09).
        // На стенде этого не было видно: там слой рисуется поверх тела без глубины.
        List<BodyMeridians.Part> parts = new ArrayList<>();
        parts.add(new BodyMeridians.Part(core, chest, 0.2D, SKIN_TORSO, 5));
        parts.add(new BodyMeridians.Part(chest, head, 0.08D, SKIN_HEAD, 2));
        parts.add(new BodyMeridians.Part(chest, rs, 0.06D, SKIN_TORSO, 3));
        parts.add(new BodyMeridians.Part(chest, ls, 0.06D, SKIN_TORSO, 3));
        parts.add(new BodyMeridians.Part(rs, rh, 0.05D, SKIN_LIMB, 4));
        parts.add(new BodyMeridians.Part(ls, lh, 0.05D, SKIN_LIMB, 4));
        parts.add(new BodyMeridians.Part(core, rk, 0.05D, SKIN_LIMB, 2));
        parts.add(new BodyMeridians.Part(core, lk, 0.05D, SKIN_LIMB, 2));

        double lowest = core.y - 0.3D;
        double highest = head == null ? core.y + 1.0D : head.y + 0.2D;
        // MURIM_VEIN_DEPTH=1 — отладка: жилы С проверкой глубины, чтобы на стенде увидеть,
        // закрывает ли их тело (у автора на живой игре закрывало, на стенде — нет).
        RenderType veinType = DEPTH_DEBUG ? MurimRenderTypes.mote() : MurimRenderTypes.bodyGlow();
        VertexConsumer channel = s.buffers().getBuffer(veinType);
        BodyMeridians.draw(channel, s.pose(), s.camera(), parts, lowest, highest, reach,
                           width, alpha, SEED, halo, coreColour, s.facing());
        s.buffers().endBatch(veinType);
    }

    private MeditationVfxRenderer() {
    }
}
