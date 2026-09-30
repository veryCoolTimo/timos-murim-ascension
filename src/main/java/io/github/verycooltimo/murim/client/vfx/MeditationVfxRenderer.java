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
        ClientMeditationState.Aftermath aftermath = ClientMeditationState.aftermath();
        if (!state.active() && sceneAge < 0 && aftermath == ClientMeditationState.Aftermath.NONE) {
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
            if (sceneAge >= 0) {
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
        float veins = age < 20.0F ? age / 20.0F : Mth.clamp(1.0F - (age - 120.0F) / 100.0F, 0.0F, 1.0F);
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
                                  CORE.red(), CORE.green(), CORE.blue());
                VfxDraw.billboard(glow, s.pose(), at, s.camera(), 0.08D, a * 0.3F,
                                  HALO.red(), HALO.green(), HALO.blue());
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

        List<BodyMeridians.Part> parts = new ArrayList<>();
        parts.add(new BodyMeridians.Part(core, chest, 0.2D, 0.12D, 5));
        parts.add(new BodyMeridians.Part(chest, head, 0.08D, 0.08D, 2));
        parts.add(new BodyMeridians.Part(chest, rs, 0.06D, 0.06D, 3));
        parts.add(new BodyMeridians.Part(chest, ls, 0.06D, 0.06D, 3));
        parts.add(new BodyMeridians.Part(rs, rh, 0.05D, 0.05D, 4));
        parts.add(new BodyMeridians.Part(ls, lh, 0.05D, 0.05D, 4));
        parts.add(new BodyMeridians.Part(core, rk, 0.05D, 0.05D, 2));
        parts.add(new BodyMeridians.Part(core, lk, 0.05D, 0.05D, 2));

        double lowest = core.y - 0.3D;
        double highest = head == null ? core.y + 1.0D : head.y + 0.2D;
        VertexConsumer channel = s.buffers().getBuffer(MurimRenderTypes.bodyGlow());
        BodyMeridians.draw(channel, s.pose(), s.camera(), parts, lowest, highest, reach,
                           0.006D, alpha, SEED, HALO, CORE, s.facing());
        s.buffers().endBatch(MurimRenderTypes.bodyGlow());
    }

    private MeditationVfxRenderer() {
    }
}
