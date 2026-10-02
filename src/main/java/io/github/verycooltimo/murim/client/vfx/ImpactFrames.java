package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.joml.Matrix4f;

/**
 * Импакт-кадры по разбору rimuru.dev (docs/03-vfx/13-rimuru-impact-frames.md, «Падение дерева»):
 * удар показывается сменой способа изображения — белая вспышка → негатив (светлое становится
 * тушью: ствол и ветви — чёрные массы на бумаге) → киноварь с чёрным → обычный мир.
 * Без шейдеров: негатив — белый квадрат со смешиванием {@code 1 − dst}, перекраска —
 * умножение ({@code dst × цвет}).
 *
 * <p>Уважает «экранные вспышки» ({@link ClientConfig#SCREEN_FLASHES}): без них — только
 * киноварная фаза приглушённо. Один импакт — одна вспышка, серии не копятся.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ImpactFrames {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "impact_frames");

    /** Тики фаз: вспышка, негатив, киноварь. */
    private static final int FLASH = 1;
    private static final int NEGATIVE = 2;
    private static final int RED = 2;

    private static int ticks;
    private static int born = -100;

    /** Запустить импакт-кадры (только своему экрану). */
    public static void trigger() {
        if (ticks - born < FLASH + NEGATIVE + RED + 6) {
            return;
        }
        born = ticks;
    }

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.CROSSHAIR, LAYER, ImpactFrames::render);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!Minecraft.getInstance().isPaused()) {
            ticks++;
        }
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        int age = ticks - born;
        if (age < 0 || age >= FLASH + NEGATIVE + RED) {
            return;
        }
        boolean flashes = ClientConfig.SCREEN_FLASHES.get();
        float w = graphics.guiWidth();
        float h = graphics.guiHeight();
        Matrix4f m = graphics.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        if (age < FLASH) {
            if (flashes) {
                RenderSystem.defaultBlendFunc();
                quad(m, w, h, 1.0F, 1.0F, 1.0F, 0.92F);
            }
        } else if (age < FLASH + NEGATIVE) {
            if (flashes) {
                // Негатив: светлое → тушь. Затем тёплая бумага умножением.
                RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR, GlStateManager.DestFactor.ZERO);
                quad(m, w, h, 1.0F, 1.0F, 1.0F, 1.0F);
                RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
                quad(m, w, h, 1.0F, 0.86F, 0.8F, 1.0F);
            }
        } else {
            // Киноварь с чёрным: негатив, умноженный на #F05A3C (без вспышек — только лёгкий тон).
            if (flashes) {
                RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR, GlStateManager.DestFactor.ZERO);
                quad(m, w, h, 1.0F, 1.0F, 1.0F, 1.0F);
            }
            RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
            quad(m, w, h, 0xF0 / 255.0F, flashes ? 0x5A / 255.0F : 0.7F, flashes ? 0x3C / 255.0F : 0.65F, 1.0F);
        }
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void quad(Matrix4f m, float w, float h, float r, float g, float b, float a) {
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buf.addVertex(m, 0.0F, h, 0.0F).setColor(r, g, b, a);
        buf.addVertex(m, w, h, 0.0F).setColor(r, g, b, a);
        buf.addVertex(m, w, 0.0F, 0.0F).setColor(r, g, b, a);
        buf.addVertex(m, 0.0F, 0.0F, 0.0F).setColor(r, g, b, a);
        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    private ImpactFrames() {
    }
}
