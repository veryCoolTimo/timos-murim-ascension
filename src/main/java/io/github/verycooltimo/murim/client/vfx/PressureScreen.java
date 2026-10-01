package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientAuraState;
import io.github.verycooltimo.murim.client.ClientConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Давление ауры на экране (docs/design/19 §3ж, концепт-кадры «от первого лица»).
 *
 * <ol>
 *   <li><b>Марево</b> — полноэкранный шейдер {@code murim:shaders/post/pressure.json}: кольца
 *       искажения от противника, рябь горячего воздуха, уход цвета, тёмные края, двоение
 *       каналов при сильном давлении. Накладывается на мир до интерфейса.</li>
 *   <li><b>Мазки</b> — несколько крупных асимметричных мазков туши к противнику (не рамка),
 *       с окном вокруг его силуэта; входят на фронте и почти не двигаются.</li>
 * </ol>
 *
 * <p>Слой интерфейса показывается и при скрытом интерфейсе (F1): это часть мира, не HUD. Но
 * только когда камера — глаза своего игрока: со стороны давление на экране было бы ложью.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class PressureScreen {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "aura_pressure");
    private static final ResourceLocation STROKES = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/aura_strokes.png");
    private static final ResourceLocation CHAIN = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "shaders/post/pressure.json");

    private static PostChain chain;
    private static boolean chainFailed;
    private static int chainWidth;
    private static int chainHeight;

    /** Точка противника на экране в долях, обновляется при отрисовке мира. */
    private static float centerX = 0.5F;
    private static float centerY = 0.5F;
    private static boolean centerVisible;
    /** Радиус окна вокруг противника в долях высоты экрана. */
    private static float window = 0.2F;

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        // Под прицелом и хотбаром: давление — мир, а не интерфейс.
        event.registerBelow(VanillaGuiLayers.CROSSHAIR, LAYER, PressureScreen::render);
    }

    private static boolean ownEyes(Minecraft minecraft) {
        return minecraft.player != null && minecraft.getCameraEntity() == minecraft.player;
    }

    /** Проекция точки противника: матрицы есть только при отрисовке мира. */
    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        centerVisible = false;
        if (minecraft.level == null || ClientAuraState.sourceId() < 0) {
            return;
        }
        Entity source = minecraft.level.getEntity(ClientAuraState.sourceId());
        if (source == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 at = source.getPosition(partial).add(0.0D, source.getBbHeight() * 0.6D, 0.0D)
                .subtract(event.getCamera().getPosition());
        Vector4f clip = new Vector4f((float) at.x, (float) at.y, (float) at.z, 1.0F);
        new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix()).transform(clip);
        if (clip.w <= 0.05F) {
            return;
        }
        centerX = Mth.clamp(clip.x / clip.w * 0.5F + 0.5F, -0.2F, 1.2F);
        centerY = Mth.clamp(clip.y / clip.w * 0.5F + 0.5F, -0.2F, 1.2F);
        // Окно под противника: половина его экранной высоты плюс 10 % — туда ни мазки, ни марево.
        Vec3 top = source.getPosition(partial).add(0.0D, source.getBbHeight() * 1.05D, 0.0D)
                .subtract(event.getCamera().getPosition());
        Vector4f topClip = new Vector4f((float) top.x, (float) top.y, (float) top.z, 1.0F);
        new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix()).transform(topClip);
        if (topClip.w > 0.05F) {
            float topY = topClip.y / topClip.w * 0.5F + 0.5F;
            window = Mth.clamp(Math.abs(topY - centerY) * 1.4F, 0.06F, 0.6F);
        }
        centerVisible = centerX > -0.05F && centerX < 1.05F && centerY > -0.05F && centerY < 1.05F;
    }

    /** Марево — до интерфейса, поверх мира и руки. */
    @SubscribeEvent
    static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        float p = ClientAuraState.pressure(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        if (p <= 0.01F || !ownEyes(minecraft) || !ClientConfig.DISTORTION_EFFECTS.get() || chainFailed) {
            return;
        }
        int w = minecraft.getWindow().getWidth();
        int h = minecraft.getWindow().getHeight();
        if (chain == null) {
            try {
                chain = new PostChain(minecraft.getTextureManager(), minecraft.getResourceManager(),
                        minecraft.getMainRenderTarget(), CHAIN);
            } catch (Exception e) {
                chainFailed = true;
                MurimMod.LOGGER.warn("Шейдер давления не загрузился", e);
                return;
            }
            chainWidth = -1;
        }
        if (w != chainWidth || h != chainHeight) {
            chain.resize(w, h);
            chainWidth = w;
            chainHeight = h;
        }
        float scale = (float) (double) minecraft.options.screenEffectScale().get();
        chain.setUniform("Pressure", p * scale);
        chain.setUniform("Clock", (minecraft.level.getGameTime() % 24000L
                + event.getPartialTick().getGameTimeDeltaPartialTick(false)) / 20.0F);
        // Центр в координатах текстуры: у неё ось Y снизу вверх, как и у клипа.
        chain.setUniform("CenterX", centerVisible ? centerX : 0.5F);
        chain.setUniform("CenterY", centerVisible ? centerY : 0.5F);
        chain.setUniform("Demonic", ClientAuraState.sourceDemonic() ? 1.0F : 0.0F);
        chain.setUniform("Front", ClientAuraState.frontAge(event.getPartialTick().getGameTimeDeltaPartialTick(false)) / 20.0F);
        chain.setUniform("Window", window);
        chain.setUniform("Visible", centerVisible ? 1.0F : 0.0F);
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.resetTextureMatrix();
        chain.process(event.getPartialTick().getGameTimeDeltaTicks());
        minecraft.getMainRenderTarget().bindWrite(true);
        RenderSystem.enableDepthTest();
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        float partial = delta.getGameTimeDeltaPartialTick(false);
        float p = ClientAuraState.pressure(partial);
        if (p < 0.2F || !ownEyes(minecraft)) {
            return;
        }
        p *= (float) (double) minecraft.options.screenEffectScale().get();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        strokes(graphics, graphics.guiWidth(), graphics.guiHeight(), p, ClientAuraState.sourceDemonic(),
                minecraft.level.getGameTime() + partial, ClientAuraState.frontAge(partial));
        RenderSystem.disableBlend();
    }

    /**
     * Несколько крупных мазков вместо рамки (разбор astra 01.10): Р1/Р2/Р3 — 3/5/7 широких и
     * 4/7/10 тонких; длина 12–30 % высоты экрана, основание 3–8 %. Неравномерно: один главный
     * сектор, два поддерживающих, пустые промежутки. Направлены к груди источника, у его силуэта
     * гаснут. На фронте входят за 0,35 с, дальше лишь дрейфуют на 1–2 %.
     */
    private static void strokes(GuiGraphics graphics, int width, int height, float p, boolean demonic, float time, float frontAge) {
        int tier = ClientAuraState.tier(p);
        int[] wideByTier = {0, 3, 4, 5};
        int[] thinByTier = {0, 4, 7, 10};
        float[] alphaByTier = {0.0F, 0.16F, 0.28F, 0.42F};
        int wide = wideByTier[tier];
        int thin = thinByTier[tier];
        float alphaWide = alphaByTier[tier];
        float enter = Mth.clamp(frontAge / 7.0F, 0.0F, 1.0F);
        enter = 1.0F - (1.0F - enter) * (1.0F - enter);
        float cx = (centerVisible ? centerX : 0.5F) * width;
        float cy = (1.0F - (centerVisible ? centerY : 0.5F)) * height;
        float windowPx = window * height;
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, STROKES);
        Matrix4f m = graphics.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        float r = demonic ? 0.16F : 0.031F;
        float g = demonic ? 0.024F : 0.043F;
        float bl = demonic ? 0.05F : 0.07F;
        // Главный сектор — со стороны, противоположной противнику на экране: оттуда «давит».
        float main = (float) Math.atan2(cy - height * 0.5F, cx - width * 0.5F) + (float) Math.PI;
        if (!centerVisible) {
            main = (float) Math.PI * 1.5F;
        }
        boolean drew = false;
        for (int i = 0; i < wide + thin; i++) {
            boolean big = i < wide;
            long h = (i + 1) * 0x9E3779B97F4A7C15L + (big ? 0x5DEECE66DL : 0x2545F4914F6CDD1DL);
            h ^= h >>> 31;
            h *= 0xBF58476D1CE4E5B9L;
            h ^= h >>> 29;
            float u1 = (h & 0xFFFF) / 65535.0F;
            float u2 = (h >>> 16 & 0xFFFF) / 65535.0F;
            float u3 = (h >>> 32 & 0xFFFF) / 65535.0F;
            // Половина мазков — в главном секторе (±35°), остальные — в двух поддерживающих.
            int sector = i % 4 == 0 || i % 4 == 2 ? 0 : i % 4 == 1 ? 1 : 2;
            float base = main + (sector == 0 ? 0.0F : sector == 1 ? 1.9F : -1.7F);
            float a0 = base + (u1 - 0.5F) * (sector == 0 ? 1.2F : 0.8F);
            float dx = Mth.cos(a0);
            float dy = Mth.sin(a0);
            // Точка на краю экрана по лучу из центра противника.
            float tx = dx > 0 ? (width - cx) / dx : dx < 0 ? -cx / dx : 1.0E6F;
            float ty = dy > 0 ? (height - cy) / dy : dy < 0 ? -cy / dy : 1.0E6F;
            float edge = Math.min(Math.abs(tx), Math.abs(ty));
            float length = height * (big ? 0.12F + 0.18F * u2 : 0.08F + 0.1F * u2) * (0.7F + 0.3F * enter);
            float drift = height * 0.015F * Mth.sin(time * 0.04F + i);
            float tail = edge + height * 0.03F;
            float head = Math.max(windowPx, tail - length - drift);
            if (tail - head < height * 0.04F) {
                continue;
            }
            float halfWidth = height * (big ? 0.015F + 0.025F * u3 : 0.006F + 0.008F * u3);
            float alpha = (big ? alphaWide : alphaWide * 0.5F) * enter;
            int cell = (int) (u3 * 8.0F) & 7;
            float su0 = (cell % 4) / 4.0F;
            float su1 = su0 + 0.25F;
            float sv0 = (cell / 4) / 2.0F;
            float sv1 = sv0 + 0.5F;
            float hx = cx + dx * head, hy = cy + dy * head;
            float ex = cx + dx * tail, ey = cy + dy * tail;
            float nx = -dy * halfWidth, ny = dx * halfWidth;
            // Ночью чёрное на тёмном небе не видно (разбор astra): под широким мазком —
            // дымчатая подложка шире на 60 %, у демонической — тёмно-красная.
            if (big) {
                float ur = demonic ? 0.36F : 0.36F;
                float ug = demonic ? 0.07F : 0.4F;
                float ub = demonic ? 0.1F : 0.46F;
                float ua = alpha * 0.6F;
                float ux = nx * 1.6F, uy = ny * 1.6F;
                b.addVertex(m, ex + ux, ey + uy, 0.0F).setUv(su0, sv0).setColor(ur, ug, ub, ua);
                b.addVertex(m, ex - ux, ey - uy, 0.0F).setUv(su0, sv1).setColor(ur, ug, ub, ua);
                b.addVertex(m, hx - ux, hy - uy, 0.0F).setUv(su1, sv1).setColor(ur, ug, ub, ua);
                b.addVertex(m, hx + ux, hy + uy, 0.0F).setUv(su1, sv0).setColor(ur, ug, ub, ua);
            }
            // U = основание мазка у края экрана, конец — к противнику.
            b.addVertex(m, ex + nx, ey + ny, 0.0F).setUv(su0, sv0).setColor(r, g, bl, alpha);
            b.addVertex(m, ex - nx, ey - ny, 0.0F).setUv(su0, sv1).setColor(r, g, bl, alpha);
            b.addVertex(m, hx - nx, hy - ny, 0.0F).setUv(su1, sv1).setColor(r, g, bl, alpha);
            b.addVertex(m, hx + nx, hy + ny, 0.0F).setUv(su1, sv0).setColor(r, g, bl, alpha);
            drew = true;
        }
        if (drew) {
            BufferUploader.drawWithShader(b.buildOrThrow());
        } else {
            b.build();
        }
    }

    private PressureScreen() {
    }
}
