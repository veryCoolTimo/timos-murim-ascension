package io.github.verycooltimo.murim.client.sect;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.DialoguePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Разговор с NPC (автор 03.10: «как в играх — экран, и там NPC стоит, и диалог, а не в виде картинки»):
 * мир виден, камера за плечом ({@link DialogueCamera}), сверху и снизу — тонкие кинополосы, внизу —
 * полупрозрачный свиток в стиле экрана раскладки (тот же пиксель-арт: бумага, бирюзовая парча, валики).
 * На свитке тушью: имя и титул, реплика печатается, справа — 2–4 ответа, мышью или клавишами 1–4.
 *
 * <p>Экран ничего не решает: номер варианта уходит серверу, ответ — новая реплика или конец разговора.
 */
public final class DialogueScreen extends Screen {

    private static final ResourceLocation PANEL = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/gui/dialogue_panel.png");
    private static final int PW = 380;
    private static final int PH = 88;
    /** Поля бумаги внутри свитка (валики 16, парча 10). */
    private static final int IN_X = 22;
    private static final int IN_Y = 13;
    /** Колонка ответов справа (codex 03.10: ~40 % ширины, ряды фиксированной высоты). */
    private static final int OPT_X = 226;
    private static final int OPT_RIGHT = PW - 20;
    /** Масштаб реплики и ответов (codex: текст крупнее, иерархия имя > реплика > титул). */
    private static final float SPEECH = 1.0F;
    private static final float OPTION = 0.9F;

    private static final int INK = 0xFF1B1612;
    private static final int FADED = 0xFF5A4E42;
    private static final int SEAL = 0xFFB3262C;
    private static final int SEAL_TEXT = 0xFFF3E6D0;

    /** Печать текста, символов за тик. */
    private static final float CPS = 1.7F;

    private DialoguePayloads.Open line;
    private String full;
    private float typed;
    private int ticks;
    private int lineTicks;
    private boolean sent;
    private int hover = -1;
    /** Выбранный клавишами стрелок/наведением ответ (подсветка строки). */
    private int focus = -1;
    private int px;
    private int py;
    private float k = 1.0F;

    private DialogueScreen(DialoguePayloads.Open line) {
        super(line.name());
        setLine(line);
    }

    /** Новая реплика: открыть экран и камеру или продолжить разговор. */
    public static void open(DialoguePayloads.Open payload) {
        Minecraft mc = Minecraft.getInstance();
        DialogueCamera.start(payload.npc());
        if (mc.screen instanceof DialogueScreen ds) {
            ds.setLine(payload);
        } else {
            mc.setScreen(new DialogueScreen(payload));
        }
        if (!payload.playerAnim().isEmpty() && mc.player != null) {
            io.github.verycooltimo.murim.client.MurimPlayerAnimations.play(mc.player, ResourceLocation.parse(payload.playerAnim()));
        }
    }

