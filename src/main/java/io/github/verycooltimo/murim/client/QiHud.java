package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.QiRules;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.profile.DantianProfile;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Шкала ци — появляется с даньтянем (docs/design/19 §3а: «затем шкала ци»).
 *
 * <p>Два уровня ци (§2): циркулирующая — боевая, тратится техниками и восстанавливается
 * сама; накопленная — запас, растёт медитацией. Требование автора 30.09: минималистично
 * и красиво — главное на экране персонаж.
 *
 * <p>В творческом режиме и в наблюдателе шкалы нет: там ци не тратится ({@link QiRules#free}), автор 18.10.
 *
 * <p>Из трёх показанных вариантов (линия, шарики, дуга у прицела) автор выбрал линию.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class QiHud {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "qi");

    /** Ширина как у ряда голода: десять иконок по восемь пикселей и одна на отступ. */
    private static final int WIDTH = 81;

    private static final int CYAN = 0x8FE8F4;
    private static final int DEEP = 0x2E6BD8;
    /** Янтарь резерва: тот же, что у уголька на теле при поглощении. */
    private static final int AMBER = 0xC9A24B;

    /** Сглаженное значение: шкала течёт, а не прыгает раз в секунду по пакету сервера. */
    private static float shownCirc = -1.0F;
    private static float shownPool = -1.0F;

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.FOOD_LEVEL, LAYER, QiHud::render);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        DantianProfile profile = ClientProfileState.profile();
        float circ = circ(profile);
        float pool = pool(profile);
        if (shownCirc < 0.0F) {
            shownCirc = circ;
            shownPool = pool;
        }
        shownCirc += (circ - shownCirc) * 0.25F;
        shownPool += (pool - shownPool) * 0.25F;
    }

    private static float circ(DantianProfile p) {
        return (float) Mth.clamp(p.circulating() / Math.max(1.0E-6D, p.maxCirculating()), 0.0D, 1.0D);
    }

    private static float pool(DantianProfile p) {
        return (float) Mth.clamp(p.pool() / Math.max(1.0D, Realm.wall(p)), 0.0D, 1.0D);
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null || minecraft.gameMode == null
                || QiRules.free(minecraft.player) || !ClientProfileState.profile().isAwakened()) {
            return;
        }
        float circ = Mth.clamp(shownCirc, 0.0F, 1.0F);
        float pool = Mth.clamp(shownPool, 0.0F, 1.0F);
        float time = minecraft.player.tickCount + delta.getGameTimeDeltaPartialTick(false);
        line(graphics, minecraft, circ, pool, time, wallPulse(ClientProfileState.profile()));
        graphics.flush();
    }

    private static int rowY(Minecraft minecraft, GuiGraphics graphics) {
        // Над голодом, где ваниль рисует пузыри воздуха; под водой поднимаемся на ряд.
        int y = graphics.guiHeight() - 49;
        if (minecraft.player.getAirSupply() < minecraft.player.getMaxAirSupply()) {
            y -= 10;
        }
        return y;
    }

    /** Тонкая светящаяся линия без фона: градиент к яркому краю, запас — нить под ней. */
    private static void line(GuiGraphics graphics, Minecraft minecraft, float circ, float pool, float time, boolean wallPulse) {
        int right = graphics.guiWidth() / 2 + 91;
        int left = right - WIDTH;
        int y = rowY(minecraft, graphics) + 5;
        // Пустая часть — едва заметный след, чтобы длина шкалы читалась.
        graphics.fill(left, y, right, y + 1, 0x30FFFFFF);
        int filled = (int) (WIDTH * circ);
        for (int i = 0; i < filled; i++) {
            float t = filled <= 1 ? 1.0F : i / (float) (filled - 1);
            // От глубокого синего у основания к светлому циану у края.
            int colour = lerpRgb(DEEP, CYAN, t);
            graphics.fill(right - 1 - i, y - 1, right - i, y + 1, 0xE0000000 | colour);
        }
        if (filled > 0) {
            float pulse = 0.5F + 0.5F * Mth.sin(time * 0.15F);
            GuiShapes.glow(graphics, right - filled, y, 3.5F + pulse, CYAN, 150);
            GuiShapes.ring(graphics, right - filled, y, 0.0F, 1.2F, 0xFFF4FCFF);
        }
        graphics.fill(right - (int) (WIDTH * pool), y + 2, right, y + 3, 0x90000000 | DEEP);
        if (wallPulse) {
            // Thirty ticks = 1.5 seconds; a small jade edge, without text or a screen flash.
            // API: minecraft-src/net/minecraft/util/Mth.java#sin; client/gui/GuiGraphics.java#fill
            float pulse = 0.5F + 0.5F * Mth.sin(time * (float) (Math.PI * 2.0D / 30.0D));
            int jade = ((int) (60 + 90 * pulse) << 24) | 0x75C9A3;
            graphics.fill(left - 1, y - 1, left, y + 4, jade);
            graphics.fill(left, y + 3, right, y + 4, jade);
        }
        // Резерв неусвоенной доли пилюли (19.10): янтарная нить продолжает нить запаса и тает слева направо,
        // пока ци оседает за десять минут игры; на конце тлеет уголёк.
        float reserve = Mth.clamp(ClientPillState.reserve(), 0.0F, 1.0F - pool);
        if (reserve > 0.0F) {
            int from = right - (int) (WIDTH * pool);
            int to = right - (int) (WIDTH * Math.min(1.0F, pool + reserve));
            float flick = 0.7F + 0.3F * Mth.sin(time * 0.2F);
            graphics.fill(to, y + 2, from, y + 3, ((int) (0xC0 * flick) << 24) | AMBER);
            GuiShapes.glow(graphics, to, y + 2, 2.5F + flick, AMBER, 120);
        }
    }

    /** Derived from the synced reserve, never the smoothed bar or a stale meditation reason. */
    public static boolean wallPulse(DantianProfile profile) {
        return Realm.atWall(profile);
    }

    private static int lerpRgb(int from, int to, float k) {
        float t = Mth.clamp(k, 0.0F, 1.0F);
        int r = (int) Mth.lerp(t, from >> 16 & 0xFF, to >> 16 & 0xFF);
        int g = (int) Mth.lerp(t, from >> 8 & 0xFF, to >> 8 & 0xFF);
        int b = (int) Mth.lerp(t, from & 0xFF, to & 0xFF);
        return r << 16 | g << 8 | b;
    }

    private QiHud() {
    }
}
