package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.mastery.Loadout;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.LoadoutPayloads;
import io.github.verycooltimo.murim.network.SyncMasteryPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Optional;

/**
 * Экран раскладки техник (автор 01.10: «экран, где ты задаёшь техники»). Слева — выученные
 * техники со слоем, справа — слоты; их число растёт с прогрессией, закрытые видны замком.
 *
 * <p>ЛКМ по технике, затем по слоту — положить; ЛКМ по занятому слоту без выбранной
 * техники — взять оттуда; ПКМ по слоту — убрать.
 */
public final class LoadoutScreen extends Screen {

    private static final int PANEL_W = 300;
    private static final int PANEL_H = 176;
    private static final int ROW = 22;
    private static final int SLOT = 26;

    private ResourceLocation picked;
    private int scroll;

    public LoadoutScreen() {
        super(Component.translatable("murim.loadout.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int left() {
        return (width - PANEL_W) / 2;
    }

    private int top() {
        return (height - PANEL_H) / 2;
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
        Font font = this.font;

        // Панель: тёмное стекло с тонким холодным ободом.
        graphics.fill(x0, y0, x0 + PANEL_W, y0 + PANEL_H, 0xD0080C18);
        frame(graphics, x0, y0, PANEL_W, PANEL_H, 0xFF2E4C7A);
        graphics.drawString(font, title, x0 + 10, y0 + 8, 0xFFE6F4FF, false);
        graphics.fill(x0 + 10, y0 + 19, x0 + PANEL_W - 10, y0 + 20, 0x402E4C7A);

        // Слева — выученные техники.
        List<SyncMasteryPayload.Entry> list = techniques();
        int listX = x0 + 10;
        int listY = y0 + 26;
        int listW = 160;
        int visible = 5;
        scroll = Mth.clamp(scroll, 0, Math.max(0, list.size() - visible));
        if (list.isEmpty()) {
            graphics.drawString(font, Component.translatable("murim.loadout.none"), listX, listY + 4, 0xFF8090A8, false);
        }
        for (int i = 0; i < Math.min(visible, list.size() - scroll); i++) {
            SyncMasteryPayload.Entry e = list.get(i + scroll);
            int ry = listY + i * ROW;
            boolean hover = inside(mouseX, mouseY, listX, ry, listW, ROW - 2);
            boolean isPicked = e.technique().equals(picked);
            int bg = isPicked ? 0x603C78D8 : hover ? 0x40203858 : 0x20101828;
            graphics.fill(listX, ry, listX + listW, ry + ROW - 2, bg);
            icon(graphics, e.technique(), listX + 2, ry + 1, 18, 1.0F);
            // Длинное название обрезается многоточием: иначе оно залезает на слоты.
            String name = MasteryService.name(e.technique()).getString();
            int room = listW - 28;
            if (font.width(name) > room) {
                name = font.plainSubstrByWidth(name, room - font.width("…")) + "…";
            }
            graphics.drawString(font, name, listX + 24, ry + 2, 0xFFDCEBFA, false);
            Component layer = Component.translatable("murim.loadout.layer", e.layer(), e.cap());
            smallText(graphics, layer, listX + 24, ry + 12, 0xFF8FB8E0);
            // Освоение текущего слоя.
            int barX = listX + 24 + (int) (font.width(layer) * 0.75F) + 6;
            int barW = listX + listW - 6 - barX;
            if (barW > 6 && e.layer() < e.cap()) {
                graphics.fill(barX, ry + 14, barX + barW, ry + 15, 0x40FFFFFF);
                graphics.fill(barX, ry + 14, barX + (int) (barW * Mth.clamp(e.progress(), 0.0F, 1.0F)), ry + 15, 0xFF7FE0C8);
            }
        }
        if (list.size() > visible) {
            smallText(graphics, Component.literal((scroll + 1) + "–" + Math.min(list.size(), scroll + visible) + " / " + list.size()),
                    listX, listY + visible * ROW, 0xFF6E7F98);
        }

        // Справа — слоты: открытые и закрытые (замок).
        int open = ClientLoadoutState.open();
        List<Optional<ResourceLocation>> slots = ClientLoadoutState.slots();
        smallText(graphics, Component.translatable("murim.loadout.slots", open), slotX(0), listY - 2, 0xFF8FB8E0);
        for (int i = 0; i < Loadout.MAX_SLOTS; i++) {
            int sx = slotX(i);
            int sy = slotY(i);
            boolean unlocked = i < open;
            boolean hover = unlocked && inside(mouseX, mouseY, sx, sy, SLOT, SLOT);
            graphics.fill(sx, sy, sx + SLOT, sy + SLOT, unlocked ? (hover ? 0x60203858 : 0x50101828) : 0x30060A12);
            frame(graphics, sx, sy, SLOT, SLOT, !unlocked ? 0x40384868
                    : i == ClientLoadoutState.active() ? 0xFF9FE8FF : 0xFF2E4C7A);
            if (!unlocked) {
                lock(graphics, sx + SLOT / 2, sy + SLOT / 2);
                continue;
            }
            Optional<ResourceLocation> technique = i < slots.size() ? slots.get(i) : Optional.empty();
            technique.ifPresent(id -> icon(graphics, id, sx + 3, sy + 3, SLOT - 6, 1.0F));
            smallText(graphics, Component.literal(String.valueOf(i + 1)), sx + 2, sy + 2, 0xA0C8DCEC);
        }
        smallText(graphics, Component.translatable("murim.loadout.locked_hint"), slotX(0), slotY(7) + SLOT + 4, 0xFF6E7F98);

        // Подсказка внизу и выбранная техника под курсором.
        smallText(graphics, Component.translatable("murim.loadout.hint"), x0 + 10, y0 + PANEL_H - 11, 0xFF6E7F98);
        if (picked != null) {
            icon(graphics, picked, mouseX - 9, mouseY - 9, 18, 0.85F);
        }
    }

    private int slotX(int i) {
        return left() + 186 + (i % 3) * (SLOT + 6);
    }

    private int slotY(int i) {
        return top() + 32 + (i / 3) * (SLOT + 6);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int open = ClientLoadoutState.open();
        for (int i = 0; i < open; i++) {
            if (inside(mouseX, mouseY, slotX(i), slotY(i), SLOT, SLOT)) {
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
        for (int i = 0; i < Math.min(5, list.size() - scroll); i++) {
            if (inside(mouseX, mouseY, left() + 10, top() + 26 + i * ROW, 160, ROW - 2)) {
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

    /** Выбрать техники для стенда съёмки: «взять» технику, чтобы показать перенос. */
    public void pickForCapture(ResourceLocation technique) {
        picked = technique;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void frame(GuiGraphics g, int x, int y, int w, int h, int colour) {
        g.fill(x, y, x + w, y + 1, colour);
        g.fill(x, y + h - 1, x + w, y + h, colour);
        g.fill(x, y, x + 1, y + h, colour);
        g.fill(x + w - 1, y, x + w, y + h, colour);
    }

    private static void icon(GuiGraphics g, ResourceLocation technique, int x, int y, int size, float alpha) {
        g.setColor(1.0F, 1.0F, 1.0F, alpha);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.blit(TechniqueIcons.of(technique), x, y, size, size, 0.0F, 0.0F, 48, 48, 48, 48);
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** Замок закрытого слота: дужка и корпус, без текстуры. */
    private static void lock(GuiGraphics g, int cx, int cy) {
        GuiShapes.arc(g, cx, cy - 1, 2.5F, 3.6F, Math.PI, Math.PI * 2.0D, 0x80586880);
        g.fill(cx - 4, cy - 1, cx + 4, cy + 5, 0x80586880);
        g.flush();
    }

    private void smallText(GuiGraphics g, Component text, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(0.75F, 0.75F, 1.0F);
        g.drawString(this.font, text, 0, 0, colour, false);
        g.pose().popPose();
    }
}
