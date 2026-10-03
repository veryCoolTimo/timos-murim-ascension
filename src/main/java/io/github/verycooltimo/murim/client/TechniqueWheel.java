package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.LoadoutPayloads;
import io.github.verycooltimo.murim.network.SyncMasteryPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Optional;

/**
 * Кольцо выбора техник (автор 01.10): зажал клавишу — полупрозрачное кольцо с иконками,
 * повёл мышь — выбрал, отпустил — выбрано.
 *
 * <p>Курсор кольца — это движение мыши: пока кольцо открыто, поворот игрока каждый кадр
 * возвращается на место, а разница копится в курсор. Камера стоит, мышь выбирает.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class TechniqueWheel {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "technique_wheel");

    // Размеры и рисунок — по референсу docs/design/reference/ui/technique-wheel-ref.png:
    // крупные иконки на весь сектор, тонкая светлая обводка секторов, центр — отдельный круг.
    // Кольцо уменьшено по замечанию автора 01.10 («занимает слишком много места»): полоса
    // ровно под иконку 32×32 — пиксельные иконки рисуются без нецелого масштаба.
    private static final float INNER = 24.0F;
    private static final float OUTER = 58.0F;
    /**
     * Двойное кольцо (автор 03.10, «кольцо супер»): внутри — формы показанного стиля, снаружи —
     * все открытые слоты раскладки. Наведение на слот снаружи показывает внутри его формы;
     * отпустил V на слоте — выбран слот с его последней формой, на форме — эта форма.
     */
    private static final float SLOT_INNER = OUTER + 4.0F;
    private static final float SLOT_OUTER = OUTER + 34.0F;
    /** Пикселей курсора на градус поворота мыши. */
    private static final float SENSITIVITY = 2.2F;
    /** Мёртвая зона в центре: без неё дрожь руки перебирала бы секторы. */
    private static final float DEAD_ZONE = 10.0F;

    private static boolean open;
    private static float baseYaw;
    private static float basePitch;
    private static float cursorX;
    private static float cursorY;
    private static int selected = -1;
    /** Слот под курсором во внешнем кольце, −1 — курсор не там. */
    private static int slotHover = -1;
    private static int age;
    /**
     * Страница кольца — слот раскладки (решение codex 02.10): секторы — формы стиля этого слота
     * (или одна техника). Колёсико при зажатой V листает непустые слоты; отпустил V — выбрана
     * форма, и слот становится активным.
     */
    private static int page;
    private static List<ResourceLocation> entries = List.of();

    private static List<ResourceLocation> entriesOf(int slot) {
        List<Optional<ResourceLocation>> slots = ClientLoadoutState.slots();
        if (slot < 0 || slot >= slots.size() || slots.get(slot).isEmpty()) {
            return List.of();
        }
        ResourceLocation t = slots.get(slot).get();
        Optional<io.github.verycooltimo.murim.technique.Styles.Style> style = io.github.verycooltimo.murim.technique.Styles.of(t);
        if (style.isEmpty()) {
            return List.of(t);
        }
        List<ResourceLocation> known = new java.util.ArrayList<>();
        for (ResourceLocation f : style.get().forms()) {
            if (f.equals(t) || TechniqueSlotsHud.mastery(f) != null) {
                known.add(f);
            }
        }
        return known;
    }

    /** Следующий непустой слот в сторону {@code dir}; если других нет — тот же. */
    private static int nextPage(int from, int dir) {
        int n = Math.max(1, ClientLoadoutState.open());
        for (int k = 1; k <= n; k++) {
            int i = Math.floorMod(from + dir * k, n);
            if (!entriesOf(i).isEmpty()) {
                return i;
            }
        }
        return from;
    }

    @SubscribeEvent
    static void onScroll(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent event) {
        if (!open || event.getScrollDeltaY() == 0.0D) {
            return;
        }
        // API: reference/neoforge-src/net/neoforged/neoforge/client/event/InputEvent.java#MouseScrollingEvent (ICancellableEvent)
        event.setCanceled(true);
        page = nextPage(page, event.getScrollDeltaY() > 0.0D ? -1 : 1);
        entries = entriesOf(page);
        selected = sectorAt(cursorX, cursorY, entries.size());
    }

    public static boolean open() {
        return open;
    }

    /** Открыть или закрыть кольцо вручную — для стенда съёмки. */
    public static void setCursor(float x, float y) {
        cursorX = x;
        cursorY = y;
        pick();
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        boolean wanted = player != null && minecraft.screen == null && ModKeyMappings.WHEEL.isDown()
                && ClientLoadoutState.open() > 0 && !ClientMeditationState.state().active();
        if (wanted && !open) {
            open = true;
            age = 0;
            baseYaw = player.getYRot();
            basePitch = player.getXRot();
            cursorX = 0.0F;
            cursorY = 0.0F;
            selected = -1;
            slotHover = -1;
            page = entriesOf(ClientLoadoutState.active()).isEmpty() ? nextPage(ClientLoadoutState.active(), 1) : ClientLoadoutState.active();
            entries = entriesOf(page);
        } else if (!wanted && open) {
            open = false;
            if (slotHover >= 0 && !entriesOf(slotHover).isEmpty()) {
                // Слот снаружи: стиль с его последней формой (или отдельная техника, шаг).
                PacketDistributor.sendToServer(new LoadoutPayloads.Select(slotHover));
            } else if (selected >= 0 && selected < entries.size()) {
                ResourceLocation form = entries.get(selected);
                List<Optional<ResourceLocation>> slots = ClientLoadoutState.slots();
                if (page >= slots.size() || !slots.get(page).equals(Optional.of(form))) {
                    PacketDistributor.sendToServer(new LoadoutPayloads.SetSlot(page, Optional.of(form)));
                }
                PacketDistributor.sendToServer(new LoadoutPayloads.Select(page));
            }
        }
        if (open) {
            age++;
        }
    }

    @SubscribeEvent
    static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!open) {
            return;
        }
        LocalPlayer me = Minecraft.getInstance().player;
        if (me == null) {
            return;
        }
        float dx = Mth.wrapDegrees(me.getYRot() - baseYaw);
        float dy = me.getXRot() - basePitch;
        if (dx != 0.0F || dy != 0.0F) {
            cursorX += dx * SENSITIVITY;
            cursorY += dy * SENSITIVITY;
            float length = Mth.sqrt(cursorX * cursorX + cursorY * cursorY);
            if (length > SLOT_OUTER) {
                cursorX *= SLOT_OUTER / length;
                cursorY *= SLOT_OUTER / length;
            }
            pick();
        }
        me.setYRot(baseYaw);
        me.yRotO = baseYaw;
        me.setXRot(basePitch);
        me.xRotO = basePitch;
        event.setYaw(baseYaw);
        event.setPitch(basePitch);
    }

    /** Что под курсором: форма во внутреннем кольце или слот во внешнем (он же меняет страницу). */
    private static void pick() {
        float r = Mth.sqrt(cursorX * cursorX + cursorY * cursorY);
        if (r > OUTER + 2.0F) {
            slotHover = sectorAt(cursorX, cursorY, ClientLoadoutState.open());
            selected = -1;
            if (slotHover >= 0 && slotHover != page && !entriesOf(slotHover).isEmpty()) {
                page = slotHover;
                entries = entriesOf(page);
            }
        } else {
            slotHover = -1;
            selected = sectorAt(cursorX, cursorY, entries.size());
        }
    }

    /** Сектор под курсором: первый — сверху, дальше по часовой. */
    static int sectorAt(float x, float y, int count) {
        if (count <= 0 || x * x + y * y < DEAD_ZONE * DEAD_ZONE) {
            return -1;
        }
        double angle = Math.toDegrees(Math.atan2(y, x)) + 90.0D;
        double span = 360.0D / count;
        double shifted = ((angle + span / 2.0D) % 360.0D + 360.0D) % 360.0D;
        return Math.min(count - 1, (int) (shifted / span));
    }

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER, TechniqueWheel::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        if (!open) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        float appear = Mth.clamp((age + delta.getGameTimeDeltaPartialTick(false)) / 4.0F, 0.0F, 1.0F);
        float cx = graphics.guiWidth() / 2.0F;
        float cy = graphics.guiHeight() / 2.0F;
        int count = Math.max(1, entries.size());
        double span = Math.PI * 2.0D / count;
        int bgA = (int) (appear * 0x90);

        // Секторы залиты текстурой туши (референс автора 01.10 — «дымка» в секторах:
        // docs/design/reference/ui/wheel-author-pick.png), зазоры постоянной ширины,
        // тонкие светлые обводки; выбранный сектор — голубой со светлым ободом.
        float gap = 2.0F;
        float texSpan = OUTER * 2.0F;
        for (int i = 0; i < count; i++) {
            double from = -Math.PI / 2.0D - span(count) / 2.0D + i * span(count);
            double to = from + span(count);
            boolean hot = i == selected;
            // Кольцо полупрозрачное (автор 01.10): видно, что за ним; непрозрачны только контуры и иконки.
            int fill = hot ? argb(appear * 0.62F, 0.62F, 1.0F, 1.0F) : argb(appear * 0.62F, 0.85F, 0.9F, 1.0F);
            GuiShapes.texturedSector(graphics, MIST, cx, cy, INNER, OUTER, from, to, gap, texSpan, fill);
            // Середина сектора темнее, туман остаётся только у краёв (автор 01.10, как в референсе).
            GuiShapes.polarSector(graphics, VIGNETTE, cx, cy, INNER, OUTER, from, to, gap,
                    argb(appear * (hot ? 0.45F : 0.75F), 1.0F, 1.0F, 1.0F));
            int edge = hot ? argb(appear, 0.75F, 0.95F, 1.0F) : argb(appear * 0.30F, 0.80F, 0.86F, 0.95F);
            if (hot) {
                // Выбранный сектор светится изнутри голубым поверх тумана.
                GuiShapes.sectorFill(graphics, cx, cy, INNER, OUTER, from, to, gap,
                        argb(appear * 0.24F, 0.45F, 0.82F, 0.95F));
                // Свечение края: широкая бледная обводка под тонкой яркой.
                GuiShapes.sectorOutline(graphics, cx, cy, INNER, OUTER, from, to, gap, 3.5F,
                        argb(appear * 0.30F, 0.45F, 0.85F, 1.0F));
            }
            GuiShapes.sectorOutline(graphics, cx, cy, INNER, OUTER, from, to, gap, hot ? 1.2F : 0.6F, edge);
        }
        // Внешнее кольцо — слоты раскладки: иконка слота, активный помечен точкой.
        int slotsN = ClientLoadoutState.open();
        List<Optional<ResourceLocation>> slotList = ClientLoadoutState.slots();
        for (int i = 0; i < slotsN; i++) {
            double from = -Math.PI / 2.0D - span(slotsN) / 2.0D + i * span(slotsN);
            double to = from + span(slotsN);
            boolean hot = i == slotHover;
            boolean shown = i == page;
            GuiShapes.sectorFill(graphics, cx, cy, SLOT_INNER, SLOT_OUTER, from, to, gap,
                    hot ? argb(appear * 0.55F, 0.45F, 0.82F, 0.95F) : argb(appear * (shown ? 0.42F : 0.30F), 0.06F, 0.08F, 0.12F));
            GuiShapes.sectorOutline(graphics, cx, cy, SLOT_INNER, SLOT_OUTER, from, to, gap, hot ? 1.2F : 0.6F,
                    hot ? argb(appear, 0.75F, 0.95F, 1.0F) : argb(appear * (shown ? 0.55F : 0.25F), 0.80F, 0.86F, 0.95F));
        }
        // Центр — отдельный тёмный круг с ободком.
        GuiShapes.ring(graphics, cx, cy, 0.0F, INNER - 3.0F, argb(appear * 0.55F, 0.02F, 0.035F, 0.07F));
        GuiShapes.ring(graphics, cx, cy, INNER - 4.0F, INNER - 3.0F, argb(appear * 0.6F, 0.80F, 0.86F, 0.95F));
        graphics.flush();

        // Иконки в серединах секторов.
        for (int i = 0; i < count; i++) {
            double mid = -Math.PI / 2.0D + i * span;
            float r = (INNER + OUTER) / 2.0F;
            // Пиксельные иконки — строго 32×32: при нецелом масштабе пиксели выходят неровными.
            int ix = (int) (cx + Math.cos(mid) * r) - 16;
            int iy = (int) (cy + Math.sin(mid) * r) - 16;
            Optional<ResourceLocation> technique = i < entries.size() ? Optional.of(entries.get(i)) : Optional.empty();
            if (technique.isPresent()) {
                graphics.setColor(1.0F, 1.0F, 1.0F, appear * (i == selected ? 1.0F : 0.75F));
                com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                int size = 32;
                int off = 0;
                graphics.blit(TechniqueIcons.of(technique.get()), ix - off, iy - off, size, size,
                        0.0F, 0.0F, 48, 48, 48, 48);
                graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            } else {
                GuiShapes.ring(graphics, ix + 16, iy + 16, 0.0F, 2.0F, ((int) (appear * 0x80) << 24) | 0xC8DCEC);
            }
        }

        // Иконки слотов 24×24 (половина 48 — без неровных пикселей).
        for (int i = 0; i < slotsN; i++) {
            double mid = -Math.PI / 2.0D + i * span(slotsN);
            float r = (SLOT_INNER + SLOT_OUTER) / 2.0F;
            int ix = (int) (cx + Math.cos(mid) * r) - 12;
            int iy = (int) (cy + Math.sin(mid) * r) - 12;
            Optional<ResourceLocation> t = i < slotList.size() ? slotList.get(i) : Optional.empty();
            if (t.isPresent()) {
                graphics.setColor(1.0F, 1.0F, 1.0F, appear * (i == slotHover || i == page ? 1.0F : 0.55F));
                com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                Optional<io.github.verycooltimo.murim.technique.Styles.Style> st = io.github.verycooltimo.murim.technique.Styles.of(t.get());
                // Слот стиля — иконка первой формы (знак стиля), отдельная техника — своя.
                ResourceLocation iconOf = st.map(x -> x.forms().get(0)).orElse(t.get());
                graphics.blit(TechniqueIcons.of(iconOf), ix, iy, 24, 24, 0.0F, 0.0F, 48, 48, 48, 48);
                graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            }
            if (i == ClientLoadoutState.active()) {
                GuiShapes.ring(graphics, ix + 12, iy - 3, 0.0F, 1.6F, ((int) (appear * 0xE0) << 24) | 0xF1A9CB);
            }
        }

        // Название и слой — над кольцом: в уменьшенный центр не помещаются, а снизу налезали на шкалу ци.
        Font font = minecraft.font;
        List<Optional<ResourceLocation>> slots = ClientLoadoutState.slots();
        Optional<ResourceLocation> current = page < slots.size() ? slots.get(page) : Optional.empty();
        Optional<ResourceLocation> technique = selected >= 0 && selected < entries.size() ? Optional.of(entries.get(selected))
                : slotHover >= 0 && slotHover < slots.size() ? slots.get(slotHover) : current;
        int textA = Math.max(4, (int) (appear * 255)) << 24;
        // Страницы листаются и колёсиком (запасной путь), но слоты теперь видны снаружи.
        if (technique.isPresent()) {
            Optional<io.github.verycooltimo.murim.technique.Styles.Style> style = io.github.verycooltimo.murim.technique.Styles.of(technique.get());
            if (style.isPresent()) {
                small(graphics, font, Component.translatable(style.get().nameKey()), (int) cx, titleY() - 11, textA | 0xF1A9CB);
            }
            // Форма стиля — короткое имя («Вихрь»): название стиля уже стоит строкой выше.
            String formKey = "form." + technique.get().getNamespace() + "." + technique.get().getPath();
            Component name = style.isPresent() && net.minecraft.client.resources.language.I18n.exists(formKey)
                    ? Component.translatable(formKey)
                    : io.github.verycooltimo.murim.mastery.MasteryService.name(technique.get());
            SyncMasteryPayload.Entry entry = TechniqueSlotsHud.mastery(technique.get());
            int ty = titleY();
            graphics.drawString(font, name, (int) cx - font.width(name) / 2, ty, textA | 0xE6F4FF, true);
            if (entry != null) {
                small(graphics, font, Component.translatable("murim.loadout.layer", entry.layer(), entry.cap()),
                        (int) cx, ty + 11, textA | 0x8FD3E8);
            }
        } else {
            small(graphics, font, Component.translatable("murim.loadout.empty_slot"), (int) cx, titleY(),
                    textA | 0x8090A8);
        }
        GuiShapes.ring(graphics, cx + cursorX, cy + cursorY, 0.0F, 1.6F, ((int) (appear * 0xC0) << 24) | 0xFFFFFF);
        graphics.flush();
    }

    private static final ResourceLocation VIGNETTE =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/gui/wheel_sector_vignette.png");

    private static final ResourceLocation MIST = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/gui/wheel_mist.png");

    /** Строка названия над кольцом; на маленьком экране прижимается к верху, а не уходит за край. */
    private static int titleY() {
        Minecraft mc = Minecraft.getInstance();
        int cy = mc.getWindow().getGuiScaledHeight() / 2;
        return Math.max(13, (int) (cy - SLOT_OUTER - 22));
    }

    private static double span(int count) {
        return Math.PI * 2.0D / count;
    }

    private static int argb(float a, float r, float g, float b) {
        return (int) (Mth.clamp(a, 0.0F, 1.0F) * 255) << 24 | (int) (r * 255) << 16 | (int) (g * 255) << 8 | (int) (b * 255);
    }

    private static void small(GuiGraphics graphics, Font font, Component text, int cx, int y, int colour) {
        graphics.pose().pushPose();
        graphics.pose().translate(cx, y, 0.0F);
        graphics.pose().scale(0.75F, 0.75F, 1.0F);
        graphics.drawString(font, text, -font.width(text) / 2, 0, colour, true);
        graphics.pose().popPose();
    }

    public static void reset() {
        open = false;
        selected = -1;
        slotHover = -1;
    }

    private TechniqueWheel() {
    }
}
