package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
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

    private static final long SEED = 0x5EEDDA17L;

    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if(io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.benchActive())return;
        // The body game owns this surface, including its quiet finale and torn feedback (ADR 0022).
        if (io.github.verycooltimo.murim.client.flow.FlowClient.active()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        SyncMeditationPayload state = ClientMeditationState.state();
        int sceneAge = ClientMeditationState.seedSceneAge();
        int rankUpAge = ClientMeditationState.rankUpAge();
        ClientMeditationState.Aftermath aftermath = ClientMeditationState.aftermath();
        int stainAge = ClientMeditationState.stainAge();
        if (stainAge >= 0 && ClientMeditationState.stainOrigin() != null
                && rankUpAge < 0 && ClientMeditationState.breakthroughAge() < 0) {
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
            boolean creation = breakAge < 0 && rankUpAge < 0 && warnAge < 0
                    && (sceneAge >= 0 || state.beats() < 3);
            if (creation) {
                var body = io.github.verycooltimo.murim.client.vfx.bt.BtBody.of(player, cameraPos);
                if (body != null) {
                    // API: /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/client/event/
                    // RenderLevelStageEvent.java#getModelViewMatrix/getProjectionMatrix.
                    if(sceneAge>=0)io.github.verycooltimo.murim.client.vfx.bt.BtImpactFrame.capture(
                            body,cameraPos,event.getModelViewMatrix(),event.getProjectionMatrix());
                    var ctx = new io.github.verycooltimo.murim.client.vfx.bt.BtCtx(minecraft, body,
                            cameraPos, scene.buffers(), poseStack, 0, partial);
                    if (sceneAge >= 0) drawCreationDimmer((sceneAge + partial) / 20.0);
                    io.github.verycooltimo.murim.client.vfx.bt.BtGlow.prepare(minecraft, .208F);
                    try {
                        ClientMeditationState.creationFlow().render(ctx);
                    } finally {
                        io.github.verycooltimo.murim.client.vfx.bt.BtGlow.composite(minecraft);
                    }
                }
                return;
            }
            if (breakAge >= 0 || rankUpAge >= 0) {
                // The lab tail owns every layer; do not draw normal seed/meditation CoreGlow over it.
            } else if (state.active()) {
                // Итог прошлого такта доигрывается поверх начала следующего: сидение
                // непрерывно, и послесвечению больше некуда деваться.
                if (aftermath != ClientMeditationState.Aftermath.NONE) {
                    float age = ClientMeditationState.AFTERMATH_TICKS
                            - ClientMeditationState.aftermathTicks() + partial;
                    afterSession(scene, aftermath, ClientMeditationState.aftermathBeats(), age);
                }
                float t = ClientMeditationState.sessionTicks() + partial;
                if (state.beats() >= 3) {
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
     * API verified via /tmp/mr-ref-1121/minecraft-src/net/minecraft/ call sites:
     * client/renderer/CubeMap.java#render: projection backup/restore, model-view push/pop;
     * client/renderer/GameRenderer.java#render: setOrtho, VertexSorting.ORTHOGRAPHIC_Z;
     * client/renderer/LevelRenderer.java#renderSky: Tesselator.begin, BufferBuilder,
     * buildOrThrow, BufferUploader.drawWithShader; #renderChunkLayer: RenderSystem.getShader;
     * client/gui/GuiGraphics.java#fill: POSITION_COLOR vertices and packed ARGB setColor.
     * fill uses RenderType.gui's RENDERTYPE_GUI_SHADER; here the requested
     * GameRenderer.java#getPositionColorShader is selected explicitly (no fog).
     * client/renderer/ShaderInstance.java#setDefaultUniforms: getShaderColor;
     * client/renderer/RenderStateShard.java: blend factors and state setters.
     * Blaze3D implementation files are absent from this supplied source tree.
     */
    private static void drawCreationDimmer(double seconds) {
        double shade = io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.shadeAlpha(seconds, 0, 0);
        if (shade <= 0) return;
        // Preserve round 11's 8-bit alpha truncation as well as its colour and curve.
        int colour = ((int) (shade * 255) << 24) | 0x040810;
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        var shader = RenderSystem.getShader();
        float[] shaderColour = RenderSystem.getShaderColor().clone();
        var modelView = RenderSystem.getModelViewStack();
        RenderSystem.backupProjectionMatrix();
        modelView.pushMatrix();
        try {
            // Normalized viewport coordinates cover the current render target at every GUI scale.
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, 1, 1, 0, -1, 1), VertexSorting.ORTHOGRAPHIC_Z);
            modelView.identity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            buffer.addVertex(1, 0, 0).setColor(colour);
            buffer.addVertex(0, 0, 0).setColor(colour);
            buffer.addVertex(0, 1, 0).setColor(colour);
            buffer.addVertex(1, 1, 0).setColor(colour);
            BufferUploader.drawWithShader(buffer.buildOrThrow());
        } finally {
            // ShaderInstance.java#apply/clear uses ProgramManager.glUseProgram.
            ProgramManager.glUseProgram(program);
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderColor(shaderColour[0], shaderColour[1], shaderColour[2], shaderColour[3]);
            // [НЕПРОВЕРЕНО: integer overload absent from supplied sources; tester: compileJava.]
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            RenderSystem.depthMask(depthWrite);
            RenderSystem.restoreProjectionMatrix();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /** Всё, что нужно для рисования одного кадра. */
    private record Scene(Minecraft minecraft, AbstractClientPlayer player, PoseStack.Pose pose,
                         Vec3 camera, Vec3 axis, Vec3 core, Vec3 facing,
                         io.github.verycooltimo.murim.client.vfx.bt.BtCtx lab) {
        Scene(Minecraft minecraft, AbstractClientPlayer player, PoseStack.Pose pose, Vec3 camera, Vec3 axis, Vec3 core, Vec3 facing) {
            this(minecraft,player,pose,camera,axis,core,facing,null);
        }

        MultiBufferSource.BufferSource buffers() {
            return minecraft.renderBuffers().bufferSource();
        }
    }

    // ------------------------------------------------------------------ такт 1



    // ------------------------------------------------------------------ такты 2 и 3

    /** Сорвавшееся кольцо: красный, как на круге мини-игры. */
    private static final VfxColour STRAIN = new VfxColour(1.0F, 0.26F, 0.18F);



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



    // ------------------------------------------------------------------ прорыв

    /** Кости: тёплый белый, как раскалённое; ядро почти белое. */
    // --------------------------------------------------- третий ранг: очищение

    /** Lab ordering: damp, sweat, drips/spots, then lingering steam. Existing bead/stain/steam primitives retained. */
    public static void labPurificationDetails(io.github.verycooltimo.murim.client.vfx.bt.BtCtx c) {
        var b = c.body();
        Scene s = new Scene(c.mc(), c.mc().player, c.pose(), c.camera(), b.axis(),
                b.surface(b.bone(BoneAnchorLayer.Bone.DANTIAN)), b.near(), c);
        ClientMeditationState.rememberStainFrame(new Vec3(b.axis().x,b.ground(),b.axis().z),b.right(),b.near(),b.scale());
        float t = (float)c.labSeconds();
        float sheen = labSmooth(4.2,7.5,t) * (1-labSmooth(10.8,11.1,t));
        drawSweat(s, t*20, sheen);
        drawDampAndDrips(c);
        float steam = labSmooth(4.5,7.5,t) * (1-.55F*labSmooth(11.3,14,t));
        drawSteam(s,t*20,steam*(1-labSmooth(15.6,16,t)));
        drawMist(s,t*20,labSmooth(9,12,t)*steam*(1-labSmooth(15.6,16,t)),HALO);
        var dark = s.buffers().getBuffer(MurimRenderTypes.impurity());
        stainSet(dark,s.pose(),new Vec3(s.axis().x,ground(s)+.01,s.axis().z),.85F,t);
        s.buffers().endBatch(MurimRenderTypes.impurity());
    }
    private static float labSmooth(double a,double b,double t) {
        return (float)io.github.verycooltimo.murim.client.vfx.bt.BtMath.smooth(a,b,t);
    }
    private static double labRandom(double seed) {
        return io.github.verycooltimo.murim.client.vfx.bt.BtMath.random(seed);
    }
    private static void drawDampAndDrips(io.github.verycooltimo.murim.client.vfx.bt.BtCtx c) {
        double t=c.labSeconds(),soak=labSmooth(5,9,t)*(1-labSmooth(11.1,13.3,t));
        var dark=c.buffers().getBuffer(MurimRenderTypes.impuritySkin());
        double sheen=labSmooth(4.2,7.5,t)*(1-labSmooth(10.8,11.1,t));
        double[][] sheenPatches={{394,200,22,28},{398,240,12,10},{335,418,40,12},{465,418,40,12},{400,436,22,9}};
        for(double[] q:sheenPatches) {
            // Canvas multiply is approximated by dark alpha-blended skin motes, as in the existing sweat renderer.
            VfxDraw.billboard(dark,c.pose(),c.body().skinPoint(q[0],q[1]),c.camera(),c.body().px(q[2]),(float)(sheen*.55),92/255F,70/255F,58/255F);
        }
        for(int i=0;i<8;i++) {
            double[][] patches={{318,300,34,22},{484,302,32,20},{290,350,22,34},{512,352,24,32},{300,410,26,14},{500,410,26,14},{372,300,20,26},{430,306,18,24}};
            Vec3 at=c.body().skinPoint(patches[i][0],patches[i][1]);
            VfxDraw.billboard(dark,c.pose(),at,c.camera(),c.body().px(patches[i][2]),.7F*(float)soak,90/255F,72/255F,66/255F);
        }
        for(var spot:io.github.verycooltimo.murim.client.vfx.bt.BtPurification.SPOTS) {
            if(spot.period()==0)continue;
            for(int k=0;spot.start()+k*spot.period()<=Math.min(t,10.8);k++) {
                double e=t-spot.start()-k*spot.period();if(e>.4+spot.fall())continue;
                double grow=labSmooth(0,.4,e),f=Math.max(0,e-.4),y=spot.hemY()+300*f*f,r=1.3+.9*labRandom(spot.seed()+k);
                // The last camera-relative floor frame is retained through the 30-second stain window.
                Vec3 at=new Vec3(c.body().axis().x,c.body().ground()+c.body().px(585-y),c.body().axis().z)
                        .add(c.body().right().scale(c.body().px(spot.x()-400)))
                        .add(c.body().near().scale(c.body().px((spot.y()-585)/.34)));
                VfxDraw.billboard(dark,c.pose(),at,c.camera(),c.body().px(r*grow),.9F,34/255F,20/255F,12/255F);
            }
        }
        c.buffers().endBatch(MurimRenderTypes.impuritySkin());
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
        if(s.lab()!=null) {
            double t=age/20;
            for(int i=0;i<9;i++) {
                double x=120+i*70+Math.sin(t*.25+i)*30;
                Vec3 at=s.lab().body().plate(x,594+(600+12*Math.sin(i*1.7)-585),0);
                VfxDraw.billboard(c,s.pose(),at,s.camera(),s.lab().body().px(110),.12F*strength,180/255F,206/255F,232/255F);
            }
            s.buffers().endBatch(MurimRenderTypes.impactCore());
            return;
        }
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
        stainSet(dark, pose, origin.add(0.0D, 0.03D, 0.0D), 0.85F * fade, 10.8F);
        buffers.endBatch(MurimRenderTypes.impurity());
    }

    /** Набор клякс: кольцом вокруг тела, крупные и мелкие брызги; {@code grow} — доля проявления. */
    private static void stainSet(VertexConsumer dark, PoseStack.Pose pose, Vec3 centre, float alpha, float grow) {
        for (var spot : io.github.verycooltimo.murim.client.vfx.bt.BtPurification.SPOTS) {
            double g=1-Math.exp(-.75*spot.landed(grow));
            if(g<=.01)continue;
            double scale=ClientMeditationState.stainScale();
            Vec3 at=centre.add(ClientMeditationState.stainRight().scale((spot.x()-400)/360*scale))
                    .add(ClientMeditationState.stainNear().scale((spot.y()-585)/360/.34*scale));
            double big=spot.radius()*g/360*scale;
            flatSpot(dark,pose,at,big*1.35,alpha*.4F);
            flatSpot(dark,pose,at.add(0,.001,0),big,alpha*.6F);
            flatSpot(dark,pose,at.add(0,.002,0),big*.55,alpha*.4F);
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
        for (int i = 0; i < 30 * io.github.verycooltimo.murim.client.vfx.bt.BtScenes.quality(s.minecraft()); i++) {
            int seed=i*19+300;
            boolean arm=i>=14;
            Vec3 base=anchors[arm?(i%2==0?4:5):6];
            if(base==null)continue;
            double offset=arm?(labRandom(seed+7)-.5)*.16:(labRandom(seed+7)-.5)*.12;
            double u=labRandom(seed+7),plateX=i<11?372+44*u:i<14?388+22*u:400+(i%2==1?1:-1)*(110-80*u);
            double plateY=i<11?168+50*labRandom(seed+14):i<14?232+20*labRandom(seed+14):402+30*u+(labRandom(seed+14)-.5)*10;
            Vec3 at0=s.lab()!=null?s.lab().body().skinPoint(plateX,plateY):base.add(side.scale(offset));
            float born=(float)(4.2+4.4*labRandom(seed+21));
            float t=age/20-born;
            if (t < 0.0F) {
                continue;
            }
            double scale=s.lab()==null?1:s.lab().body().scale();
            double size = (1.8+1.5*labRandom(seed+28))/360*scale;
            if (t < 5.0F) {
                // Выступает (растёт), медленно сползает, к концу срывается вниз.
                double grow = labSmooth(0,.5,t),hold=.8+.9*labRandom(seed+42),fall=Math.max(0,t-hold);
                double slide=arm?(Math.min(t,hold)*3+380*fall*fall)/360:Math.max(0,t-.5)*(4+6*labRandom(seed+35))/360;
                float own=arm?1-labSmooth(.25,.4,fall):1-labSmooth(232,244,plateY+slide*360);
                Vec3 at = at0.add(0.0D,-slide*scale,0.0D);
                if (at.y < ground) {
                    at = new Vec3(at.x, ground + size, at.z);
                }
                VfxDraw.billboard(dark, s.pose(), at, s.camera(), size * grow, 0.9F * strength * own,
                                  34/255F,18/255F,10/255F);
                // Блик мокрой капли.
                VfxDraw.billboard(dark, s.pose(), at.add(0.0D, size * 0.3D, 0.0D).add(s.facing().scale(0.01D)),
                                  s.camera(), size * 0.25D * grow, 0.45F * strength * own,230/255F,240/255F,1F);
                // Вытянутый след капли по коже.
                VfxDraw.segment(dark, s.pose(), at0, at, s.camera(), size * 0.35D * grow, 0.35F * strength * own,
                                40/255F,24/255F,16/255F);
            }

        }
        s.buffers().endBatch(MurimRenderTypes.impuritySkin());
    }

    /** Пар от пота: 54 клуба по лабораторному таймлайну от предплечий, плеч и висков, растут и поднимаются, светлые серо-голубые. */
    private static void drawSteam(Scene s, float age, float strength) {
        if (strength <= 0.0F) {
            return;
        }
        Vec3[] anchors = skinAnchors(s);
        int[] from = {0, 2, 3, 4, 5, 6};
        VertexConsumer puff = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        for (int i = 0; i < 54 * io.github.verycooltimo.murim.client.vfx.bt.BtScenes.quality(s.minecraft()); i++) {
            int seed=i*13+2600;
            double[][] src={{393,118},{372,150},{414,150},{306,288},{494,288},{282,360},{518,360},{330,420},{470,420}};
            double[] o=src[i%src.length];
            Vec3 base=s.lab()!=null?s.lab().body().skinPoint(o[0],o[1]):anchors[from[i%from.length]];
            if(base==null)continue;
            float life = (float)(2.6+1.8*labRandom(seed+17))*20;
            float t = (float)((age+labRandom(seed+34)*life)%life)/life;
            double scale=s.lab()==null?1:s.lab().body().scale();
            Vec3 right=s.lab()==null?s.facing().cross(new Vec3(0,1,0)):s.lab().body().right();
            Vec3 at=base.add(right.scale(((labRandom(seed+51)-.5)*24+Math.sin(age/20*.8+i)*8*t+t*14)/360*scale))
                    .add(0,t*(55+45*labRandom(seed+68))/360*scale,0);
            // Steam is dark-side/side light near the head; never a glow over the face.
            if(s.lab()!=null&&!s.lab().body().faceClear(at))continue;
            VfxDraw.billboard(puff, s.pose(), at, s.camera(), (8+26*t)/360*scale, 0.11F * strength * (float) Math.sin(t * Math.PI),
                              196/255F,214/255F,230/255F);
        }
        s.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    // ------------------------------------------ первый ранг: ци держит форму

    // ------------------------------------------------ Пик: перестройка тела

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

    /** Горизонтальное пятно на земле. */
    private static void flatSpot(VertexConsumer c, PoseStack.Pose pose, Vec3 at, double r, float alpha) {
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, pose, at.add(-r, 0.0D, -r), up, 0.0F, 0.0F, alpha, 0.06F, 0.04F, 0.05F);
        VfxDraw.vertex(c, pose, at.add(-r, 0.0D, r), up, 0.0F, 1.0F, alpha, 0.06F, 0.04F, 0.05F);
        VfxDraw.vertex(c, pose, at.add(r, 0.0D, r), up, 1.0F, 1.0F, alpha, 0.06F, 0.04F, 0.05F);
        VfxDraw.vertex(c, pose, at.add(r, 0.0D, -r), up, 1.0F, 0.0F, alpha, 0.06F, 0.04F, 0.05F);
    }

    // ------------------------------------------------------------------ после семени

    /** Медитация с семенем: ровное дыхание ядра и медленный приток искр. */
    private static void seededBreath(Scene s, float t) {
        float breath = 0.5F + 0.5F * Mth.sin(t * 0.08F);
        float rise = Mth.clamp(t / 40.0F, 0.0F, 1.0F) * ClientMeditationState.state().auraScale();
        VertexConsumer glow = s.buffers().getBuffer(MurimRenderTypes.impactCore());
        CoreGlow.draw(glow, s.pose(), s.core(), s.camera(), t, 0.05D + 0.015D * breath,
                      (0.4F + 0.2F * breath) * rise, DEEP, CORE);
        BillboardBurst.inward(glow, s.pose(), s.core(), s.camera(), 10, t * 0.6F, 0.5D,
                              0.35F * rise, HALO, CORE);
        s.buffers().endBatch(MurimRenderTypes.impactCore());
        // Ци бежит по телу к даньтяню ровным темпом; яркость потока — скорость прироста: она не падает за сессию
        // (затухания нет), у места силы поток гуще (docs/design/19b §4).
        // Во время поглощения пилюли тело занято её сгустком — поток не рисуется.
        if (!io.github.verycooltimo.murim.client.ClientPillState.absorbing()) {
            double rate = 1.0D;
            boolean full = io.github.verycooltimo.murim.cultivation.Realm.atWall(
                    io.github.verycooltimo.murim.client.ClientProfileState.profile());
            boolean place = io.github.verycooltimo.murim.client.ClientPlaceState.node() != null;
            float strength = full || io.github.verycooltimo.murim.client.ClientPlaceState.stunned() ? 0.0F
                    : (float) Mth.clamp(rate * (place ? 1.0D : 0.6D), 0.12D, 1.0D) * rise;
            drawStreams(s, t, strength);
        }
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
