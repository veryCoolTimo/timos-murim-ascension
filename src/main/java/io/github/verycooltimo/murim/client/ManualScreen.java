package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.ManualPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Книга-манускрипт (автор 03.10): прочитать, потом «Изучить». Пиксель-арт развороты
 * (docs/design/reference/ui/books-v2-pixel): обычные техники — шаблон секты, текст на правой
 * странице; формы Семи Цветков — титул и иллюстрированное руководство, текст в нижней полосе.
 * Секретные (24 Движения) — пока на развороте Семи Цветков, свои развороты будут позже.
 */
public final class ManualScreen extends Screen {

    private static final int TEX_W = 384;
    private static final int TEX_H = 256;
    private static final ResourceLocation BASIC = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/gui/book/basic_huashan.png");
    private static final ResourceLocation PLUM = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/gui/book/seven_plum.png");
    private static final int INK = 0xFF1B1612;
    private static final int FADED = 0xFF4A3F35;

    private final ManualPayloads.Open open;
    private final boolean illustrated;
    private int bx;
    private int by;
    private float k;

    public static void open(ManualPayloads.Open open) {
        net.minecraft.client.Minecraft.getInstance().setScreen(new ManualScreen(open));
    }

    public ManualScreen(ManualPayloads.Open open) {
        super(io.github.verycooltimo.murim.mastery.MasteryService.name(open.technique()));
        this.open = open;
        String path = open.technique().getPath();
        this.illustrated = path.startsWith("seven_plum") || path.startsWith("twenty_four_plum");
    }

    @Override
    protected void init() {
        // Разворот — целым кратным масштабом, чтобы пиксели оставались квадратными.
        // Под разворотом: кнопка (28), у иллюстрированных ещё 3 строки описания формы.
        int reserve = illustrated ? 64 : 34;
        k = Math.max(1, Math.min((width - 16) / TEX_W, (height - reserve) / TEX_H));
        if ((width - 16) < TEX_W || (height - reserve) < TEX_H) {
            k = Math.min((width - 16) / (float) TEX_W, (height - reserve) / (float) TEX_H);
        }
        bx = (int) ((width - TEX_W * k) / 2);
        by = (int) ((height - reserve - TEX_H * k) / 2) + 2;
        addRenderableWidget(Button.builder(Component.translatable("murim.manual.learn"), b -> {
            PacketDistributor.sendToServer(new ManualPayloads.Learn(open.mainHand()));
            onClose();
        }).bounds(width / 2 - 50, (int) (by + TEX_H * k) + (illustrated ? 36 : 6), 100, 20).build());

    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.pose().pushPose();
        g.pose().translate(bx, by, 0.0F);
        g.pose().scale(k, k, 1.0F);
        g.blit(illustrated ? PLUM : BASIC, 0, 0, 0.0F, 0.0F, TEX_W, TEX_H, TEX_W, TEX_H);
        if (illustrated) {
            // В полосе руководства — только название формы; описание — под разворотом (в полосе не читалось).
            g.pose().pushPose();
            g.pose().translate(214, 236, 0.0F);
            g.pose().scale(0.6F, 0.6F, 1.0F);
            g.drawString(font, title, 0, 0, INK, false);
            g.pose().popPose();
        } else {
            // Рамка правой страницы шаблона секты.
            text(g, 214, 24, 144, 206, 0.8F);
        }
        g.pose().popPose();
        if (illustrated) {
            int ty = (int) (by + TEX_H * k) + 4;
            int tw = Math.min(width - 40, (int) (TEX_W * k));
            String key = "book.murim." + open.technique().getPath();
            if (I18n.exists(key)) {
                int ly = ty;
                for (FormattedCharSequence line : font.split(FormattedText.of(I18n.get(key)), tw).stream().limit(3).toList()) {
                    g.drawString(font, line, width / 2 - font.width(line) / 2, ly, 0xFFEFE3CC, true);
                    ly += font.lineHeight + 1;
                }
            }
        }
    }

    /** Название и текст техники в прямоугольнике страницы (координаты текстуры 384×256). */
    private void text(GuiGraphics g, int x, int y, int w, int h, float scale) {
        String path = open.technique().getPath();
        String key = "book.murim." + path;
        String body = I18n.exists(key) ? I18n.get(key)
                : I18n.exists("technique.murim." + path + ".weakness") ? I18n.get("technique.murim." + path + ".weakness") : "";
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(scale, scale, 1.0F);
        int lw = (int) (w / scale);
        int cy = 0;
        for (FormattedCharSequence line : font.split(title, lw)) {
            g.drawString(font, line, 0, cy, INK, false);
            cy += font.lineHeight + 1;
        }
        if (open.depth() > 0) {
            g.drawString(font, Component.translatable("murim.manual.torn", open.depth()), 0, cy, FADED, false);
            cy += font.lineHeight + 1;
        }
        cy += 4;
        List<FormattedCharSequence> lines = font.split(FormattedText.of(body), lw);
        for (FormattedCharSequence line : lines) {
            if (cy + font.lineHeight > h / scale) {
                break;
            }
            g.drawString(font, line, 0, cy, FADED, false);
            cy += font.lineHeight + 1;
        }
        g.pose().popPose();
    }
}
