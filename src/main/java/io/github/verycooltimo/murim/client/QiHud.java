package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.MeditationService;
import io.github.verycooltimo.murim.profile.DantianProfile;
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

/**
 * Шкала ци — появляется с семенем даньтяня (docs/design/19 §3а: «затем шкала ци»).
 *
 * <p>Два уровня ци (§2): циркулирующая — боевая, тратится техниками и восстанавливается
 * сама; накопленная — запас, растёт медитацией. Компактно, над шкалой голода: главное
 * на экране — персонаж, а не интерфейс (замечание автора 30.09).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class QiHud {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "qi");

    /** Ширина как у ряда голода: десять иконок по восемь пикселей и одна на отступ. */
    private static final int WIDTH = 81;

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.FOOD_LEVEL, LAYER, QiHud::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null || minecraft.gameMode == null
                || !minecraft.gameMode.canHurtPlayer()) {
            return;
        }
        DantianProfile profile = ClientProfileState.profile();
        if (!profile.isAwakened()) {
            return;
        }
        int right = graphics.guiWidth() / 2 + 91;
        int left = right - WIDTH;
        // Над голодом, где ваниль рисует пузыри воздуха; под водой поднимаемся на ряд.
        int y = graphics.guiHeight() - 49;
        if (minecraft.player.getAirSupply() < minecraft.player.getMaxAirSupply()) {
            y -= 10;
        }
        float circ = (float) Mth.clamp(profile.circulating() / Math.max(1.0E-6D, profile.maxCirculating()), 0.0D, 1.0D);
        double cap = Math.max(1.0D, profile.capacity() * MeditationService.POOL_CAP);
        float pool = (float) Mth.clamp(profile.pool() / cap, 0.0D, 1.0D);

        // Циркулирующая — яркая полоса, растёт справа налево, как голод.
        graphics.fill(left - 1, y + 2, right + 1, y + 7, 0x90000000);
        graphics.fill(right - (int) (WIDTH * circ), y + 3, right, y + 6, 0xFF7FE0E8);
        // Накопленная — тонкая линия под ней.
        graphics.fill(right - (int) (WIDTH * pool), y + 6, right, y + 7, 0xFF3C78D8);
        // Даньтянь — точка слева: знак, что шкала про ци, а не про что-то ванильное.
        graphics.fill(left - 5, y + 3, left - 2, y + 6, 0xFFBFE6FF);
    }

    private QiHud() {
    }
}
