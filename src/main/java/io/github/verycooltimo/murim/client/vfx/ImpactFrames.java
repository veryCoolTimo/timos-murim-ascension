package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Импакт-кадры по разбору rimuru.dev (docs/03-vfx/13-rimuru-impact-frames.md): удар показан
 * сменой способа изображения — классический чёрно-белый. Последовательность по тикам:
 * белый разрыв → почти чёрный кадр с белыми контурами → графическая версия (бумага, тушь,
 * растр, радиальные штрихи; 2 тика) → смена полярности → обычный мир. ~0,25 с.
 *
 * <p>Шейдер {@code murim:impact} поверх мира и руки, до интерфейса — как давление ауры
 * ({@link PressureScreen}): та же схема PostChain. Без «экранных вспышек» остаётся только
 * графическая версия, без белого разрыва и смены полярности.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ImpactFrames {

    private static final ResourceLocation CHAIN = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "shaders/post/impact.json");
    /** Режим шейдера по тику: вспышка, тьма с контурами, графика ×2, полярность. */
    private static final int[] SEQUENCE = {0, 1, 2, 2, 3};

    private static PostChain chain;
    private static boolean chainFailed;
    private static int chainWidth = -1;
    private static int chainHeight = -1;
    private static int ticks;
    private static int born = -100;
    private static float centerX = 0.5F;
    private static float centerY = 0.5F;
    private static float seed;

    /** Запустить импакт-кадры своему экрану; центр штрихов — точка экрана (0..1, Y снизу). */
    public static void trigger(float screenX, float screenY) {
        if (ticks - born < SEQUENCE.length + 6) {
            return;
        }
        born = ticks;
        centerX = screenX;
        centerY = screenY;
        seed = (ticks % 97) * 0.37F;
    }

    public static void trigger() {
        trigger(0.5F, 0.45F);
    }

    /** Точка удара в мире: центр штрихов пересчитывается в экран при отрисовке мира. */
    private static net.minecraft.world.phys.Vec3 contact;

    public static void trigger(net.minecraft.world.phys.Vec3 worldContact) {
        int before = born;
        trigger(0.5F, 0.45F);
        if (born != before) {
            contact = worldContact;
        }
    }

    @SubscribeEvent
    static void onRenderStage(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES
                || contact == null || ticks - born >= SEQUENCE.length) {
            return;
        }
        // API: как PressureScreen#onRenderStage — проекция × вид, деление на w.
        net.minecraft.world.phys.Vec3 at = contact.subtract(event.getCamera().getPosition());
        org.joml.Vector4f clip = new org.joml.Vector4f((float) at.x, (float) at.y, (float) at.z, 1.0F);
        new org.joml.Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix()).transform(clip);
        if (clip.w > 0.05F) {
            centerX = net.minecraft.util.Mth.clamp(clip.x / clip.w * 0.5F + 0.5F, 0.05F, 0.95F);
            centerY = net.minecraft.util.Mth.clamp(clip.y / clip.w * 0.5F + 0.5F, 0.05F, 0.95F);
        }
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!Minecraft.getInstance().isPaused()) {
            ticks++;
        }
    }

    @SubscribeEvent
    static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        int age = ticks - born;
        if (age < 0 || age >= SEQUENCE.length || chainFailed) {
            return;
        }
        int mode = SEQUENCE[age];
        if (!ClientConfig.SCREEN_FLASHES.get() && mode != 2) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int w = minecraft.getWindow().getWidth();
        int h = minecraft.getWindow().getHeight();
        if (chain == null) {
            try {
                chain = new PostChain(minecraft.getTextureManager(), minecraft.getResourceManager(),
                        minecraft.getMainRenderTarget(), CHAIN);
            } catch (Exception e) {
                chainFailed = true;
                MurimMod.LOGGER.warn("Шейдер импакт-кадров не загрузился", e);
                return;
            }
            chainWidth = -1;
        }
        if (w != chainWidth || h != chainHeight) {
            chain.resize(w, h);
            chainWidth = w;
            chainHeight = h;
        }
        chain.setUniform("Mode", mode);
        chain.setUniform("CenterX", centerX);
        chain.setUniform("CenterY", centerY);
        chain.setUniform("Seed", seed + (mode == 2 ? age : 0));
        // Ночная сцена тёмная: поднимаем экспозицию, чтобы растр не съел всё.
        chain.setUniform("Exposure", 1.8F);
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.resetTextureMatrix();
        chain.process(event.getPartialTick().getGameTimeDeltaTicks());
        minecraft.getMainRenderTarget().bindWrite(true);
        RenderSystem.enableDepthTest();
    }

    private ImpactFrames() {
    }
}
