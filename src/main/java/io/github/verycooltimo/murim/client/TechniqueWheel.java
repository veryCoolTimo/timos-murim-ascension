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
    private static final float INNER = 30.0F;
    private static final float OUTER = 78.0F;
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
    private static int age;

    public static boolean open() {
        return open;
    }

    /** Открыть или закрыть кольцо вручную — для стенда съёмки. */
    public static void setCursor(float x, float y) {
        cursorX = x;
        cursorY = y;
        selected = sectorAt(cursorX, cursorY, ClientLoadoutState.open());
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
        } else if (!wanted && open) {
            open = false;
            if (selected >= 0) {
                PacketDistributor.sendToServer(new LoadoutPayloads.Select(selected));
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
            if (length > OUTER) {
                cursorX *= OUTER / length;
                cursorY *= OUTER / length;
            }
            selected = sectorAt(cursorX, cursorY, ClientLoadoutState.open());
        }
        me.setYRot(baseYaw);
        me.yRotO = baseYaw;
        me.setXRot(basePitch);
        me.xRotO = basePitch;
        event.setYaw(baseYaw);
        event.setPitch(basePitch);
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
        List<Optional<ResourceLocation>> slots = ClientLoadoutState.slots();
        int count = Math.max(1, ClientLoadoutState.open());
        double span = Math.PI * 2.0D / count;
        int bgA = (int) (appear * 0x90);

        // Полупрозрачная подложка и секторы.
        GuiShapes.ring(graphics, cx, cy, 0.0F, OUTER + 6.0F, (bgA << 24) | 0x05080F);
        for (int i = 0; i < count; i++) {
            double from = -Math.PI / 2.0D - span / 2.0D + i * span + 0.03D;
            double to = from + span - 0.06D;
            boolean hot = i == selected;
            boolean current = i == ClientLoadoutState.active();
            int colour = hot ? 0x5FA8E0 : current ? 0x2A4F86 : 0x16223A;
            int a = (int) (appear * (hot ? 0xD0 : 0xA0));
            GuiShapes.arc(graphics, cx, cy, INNER, OUTER, from, to, (a << 24) | colour);
            // Тонкая светлая обводка сектора: внешняя и внутренняя дуги.
            int edge = ((int) (appear * (hot ? 0xE0 : 0x55)) << 24) | 0xBFD8F0;
            GuiShapes.arc(graphics, cx, cy, OUTER - 0.8F, OUTER, from, to, edge);
            GuiShapes.arc(graphics, cx, cy, INNER, INNER + 0.8F, from, to, edge);
            if (hot) {
                GuiShapes.arc(graphics, cx, cy, OUTER, OUTER + 2.5F, from, to, ((int) (appear * 255) << 24) | 0xBFF0FF);
            }
        }
        // Центр — отдельный тёмный круг с ободком.
        GuiShapes.ring(graphics, cx, cy, 0.0F, INNER - 3.0F, ((int) (appear * 0xC0) << 24) | 0x070B16);
        GuiShapes.ring(graphics, cx, cy, INNER - 3.8F, INNER - 3.0F, ((int) (appear * 0x70) << 24) | 0xBFD8F0);
        graphics.flush();

        // Иконки в серединах секторов.
        for (int i = 0; i < count; i++) {
            double mid = -Math.PI / 2.0D + i * span;
            float r = (INNER + OUTER) / 2.0F;
            int ix = (int) (cx + Math.cos(mid) * r) - 14;
            int iy = (int) (cy + Math.sin(mid) * r) - 14;
            Optional<ResourceLocation> technique = i < slots.size() ? slots.get(i) : Optional.empty();
            if (technique.isPresent()) {
                graphics.setColor(1.0F, 1.0F, 1.0F, appear * (i == selected ? 1.0F : 0.75F));
                com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                int size = i == selected ? 32 : 28;
                int off = (size - 28) / 2;
                graphics.blit(TechniqueIcons.of(technique.get()), ix - off, iy - off, size, size,
                        0.0F, 0.0F, 64, 64, 64, 64);
                graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            } else {
                GuiShapes.ring(graphics, ix + 14, iy + 14, 0.0F, 2.0F, ((int) (appear * 0x80) << 24) | 0xC8DCEC);
            }
        }

        // Центр: что выбрано — название и слой; курсор — маленькая точка.
        Font font = minecraft.font;
        int show = selected >= 0 ? selected : ClientLoadoutState.active();
        Optional<ResourceLocation> technique = show < slots.size() ? slots.get(show) : Optional.empty();
        int textA = Math.max(4, (int) (appear * 255)) << 24;
        if (technique.isPresent()) {
            Component name = io.github.verycooltimo.murim.mastery.MasteryService.name(technique.get());
            SyncMasteryPayload.Entry entry = TechniqueSlotsHud.mastery(technique.get());
            small(graphics, font, name, (int) cx, (int) cy - 6, textA | 0xE6F4FF);
            if (entry != null) {
                small(graphics, font, Component.translatable("murim.loadout.layer", entry.layer(), entry.cap()),
                        (int) cx, (int) cy + 2, textA | 0x8FB8E0);
            }
        } else {
            small(graphics, font, Component.translatable("murim.loadout.empty_slot"), (int) cx, (int) cy - 3,
                    textA | 0x8090A8);
        }
        GuiShapes.ring(graphics, cx + cursorX, cy + cursorY, 0.0F, 1.6F, ((int) (appear * 0xC0) << 24) | 0xFFFFFF);
        graphics.flush();
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
    }

    private TechniqueWheel() {
    }
}
