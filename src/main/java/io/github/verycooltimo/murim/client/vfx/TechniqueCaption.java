package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
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
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Надпись приёма, как в манхве (ref4, ref10 Семи Цветков Сливы): слева сверху мелко — школа
 * («ULTIMATE SEVEN PLUM BLOSSOMS SWORD TECHNIQUE»), под ней крупно, по слову в строку, —
 * форма («PLUM BLOSSOM SLASH»). Белые буквы в чёрной обводке, с наклоном; влетают крупнее
 * и садятся за 3 тика, гаснут за последние 8. Видит только тот, кто применяет.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class TechniqueCaption {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "technique_caption");

    private static Component school = Component.empty();
    private static Component form = Component.empty();
    private static int born = -1;
    private static int life;
    private static int ticks;
    private static boolean secret;

    /** Большая надпись на экране: мелкое имя техники в это время не рисуется, чтобы не налезать. */
    public static boolean active() {
        return born >= 0 && ticks - born < life;
    }

    public static void show(Component schoolName, Component formName, int lifeTicks) {
        school = schoolName;
        form = formName;
        life = lifeTicks;
        born = ticks;
        secret = false;
    }

    /**
     * Надпись секретной техники (Меч 24 Движений): розово-белая фактура в тёмной обводке,
     * проявление мазком, лепестки с краёв букв — см. {@link SecretCaption}. Обычные формы
     * (Семь Цветков и др.) по-прежнему идут через {@link #show}.
     */
    public static void showSecret(Component schoolName, Component formName, int lifeTicks) {
        show(schoolName, formName, lifeTicks);
        secret = true;
        SecretCaption.reset();
    }

    /** Удар под надписью (касание ливня): толчок, вспышка фактуры, лепестки. Только для секретной. */
    public static void impact(float power) {
        if (secret && active()) {
            SecretCaption.impact(power);
        }
    }

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, LAYER, TechniqueCaption::render);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!Minecraft.getInstance().isPaused()) {
            ticks++;
        }
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (born < 0 || mc.options.hideGui && !"1".equals(System.getenv("MURIM_CAPTURE_GUI"))) {
            return;
        }
        float age = ticks - born + delta.getGameTimeDeltaPartialTick(false);
        if (age > life) {
            born = -1;
            return;
        }
        if (secret) {
            SecretCaption.render(graphics, mc.font, school, form, age, life);
            return;
        }
        float alpha = Mth.clamp(age / 1.5F, 0.0F, 1.0F) * Mth.clamp((life - age) / 8.0F, 0.0F, 1.0F);
        if (alpha <= 0.02F) {
            return;
        }
        float slam = 1.0F + 0.5F * Math.max(0.0F, 1.0F - age / 3.0F);
        Font font = mc.font;
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(w * 0.05F, h * 0.08F, 0.0F);
            // Наклон букв вправо, как у кисти в манхве.
            pose.mulPose(new Matrix4f(1.0F, 0.0F, 0.0F, 0.0F, -0.16F, 1.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F));
            float y = 0.0F;
            float small = Math.max(1.0F, h / 260.0F) * slam;
            for (String line : wrap(school.getString().toUpperCase(Locale.ROOT), 14)) {
                text(graphics, font, line, 0.0F, y, small, alpha);
                y += (font.lineHeight + 1) * small;
            }
            y += font.lineHeight * small * 0.8F;
            float big = Math.max(2.0F, h / 115.0F) * slam;
            for (String word : form.getString().toUpperCase(Locale.ROOT).split(" ")) {
                text(graphics, font, word, small * 6.0F, y, big, alpha);
                y += (font.lineHeight + 1) * big;
            }
        } finally {
            pose.popPose();
        }
    }

    /**
     * Шрифт надписи — Reggae One (OFL, assets/murim/font/caption-ofl.txt): кисть с засечками,
     * латиница и кириллица одного характера. Выбор автора 02.10 из шести вариантов.
     */
    private static final ResourceLocation FONT = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "caption");

    /** Строка белым в чёрной обводке (8 смещений на 1 пиксель шрифта). */
    private static void text(GuiGraphics g, Font font, String raw, float x, float y, float scale, float alpha) {
        Component s = Component.literal(raw).withStyle(st -> st.withFont(FONT));
        PoseStack pose = g.pose();
        pose.pushPose();
        try {
            pose.translate(x, y, 0.0F);
            pose.scale(scale, scale, 1.0F);
            int a = (int) (alpha * 255.0F) << 24;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0) {
                        g.drawString(font, s, dx, dy, a | 0x12060A, false);
                    }
                }
            }
            g.drawString(font, s, 0, 0, a | 0xFFFFFF, false);
        } finally {
            pose.popPose();
        }
    }

    private static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    private TechniqueCaption() {
    }
}
