package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.Loadout;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.LoadoutPayloads;
import io.github.verycooltimo.murim.network.SyncMasteryPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Optional;

/**
 * Экран раскладки техник — развёрнутый свиток (автор 01.10: «задумка со скроллом, китайской
 * картиной; внутренний интерфейс упростить»). Свиток и слоты — текстуры, сгенерированные
 * пиксель-артом (docs/design/reference/ui); всё внутри пишется тушью прямо по бумаге,
 * без рамок и досок.
 *
 * <p>Слева выученные техники со слоем и освоением, справа слоты; их число растёт с
 * прогрессией, закрытые — с печатью-замком. ЛКМ по технике, затем по слоту — положить;
 * ЛКМ по занятому слоту — взять оттуда; ПКМ по слоту — убрать.
 */
public final class LoadoutScreen extends Screen {

    private static final ResourceLocation SCROLL = tex("technique_scroll");
    private static final ResourceLocation SLOT = tex("technique_slot");
    private static final ResourceLocation SLOT_ACTIVE = tex("technique_slot_active");
    private static final ResourceLocation SLOT_LOCKED = tex("technique_slot_locked");

    /** Свиток рисуется 1:1 к своей пиксельной сетке. */
    private static final int W = 320;
    private static final int H = 180;

    /** Спокойная середина свитка: по краям нарисованы горы, сосна и бамбук. */
    private static final int LIST_X = 50;
    private static final int LIST_W = 132;
    private static final int TOP = 36;
    private static final int ROW = 22;
    private static final int VISIBLE = 5;
    private static final int SLOT_SIZE = 24;
    private static final int SLOTS_X = 192;
    private static final int SLOT_STEP = 26;

    private static final float NAME_SCALE = 0.85F;

    private static final int INK = 0xFF2B2622;
    private static final int INK_GREY = 0xFF6E665B;
    private static final int INK_FAINT = 0xFFA79D8C;
    private static final int JADE = 0xFF5E9A7A;

    private ResourceLocation picked;
    private int scroll;

