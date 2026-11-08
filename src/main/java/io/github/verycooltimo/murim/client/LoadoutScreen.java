package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.Loadout;
import io.github.verycooltimo.murim.mastery.ArtSlots;
import io.github.verycooltimo.murim.mastery.Arts;
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
    private static final int LIST_X = 58;
    private static final int LIST_W = 120;
    private static final int TOP = 58;
    private static final int ROW = 22;
    private static final int VISIBLE = 4;
    private static final int SLOT_SIZE = 24;
    private static final int SLOTS_X = 186;
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
    private ArtSlots.Kind filter = ArtSlots.Kind.ALL;
    private boolean sortBySchool;
    private static final ArtSlots.Kind[] FILTERS = {
            ArtSlots.Kind.ALL, ArtSlots.Kind.SWORD, ArtSlots.Kind.STEPS, ArtSlots.Kind.FIST_PALM,
            ArtSlots.Kind.HIDDEN_WEAPON, ArtSlots.Kind.BREATHING, ArtSlots.Kind.BODY_PROTECTION
    };

    /** Whether the player knows (or has set) an art of this kind: only then its own cell and filter exist (author 22.10). */
    private boolean owns(ArtSlots.Kind kind) {
        for (SyncMasteryPayload.Entry e : ClientMasteryState.entries()) {
            if ((Arts.isMethod(e.technique())
                    || io.github.verycooltimo.murim.technique.TechniqueLoader.get(e.technique()) != null)
                    && ArtSlots.kind(e.technique()) == kind) {
                return true;
            }
        }
        return false;
    }

    /** Row of a special cell on the screen: 0 foundation (always), 1 breathing, 2 body protection; -1 hidden until such an art is known. */
    private int visualRow(int logical) {
        boolean breathing = owns(ArtSlots.Kind.BREATHING) || ClientLoadoutState.breathing().isPresent();
        boolean protection = owns(ArtSlots.Kind.BODY_PROTECTION) || ClientLoadoutState.bodyProtection().isPresent();
        return switch (logical) {
            case 0 -> 0;
            case 1 -> breathing ? 1 : -1;
            default -> protection ? (breathing ? 2 : 1) : -1;
        };
    }

    /** Filter buttons only for the kinds of art the player has learned (playtest 22.10: no «fist» tab without a fist art). */
    private List<ArtSlots.Kind> filters() {
        java.util.Set<ArtSlots.Kind> have = new java.util.HashSet<>();
        for (SyncMasteryPayload.Entry e : ClientMasteryState.entries()) {
            if (Arts.isMethod(e.technique())
                    || io.github.verycooltimo.murim.technique.TechniqueLoader.get(e.technique()) != null) {
                have.add(ArtSlots.kind(e.technique()));
            }
        }
        List<ArtSlots.Kind> out = new java.util.ArrayList<>();
        for (ArtSlots.Kind k : FILTERS) {
            if (k == ArtSlots.Kind.ALL || have.contains(k)) out.add(k);
        }
        if (!out.contains(filter)) filter = ArtSlots.Kind.ALL;
        return out;
    }

    private static String filterKey(ArtSlots.Kind kind) {
        return "murim.loadout.filter." + kind.name().toLowerCase(java.util.Locale.ROOT);
    }

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
            if (!Arts.isMethod(e.technique())
                    && io.github.verycooltimo.murim.technique.TechniqueLoader.get(e.technique()) == null) continue;
            if (filter != ArtSlots.Kind.ALL && ArtSlots.kind(e.technique()) != filter) continue;
            // One art: the sub-techniques of volume 2 are the same Six Elements Sword, set by dragging it on a technique slot.
            if (ArtSlots.SIX_VOLUME_TWO.equals(e.technique())
                    && ClientMasteryState.entries().stream().anyMatch(o -> ArtSlots.SIX.equals(o.technique()))) continue;
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
        java.util.Comparator<SyncMasteryPayload.Entry> byName = java.util.Comparator
                .comparing(e -> MasteryService.artName(e.technique()).getString());
        out.sort((sortBySchool ? java.util.Comparator.<SyncMasteryPayload.Entry, String>comparing(
                e -> Arts.school(e.technique())).thenComparing(byName) : byName)
                .thenComparing(e -> e.technique().toString()));
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

        // Ink-only mouse tabs retain the approved scroll and existing slot textures.
        List<ArtSlots.Kind> filters = filters();
        for (int i = 0; i <= filters.size(); i++) {
            int bx = x0 + LIST_X + (i % 4) * 54;
            int by = y0 + 36 + (i / 4) * 10;
            boolean selected = i < filters.size() && filter == filters.get(i);
            if (selected || inside(mouseX, mouseY, bx, by, 52, 10)) {
                graphics.fill(bx - 1, by - 1, bx + 51, by + 8, selected ? 0x60BFD6C2 : 0x30A79D8C);
            }
            Component text = Component.translatable(i < filters.size() ? filterKey(filters.get(i))
                    : sortBySchool ? "murim.loadout.sort.school" : "murim.loadout.sort.name");
            small(graphics, text, bx, by, INK,
                    Math.min(0.65F, 51.0F / Math.max(1, font.width(text))));
        }

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
            var definition = io.github.verycooltimo.murim.technique.TechniqueLoader.get(e.technique());
            String name = (definition != null && definition.foundation()
                    ? MasteryService.name(e.technique()) : MasteryService.artName(e.technique())).getString();
            int room = (int) ((LIST_W - 26) / NAME_SCALE);
            if (font.width(name) > room) {
                name = font.plainSubstrByWidth(name, room - font.width("…")) + "…";
            }
            graphics.pose().pushPose();
            graphics.pose().translate(lx + 24, ry + 2, 0.0F);
            graphics.pose().scale(NAME_SCALE, NAME_SCALE, 1.0F);
            graphics.drawString(font, name, 0, 0, INK, false);
            graphics.pose().popPose();
            Component stars = MasteryText.summary(e.technique(), e);
            float starScale = Math.min(0.75F, (LIST_W - 26.0F) / Math.max(1, font.width(stars)));
            small(graphics, stars, lx + 24, ry + 11, INK_GREY, starScale);
            // Progress belongs below the gauge so twelve stars fit the same row.
            int barX = lx + 24;
            int barW = LIST_W - 26;
            if (e.stars() < e.cap()) {
                graphics.fill(barX, ry + 19, barX + barW, ry + 20, 0x60A79D8C);
                graphics.fill(barX, ry + 19, barX + (int) (barW * Mth.clamp(e.progress(), 0.0F, 1.0F)), ry + 20, JADE);
            }
        }
        if (list.size() > VISIBLE) {
            small(graphics, Component.literal((scroll + 1) + "–" + Math.min(list.size(), scroll + VISIBLE) + " / " + list.size()),
                    lx, y0 + TOP + VISIBLE * ROW, INK_FAINT);
        }

        // Справа — слоты.
        int open = ClientLoadoutState.open();
        List<Optional<ResourceLocation>> slots = ClientLoadoutState.slots();
        for (int i = 0; i < Loadout.MAX_SLOTS; i++) {
            int sx = slotX(i);
            int sy = slotY(i);
            boolean unlocked = i < open;
            ResourceLocation frame = !unlocked ? SLOT_LOCKED
                    : (i == ClientLoadoutState.active() ? SLOT_ACTIVE : SLOT);
            graphics.blit(frame, sx, sy, 0, 0.0F, 0.0F, SLOT_SIZE, SLOT_SIZE, SLOT_SIZE, SLOT_SIZE);
            if (unlocked && picked != null && ArtSlots.accepts(i, picked)) {
                graphics.fill(sx, sy, sx + SLOT_SIZE, sy + 2, 0xFF8BC9A2);
            }
            if (!unlocked) {
                continue;
            }
            if (inside(mouseX, mouseY, sx, sy, SLOT_SIZE, SLOT_SIZE)) {
                graphics.fill(sx + 2, sy + 2, sx + SLOT_SIZE - 2, sy + SLOT_SIZE - 2, 0x28A79D8C);
            }
            Optional<ResourceLocation> technique = i < slots.size() ? slots.get(i) : Optional.empty();
            technique.ifPresent(id -> icon(graphics, id, sx + 2, sy + 2, 20, 1.0F));
            if (technique.isPresent() && ArtSlots.SIX_VOLUME_TWO.equals(technique.get())) {
                small(graphics, Component.literal("II"), sx + SLOT_SIZE - 10, sy + SLOT_SIZE - 9, INK);
            }
        }

        specialSlot(graphics, mouseX, mouseY, 0, "murim.loadout.foundation", ClientLoadoutState.foundation());
        if (visualRow(1) >= 0) specialSlot(graphics, mouseX, mouseY, 1, "murim.loadout.breathing", ClientLoadoutState.breathing());
        if (visualRow(2) >= 0) specialSlot(graphics, mouseX, mouseY, 2, "murim.loadout.body_protection", ClientLoadoutState.bodyProtection());

        // Подсказка внизу — бледной тушью.
        Component hint = slotError != null ? slotError : Component.translatable("murim.loadout.hint");
        for (int row = 0; row < 3; row++) {
            if (visualRow(row) >= 0 && inside(mouseX, mouseY, x0 + FOUNDATION_X, y0 + TOP + visualRow(row) * 34, SLOT_SIZE, SLOT_SIZE)) {
                Optional<ResourceLocation> art = row == 0 ? ClientLoadoutState.foundation()
                        : row == 1 ? ClientLoadoutState.breathing() : ClientLoadoutState.bodyProtection();
                if (slotError == null && art.isPresent()) hint = MasteryService.artName(art.get());
            }
        }
        small(graphics, hint, x0 + (W - (int) (font.width(hint) * 0.75F)) / 2, y0 + H - 20, INK_GREY);
        if (picked != null) {
            icon(graphics, picked, mouseX - 10, mouseY - 10, 20, 0.85F);
        }
    }

    private void specialSlot(GuiGraphics g, int mouseX, int mouseY, int row, String key,
                             Optional<ResourceLocation> art) {
        int x = left() + FOUNDATION_X;
        int y = top() + TOP + visualRow(row) * 34;
        g.blit(SLOT, x, y, 0, 0.0F, 0.0F, SLOT_SIZE, SLOT_SIZE, SLOT_SIZE, SLOT_SIZE);
        int slot = row == 0 ? ArtSlots.FOUNDATION : row == 1 ? ArtSlots.BREATHING : ArtSlots.BODY_PROTECTION;
        if (picked != null && ArtSlots.accepts(slot, picked)) {
            g.fill(x, y, x + SLOT_SIZE, y + 2, 0xFF8BC9A2);
        }
        if (inside(mouseX, mouseY, x, y, SLOT_SIZE, SLOT_SIZE)) {
            g.fill(x + 2, y + 2, x + SLOT_SIZE - 2, y + SLOT_SIZE - 2, 0x28A79D8C);
        }
        art.ifPresent(id -> icon(g, id, x + 2, y + 2, 20, 1.0F));
        // The same art on the basic-sword slot is volume I: a jade tint and a numeral so the change is plain.
        if (row == 0 && art.isPresent() && ArtSlots.SIX.equals(art.get())) {
            g.fill(x + 2, y + 2, x + SLOT_SIZE - 2, y + SLOT_SIZE - 2, 0x2268C79A);
            small(g, Component.literal("I"), x + SLOT_SIZE - 7, y + SLOT_SIZE - 9, INK);
        }
        Component label = Component.translatable(key);
        float scale = Math.min(0.65F, (W - ROD - FOUNDATION_X - 4) / (float) Math.max(1, font.width(label)));
        // A parchment backing: the label sat on the painted pine and blended into it (playtest 22.10).
        int lw = (int) Math.ceil(font.width(label) * scale);
        g.fill(x - 3, y + SLOT_SIZE, x - 1 + lw + 2, y + SLOT_SIZE + 9, 0xD8EADFC4);
        small(g, label, x - 2, y + SLOT_SIZE + 1, 0xFF1E1A16, scale);
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
        // С Пика — и подступень: «Пик · утвердившаяся» (автор 03.10).
        Component name = Component.translatable(io.github.verycooltimo.murim.cultivation.Realm.nameKey(rank));
        if (rank == io.github.verycooltimo.murim.cultivation.Realm.PEAK) {
            name = name.copy().append(" · ").append(Component.translatable(
                    io.github.verycooltimo.murim.cultivation.Realm.stageKey(profile.stage())));
        }
        small(graphics, name, x + (int) (font.width(title) * 1.5F) + 10, y + 5, INK);
        Component power = Component.translatable("murim.rank.power",
                number(io.github.verycooltimo.murim.cultivation.Realm.power(profile)));
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

    /** UX #28: a wrong destination explains the rule and leaves the technique picked up. */
    private Component slotError;

    private boolean compatible(int slot) {
        if (ArtSlots.accepts(slot, picked)) return true;
        String key = switch (ArtSlots.kind(picked)) {
            case BREATHING -> "murim.loadout.need_breathing_slot";
            case BODY_PROTECTION -> "murim.loadout.need_body_protection_slot";
            default -> io.github.verycooltimo.murim.mastery.LoadoutService.isFoundation(picked)
                    ? "murim.loadout.need_foundation_slot" : "murim.loadout.need_technique_slot";
        };
        slotError = Component.translatable(key);
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        slotError = null;
        if (button != 0 && button != 1) return super.mouseClicked(mouseX, mouseY, button);
        List<ArtSlots.Kind> filters = filters();
        for (int i = 0; i <= filters.size(); i++) {
            if (inside(mouseX, mouseY, left() + LIST_X + (i % 4) * 54,
                    top() + 36 + (i / 4) * 10, 52, 10)) {
                if (i < filters.size()) filter = filters.get(i);
                else sortBySchool = !sortBySchool;
                scroll = 0;
                return true;
            }
        }
        int[] special = {ArtSlots.FOUNDATION, ArtSlots.BREATHING, ArtSlots.BODY_PROTECTION};
        for (int row = 0; row < special.length; row++) {
            if (visualRow(row) >= 0 && inside(mouseX, mouseY, left() + FOUNDATION_X, top() + TOP + visualRow(row) * 34, SLOT_SIZE, SLOT_SIZE)) {
                if (button == 1) {
                    PacketDistributor.sendToServer(new LoadoutPayloads.SetSlot(special[row], Optional.empty()));
                } else if (picked != null) {
                    if (!compatible(special[row])) return true;
                    PacketDistributor.sendToServer(new LoadoutPayloads.SetSlot(special[row], Optional.of(picked)));
                    picked = null;
                } else if (picked == null) {
                    picked = (row == 0 ? ClientLoadoutState.foundation() : row == 1
                            ? ClientLoadoutState.breathing() : ClientLoadoutState.bodyProtection()).orElse(null);
                }
                return true;
            }
        }
        int open = ClientLoadoutState.open();
        for (int i = 0; i < open; i++) {
            if (inside(mouseX, mouseY, slotX(i), slotY(i), SLOT_SIZE, SLOT_SIZE)) {
                if (button == 1) {
                    PacketDistributor.sendToServer(new LoadoutPayloads.SetSlot(i, Optional.empty()));
                } else if (picked != null) {
                    if (!compatible(i)) return true;
                    PacketDistributor.sendToServer(new LoadoutPayloads.SetSlot(i, Optional.of(picked)));
                    picked = null;
                } else if (picked == null) {
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
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, Math.max(0, techniques().size() - VISIBLE));
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
        small(g, text, x, y, colour, 0.75F);
    }

    private void small(GuiGraphics g, Component text, int x, int y, int colour, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(scale, scale, 1.0F);
        g.drawString(this.font, text, 0, 0, colour, false);
        g.pose().popPose();
    }
}
