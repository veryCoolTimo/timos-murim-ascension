package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientMeditationState;
import io.github.verycooltimo.murim.client.ClientProfileState;
import io.github.verycooltimo.murim.world.ModWorld;
import io.github.verycooltimo.murim.world.PlaceKind;
import io.github.verycooltimo.murim.world.PlaceRules;
import io.github.verycooltimo.murim.world.SpiritVeinBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import java.util.Random;

/**
 * Аура места силы (docs/design/19b §3): над камнем жилы — столб тонких восходящих нитей, видный
 * издалека (рендер блок-сущности, а не случайные тики), вблизи — искры спиралью и пылинки,
 * втягивающиеся к узлу; перед волной всё разгорается и ускоряется; при медитации рядом — поток
 * искр от камня к животу медитирующего (бонус объясняется без текста).
 *
 * <p>Всё — движущиеся точки и тонкие отрезки (симуляция: скорость, закрутка, турбулентность
 * задаются формулой от времени), без больших карточек. RenderType —
 * {@link MurimRenderTypes#impactCore()} (аддитивный, полная яркость).
 * API: reference/minecraft-src/net/minecraft/client/renderer/blockentity/BlockEntityRenderer.java,
 * reference/neoforge-src/net/neoforged/neoforge/client/extensions/IBlockEntityRendererExtension.java#getRenderBoundingBox
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class PlaceAuraRenderer implements BlockEntityRenderer<SpiritVeinBlockEntity> {

    /** Высота столба: видно над кронами. */
    static final double PILLAR = 12.0D;

    public PlaceAuraRenderer(BlockEntityRendererProvider.Context context) {
    }

    @SubscribeEvent
    static void onRegister(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModWorld.SPIRIT_VEIN_ENTITY.get(), PlaceAuraRenderer::new);
    }

    @Override
    public boolean shouldRenderOffScreen(SpiritVeinBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 64;
    }

    @Override
    public AABB getRenderBoundingBox(SpiritVeinBlockEntity be) {
        BlockPos p = be.getBlockPos();
        return new AABB(p.getX() - 2, p.getY(), p.getZ() - 2, p.getX() + 3, p.getY() + PILLAR + 1, p.getZ() + 3);
    }

    private static VfxColour colour(PlaceKind kind) {
        int c = kind.colour();
        return new VfxColour(((c >> 16) & 0xFF) / 255.0F, ((c >> 8) & 0xFF) / 255.0F, (c & 0xFF) / 255.0F);
    }

    /**
     * Общий источник буферов строит один тип за раз: запрос второго закрывает первый, и запись
     * в закрытый падает. Поэтому два прохода: сначала ленты (мягкая лента ribbon), потом точки
     * и свечения (impact_core). Поля — только рендер-поток, игрового состояния здесь нет.
     */
    private static VertexConsumer lines;
    private static VertexConsumer dots;

    private static void seg(PoseStack.Pose pose, Vec3 a, Vec3 b, Vec3 cam, double w, float alpha, float r, float g, float bl) {
        if (lines != null) {
            VfxDraw.segment(lines, pose, a, b, cam, w, alpha, r, g, bl);
        }
    }

    private static void dot(PoseStack.Pose pose, Vec3 at, Vec3 cam, double size, float alpha, float r, float g, float bl) {
        if (dots != null) {
            VfxDraw.billboard(dots, pose, at, cam, size, alpha, r, g, bl);
        }
    }

    private static void glow(PoseStack.Pose pose, Vec3 at, Vec3 cam, float age, double radius, float intensity,
                             VfxColour halo, VfxColour core) {
        if (dots != null) {
            CoreGlow.draw(dots, pose, at, cam, age, radius, intensity, halo, core);
        }
    }

    @Override
    public void render(SpiritVeinBlockEntity be, float partial, PoseStack poseStack, MultiBufferSource buffers,
                       int light, int overlay) {
        lines = buffers.getBuffer(MurimRenderTypes.ribbon());
        dots = null;
        renderPass(be.getBlockPos(), be.kind(), partial, poseStack);
        lines = null;
        dots = buffers.getBuffer(MurimRenderTypes.impactCore());
        renderPass(be.getBlockPos(), be.kind(), partial, poseStack);
        dots = null;
    }

    /**
     * Природное место силы (пик, вода, старое дерево — автор 03.10): у него нет блок-сущности,
     * аура рисуется в мировой стадии у якоря, когда игрок рядом.
     */
    @SubscribeEvent
    static void onRenderStage(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        io.github.verycooltimo.murim.world.Place place = io.github.verycooltimo.murim.client.ClientPlaceState.place();
        if (place == null || place.stone()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        ps.pushPose();
        try {
            BlockPos a = place.anchor();
            ps.translate(a.getX() - cam.x, a.getY() - cam.y, a.getZ() - cam.z);
            lines = buffers.getBuffer(MurimRenderTypes.ribbon());
            renderPass(a, place.kind(), partial, ps);
            lines = null;
            buffers.endBatch(MurimRenderTypes.ribbon());
            dots = buffers.getBuffer(MurimRenderTypes.impactCore());
            renderPass(a, place.kind(), partial, ps);
            dots = null;
            buffers.endBatch(MurimRenderTypes.impactCore());
        } finally {
            lines = null;
            dots = null;
            ps.popPose();
        }
    }

    private static void renderPass(BlockPos pos, PlaceKind kind, float partial, PoseStack poseStack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        long gameTime = mc.level.getGameTime();
        float t = gameTime + partial;
        Vec3 origin = Vec3.atLowerCornerOf(pos);
        Vec3 camLocal = mc.gameRenderer.getMainCamera().getPosition().subtract(origin);
        double dist = camLocal.length();
        VfxColour col = colour(kind);
        VfxColour white = new VfxColour(1.0F, 1.0F, 1.0F);
        // С рангом места силы «чувствуются» ярче (docs/design/19 §3д).
        int rank = ClientProfileState.profile().rank();
        float sense = Mth.clamp(0.55F + 0.15F * rank, 0.55F, 1.0F);
        float warn = PlaceRules.warning(pos.asLong(), gameTime);
        int since = PlaceRules.sinceWave(pos.asLong(), gameTime);
        float surge = 1.0F + 1.2F * warn;
        PoseStack.Pose pose = poseStack.last();
        Vec3 top = new Vec3(0.5D, 1.0D, 0.5D);

        // Ядро над камнем: место «дышит» ци; перед волной разгорается.
        glow(pose, new Vec3(0.5D, 1.25D, 0.5D), camLocal, t, 0.5D + 0.15D * warn,
                (0.45F + 0.5F * warn) * sense, col, white);
        // Масса столба: цепочка полупрозрачных свечений вверх, шириной с камень (codex, раунд 1).
        for (int i = 0; i < 6; i++) {
            double y = 1.3D + i * 1.6D;
            float a = (float) (1.0D - i / 6.0D);
            glow(pose, new Vec3(0.5D, y, 0.5D), camLocal, t + i * 13, 0.6D + 0.05D * i,
                    0.35F * a * sense * surge, col, col);
        }
        // Свечение земли вокруг камня: кольцо точек, дышит.
        for (int i = 0; i < 28; i++) {
            double a = i * Math.PI * 2 / 28 + t * 0.01D;
            double rr = 1.3D + 0.1D * Math.sin(t * 0.07D + i);
            double a1 = (i + 1) * Math.PI * 2 / 28 + t * 0.01D;
            seg(pose, new Vec3(0.5D + Math.cos(a) * rr, 0.06D, 0.5D + Math.sin(a) * rr),
                    new Vec3(0.5D + Math.cos(a1) * rr, 0.06D, 0.5D + Math.sin(a1) * rr), camLocal, 0.07D,
                    (0.3F + 0.35F * warn) * sense, col.red(), col.green(), col.blue());
        }
        // Столб: тонкие нити, раскачиваются и расходятся кверху; бусины бегут вверх.
        int strands = 12;
        for (int s = 0; s < strands; s++) {
            Random r = new Random(pos.asLong() * 31L + s * 7919L);
            double phase = r.nextDouble() * Math.PI * 2;
            double spread = 0.25D + 0.3D * r.nextDouble();
            int seg = 16;
            Vec3 prev = null;
            for (int i = 0; i <= seg; i++) {
                double y = PILLAR * i / seg;
                double rad = spread * (0.3D + y / PILLAR) * surge;
                double a = phase + y * 0.45D + t * 0.03D * surge;
                Vec3 p = top.add(Math.cos(a) * rad, y, Math.sin(a) * rad);
                if (prev != null) {
                    float fade = (float) (1.0D - y / PILLAR);
                    seg(pose, prev, p, camLocal, 0.05D + 0.03D * warn, 0.55F * fade * sense * surge,
                            col.red(), col.green(), col.blue());
                }
                prev = p;
            }
            // Бусины: скорость вверх, закрутка вместе с нитью.
            for (int b = 0; b < 3; b++) {
                double k = ((t * 0.006D * surge + b / 3.0D + r.nextDouble()) % 1.0D);
                double y = PILLAR * k;
                double rad = spread * (0.3D + k) * surge;
                double a = phase + y * 0.45D + t * 0.03D * surge;
                Vec3 p = top.add(Math.cos(a) * rad, y, Math.sin(a) * rad);
                dot(pose, p, camLocal, 0.055D, (float) (1.0D - k) * sense, col.red(), col.green(), col.blue());
                dot(pose, p, camLocal, 0.025D, (float) (1.0D - k) * sense, white.red(), white.green(), white.blue());
            }
        }

        if (dist < 24.0D) {
            // Вблизи: искры спиралью вверх и пылинки, втягивающиеся к узлу.
            for (int i = 0; i < 26; i++) {
                Random r = new Random(pos.asLong() + i * 104729L);
                float life = 30.0F + r.nextFloat() * 20.0F;
                float k = ((t * surge + r.nextFloat() * life) % life) / life;
                double a = r.nextDouble() * Math.PI * 2 + k * 4.0D;
                double rad = 0.6D * (1.0D - k) + 0.1D;
                Vec3 p = top.add(Math.cos(a) * rad, -0.4D + 1.6D * k, Math.sin(a) * rad);
                dot(pose, p, camLocal, 0.032D, (float) Math.sin(Math.PI * k) * sense,
                        col.red(), col.green(), col.blue());
            }
            for (int i = 0; i < 14; i++) {
                Random r = new Random(pos.asLong() * 3L + i * 15485863L);
                float life = 40.0F + r.nextFloat() * 20.0F;
                float k = ((t + r.nextFloat() * life) % life) / life;
                double a = r.nextDouble() * Math.PI * 2;
                double rad = 2.4D * (1.0D - k) + 0.2D;
                Vec3 p = new Vec3(0.5D + Math.cos(a) * rad, 0.1D + 0.3D * r.nextDouble(), 0.5D + Math.sin(a) * rad);
                dot(pose, p, camLocal, 0.02D, 0.8F * k * sense, white.red(), white.green(), white.blue());
            }
        }

        // Волна: кольцо по земле расходится от узла.
        if (since < 16) {
            float k = since / 16.0F;
            int n = 36;
            double rad = 1.8D + 4.2D * k;
            for (int i = 0; i < n; i++) {
                double a0 = i * Math.PI * 2 / n;
                double a1 = (i + 1) * Math.PI * 2 / n;
                Vec3 p0 = new Vec3(0.5D + Math.cos(a0) * rad, 0.15D, 0.5D + Math.sin(a0) * rad);
                Vec3 p1 = new Vec3(0.5D + Math.cos(a1) * rad, 0.15D, 0.5D + Math.sin(a1) * rad);
                seg(pose, p0, p1, camLocal, 0.35D * (1.0F - 0.5F * k), 1.0F - k, col.red(), col.green(), col.blue());
                seg(pose, p0, p1, camLocal, 0.08D, 1.0F - k, 1.0F, 1.0F, 1.0F);
            }
        }

        // Медитирующий рядом: поток искр от камня к животу.
        inflow(mc, pose, origin, camLocal, t, col, pos);
    }

    private static void inflow(Minecraft mc, PoseStack.Pose pose, Vec3 origin, Vec3 camLocal, float t,
                               VfxColour col, BlockPos pos) {
        if (!(mc.player instanceof AbstractClientPlayer player) || !ClientMeditationState.state().active()
                || ClientMeditationState.state().beats() < 3) {
            return;
        }
        if (player.position().distanceTo(Vec3.atCenterOf(pos)) > PlaceRules.RADIUS + 0.5D) {
            return;
        }
        Vec3 d = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.DANTIAN);
        if (d == null) {
            return;
        }
        // Кость даньтяня — внутри модели: конец потока выносим к поверхности живота со стороны камеры.
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 target = d.add(cam.subtract(d).normalize().scale(0.2D)).subtract(origin);
        Vec3 from = new Vec3(0.5D, 1.1D, 0.5D);
        boolean stunned = PlaceRules.sinceWave(pos.asLong(), mc.level.getGameTime()) < PlaceRules.STUN;
        if (stunned) {
            return;
        }
        // Непрерывный изогнутый шлейф от камня к животу, по нему бегут сгустки, у пупка — яркая точка входа.
        Vec3 mid = from.lerp(target, 0.5D).add(0, 0.35D, 0);
        int n = 20;
        Vec3 prev = null;
        for (int i = 0; i <= n; i++) {
            double k = i / (double) n;
            Vec3 p = from.scale((1 - k) * (1 - k)).add(mid.scale(2 * k * (1 - k))).add(target.scale(k * k));
            if (prev != null) {
                seg(pose, prev, p, camLocal, 0.09D, 0.55F, col.red(), col.green(), col.blue());
                seg(pose, prev, p, camLocal, 0.025D, 0.7F, 1.0F, 1.0F, 1.0F);
            }
            prev = p;
        }
        for (int i = 0; i < 10; i++) {
            float k = ((t * 0.035F + i / 10.0F) % 1.0F);
            Vec3 p = from.scale((1 - k) * (1 - k)).add(mid.scale(2 * k * (1 - k))).add(target.scale(k * k));
            dot(pose, p, camLocal, 0.07D, 0.9F, col.red(), col.green(), col.blue());
            dot(pose, p, camLocal, 0.03D, 1.0F, 1.0F, 1.0F, 1.0F);
        }
        glow(pose, target, camLocal, t, 0.07D, 0.8F, col, new VfxColour(1.0F, 1.0F, 1.0F));
    }
}
