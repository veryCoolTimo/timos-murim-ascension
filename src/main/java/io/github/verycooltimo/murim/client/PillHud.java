package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
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
 * Интерфейс пилюль (docs/design/19b §2): окно «сразу» у хотбара и красная кайма отдачи. The game itself is a screen of its own
 * ({@link io.github.verycooltimo.murim.client.flow.FlowOverlay}).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class PillHud {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "pills");

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
        int dev = ClientPillState.deviationAge();
        if (dev >= 0) {
            // Отдача ударом: красная кайма по краям кадра гаснет за секунду; центр свободен.
            float k = 1.0F - (dev + delta.getGameTimeDeltaPartialTick(false)) / ClientPillState.DEVIATION_TICKS;
            int a = (int) (Mth.clamp(k, 0.0F, 1.0F) * 0xA0);
            int w = g.guiWidth();
            int h = g.guiHeight();
            int edge = Math.max(10, h / 6);
            g.fillGradient(0, 0, w, edge, (a << 24) | 0xC01010, 0x00C01010);
            g.fillGradient(0, h - edge, w, h, 0x00C01010, (a << 24) | 0xC01010);
        }
        if (!s.active() && !mc.options.hideGui && mc.player.getMainHandItem().getItem()
                instanceof io.github.verycooltimo.murim.item.PillItem pill && !pill.kind().utility()) {
            var profile = ClientProfileState.profile();
            java.util.List<PillKind> kinds = new java.util.ArrayList<>();
            for (int id : s.pending()) kinds.add(PillKind.byId(id));
            PillRules.Refusal refusal = !profile.isAwakened() ? PillRules.Refusal.NO_DANTIAN
                    : PillRules.canAdd(kinds, pill.kind());
            if (!kinds.contains(pill.kind())) kinds.add(pill.kind());
            double tolerance = kinds.size() == 1 ? PillRules.toleranceFactor(ClientPillState.toleranceSteps(pill.kind())) : 1.0D;
            var plan = PillRules.plan(kinds, profile.rank(), io.github.verycooltimo.murim.cultivation.Realm.wall(profile), tolerance);
            double room = Math.max(0.0D, plan.wall() - profile.pool());
            Component hint = refusal != PillRules.Refusal.NONE
                    ? io.github.verycooltimo.murim.cultivation.PillService.refusalText(refusal)
                    : plan.torn() ? Component.translatable("murim.pill.preview.torn")
                    : room <= 0.0D ? Component.translatable("murim.pill.preview.full")
                    : Component.translatable("murim.pill.preview", Math.round(Math.min(room, plan.gain(1.0D))));
            small(g, mc.font, hint, g.guiWidth() / 2, g.guiHeight() - 85,
                    refusal != PillRules.Refusal.NONE || plan.torn() || room <= 0.0D ? 0xFFFF7777 : 0xE0CFEFFF);
        }
        if (!s.active() && s.windowLeft() > 0 && s.pending().length > 0 && !mc.options.hideGui) {
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
            case HUNDRED_GINSENG -> ModItems.HUNDRED_GINSENG.get();
            case PURPLE_GINSENG -> ModItems.PURPLE_GINSENG.get();
            case THOUSAND_GINSENG -> ModItems.THOUSAND_GINSENG.get();
            case BLACK_LINGZHI -> ModItems.BLACK_LINGZHI.get();
            case PURPLE_HEAVEN -> ModItems.PILL_PURPLE_HEAVEN.get();
            case SMALL_RESTORATION -> ModItems.PILL_SMALL_RESTORATION.get();
            case HEAVENLY_EARTH -> ModItems.PILL_HEAVENLY_EARTH.get();
            case MOONLIGHT -> ModItems.PILL_MOONLIGHT.get();
            case ASCENDING_DRAGON -> ModItems.PILL_ASCENDING_DRAGON.get();
            case GREAT_RESTORATION -> ModItems.PILL_GREAT_RESTORATION.get();
            case THOUSAND_LINGZHI -> ModItems.THOUSAND_LINGZHI.get();
            case BLUE -> ModItems.PILL_BLUE.get();
            case BLOOD -> ModItems.PILL_BLOOD.get();
            case VITALITY -> ModItems.PILL_VITALITY.get();
            case PLUM_BLOSSOM -> ModItems.PILL_PLUM_BLOSSOM.get();
            case MENDING -> ModItems.PILL_MENDING.get();
            case FASTING -> ModItems.PILL_FASTING.get();
            case ANTIDOTE -> ModItems.PILL_ANTIDOTE.get();
            case BLOOD_REPLENISHING -> ModItems.PILL_BLOOD_REPLENISHING.get();
            case QUICK_RECOVERY -> ModItems.PILL_QUICK_RECOVERY.get();
        });
    }

    /**
     * During absorption and its finale, hide decorative vanilla layers while keeping health, armor,
     * food, air and effects visible (UX #33). Mod layers render normally.
     */
    @SubscribeEvent
    static void onVanillaLayer(net.neoforged.neoforge.client.event.RenderGuiLayerEvent.Pre event) {
        if (!(ClientPillState.absorbing() || ClientPillState.finaleAge() >= 0)) {
            return;
        }
        ResourceLocation n = event.getName();
        // UX #33: survival information stays visible during vulnerable absorption.
        if (n.equals(VanillaGuiLayers.EXPERIENCE_BAR)
                || n.equals(VanillaGuiLayers.EXPERIENCE_LEVEL) || n.equals(VanillaGuiLayers.SELECTED_ITEM_NAME)
                || n.equals(VanillaGuiLayers.JUMP_METER)) {
            event.setCanceled(true);
        }
    }

    /** Small caption in the shared HUD style ({@link HudText}). */
    private static void small(GuiGraphics g, Font font, Component text, int cx, int y, int colour) {
        HudText.centered(g, font, text, cx, y, colour, 0.75F);
    }

    private PillHud() {
    }
}