    /** Сервер закончил разговор. */
    public static void closeFromServer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DialogueScreen ds) {
            ds.sent = true;
            mc.setScreen(null);
        }
        DialogueCamera.stop();
    }

    private void setLine(DialoguePayloads.Open l) {
        this.line = l;
        this.full = l.line().getString();
        this.typed = 0.0F;
        this.lineTicks = 0;
        this.sent = false;
        this.hover = -1;
        this.focus = -1;
        this.typedDoneTick = 0;
    }

    private boolean typing() {
        return typed < full.length();
    }

    @Override
    protected void init() {
        // Свиток 1:1 к пикселям текстуры; на узком окне — уже.
        k = Math.min(1.0F, (width - 16) / (float) PW);
        px = (int) ((width - PW * k) / 2);
        // Свиток целиком над нижней кинополосой.
        py = (int) (height - PH * k - bar(1.0F) - 8);
    }

    @Override
    public void tick() {
        ticks++;
        lineTicks++;
        // Первая реплика пишется, когда камера доехала: наезд не перебивает текст (codex 03.10).
        if (typing() && DialogueCamera.amount(0.0F) > 0.9F) {
            int before = (int) typed;
            typed = Math.min(full.length(), typed + CPS);
            if (!typing()) {
                typedDoneTick = lineTicks;
            }
            // Тихий шорох кисти раз в несколько символов — текст «пишется».
            if ((int) typed / 6 != before / 6 && minecraft != null) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.8F, 0.08F));
            }
        }
        if (minecraft != null && minecraft.player != null && minecraft.level != null
                && !(minecraft.level.getEntity(line.npc()) instanceof net.minecraft.world.entity.LivingEntity)) {
            onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Мир не размывается и не затемняется: разговор идёт в кадре. Только кинополосы.
        float a = DialogueCamera.amount(partial);
        int bar = bar(a);
        if (bar > 0) {
            g.fill(0, 0, width, bar, 0xFF000000);
            g.fill(0, height - bar, width, height, 0xFF000000);
        }
    }

    private int bar(float a) {
        return Math.round(height * 0.05F * a);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g, mouseX, mouseY, partial);
        // Свиток проявляется, когда камера почти доехала (codex: не поверх пролёта камеры).
        float appear = Mth.clamp((DialogueCamera.amount(partial) - 0.8F) / 0.2F, 0.0F, 1.0F);
        if (appear <= 0.0F) {
            return;
        }
        g.pose().pushPose();
        // Свиток выезжает снизу на несколько пикселей и проявляется.
        g.pose().translate(px, py + (1.0F - appear) * 10.0F, 0.0F);
        g.pose().scale(k, k, 1.0F);
        RenderSystem.enableBlend();
        g.setColor(1.0F, 1.0F, 1.0F, 0.88F * appear);
        g.blit(PANEL, 0, 0, 0.0F, 0.0F, PW, PH, PW, PH);
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        int alpha = Math.max(4, (int) (appear * 255)) << 24;
        speaker(g, alpha);
        speech(g, alpha, partial);
        options(g, mouseX, mouseY, alpha);
        g.pose().popPose();
        RenderSystem.disableBlend();
    }

    /** Имя тушью и титул бледнее; у имени — маленькая красная печать. */
    private void speaker(GuiGraphics g, int alpha) {
        int x = IN_X;
        int y = IN_Y;
        g.fill(x, y + 1, x + 3, y + 8, SEAL & 0x00FFFFFF | alpha);
        g.drawString(font, line.name(), x + 6, y, INK & 0x00FFFFFF | alpha, false);
        if (!line.title().getString().isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(x + 6, y + 10, 0.0F);
            g.pose().scale(0.7F, 0.7F, 1.0F);
            g.drawString(font, line.title(), 0, 0, FADED & 0x00FFFFFF | alpha, false);
            g.pose().popPose();
        }
        // Тонкий мазок туши под именем.
        g.fill(x, y + 17, OPT_X - 12, y + 18, 0x401B1612);
    }

    /** Реплика: печатается посимвольно, перенос по ширине левой колонки. */
    private void speech(GuiGraphics g, int alpha, float partial) {
        int w = (line.options().isEmpty() ? PW - 2 * IN_X : OPT_X - IN_X - 10);
        float shown = Math.min(full.length(), typed + (typing() ? CPS * partial : 0.0F));
        String part = full.substring(0, (int) shown);
        float s = SPEECH;
        int room = PH - IN_Y - 20 - 9;
        // Long lines (EN runs ~20 % longer) were cut at the bottom: shrink from the full line, so the size does not jump while typing.
        while (s > SPEECH * 0.75F && font.split(FormattedText.of(full), (int) (w / s)).size() * font.lineHeight * s > room) {
            s -= SPEECH * 0.05F;
        }
        g.pose().pushPose();
        g.pose().translate(IN_X, IN_Y + 20, 0.0F);
        g.pose().scale(s, s, 1.0F);
        List<FormattedCharSequence> lines = font.split(FormattedText.of(part), (int) (w / s));
        int y = 0;
        int max = (int) ((PH - IN_Y - 20 - 9) / s);
        for (FormattedCharSequence l : lines) {
            if (y + font.lineHeight > max) {
                break;
            }
            g.drawString(font, l, 0, y, INK & 0x00FFFFFF | alpha, false);
            y += font.lineHeight;
        }
        g.pose().popPose();
        if (!typing() && line.options().isEmpty() && (ticks / 10) % 2 == 0) {
            g.drawString(font, "▾", PW - IN_X - 6, PH - 20, FADED & 0x00FFFFFF | alpha, false);
        }
    }

    /** Ответы справа: номер в красной печати, текст тушью; наведённый — печать темнее, текст чернее. */
    private void options(GuiGraphics g, int mouseX, int mouseY, int alpha) {
        if (line.options().isEmpty()) {
            return;
        }
        // Вертикальный мазок, отделяющий ответы от реплики.
        g.fill(OPT_X - 6, IN_Y + 2, OPT_X - 5, PH - 12, 0x30000000);
        float fade = typing() ? 0.0F : Mth.clamp((lineTicks - typedDoneTick) / 5.0F, 0.0F, 1.0F);
        if (fade <= 0.0F) {
            return;
        }
        int oa = (int) (fade * (alpha >>> 24)) << 24;
        if (focus < 0) {
            // Фокус по умолчанию на первом ответе: Enter/E сразу работают, как в RPG.
            focus = 0;
        }
        int at = optionAt(mouseX, mouseY);
        if (at != hover) {
            hover = at;
            if (at >= 0) {
                focus = at;
            }
        }
        int n = line.options().size();
        int step = optionStep(n);
        int y0 = optionTop(n);
        float s = OPTION;
        for (int i = 0; i < n; i++) {
            int y = y0 + i * step;
            boolean hot = i == focus;
            if (hot) {
                // Строка в фокусе: лёгкая заливка тушью и красная черта слева.
                g.fill(OPT_X - 3, y - 2, OPT_RIGHT, y + step - 3, 0x261B1612 & 0x00FFFFFF | (int) (0x26 * fade) << 24);
                g.fill(OPT_X - 3, y - 2, OPT_X - 1, y + step - 3, SEAL & 0x00FFFFFF | oa);
            }
            int sx = OPT_X + 2;
            g.fill(sx, y, sx + 9, y + 9, (hot ? 0x7E1A1F : SEAL & 0x00FFFFFF) | oa);
            g.pose().pushPose();
            g.pose().translate(sx + 2.5F, y + 1.0F, 0.0F);
            g.pose().scale(0.85F, 0.85F, 1.0F);
            g.drawString(font, String.valueOf(i + 1), 0, 0, SEAL_TEXT & 0x00FFFFFF | oa, false);
            g.pose().popPose();
            // Two lines per option: a longer reply (RU runs past two) shrinks instead of losing its tail (gallery 05.10).
            s = OPTION;
            List<FormattedCharSequence> wrapped = font.split(line.options().get(i), (int) ((OPT_RIGHT - sx - 13) / s));
            while (wrapped.size() > 2 && s > OPTION * 0.75F) {
                s -= OPTION * 0.05F;
                wrapped = font.split(line.options().get(i), (int) ((OPT_RIGHT - sx - 13) / s));
            }
            g.pose().pushPose();
            g.pose().translate(sx + 13, y + 1.0F, 0.0F);
            g.pose().scale(s, s, 1.0F);
            int ly = 0;
            for (int j = 0; j < Math.min(2, wrapped.size()); j++) {
                g.drawString(font, wrapped.get(j), 0, ly, INK & 0x00FFFFFF | oa, false);
                ly += font.lineHeight;
            }
            g.pose().popPose();
        }
    }

    private int typedDoneTick;

    private int optionStep(int n) {
        return n <= 2 ? 20 : n == 3 ? 19 : 16;
    }

    private int optionTop(int n) {
        int area = PH - IN_Y - 10;
        return IN_Y + Math.max(0, (area - optionStep(n) * n) / 2) + 1;
    }

    private int optionAt(double mouseX, double mouseY) {
        double tx = (mouseX - px) / k;
        double ty = (mouseY - py) / k;
        int n = line.options().size();
        if (typing() || tx < OPT_X - 3 || tx > OPT_RIGHT) {
            return -1;
        }
        int step = optionStep(n);
        int y0 = optionTop(n);
        for (int i = 0; i < n; i++) {
            if (ty >= y0 + i * step - 2 && ty < y0 + i * step + step - 2) {
                return i;
            }
        }
        return -1;
    }

    private void skipTyping() {
        typed = full.length();
        typedDoneTick = lineTicks;
    }

    private void choose(int i) {
        if (sent) {
            return;
        }
        if (typing()) {
            skipTyping();
            return;
        }
        if (line.options().isEmpty()) {
            // Реплика без ответов — клик закрывает разговор.
            sent = true;
            PacketDistributor.sendToServer(new DialoguePayloads.Choose(-1));
            return;
        }
        if (i < 0 || i >= line.options().size()) {
            return;
        }
        sent = true;
        if (minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.4F, 0.25F));
        }
        PacketDistributor.sendToServer(new DialoguePayloads.Choose(i));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int at = optionAt(mouseX, mouseY);
            choose(typing() || line.options().isEmpty() ? 0 : at);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_4) {
            choose(key - GLFW.GLFW_KEY_1);
            return true;
        }
        if (key >= GLFW.GLFW_KEY_KP_1 && key <= GLFW.GLFW_KEY_KP_4) {
            choose(key - GLFW.GLFW_KEY_KP_1);
            return true;
        }
        int n = line.options().size();
        if ((key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_S) && n > 0 && !typing()) {
            focus = (focus + 1) % n;
            return true;
        }
        if ((key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_W) && n > 0 && !typing()) {
            focus = focus <= 0 ? n - 1 : focus - 1;
            return true;
        }
        if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_E) {
            if (typing()) {
                skipTyping();
            } else if (n == 0) {
                choose(0);
            } else if (focus >= 0) {
                choose(focus);
            }
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void onClose() {
        if (!sent) {
            sent = true;
            PacketDistributor.sendToServer(new DialoguePayloads.Choose(-1));
        }
        DialogueCamera.stop();
        super.onClose();
    }

    @Override
    public void removed() {
        // Экран сменили (смерть, другой экран) — камера возвращается в глаза.
        if (!(Minecraft.getInstance().screen instanceof DialogueScreen)) {
            DialogueCamera.stop();
        }
    }

    /** Для стенда: печать закончена и ответы видны. */
    public boolean ready() {
        return !typing();
    }

    /** Для стенда: выбрать ответ как клавишей. */
    public void press(int i) {
        choose(i);
    }
}
