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
    // Список правее монастыря на скале, слоты — левее сосны: пейзаж по краям остаётся открытым.
    private static final int LIST_X = 74;
    private static final int LIST_W = 120;
    private static final int TOP = 36;
    private static final int ROW = 22;
    private static final int VISIBLE = 5;
    private static final int SLOT_SIZE = 24;
    private static final int SLOTS_X = 202;
    private static final int SLOT_COLUMNS = 2;
    private static final int SLOT_STEP = 25;

    /** Ячейка основы меча — правее слотов техник (автор 01.10: основа живёт на ЛКМ). */
    private static final int FOUNDATION_X = SLOTS_X + SLOT_COLUMNS * SLOT_STEP + 8;

    private static final float NAME_SCALE = 0.85F;

    private static final int INK = 0xFF2B2622;
    private static final int INK_GREY = 0xFF6E665B;
    private static final int INK_FAINT = 0xFFA79D8C;
    private static final int JADE = 0xFF5E9A7A;

    private ResourceLocation picked;
    private int scroll;

    /** Раскрытие свитка (автор 03.10: «короткую анимацию для свитка»): тики с открытия. */
    private int openTicks;
    private static final float UNROLL = 6.0F;
    private static final int ROD = 14;

    @Override
    public void tick() {
        super.tick();
        openTicks++;
    }

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

    /** Выученное: стиль — одной строкой (первая выученная форма как представитель). */
    private List<SyncMasteryPayload.Entry> techniques() {
        java.util.Set<ResourceLocation> seenStyles = new java.util.HashSet<>();
        List<SyncMasteryPayload.Entry> out = new java.util.ArrayList<>();
        for (SyncMasteryPayload.Entry e : ClientMasteryState.entries().stream()
                .sorted(java.util.Comparator.comparing(e -> e.technique().toString())).toList()) {
            Optional<io.github.verycooltimo.murim.technique.Styles.Style> style = io.github.verycooltimo.murim.technique.Styles.of(e.technique());
            if (style.isPresent()) {
                if (!seenStyles.add(style.get().id())) {
                    continue;
                }
                ResourceLocation first = style.get().forms().stream()
                        .filter(f -> TechniqueSlotsHud.mastery(f) != null).findFirst().orElse(e.technique());
                SyncMasteryPayload.Entry rep = TechniqueSlotsHud.mastery(first);
                out.add(rep != null ? rep : e);
                continue;
            }
            out.add(e);
        }
        return out;
    }

    private static int knownForms(io.github.verycooltimo.murim.technique.Styles.Style style) {
        int n = 0;
        for (ResourceLocation f : style.forms()) {
            if (TechniqueSlotsHud.mastery(f) != null) {
                n++;
            }
        }
        return n;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        // Свиток разворачивается от центра: видимая полоса растёт, валики едут к краям.
        float o = Mth.clamp((openTicks + partial) / UNROLL, 0.0F, 1.0F);
        float e = 1.0F - (1.0F - o) * (1.0F - o) * (1.0F - o);
        int half = (int) (W / 2.0F * (0.1F + 0.9F * e));
        int cx = left() + W / 2;
        boolean unrolling = o < 1.0F;
        if (unrolling) {
            graphics.enableScissor(cx - half + ROD, top() - 4, cx + half - ROD, top() + H + 4);
        }
        renderContent(graphics, mouseX, mouseY, partial);
        if (unrolling) {
            graphics.disableScissor();
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            graphics.blit(SCROLL, cx - half, top(), 0, 0.0F, 0.0F, ROD, H, W, H);
            graphics.blit(SCROLL, cx + half - ROD, top(), 0, (float) (W - ROD), 0.0F, ROD, H, W, H);
        }
    }

    private void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
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
        rankLine(graphics, x0 + LIST_X, y0 + 14);

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
            Optional<io.github.verycooltimo.murim.technique.Styles.Style> rowStyle = io.github.verycooltimo.murim.technique.Styles.of(e.technique());
            String name = rowStyle.isPresent() ? Component.translatable(rowStyle.get().nameKey()).getString()
                    : MasteryService.name(e.technique()).getString();
            int room = (int) ((LIST_W - 26) / NAME_SCALE);
            if (font.width(name) > room) {
                name = font.plainSubstrByWidth(name, room - font.width("…")) + "…";
            }
            graphics.pose().pushPose();
            graphics.pose().translate(lx + 24, ry + 2, 0.0F);
            graphics.pose().scale(NAME_SCALE, NAME_SCALE, 1.0F);
            graphics.drawString(font, name, 0, 0, INK, false);
            graphics.pose().popPose();
            Component layer = rowStyle.isPresent()
                    ? Component.translatable("murim.loadout.forms", knownForms(rowStyle.get()), rowStyle.get().forms().size())
                    : Component.translatable("murim.loadout.layer", e.layer(), e.cap());
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

        // Основа меча — отдельная ячейка.
        int fx = x0 + FOUNDATION_X;
        int fy = y0 + TOP;
        small(graphics, Component.translatable("murim.loadout.foundation"), fx, fy - 10, INK_GREY);
        graphics.blit(SLOT, fx, fy, 0, 0.0F, 0.0F, SLOT_SIZE, SLOT_SIZE, SLOT_SIZE, SLOT_SIZE);
        if (inside(mouseX, mouseY, fx, fy, SLOT_SIZE, SLOT_SIZE)) {
            graphics.fill(fx + 2, fy + 2, fx + SLOT_SIZE - 2, fy + SLOT_SIZE - 2, 0x28A79D8C);
        }
        ClientLoadoutState.foundation().ifPresent(id -> icon(graphics, id, fx + 2, fy + 2, 20, 1.0F));

        // Подсказка внизу — бледной тушью.
        Component hint = Component.translatable("murim.loadout.hint");
        small(graphics, hint, x0 + (W - (int) (font.width(hint) * 0.75F)) / 2, y0 + H - 20, INK_GREY);
        if (picked != null) {
            icon(graphics, picked, mouseX - 10, mouseY - 10, 20, 0.85F);
        }
    }

    /**
     * Что дал ранг (автор 03.10, этап M2: «игрок должен понять, что ранг дал»): имя ранга рядом
     * с заголовком, под ним — множитель силы техник и прибавка скорости. Ненавязчиво, бледной тушью.
     */
    private void rankLine(GuiGraphics graphics, int x, int y) {
        io.github.verycooltimo.murim.profile.DantianProfile profile = ClientProfileState.profile();
        if (!profile.isAwakened()) {
            return;
        }
        int rank = profile.rank();
        small(graphics, Component.translatable(io.github.verycooltimo.murim.cultivation.Realm.nameKey(rank)),
                x + (int) (font.width(title) * 1.5F) + 10, y + 5, INK);
        Component power = Component.translatable("murim.rank.power",
                number(io.github.verycooltimo.murim.cultivation.Realm.power(rank)));
        int speed = (int) Math.round(io.github.verycooltimo.murim.cultivation.Realm.bonusSpeed(rank) * 100.0D);
        Component line = speed <= 0 ? power : power.copy().append(" · ")
                .append(Component.translatable("murim.rank.speed", speed));
        // Мельче подписей: строка не должна доходить до «Слоты» справа.
        graphics.pose().pushPose();
        graphics.pose().translate(x, y + 13, 0.0F);
        graphics.pose().scale(0.65F, 0.65F, 1.0F);
        graphics.drawString(font, line, 0, 0, 0xFF4F4840, false);
        graphics.pose().popPose();
    }

    /** «1,25» по-русски и «1.25» по-английски; лишние нули отбрасываются. */
    private static String number(double value) {
        String s = java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        return s.replace(".", net.minecraft.client.resources.language.I18n.get("murim.rank.decimal"));
    }

    private int slotX(int i) {
        return left() + SLOTS_X + (i % SLOT_COLUMNS) * SLOT_STEP;
    }

    private int slotY(int i) {
        return top() + TOP + (i / SLOT_COLUMNS) * SLOT_STEP;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (inside(mouseX, mouseY, left() + FOUNDATION_X, top() + TOP, SLOT_SIZE, SLOT_SIZE)) {
            // Сервер сам проверит, что это основа и она выучена.
            if (button == 1) {
                PacketDistributor.sendToServer(new LoadoutPayloads.SetSlot(-1, Optional.empty()));
            } else if (picked != null) {
                PacketDistributor.sendToServer(new LoadoutPayloads.SetSlot(-1, Optional.of(picked)));
                picked = null;
            }
            return true;
        }
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
