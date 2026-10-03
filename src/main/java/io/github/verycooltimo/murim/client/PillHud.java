package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.AbsorbGame;
import io.github.verycooltimo.murim.cultivation.PillKind;
import io.github.verycooltimo.murim.cultivation.PillRules;
import io.github.verycooltimo.murim.network.PillPayloads;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Интерфейс пилюль (docs/design/19b §2): окно «сразу» у хотбара и минимум во время
 * поглощения — главное идёт на теле. Автор 03.10: «главное, чтобы не было скучно или
 * непонятно» — поэтому в первом поглощении подсказки словами, дальше только знаки.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class PillHud {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "pills");

    /** Подсказки словами — на первых двух развилках каждой сессии. */
    private static final int HINT_FORKS = 2;

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, LAYER, PillHud::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        PillPayloads.Sync s = ClientPillState.state();
        int finale = ClientPillState.finaleAge();
        if (finale >= 22 && finale < 46 && ClientPillState.finaleRare()) {
            speedLines(g, finale + delta.getGameTimeDeltaPartialTick(false) - 22.0F);
        }
        if (s.active()) {
            game(g, mc, s, delta.getGameTimeDeltaPartialTick(false));
        } else if (s.windowLeft() > 0 && s.pending().length > 0 && !mc.options.hideGui) {
            window(g, mc, s);
        }
    }

    /** Окно «сразу»: съеденные пилюли и тающая полоса над хотбаром, подсказка «G — поглотить». */
    private static void window(GuiGraphics g, Minecraft mc, PillPayloads.Sync s) {
        int w = g.guiWidth();
        int h = g.guiHeight();
        int n = s.pending().length;
        int x0 = w / 2 - n * 9;
        int y = h - 60;
        for (int i = 0; i < n; i++) {
            g.renderItem(stack(PillKind.byId(s.pending()[i])), x0 + i * 18, y);
        }
        float k = Mth.clamp(s.windowLeft() / (float) PillRules.WINDOW_TICKS, 0.0F, 1.0F);
        int bw = Math.max(36, n * 18);
        int bx = w / 2 - bw / 2;
        g.fill(bx, y + 18, bx + bw, y + 19, 0x60FFFFFF);
        g.fill(bx, y + 18, bx + (int) (bw * Math.min(1.0F, k)), y + 19, 0xE09FF0DC);
        Component hint = Component.translatable("murim.pill.hud.sit", ModKeyMappings.MEDITATION.getTranslatedKeyMessage());
        small(g, mc.font, hint, w / 2, y - 9, 0xE0CFEFFF);
    }

    private static ItemStack stack(PillKind kind) {
        return new ItemStack(switch (kind) {
            case SNOW_PLUM -> ModItems.PILL_SNOW_PLUM.get();
            case ORIGIN_ENERGY -> ModItems.PILL_ORIGIN_ENERGY.get();
            case THOUSAND_POISON -> ModItems.PILL_THOUSAND_POISON.get();
            case BEAUTY_TEAR -> ModItems.BEAUTY_TEAR.get();
        });
    }

    /**
     * Во время поглощения: сверху — какие сгустки (иконки, текущий выделен), полоса напряжения;
     * на развилке — стрелки ← → с выбором; в первых развилках — подсказка словами.
     */
    private static void game(GuiGraphics g, Minecraft mc, PillPayloads.Sync s, float partial) {
        int w = g.guiWidth();
        int h = g.guiHeight();
        int cx = w / 2;
        int top = 8;
        int[] clots = s.clots();
        int x0 = cx - clots.length * 10;
        for (int i = 0; i < clots.length; i++) {
            int x = x0 + i * 20;
            if (i == s.clot()) {
                g.fill(x - 1, top - 1, x + 17, top + 17, 0x60FFFFFF);
            }
            g.renderItem(stack(PillKind.byId(clots[i])), x, top);
        }
        // Напряжение: красная полоса и красные края экрана — язык ошибки кольца.
        int barW = 70;
        int by = top + 21;
        g.fill(cx - barW / 2 - 1, by - 1, cx + barW / 2 + 1, by + 5, 0x90101018);
        g.fill(cx - barW / 2, by, cx - barW / 2 + (int) (barW * s.strain()), by + 4, 0xF0E03020);
        if (s.strain() > 0.05F) {
            int a = (int) (Mth.clamp(s.strain(), 0.0F, 1.0F) * 0x70);
            int edge = Math.max(6, h / 10);
            g.fillGradient(0, 0, w, edge, (a << 24) | 0xB01010, 0x00B01010);
            g.fillGradient(0, h - edge, w, h, 0x00B01010, (a << 24) | 0xB01010);
        }
        AbsorbGame.Phase phase = ClientPillState.phase();
        boolean reading = phase == AbsorbGame.Phase.APPROACH
                && ClientPillState.phaseTicks() >= AbsorbGame.APPROACH_TICKS - AbsorbGame.READ_TICKS;
        int forkIndex = forkNumber(s);
        // Стрелки выбора по краям: ← и →, выбранная яркая. Показывает, что мышь сработала.
        int choice = ClientPillState.choice();
        if (phase == AbsorbGame.Phase.APPROACH) {
            int ay = h / 2 + 30;
            int off = Math.min(64, w / 6);
            arrow(g, mc.font, "◀", cx - off, ay, choice < 0, reading);
            arrow(g, mc.font, "▶", cx + off, ay, choice > 0, reading);
        }
        if (forkIndex < HINT_FORKS) {
            int ly = h - 34;
            if (phase == AbsorbGame.Phase.APPROACH && !reading) {
                small(g, mc.font, Component.translatable("murim.pill.hud.watch"), cx, ly, 0xE0CFEFFF);
            } else if (reading) {
                small(g, mc.font, Component.translatable(s.temper() == 2 ? "murim.pill.hud.wild" : "murim.pill.hud.calm"),
                        cx, ly, s.temper() == 2 ? 0xF0FF9A7A : 0xF0BFEFFF);
                small(g, mc.font, Component.translatable("murim.pill.hud.mouse"), cx, ly + 9, 0xC0C8DCEC);
            }
        }
    }

    /**
     * Тёмные линии скорости от краёв кадра к персонажу (рефы pill-01, pill-04) — на выбросе.
     * Тонкие клинья: широкие у края, сходят на нет к центру; центр свободен.
     */
    private static void speedLines(GuiGraphics g, float age) {
        float k = Mth.clamp(age / 4.0F, 0.0F, 1.0F) * (1.0F - Mth.clamp((age - 12.0F) / 12.0F, 0.0F, 1.0F));
        if (k <= 0.0F) {
            return;
        }
        int w = g.guiWidth();
        int h = g.guiHeight();
        float cx = w / 2.0F;
        float cy = h * 0.55F;
        float reach = (float) Math.hypot(w, h) * 0.6F;
        java.util.Random r = new java.util.Random(31L);
        var matrix = g.pose().last().pose();
        var vc = g.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
        int alpha = (int) (0xB0 * k);
        for (int i = 0; i < 40; i++) {
            double a = i * Math.PI * 2 / 40 + r.nextDouble() * 0.12D;
            float inner = reach * (0.42F + 0.2F * r.nextFloat());
            float half = 1.2F + 2.2F * r.nextFloat();
            float dx = (float) Math.cos(a);
            float dy = (float) Math.sin(a);
            float x0 = cx + dx * inner;
            float y0 = cy + dy * inner;
            float x1 = cx + dx * reach;
            float y1 = cy + dy * reach;
            // Клин: у края шириной half, к центру — точка. Обе стороны обхода: у gui-слоя
            // отсечение задних граней, а направление клина меняется по кругу.
            int col = (alpha << 24) | 0x0A0C14;
            float[][] q = {{x0, y0}, {x1 - dy * half, y1 + dx * half}, {x1 + dy * half, y1 - dx * half}, {x0, y0}};
            for (int k2 = 0; k2 < 4; k2++) {
                vc.addVertex(matrix, q[k2][0], q[k2][1], 0.0F).setColor(col);
            }
            for (int k2 = 3; k2 >= 0; k2--) {
                vc.addVertex(matrix, q[k2][0], q[k2][1], 0.0F).setColor(col);
            }
        }
        g.flush();
    }

    /** Номер развилки с начала сессии: по нему гасятся подсказки словами. */
    private static int forkNumber(PillPayloads.Sync s) {
        int n = 0;
        for (int i = 0; i < s.clot() && i < s.clots().length; i++) {
            n += PillKind.byId(s.clots()[i]).forks();
        }
        return n + s.fork();
    }

    private static void arrow(GuiGraphics g, Font font, String glyph, int x, int y, boolean chosen, boolean reading) {
        int colour = chosen ? 0xFFFFF6A0 : reading ? 0xA0FFFFFF : 0x50FFFFFF;
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        float scale = chosen ? 2.6F : 2.0F;
        g.pose().scale(scale, scale, 1.0F);
        g.drawString(font, glyph, -font.width(glyph) / 2, -4, colour, true);
        g.pose().popPose();
    }

    private static void small(GuiGraphics g, Font font, Component text, int cx, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(cx, y, 0.0F);
        g.pose().scale(0.75F, 0.75F, 1.0F);
        g.drawString(font, text, -font.width(text) / 2, 0, colour, true);
        g.pose().popPose();
    }

    private PillHud() {
    }
}
