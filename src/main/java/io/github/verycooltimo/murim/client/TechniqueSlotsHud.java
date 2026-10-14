package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.SyncMasteryPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.List;
import java.util.Optional;

/**
 * Слоты техник в режиме боя (автор 01.10). Два варианта на выбор автора
 * ({@code MURIM_SLOTS_STYLE}): {@code row} — второй ряд над хотбаром, {@code left} —
 * квадраты слева от хотбара, где круг медитации.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class TechniqueSlotsHud {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "technique_slots");

    private static final String STYLE = System.getenv().getOrDefault("MURIM_SLOTS_STYLE", "left");

    static final int SIZE = 20;
    private static final int GAP = 2;
    private static final ResourceLocation FINISHER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sword_finisher");

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        io.github.verycooltimo.murim.item.TechniqueManualItem.clientFootworkInfo = FootworkText::lines;
        event.registerAbove(VanillaGuiLayers.HOTBAR, LAYER, TechniqueSlotsHud::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null || minecraft.player.isSpectator()
                || ClientMeditationState.state().active() || !ClientProfileState.profile().isAwakened()) {
            return;
        }
        // Open slots are progression metadata, not evidence that the player can use an art.
        boolean usable = ClientMasteryState.entries().stream().anyMatch(entry -> {
            var definition = io.github.verycooltimo.murim.technique.TechniqueLoader.get(entry.technique());
            return definition != null
                    && io.github.verycooltimo.murim.technique.JunkArts.weaponProblem(minecraft.player, entry.technique()).isEmpty()
                    && (!io.github.verycooltimo.murim.combat.QiSword.needsSword(definition)
                    || (definition.foundation()
                        ? io.github.verycooltimo.murim.combat.QiSword.holdsSword(minecraft.player.getMainHandItem())
                        : QiSwordClient.hasBlade(minecraft.player)));
        });
        if (!usable) {
            return;
        }
        float presence = CombatMode.presence(delta.getGameTimeDeltaPartialTick(false));
        if (presence <= 0.01F) {
            return;
        }
        List<Optional<ResourceLocation>> slots = ClientLoadoutState.slots();
        int techniques = Math.max(slots.size(), ClientLoadoutState.open());
        boolean blade = io.github.verycooltimo.murim.combat.SwordBasicCombo.ownsAttack(
                ClientLoadoutState.foundation().filter(id -> mastery(id) != null).orElse(null),
                io.github.verycooltimo.murim.combat.QiSword.holdsSword(minecraft.player.getMainHandItem()),
                net.neoforged.fml.ModList.get().isLoaded("bettercombat"));
        int count = techniques + (blade ? 1 : 0);
        if (count == 0) {
            return;
        }
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        for (int i = 0; i < count; i++) {
            int x;
            int y;
            if ("row".equals(STYLE)) {
                // Второй ряд над хотбаром, над сердцами и голодом.
                int total = count * SIZE + (count - 1) * GAP;
                x = w / 2 - total / 2 + i * (SIZE + GAP);
                y = h - 49 - SIZE - 4;
            } else {
                // Слева от хотбара: столбцы по два снизу вверх, от хотбара влево.
                int col = i / 2;
                int row = i % 2;
                x = w / 2 - 91 - 6 - SIZE - col * (SIZE + GAP);
                y = h - SIZE - 1 - row * (SIZE + GAP);
            }
            if (blade && i == techniques) {
                slot(graphics, x, y, Optional.of(FINISHER), false, 0, presence);
            } else {
                slot(graphics, x, y, i < slots.size() ? slots.get(i) : Optional.empty(),
                        i == ClientLoadoutState.active(), i + 1, presence);
            }
        }
        ClientLoadoutState.activeTechnique().ifPresent(id -> {
            List<net.minecraft.network.chat.Component> details = FootworkText.combatLines(id);
            List<net.minecraft.util.FormattedCharSequence> wrapped = new java.util.ArrayList<>();
            for (net.minecraft.network.chat.Component line : details) {
                wrapped.addAll(minecraft.font.split(line, Math.min(300, w - 20)));
            }
            int y = h - 99 - wrapped.size() * minecraft.font.lineHeight;
            for (net.minecraft.util.FormattedCharSequence line : wrapped) {
                graphics.drawString(minecraft.font, line, w / 2 - minecraft.font.width(line) / 2, y,
                        ((int) (presence * 255) << 24) | 0xC9E7F4, true);
                y += minecraft.font.lineHeight;
            }
        });
        graphics.flush();
    }

    /** Один слот: тёмный квадрат, иконка, тонкая полоса освоения слоя; выбранный светится. */
    static void slot(GuiGraphics graphics, int x, int y, Optional<ResourceLocation> technique, boolean active,
                     int number, float presence) {
        int a = (int) (presence * 255);
        int bg = ((int) (presence * 0xB0) << 24) | 0x0A1020;
        graphics.fill(x, y, x + SIZE, y + SIZE, bg);
        int border = active ? 0x9FE8FF : 0x3A5A88;
        int ba = active ? a : (int) (presence * 0xC0);
        graphics.fill(x, y, x + SIZE, y + 1, (ba << 24) | border);
        graphics.fill(x, y + SIZE - 1, x + SIZE, y + SIZE, (ba << 24) | border);
        graphics.fill(x, y, x + 1, y + SIZE, (ba << 24) | border);
        graphics.fill(x + SIZE - 1, y, x + SIZE, y + SIZE, (ba << 24) | border);
        if (active) {
            GuiShapes.glow(graphics, x + SIZE / 2.0F, y + SIZE / 2.0F, SIZE * 0.75F, 0x8FE8F4, (int) (presence * 60));
        }
        if (technique.isPresent()) {
            boolean basic = number == 0 && technique.get().equals(FINISHER);
            graphics.setColor(1.0F, 1.0F, 1.0F, presence);
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            graphics.blit(TechniqueIcons.of(technique.get()), x + 2, y + 2, SIZE - 4, SIZE - 4,
                    0.0F, 0.0F, basic ? 16 : 48, basic ? 16 : 48, basic ? 16 : 48, basic ? 16 : 48);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            // Перезарядка: затемнение снизу вверх и секунды поверх иконки.
            if (basic) {
                float charge = Minecraft.getInstance().player.getAttackStrengthScale(0.0F);
                cooldownSweep(graphics, x + 2, y + 2, 1.0F - charge, ((int) (presence * 0xA0) << 24));
            }
            int left = basic ? 0 : ClientCooldowns.remaining(technique.get());
            if (left > 0) {
                int hh = (int) Math.ceil((SIZE - 4) * ClientCooldowns.fraction(technique.get()));
                graphics.fill(x + 2, y + SIZE - 2 - hh, x + SIZE - 2, y + SIZE - 2, ((int) (presence * 0xA0) << 24) | 0x000000);
                String sec = left >= 200 ? String.valueOf((left + 19) / 20) : String.format(java.util.Locale.ROOT, "%.1f", left / 20.0F);
                net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
                graphics.drawString(font, sec, x + SIZE / 2 - font.width(sec) / 2, y + SIZE / 2 - 4, (a << 24) | 0xFFFFFF, true);
            }
            // Освоение текущего слоя — тонкая полоса по низу слота.
            SyncMasteryPayload.Entry entry = basic ? ClientLoadoutState.foundation().map(TechniqueSlotsHud::mastery).orElse(null)
                    : mastery(technique.get());
            if (entry != null && entry.stars() < entry.cap()) {
                int filled = (int) ((SIZE - 4) * Mth.clamp(entry.progress(), 0.0F, 1.0F));
                graphics.fill(x + 2, y + SIZE - 3, x + 2 + filled, y + SIZE - 2, (a << 24) | 0x7FE0C8);
            }
        }
        graphics.pose().pushPose();
        graphics.pose().translate(x + 2, y + 2, 0.0F);
        graphics.pose().scale(0.5F, 0.5F, 1.0F);
        graphics.drawString(Minecraft.getInstance().font, number == 0
                        ? net.minecraft.network.chat.Component.translatable("hud.murim.sword_finisher.key").getString()
                        : String.valueOf(number), 0, 0,
                ((int) (presence * 0xA0) << 24) | 0xC8DCEC, false);
        graphics.pose().popPose();
    }

    /** Clockwise recovery mask, using the real weapon recharge; adds no gameplay cooldown. */
    private static void cooldownSweep(GuiGraphics graphics, int x, int y, float fraction, int colour) {
        if (fraction <= 0.0F) return;
        int size = SIZE - 4;
        for (int row = 0; row < size; row++) {
            int from = -1;
            for (int col = 0; col <= size; col++) {
                double angle = col == size ? 0 : Math.atan2(col + 0.5D - size / 2.0D, size / 2.0D - row - 0.5D);
                if (angle < 0) angle += Math.PI * 2;
                boolean covered = col < size && angle / (Math.PI * 2) < fraction;
                if (covered && from < 0) from = col;
                if (!covered && from >= 0) {
                    graphics.fill(x + from, y + row, x + col, y + row + 1, colour);
                    from = -1;
                }
            }
        }
    }

    static SyncMasteryPayload.Entry mastery(ResourceLocation technique) {
        for (SyncMasteryPayload.Entry entry : ClientMasteryState.entries()) {
            if (entry.technique().equals(technique)) {
                return entry;
            }
        }
        return null;
    }

    private TechniqueSlotsHud() {
    }
}
