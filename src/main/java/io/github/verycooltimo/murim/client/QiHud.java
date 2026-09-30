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
 * <p>Три варианта на выбор автора ({@code MURIM_QI_STYLE}: line, orbs, arc); после выбора
 * останется один.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class QiHud {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "qi");

    /** Ширина как у ряда голода: десять иконок по восемь пикселей и одна на отступ. */
    private static final int WIDTH = 81;

    private static final int CYAN = 0x8FE8F4;
    private static final int DEEP = 0x2E6BD8;

    private static final String STYLE = System.getenv().getOrDefault("MURIM_QI_STYLE", "line");

    /** Сглаженное значение: шкала течёт, а не прыгает раз в секунду по пакету сервера. */
    private static float shownCirc = -1.0F;
    private static float shownPool = -1.0F;
    /** Для дуги: сколько тиков шкала не менялась и полна — после этого она гаснет. */
    private static int idleTicks;

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
        boolean still = Math.abs(circ - shownCirc) < 0.002F && circ >= 0.999F;
        shownCirc += (circ - shownCirc) * 0.25F;
        shownPool += (pool - shownPool) * 0.25F;
        idleTicks = still ? idleTicks + 1 : 0;
    }

    private static float circ(DantianProfile p) {
        return (float) Mth.clamp(p.circulating() / Math.max(1.0E-6D, p.maxCirculating()), 0.0D, 1.0D);
    }

    private static float pool(DantianProfile p) {
        return (float) Mth.clamp(p.pool() / Math.max(1.0D, p.capacity() * MeditationService.POOL_CAP), 0.0D, 1.0D);
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null || minecraft.gameMode == null
                || !minecraft.gameMode.canHurtPlayer() || !ClientProfileState.profile().isAwakened()) {
            return;
        }
        float circ = Mth.clamp(shownCirc, 0.0F, 1.0F);
        float pool = Mth.clamp(shownPool, 0.0F, 1.0F);
        float time = minecraft.player.tickCount + delta.getGameTimeDeltaPartialTick(false);
        switch (STYLE) {
            case "orbs" -> orbs(graphics, minecraft, circ, pool, time);
            case "arc" -> arc(graphics, circ, pool, time);
            default -> line(graphics, minecraft, circ, pool, time);
        }
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

    /** A. Тонкая светящаяся линия без фона: градиент к яркому краю, запас — нить под ней. */
    private static void line(GuiGraphics graphics, Minecraft minecraft, float circ, float pool, float time) {
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
    }

    /** B. Десять шариков ци над голодом — ванильный ритм, но светящиеся. */
    private static void orbs(GuiGraphics graphics, Minecraft minecraft, float circ, float pool, float time) {
        int right = graphics.guiWidth() / 2 + 91;
        int y = rowY(minecraft, graphics) + 4;
        for (int i = 0; i < 10; i++) {
            float cx = right - 4.5F - i * 8;
            float share = Mth.clamp(circ * 10.0F - i, 0.0F, 1.0F);
            GuiShapes.ring(graphics, cx, y, 2.6F, 3.4F, 0x70BFD8F0);
            if (share > 0.0F) {
                GuiShapes.glow(graphics, cx, y, 4.5F, CYAN, (int) (90 * share));
                GuiShapes.ring(graphics, cx, y, 0.0F, 2.6F * (float) Math.sqrt(share), 0xF0000000 | lerpRgb(DEEP, CYAN, share));
            }
            float stock = Mth.clamp(pool * 10.0F - i, 0.0F, 1.0F);
            if (stock > 0.0F) {
                GuiShapes.ring(graphics, cx, y + 5.0F, 0.0F, 0.9F, ((int) (200 * stock) << 24) | DEEP);
            }
        }
    }

    /** C. Дуга справа от прицела; полная и неизменная — гаснет. */
    private static void arc(GuiGraphics graphics, float circ, float pool, float time) {
        float fade = 1.0F - Mth.clamp((idleTicks - 40) / 20.0F, 0.0F, 1.0F);
        if (fade <= 0.0F) {
            return;
        }
        float cx = graphics.guiWidth() / 2.0F;
        float cy = graphics.guiHeight() / 2.0F;
        double from = -Math.PI / 3.0D;
        double to = Math.PI / 3.0D;
        int a = (int) (fade * 255);
        // Дуга растёт снизу вверх; пустая часть — тонкий след.
        GuiShapes.arc(graphics, cx, cy, 13.0F, 14.0F, from, to, ((int) (fade * 60) << 24) | 0xFFFFFF);
        double top = to - (to - from) * circ;
        GuiShapes.arc(graphics, cx, cy, 12.5F, 14.5F, top, to, (a << 24) | CYAN);
        GuiShapes.arc(graphics, cx, cy, 16.0F, 16.8F, to - (to - from) * pool, to, ((int) (fade * 170) << 24) | DEEP);
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
