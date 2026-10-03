package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Название техники на пике ритуала.
 *
 * <p>Приём из манхвы: имя удара объявляется до самого удара, и это делает технику событием,
 * а не анимацией. Появляется в конце ритуала, когда энергия уже собрана, и гаснет к моменту
 * взмаха — на самом ударе текст мешал бы смотреть на клинок.
 *
 * <p>Текст рисуется по центру и чуть выше середины, не перекрывая ни прицел, ни хотбар.
 * Масштаб слегка растёт: неподвижная надпись читается как элемент интерфейса, растущая —
 * как часть постановки.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class TechniqueNameLayer {

    private static final ResourceLocation LAYER =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "technique_name");

    /** Сколько тиков живёт надпись. */
    private static final float DURATION_TICKS = 26.0F;

    private static float remaining;
    private static float delay;
    private static Component name = Component.empty();

    /**
     * Показывает название с задержкой.
     *
     * <p>Задержка обязательна: событие начала техники приходит в первый тик, а по раскадровке
     * имя объявляется на пике — когда энергия уже собрана и удар вот-вот случится. Без неё
     * надпись вспыхивала бы в самом начале трёхсекундного ритуала и успевала погаснуть
     * задолго до взмаха.
     */
    public static void show(Component techniqueName, int delayTicks) {
        name = techniqueName;
        delay = Math.max(0, delayTicks);
        remaining = DURATION_TICKS;
    }

    /** Гасит надпись: смена мира не должна оставлять её висеть. */
    public static void reset() {
        remaining = 0.0F;
        delay = 0.0F;
    }

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, LAYER, TechniqueNameLayer::render);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (delay > 0.0F) {
            delay -= 1.0F;
        } else if (remaining > 0.0F) {
            remaining = Math.max(0.0F, remaining - 1.0F);
        }
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        // Слои мода обёрткой hideGui не накрываются — GuiLayerManager вешает её только
        // на ванильные слои, поэтому проверяем сами.
        if (minecraft.options.hideGui || delay > 0.0F || remaining <= 0.0F || TechniqueCaption.active()) {
            return;
        }
        float age = remaining - delta.getGameTimeDeltaPartialTick(false);
        float life = Mth.clamp(age / DURATION_TICKS, 0.0F, 1.0F);

        // Быстрое появление, долгое затухание: имя должно возникнуть резко, как объявление.
        float alpha = life > 0.85F ? (1.0F - life) / 0.15F : life / 0.85F;
        alpha = Mth.clamp(alpha, 0.0F, 1.0F);
        if (alpha <= 0.01F) {
            return;
        }
        // Ванильная настройка силы экранных эффектов уважается и здесь.
        alpha *= (float) (double) minecraft.options.screenEffectScale().get();
        if (alpha <= 0.01F) {
            return;
        }

        Font font = minecraft.font;
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        float scale = 1.6F + 0.35F * (1.0F - life);

        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(width / 2.0F, height * 0.34F, 0.0F);
            pose.scale(scale, scale, 1.0F);
            int colour = (int) (alpha * 255.0F) << 24 | 0x00E4F0FF;
            // Тень отключена: на масштабе больше единицы она уезжает и читается как двоение.
            graphics.drawString(font, name, -font.width(name) / 2, -font.lineHeight / 2,
                                colour, false);
        } finally {
            pose.popPose();
        }
    }

    private TechniqueNameLayer() {
    }
}