    public LoadoutScreen() {
        super(Component.translatable("murim.loadout.title"));
    }

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/gui/" + name + ".png");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return (height - H) / 2;
    }

    private List<SyncMasteryPayload.Entry> techniques() {
        return ClientMasteryState.entries().stream()
                .sorted(java.util.Comparator.comparing(e -> e.technique().toString()))
                .toList();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        super.render(graphics, mouseX, mouseY, partial);
        int x0 = left();
        int y0 = top();
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        graphics.blit(SCROLL, x0, y0, 0, 0.0F, 0.0F, W, H, W, H);

        // Заголовок тушью.
        graphics.pose().pushPose();
        graphics.pose().translate(x0 + LIST_X, y0 + 14, 0.0F);
        graphics.pose().scale(1.5F, 1.5F, 1.0F);
        graphics.drawString(font, title, 0, 0, INK, false);
        graphics.pose().popPose();

        // Слева — выученные техники.
        List<SyncMasteryPayload.Entry> list = techniques();
        scroll = Mth.clamp(scroll, 0, Math.max(0, list.size() - VISIBLE));
        int lx = x0 + LIST_X;
        if (list.isEmpty()) {
            small(graphics, Component.translatable("murim.loadout.none"), lx, y0 + TOP + 4, INK_GREY);
        }
        for (int i = 0; i < Math.min(VISIBLE, list.size() - scroll); i++) {
            SyncMasteryPayload.Entry e = list.get(i + scroll);
            int ry = y0 + TOP + i * ROW;
            boolean hover = inside(mouseX, mouseY, lx - 2, ry - 1, LIST_W + 4, ROW - 1);
            if (e.technique().equals(picked)) {
                // Взятая техника — бледная нефритовая размывка, как акварель по бумаге.
                graphics.fill(lx - 2, ry - 1, lx + LIST_W + 2, ry + ROW - 2, 0x60BFD6C2);
            } else if (hover) {
                graphics.fill(lx - 2, ry - 1, lx + LIST_W + 2, ry + ROW - 2, 0x30A79D8C);
            }
            icon(graphics, e.technique(), lx, ry, 20, 1.0F);
            // Название чуть мельче обычного шрифта: длинные имена техник иначе не помещаются.
            String name = MasteryService.name(e.technique()).getString();
            int room = (int) ((LIST_W - 26) / NAME_SCALE);
            if (font.width(name) > room) {
                name = font.plainSubstrByWidth(name, room - font.width("…")) + "…";
            }
            graphics.pose().pushPose();
            graphics.pose().translate(lx + 24, ry + 2, 0.0F);
            graphics.pose().scale(NAME_SCALE, NAME_SCALE, 1.0F);
            graphics.drawString(font, name, 0, 0, INK, false);
            graphics.pose().popPose();
            Component layer = Component.translatable("murim.loadout.layer", e.layer(), e.cap());
            small(graphics, layer, lx + 24, ry + 11, INK_GREY);
            // Освоение текущего слоя — тонкий штрих кисти.
            int barX = lx + 24 + (int) (font.width(layer) * 0.75F) + 4;
            int barW = lx + LIST_W - barX;
            if (barW > 6 && e.layer() < e.cap()) {
                graphics.fill(barX, ry + 14, barX + barW, ry + 15, 0x60A79D8C);
                graphics.fill(barX, ry + 14, barX + (int) (barW * Mth.clamp(e.progress(), 0.0F, 1.0F)), ry + 15, JADE);
            }
        }
        if (list.size() > VISIBLE) {
            small(graphics, Component.literal((scroll + 1) + "–" + Math.min(list.size(), scroll + VISIBLE) + " / " + list.size()),
                    lx, y0 + TOP + VISIBLE * ROW, INK_FAINT);
        }

        // Справа — слоты.
        int open = ClientLoadoutState.open();
        List<Optional<ResourceLocation>> slots = ClientLoadoutState.slots();
        small(graphics, Component.translatable("murim.loadout.slots", open), x0 + SLOTS_X, y0 + TOP - 10, INK_GREY);
        for (int i = 0; i < Loadout.MAX_SLOTS; i++) {
            int sx = slotX(i);
            int sy = slotY(i);
            boolean unlocked = i < open;
            ResourceLocation frame = !unlocked ? SLOT_LOCKED
                    : (i == ClientLoadoutState.active() ? SLOT_ACTIVE : SLOT);
            graphics.blit(frame, sx, sy, 0, 0.0F, 0.0F, SLOT_SIZE, SLOT_SIZE, SLOT_SIZE, SLOT_SIZE);
            if (!unlocked) {
                continue;
            }
            if (inside(mouseX, mouseY, sx, sy, SLOT_SIZE, SLOT_SIZE)) {
                graphics.fill(sx + 2, sy + 2, sx + SLOT_SIZE - 2, sy + SLOT_SIZE - 2, 0x28A79D8C);
            }
            Optional<ResourceLocation> technique = i < slots.size() ? slots.get(i) : Optional.empty();
            technique.ifPresent(id -> icon(graphics, id, sx + 2, sy + 2, 20, 1.0F));
        }

        // Подсказка внизу — бледной тушью.
        Component hint = Component.translatable("murim.loadout.hint");
        small(graphics, hint, x0 + (W - (int) (font.width(hint) * 0.75F)) / 2, y0 + H - 20, INK_GREY);
        if (picked != null) {
            icon(graphics, picked, mouseX - 10, mouseY - 10, 20, 0.85F);
        }
    }

    private int slotX(int i) {
        return left() + SLOTS_X + (i % 3) * SLOT_STEP;
    }

    private int slotY(int i) {
        return top() + TOP + (i / 3) * SLOT_STEP;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int open = ClientLoadoutState.open();
        for (int i = 0; i < open; i++) {
            if (inside(mouseX, mouseY, slotX(i), slotY(i), SLOT_SIZE, SLOT_SIZE)) {
                if (button == 1) {
                    PacketDistributor.sendToServer(new LoadoutPayloads.SetSlot(i, Optional.empty()));
                } else if (picked != null) {
                    PacketDistributor.sendToServer(new LoadoutPayloads.SetSlot(i, Optional.of(picked)));
                    picked = null;
                } else {
                    List<Optional<ResourceLocation>> slots = ClientLoadoutState.slots();
                    if (i < slots.size() && slots.get(i).isPresent()) {
                        picked = slots.get(i).get();
                    }
                }
                return true;
            }
        }
        List<SyncMasteryPayload.Entry> list = techniques();
        for (int i = 0; i < Math.min(VISIBLE, list.size() - scroll); i++) {
            if (inside(mouseX, mouseY, left() + LIST_X - 2, top() + TOP + i * ROW - 1, LIST_W + 4, ROW - 1)) {
                ResourceLocation id = list.get(i + scroll).technique();
                picked = id.equals(picked) ? null : id;
                return true;
            }
        }
        picked = null;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= (int) Math.signum(scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ModKeyMappings.LOADOUT.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Взять технику для стенда съёмки — показать перенос в слот. */
    public void pickForCapture(ResourceLocation technique) {
        picked = technique;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void icon(GuiGraphics g, ResourceLocation technique, int x, int y, int size, float alpha) {
        g.setColor(1.0F, 1.0F, 1.0F, alpha);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.blit(TechniqueIcons.of(technique), x, y, size, size, 0.0F, 0.0F, 48, 48, 48, 48);
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void small(GuiGraphics g, Component text, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(0.75F, 0.75F, 1.0F);
        g.drawString(this.font, text, 0, 0, colour, false);
        g.pose().popPose();
    }
}
