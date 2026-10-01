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
 *   <li><b>Рамка тушью</b> — мазки сухой кисти от краёв к центру; чем сильнее давление, тем
 *       глубже заходят. У демонической ци — чёрно-красная.</li>
 *   <li><b>Штрихи к противнику</b> — от краёв к его точке на экране, ползут внутрь, как в
 *       манхве: взгляд тянет к тому, кто давит.</li>
 * </ol>
 *
 * <p>Слой интерфейса показывается и при скрытом интерфейсе (F1): это часть мира, не HUD. Но
 * только когда камера — глаза своего игрока: со стороны давление на экране было бы ложью.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class PressureScreen {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "aura_pressure");
    private static final ResourceLocation INK = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/gui/pressure_ink.png");
    private static final ResourceLocation STROKE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/ink_stroke.png");
    private static final ResourceLocation CHAIN = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "shaders/post/pressure.json");

    private static PostChain chain;
    private static boolean chainFailed;
    private static int chainWidth;
    private static int chainHeight;

    /** Точка противника на экране в долях, обновляется при отрисовке мира. */
    private static float centerX = 0.5F;
    private static float centerY = 0.5F;
    private static boolean centerVisible;

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
        centerVisible = true;
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
        if (p <= 0.01F || !ownEyes(minecraft)) {
            return;
        }
        p *= (float) (double) minecraft.options.screenEffectScale().get();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        boolean demonic = ClientAuraState.sourceDemonic();
        // Удар сердца: рамка на миг вздрагивает внутрь.
        float beat = (float) Math.exp(-ClientAuraState.beatAge(partial) / 3.0D);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // Рамка тушью: при слабом давлении увеличена, и в кадре остаются только кончики у
        // краёв; при полном — в натуральную величину, мазки заходят до трети экрана.
        float zoom = 1.0F + 0.9F * (1.0F - Mth.clamp(p + 0.08F * beat, 0.0F, 1.0F));
        int iw = Math.round(width * zoom);
        int ih = Math.round(height * zoom);
        int x0 = (width - iw) / 2;
        int y0 = (height - ih) / 2;
        float alpha = Mth.clamp(0.35F + 0.75F * p, 0.0F, 1.0F);
        if (demonic) {
            // Красный подслой чуть глубже чёрного: кромка мазков горит кровью.
            int rw = Math.round(width * (zoom - 0.06F));
            int rh = Math.round(height * (zoom - 0.06F));
            RenderSystem.setShaderColor(0.75F, 0.08F, 0.06F, alpha * 0.8F);
            graphics.blit(INK, (width - rw) / 2, (height - rh) / 2, rw, rh, 0.0F, 0.0F, 512, 288, 512, 288);
            RenderSystem.setShaderColor(0.12F, 0.0F, 0.0F, alpha);
        } else {
            RenderSystem.setShaderColor(0.03F, 0.035F, 0.045F, alpha);
        }
        graphics.blit(INK, x0, y0, iw, ih, 0.0F, 0.0F, 512, 288, 512, 288);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        if (p > 0.2F && ClientConfig.DISTORTION_EFFECTS.get()) {
            strokes(graphics, width, height, p, demonic, minecraft.level.getGameTime() + partial);
        }
        RenderSystem.disableBlend();
    }

    /**
     * Штрихи от краёв к противнику. Каждый ползёт внутрь и гаснет, на его место встаёт новый:
     * движение «затягивает» взгляд, неподвижные линии читались бы рамкой.
     */
    private static void strokes(GuiGraphics graphics, int width, int height, float p, boolean demonic, float time) {
        float strength = Mth.clamp((p - 0.2F) / 0.7F, 0.0F, 1.0F);
        float cx = (centerVisible ? centerX : 0.5F) * width;
        float cy = (1.0F - (centerVisible ? centerY : 0.5F)) * height;
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, STROKE);
        Matrix4f m = graphics.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        // Разбор codex 01.10: вместо частокола тонких спиц — 12–18 широких рваных пятен и
        // 20–30 коротких разрывов, неравномерно по периметру; заходят на 8–15 % экрана при
        // умеренном давлении и на 25–35 % при полном. Центр у противника остаётся открытым.
        int wide = 12 + Math.round(6 * strength);
        int thin = 20 + Math.round(10 * strength);
        float minSide = Math.min(width, height);
        for (int i = 0; i < wide + thin; i++) {
            boolean big = i < wide;
            long h = (i + 1) * 0x9E3779B97F4A7C15L + (big ? 0x5DEECE66DL : 0x2545F4914F6CDD1DL);
            h ^= h >>> 31;
            h *= 0xBF58476D1CE4E5B9L;
            h ^= h >>> 29;
            float a0 = (float) ((h & 0xFFFF) / 65535.0D * Math.PI * 2.0D);
            float speed = big ? 0.006F + 0.006F * ((h >>> 16 & 0xFF) / 255.0F) : 0.014F + 0.012F * ((h >>> 16 & 0xFF) / 255.0F);
            float phase = (time * speed + (h >>> 24 & 0xFF) / 255.0F) % 1.0F;
            float dx = Mth.cos(a0);
            float dy = Mth.sin(a0);
            float tx = dx > 0 ? (width - cx) / dx : dx < 0 ? -cx / dx : 1.0E6F;
            float ty = dy > 0 ? (height - cy) / dy : dy < 0 ? -cy / dy : 1.0E6F;
            float edge = Math.min(Math.abs(tx), Math.abs(ty)) * 1.04F;
            float depth = minSide * (0.08F + 0.24F * strength) * (0.6F + 0.4F * ((h >>> 40 & 0xFF) / 255.0F));
            float reach = big ? depth * 1.2F : depth * 0.7F;
            // Широкие стоят и дышат, тонкие ползут внутрь.
            float head = big ? edge - reach * (0.85F + 0.15F * Mth.sin(time * 0.1F + i))
                             : edge - depth * (0.3F + 0.9F * phase);
            float tail = big ? edge + 4.0F : head + reach;
            float halfWidth = big ? (10.0F + 16.0F * ((h >>> 48 & 0xFF) / 255.0F)) * (0.7F + 0.5F * strength)
                                  : 1.5F + 2.5F * ((h >>> 48 & 0xFF) / 255.0F);
            float alpha = big ? 0.35F + 0.45F * strength : (float) Math.sin(phase * Math.PI) * (0.35F + 0.35F * strength);
            // Плотные участки — чёрные, разрывы между ними — полупрозрачный серый #353D4C.
            boolean grey = !big && (h >>> 56 & 3) == 0;
            float r = demonic ? (grey ? 0.35F : 0.14F) : (grey ? 0.21F : 0.012F);
            float g = demonic ? (grey ? 0.05F : 0.01F) : (grey ? 0.24F : 0.02F);
            float bl = demonic ? (grey ? 0.05F : 0.01F) : (grey ? 0.30F : 0.031F);
            if (grey) {
                alpha *= 0.5F;
            }
            float hx = cx + dx * head, hy = cy + dy * head;
            float ex = cx + dx * tail, ey = cy + dy * tail;
            float nx = -dy * halfWidth, ny = dx * halfWidth;
            b.addVertex(m, ex + nx, ey + ny, 0.0F).setUv(0.0F, 0.0F).setColor(r, g, bl, alpha);
            b.addVertex(m, ex - nx, ey - ny, 0.0F).setUv(0.0F, 1.0F).setColor(r, g, bl, alpha);
            b.addVertex(m, hx - nx * 0.15F, hy - ny * 0.15F, 0.0F).setUv(1.0F, 1.0F).setColor(r, g, bl, alpha);
            b.addVertex(m, hx + nx * 0.15F, hy + ny * 0.15F, 0.0F).setUv(1.0F, 0.0F).setColor(r, g, bl, alpha);
        }
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    private PressureScreen() {
    }
}
