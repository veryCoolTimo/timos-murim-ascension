package io.github.verycooltimo.murim.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.MeditationService;
import io.github.verycooltimo.murim.network.SyncMeditationPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Интерфейс медитации — минимальный: главное показывает кольцо на теле.
 *
 * <p>Всё стоит у ВЕРХНЕГО края экрана. Первая версия клала полосы над хотбаром, и в
 * переднем ракурсе они ложились ровно на пояс персонажа — туда, где кольцо и должно
 * быть видно; сообщение над хотбаром при этом перечёркивало полосу.
 *
 * <p>Во время сцены семени края экрана темнеют в синеву: взгляд уходит внутрь тела.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class MeditationHud {

    private static final ResourceLocation LAYER =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "meditation");

    /** API: reference/minecraft-src/net/minecraft/client/gui/Gui.java#VIGNETTE_LOCATION */
    private static final ResourceLocation VIGNETTE =
            ResourceLocation.withDefaultNamespace("textures/misc/vignette.png");

    private static final int LINE_WIDTH = 90;
    private static final int TOP = 26;

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        // Под остальными слоями: затемнение не должно гасить сердца и хотбар.
        event.registerBelow(VanillaGuiLayers.CAMERA_OVERLAYS, LAYER, MeditationHud::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        float partial = delta.getGameTimeDeltaPartialTick(false);
        int sceneAge = ClientMeditationState.seedSceneAge();
        if (sceneAge >= 0) {
            float age = sceneAge + partial;
            vignette(graphics, age);
            seedScene(graphics, minecraft, age);
        }
        // Прорыв: только виньетка — полная заливка спрятала бы светящийся каркас тела.
        int breakAge = ClientMeditationState.breakthroughAge();
        if (breakAge >= 0) {
            vignette(graphics, breakAge + partial, io.github.verycooltimo.murim.cultivation.Realm.BREAKTHROUGH_TICKS);
        }
        int warnAge = ClientMeditationState.warningAge();
        if (warnAge >= 0) {
            heartbeat(graphics, warnAge + partial);
        }
        int rankAge = ClientMeditationState.rankUpAge();
        if (rankAge >= 0) {
            rankUp(graphics, minecraft, rankAge + partial);
        }
        if (ClientMeditationState.aftermath() == ClientMeditationState.Aftermath.BACKLASH) {
            backlash(graphics, ClientMeditationState.BACKLASH_TICKS - ClientMeditationState.aftermathTicks() + partial);
        }
        // Скрытый интерфейс не прячет мини-игру, если его скрыли МЫ на время создания
        // даньтяня; скрытый самим игроком (F1) — прячет.
        if (minecraft.options.hideGui && !ClientMeditationState.cinematic()) {
            return;
        }
        SyncMeditationPayload state = ClientMeditationState.state();
        if (!state.active()) {
            return;
        }
        if (state.beats() >= 3) {
            // Во время прорыва виджет ци не нужен: идёт сцена, игроку нечего нажимать.
            if (breakAge < 0 && rankAge < 0 && warnAge < 0) {
                seeded(graphics, minecraft, partial);
            }
            return;
        }
        if (ClientMeditationState.minigame()) {
            minigame(graphics, minecraft, state.beats(), ClientMeditationState.ring(partial));
            return;
        }
        // Первый такт: играть нечего, только ход времени тонкой линией.
        int width = graphics.guiWidth();
        int x = (width - LINE_WIDTH) / 2;
        float progress = Mth.clamp(ClientMeditationState.sessionTicks()
                / (float) MeditationService.FIRST_FEELING_TICKS, 0.0F, 1.0F);
        graphics.fill(x, TOP, x + LINE_WIDTH, TOP + 1, 0x60FFFFFF);
        graphics.fill(x, TOP, x + (int) (LINE_WIDTH * progress), TOP + 1, 0xE0A8E4FF);
    }

    /** Радиус круга мини-игры в пикселях интерфейса: радиус кольца 1 — край круга. */
    private static final int GAME_RADIUS = 15;

    /**
     * Мини-игра «давление кольца» (решение автора 30.09): тёмный круг, светлая полоса —
     * куда держать кольцо, само кольцо ци, шкала устойчивости. Промах — кольцо краснеет;
     * перетянутое дрожит мелкой дрожью, отпущенное — плывёт волной. Напряжение окрашивает
     * края экрана в красный.
     */
    private static void minigame(GuiGraphics graphics, Minecraft minecraft, int beat,
                                 SyncMeditationPayload.Ring ring) {
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        int cx = w / 2;
        // Маленький круг у верхнего края: крупным планом — персонаж, интерфейс только
        // подсказывает (замечание автора 30.09).
        int cy = 12 + GAME_RADIUS;
        float miss = ring.miss();
        boolean off = miss != 0.0F;
        long time = minecraft.level == null ? 0L : minecraft.level.getGameTime();

        // Напряжение — красные края экрана, растут вместе с ним.
        if (ring.strain() > 0.05F) {
            int a = (int) (Mth.clamp(ring.strain(), 0.0F, 1.0F) * 0x70);
            int edge = Math.max(6, h / 10);
            graphics.fillGradient(0, 0, w, edge, (a << 24) | 0xB01010, 0x00B01010);
            graphics.fillGradient(0, h - edge, w, h, 0x00B01010, (a << 24) | 0xB01010);
        }

        // Круг-подложка и светлая полоса-цель.
        annulus(graphics, cx, cy, 0.0F, GAME_RADIUS + 3.0F, 0x0, 0x70060A14);
        float bandIn = Math.max(0.0F, (ring.centre() - ring.halfWidth()) * GAME_RADIUS);
        float bandOut = (ring.centre() + ring.halfWidth()) * GAME_RADIUS;
        annulus(graphics, cx, cy, bandIn, bandOut, 0x0, off ? 0x40FFFFFF : 0x60CFF4FF);

        // Внешний обод краснеет с напряжением: «загорается красным».
        int rim = off ? 0xFFE04030 : 0xFF3A5A88;
        annulus(graphics, cx, cy, GAME_RADIUS + 2.5F, GAME_RADIUS + 3.5F, 0x0,
                blend(rim, 0xFFFF2020, ring.strain()));
        if (beat >= 2) {
            // Семя-цель в центре: туда и надо дожать.
            annulus(graphics, cx, cy, 0.0F, 1.2F, 0x0, 0xFFE8FBFF);
        }

        // Кольцо ци. Перетянуто — мелкая дрожь (трещит), отпущено — волна (рассыпается).
        float r = ring.radius() * GAME_RADIUS;
        int colour = off ? 0xFFFF5040 : 0xFF8FE4FF;
        float jitter = 0.0F;
        if (off && miss < 0.0F) {
            jitter = ((time * 7919L) % 3L - 1L) * 0.4F;
        } else if (off) {
            jitter = (float) Math.sin(time * 0.9D) * 0.8F;
        }
        annulus(graphics, cx, cy, r - 1.6F + jitter, r + 1.6F + jitter, 0x0, (colour & 0x00FFFFFF) | 0x50000000);
        annulus(graphics, cx, cy, r - 0.7F + jitter, r + 0.7F + jitter, 0x0, colour);
        // Светлая сердцевина: иначе кольцо в полосе сливалось с ней по цвету.
        annulus(graphics, cx, cy, r - 0.4F + jitter, r + 0.4F + jitter, 0x0, off ? 0xFFFFC8C0 : 0xFFF0FCFF);
        graphics.flush();

        // Устойчивость — тонкая линия под кругом. Текста нет: промах показывают цвет
        // и дрожь кольца; подсказка клавиши — только в первые четыре секунды игры.
        int by = cy + GAME_RADIUS + 6;
        int half = GAME_RADIUS + 3;
        graphics.fill(cx - half, by, cx + half, by + 1, 0x60FFFFFF);
        graphics.fill(cx - half, by, cx - half + (int) (2 * half * Mth.clamp(ring.stability(), 0.0F, 1.0F)), by + 1,
                off ? 0xFFFF7060 : 0xFF9FF0DC);
        if (ClientMeditationState.sessionTicks() < 80) {
            Font font = minecraft.font;
            Component hint = Component.translatable("murim.meditation.game.key",
                    minecraft.options.keyJump.getTranslatedKeyMessage());
            graphics.pose().pushPose();
            graphics.pose().translate(cx, by + 5, 0.0F);
            graphics.pose().scale(0.75F, 0.75F, 1.0F);
            graphics.drawString(font, hint, -font.width(hint) / 2, 0, 0xC0C8DCEC, true);
            graphics.pose().popPose();
        }
    }

    /** Смешение двух цветов ARGB. */
    private static int blend(int from, int to, float k) {
        float t = Mth.clamp(k, 0.0F, 1.0F);
        int a = (int) Mth.lerp(t, from >>> 24, to >>> 24);
        int r = (int) Mth.lerp(t, from >> 16 & 0xFF, to >> 16 & 0xFF);
        int g = (int) Mth.lerp(t, from >> 8 & 0xFF, to >> 8 & 0xFF);
        int b = (int) Mth.lerp(t, from & 0xFF, to & 0xFF);
        return a << 24 | r << 16 | g << 8 | b;
    }

    /**
     * Кольцо (или круг при нулевом внутреннем радиусе) четырёхугольниками через тот же
     * {@code RenderType.gui()}, что и ванильный {@code fill}.
     * API: reference/minecraft-src/net/minecraft/client/gui/GuiGraphics.java#fill
     *
     * <p>Обход вершин выравнивается под обход {@code fill}: у типа интерфейса включено
     * отсечение задних граней, и сегмент с обратным обходом просто не рисуется.
     */
    /**
     * Медитация после семени (замечание автора 30.09: «непонятно, что ты делаешь — просто
     * сидишь»). Тот же маленький круг сверху: даньтянь наполняется запасом ци, под ним —
     * циркулирующая, ниже — скорость накопления; когда отдача падает, «ум устаёт».
     */
    private static void seeded(GuiGraphics graphics, Minecraft minecraft, float partial) {
        io.github.verycooltimo.murim.profile.DantianProfile profile = ClientProfileState.profile();
        if (!profile.isAwakened()) {
            return;
        }
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        // Слева от хотбара (замечание автора 30.09), а не сверху: после даньтяня виден
        // ванильный интерфейс, и круг встаёт рядом с ним, как ещё одна ячейка.
        int cx = w / 2 - 91 - 8 - (GAME_RADIUS + 3);
        int cy = h - 13 - (GAME_RADIUS + 3);
        float ticks = ClientMeditationState.sessionTicks() + partial;
        double cap = Math.max(1.0D, profile.capacity() * MeditationService.POOL_CAP);
        float fill = (float) Mth.clamp(profile.pool() / cap, 0.0D, 1.0D);
        float breath = 0.5F + 0.5F * Mth.sin(ticks * 0.08F);

        annulus(graphics, cx, cy, 0.0F, GAME_RADIUS + 3.0F, 0x0, 0x70060A14);
        annulus(graphics, cx, cy, GAME_RADIUS + 2.5F, GAME_RADIUS + 3.5F, 0x0, 0xFF3A5A88);
        // Запас — площадь круга: радиус по корню, чтобы половина запаса выглядела половиной.
        float r = (float) Math.sqrt(fill) * GAME_RADIUS;
        annulus(graphics, cx, cy, 0.0F, r, 0x0, 0xC03C78D8);
        annulus(graphics, cx, cy, Math.max(0.0F, r - 1.2F), r, 0x0, 0xFF9FD8FF);
        // Семя в центре дышит в такт медитации.
        annulus(graphics, cx, cy, 0.0F, 1.2F + breath * 0.8F, 0x0, 0xFFE8FBFF);
        graphics.flush();

        int by = cy + GAME_RADIUS + 6;
        int half = GAME_RADIUS + 3;
        float circ = (float) Mth.clamp(profile.circulating() / Math.max(1.0E-6D, profile.maxCirculating()), 0.0D, 1.0D);
        graphics.fill(cx - half, by, cx + half, by + 1, 0x60FFFFFF);
        graphics.fill(cx - half, by, cx - half + (int) (2 * half * circ), by + 1, 0xFF9FF0DC);

        // Скорость накопления в секунду и её спад.
        double gain = MeditationService.gainAt((int) ticks) * profile.efficiency() * 20.0D;
        boolean tired = MeditationService.gainAt((int) ticks) < MeditationService.gainAt(0) * 0.4D;
        Font font = minecraft.font;
        Component rate = fill >= 1.0F
                ? Component.translatable("murim.meditation.seeded.full")
                : Component.translatable(tired ? "murim.meditation.seeded.tired" : "murim.meditation.seeded.rate",
                        String.format(java.util.Locale.ROOT, "%.1f", gain));
        int ty = cy - GAME_RADIUS - 11;
        small(graphics, font, rate, cx, ty, tired || fill >= 1.0F ? 0xC0A0A8B8 : 0xE0BFE6FF);
        // Что осмысливается — то же, что показывает двойник (§3г).
        java.util.List<net.minecraft.resources.ResourceLocation> pending = ClientMasteryState.pending();
        int line = ty - 8;
        if (!pending.isEmpty()) {
            small(graphics, font, Component.translatable("murim.meditation.seeded.pondering",
                    io.github.verycooltimo.murim.mastery.MasteryService.name(pending.get(0))), cx, line, 0xE0CFEFFF);
            line -= 8;
        }
        if (ClientMeditationState.sessionTicks() < 80) {
            small(graphics, font, Component.translatable("murim.meditation.seeded.leave",
                    minecraft.options.keyShift.getTranslatedKeyMessage()), cx, line, 0xB0C8DCEC);
        }
    }

    private static void small(GuiGraphics graphics, Font font, Component text, int cx, int y, int colour) {
        graphics.pose().pushPose();
        graphics.pose().translate(cx, y, 0.0F);
        graphics.pose().scale(0.75F, 0.75F, 1.0F);
        graphics.drawString(font, text, -font.width(text) / 2, 0, colour, true);
        graphics.pose().popPose();
    }

    private static void annulus(GuiGraphics graphics, float cx, float cy, float inner, float outer,
                                int unused, int colour) {
        if (outer <= 0.0F || outer <= inner) {
            return;
        }
        float in = Math.max(0.0F, inner);
        var matrix = graphics.pose().last().pose();
        var consumer = graphics.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
        int segments = 72;
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2.0D / segments;
            double a1 = (i + 1) * Math.PI * 2.0D / segments;
            float[] xs = {cx + (float) Math.cos(a0) * in, cx + (float) Math.cos(a1) * in,
                          cx + (float) Math.cos(a1) * outer, cx + (float) Math.cos(a0) * outer};
            float[] ys = {cy + (float) Math.sin(a0) * in, cy + (float) Math.sin(a1) * in,
                          cy + (float) Math.sin(a1) * outer, cy + (float) Math.sin(a0) * outer};
            float area = 0.0F;
            for (int k = 0; k < 4; k++) {
                int n = (k + 1) % 4;
                area += xs[k] * ys[n] - xs[n] * ys[k];
            }
            // У fill площадь по формуле шнурка отрицательна — держим тот же знак.
            if (area > 0.0F) {
                for (int k = 3; k >= 0; k--) {
                    consumer.addVertex(matrix, xs[k], ys[k], 0.0F).setColor(colour);
                }
            } else {
                for (int k = 0; k < 4; k++) {
                    consumer.addVertex(matrix, xs[k], ys[k], 0.0F).setColor(colour);
                }
            }
        }
    }

    /** Искажение ци: красная вспышка по экрану, гаснет за две с половиной секунды. */
    private static void backlash(GuiGraphics graphics, float age) {
        float k = Mth.clamp(1.0F - age / ClientMeditationState.BACKLASH_TICKS, 0.0F, 1.0F);
        if (k <= 0.0F) {
            return;
        }
        float flash = ClientConfig.SCREEN_FLASHES.get() ? k * k : k * k * 0.4F;
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), ((int) (flash * 0x90) << 24) | 0xA00808);
    }

    /**
     * Затемнение краёв в синеву — тот же приём, что у ванильной виньетки: смешивание
     * «ноль × источник, (1 − цвет) × приёмник» гасит каналы по цвету текстуры.
     * Синий гасится слабее красного, поэтому края уходят в глубокую синеву, а не в серость.
     */
    private static void vignette(GuiGraphics graphics, float age) {
        vignette(graphics, age, ClientMeditationState.SEED_SCENE_TICKS);
    }

    private static void vignette(GuiGraphics graphics, float age, float total) {
        float in = Mth.clamp(age / 20.0F, 0.0F, 1.0F);
        float out = Mth.clamp((total - age) / 40.0F, 0.0F, 1.0F);
        float k = in * out;
        if (k <= 0.0F) {
            return;
        }
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ZERO,
                GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        graphics.setColor(0.95F * k, 0.85F * k, 0.55F * k, 1.0F);
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        // Три прохода: одна ванильная виньетка слишком мягкая для «взгляда внутрь».
        graphics.blit(VIGNETTE, 0, 0, -90, 0.0F, 0.0F, w, h, w, h);
        graphics.blit(VIGNETTE, 0, 0, -90, 0.0F, 0.0F, w, h, w, h);
        graphics.blit(VIGNETTE, 0, 0, -90, 0.0F, 0.0F, w, h, w, h);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    /** Мягкая круглая текстура свечения — та же, что у ядра удара в мире. */
    private static final ResourceLocation GLOW =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/impact_core.png");

    /**
     * Взрыв рождения семени (замечание автора 29.09: «затемнить всё, нет текста,
     * вспышку белее, экспозицию сильнее, чуть тряски»).
     *
     * <p>Порядок слоёв: затемнение всего экрана → семя поверх темноты (иначе затемнение
     * гасит и его) → белая вспышка-передержка → титр. Вспышка одна за сцену и
     * отключается настройкой экранных вспышек (правило 04).
     */
    private static void seedScene(GuiGraphics graphics, Minecraft minecraft, float age) {
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        float total = ClientMeditationState.SEED_SCENE_TICKS;
        float out = Mth.clamp((total - age) / 40.0F, 0.0F, 1.0F);

        // Затемнение: весь мир уходит в тёмную синеву, пока идёт «взгляд внутрь».
        // Не до черноты: силуэт тела и жилы должны угадываться.
        float dark = Mth.clamp((age - 2.0F) / 10.0F, 0.0F, 1.0F) * out;
        if (dark > 0.0F) {
            int a = (int) (dark * 0xA8);
            graphics.fill(0, 0, w, h, (a << 24) | 0x03060F);
        }

        // Семя пробивает темноту: дорисовывается по своей экранной точке.
        float[] seed = io.github.verycooltimo.murim.client.vfx.MeditationVfxRenderer.seedOnScreen();
        if (seed != null && dark > 0.0F) {
            float beat = (age % 30.0F) / 30.0F;
            float pulse = (float) (Math.exp(-beat * 18.0D) + 0.6D * Math.exp(-Math.abs(beat - 0.22D) * 18.0D));
            int cx = (int) (seed[0] * w);
            int cy = (int) (seed[1] * h);
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            glow(graphics, cx, cy, (int) (h * (0.55F + 0.08F * pulse)), 0.16F, 0.42F, 0.95F, 0.55F * dark);
            glow(graphics, cx, cy, (int) (h * (0.22F + 0.05F * pulse)), 0.45F, 0.88F, 1.0F, 0.8F * dark);
            glow(graphics, cx, cy, (int) (h * (0.07F + 0.03F * pulse)), 0.9F, 0.98F, 1.0F, dark);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }

        // Вспышка: резкая передержка в белое за два тика и спад за полсекунды.
        if (ClientConfig.SCREEN_FLASHES.get()) {
            // Спад кубический: удар белым, без долгой серой дымки после него.
            float flash = age < 2.0F ? age / 2.0F : Mth.clamp(1.0F - (age - 2.0F) / 9.0F, 0.0F, 1.0F);
            if (flash > 0.0F) {
                graphics.fill(0, 0, w, h, ((int) (flash * flash * flash * 0xFA) << 24) | 0xF4FBFF);
            }
        }

        // Титр: появляется, когда вспышка отгорела, и гаснет до конца сцены.
        float title = Mth.clamp((age - 24.0F) / 16.0F, 0.0F, 1.0F) * Mth.clamp((total - 30.0F - age) / 30.0F, 0.0F, 1.0F);
        if (title > 0.02F) {
            int alpha = Math.max(4, (int) (title * 255)) << 24;
            Font font = minecraft.font;
            Component name = Component.translatable("murim.meditation.seed_title");
            graphics.pose().pushPose();
            graphics.pose().translate(w / 2.0F, h * 0.24F, 0.0F);
            graphics.pose().scale(2.0F, 2.0F, 1.0F);
            graphics.drawString(font, name, -font.width(name) / 2, 0, alpha | 0xCFEFFF, true);
            graphics.pose().popPose();
            String imprint = ClientProfileState.profile().imprint();
            if (imprint != null && imprint.contains(":")) {
                ResourceLocation id = ResourceLocation.tryParse(imprint);
                if (id != null) {
                    Component method = Component.translatable("method." + id.getNamespace() + "." + id.getPath());
                    graphics.drawString(font, method, (w - font.width(method)) / 2, (int) (h * 0.24F) + 24,
                            alpha | 0x8FB8E8, true);
                }
            }
        }
    }

    /**
     * Предупреждение перед прорывом: края экрана бьются вместе со стуком сердца, всё
     * темнее к концу — мир уходит, начинается взгляд внутрь.
     */
    private static void heartbeat(GuiGraphics graphics, float age) {
        float total = io.github.verycooltimo.murim.cultivation.Realm.WARNING_TICKS;
        float period = age < total / 2.0F ? 20.0F : 12.0F;
        float beat = (age % period) / period;
        float pulse = (float) Math.exp(-beat * 10.0D);
        float rise = Mth.clamp(age / total, 0.0F, 1.0F);
        vignette(graphics, Math.max(1.0F, 20.0F * (0.35F * rise + 0.45F * pulse)), 1.0E6F);
    }

    /**
     * Выход ауры после прорыва: короткая вспышка (отключается настройкой экранных вспышек)
     * и титр нового ранга.
     */
    private static void rankUp(GuiGraphics graphics, Minecraft minecraft, float age) {
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        float total = ClientMeditationState.RANK_UP_TICKS;
        if (ClientConfig.SCREEN_FLASHES.get()) {
            float flash = age < 2.0F ? age / 2.0F : Mth.clamp(1.0F - (age - 2.0F) / 8.0F, 0.0F, 1.0F);
            if (flash > 0.0F) {
                // Вспышка слабее и в цвете ранга: серо-белая заливка на 0xB0 съедала фигуру
                // на кадре выхода ауры (второе мнение codex 01.10).
                int tint = switch (ClientMeditationState.rankUpRank()) {
                    case 2 -> 0xFFE6A8; case 3 -> 0xFFD8EE; case 4 -> 0xF2F0FF; default -> 0xD8F4FF; };
                graphics.fill(0, 0, w, h, ((int) (flash * flash * 0x58) << 24) | tint);
            }
        }
        // Титр — после пика выброса, не вместе с ним (второе мнение по кадрам 01.10).
        // Титр — после кадра затухающей ауры, а не сразу за вспышкой.
        float title = Mth.clamp((age - 26.0F) / 10.0F, 0.0F, 1.0F) * Mth.clamp((total - age) / 15.0F, 0.0F, 1.0F);
        if (title > 0.02F) {
            int alpha = Math.max(4, (int) (title * 255)) << 24;
            Font font = minecraft.font;
            Component name = Component.translatable(
                    io.github.verycooltimo.murim.cultivation.Realm.nameKey(ClientMeditationState.rankUpRank()));
            graphics.pose().pushPose();
            graphics.pose().translate(w / 2.0F, h * 0.22F, 0.0F);
            graphics.pose().scale(2.5F, 2.5F, 1.0F);
            graphics.drawString(font, name, -font.width(name) / 2, 0, alpha | 0xF3E6C4, true);
            graphics.pose().popPose();
            Component sub = Component.translatable("murim.rank.breakthrough.title");
            graphics.drawString(font, sub, (w - font.width(sub)) / 2, (int) (h * 0.22F) + 28, alpha | 0x9FC8EE, true);
        }
    }

    private static void glow(GuiGraphics graphics, int cx, int cy, int size, float r, float g, float b, float a) {
        if (size <= 0 || a <= 0.0F) {
            return;
        }
        graphics.setColor(r, g, b, Mth.clamp(a, 0.0F, 1.0F));
        graphics.blit(GLOW, cx - size / 2, cy - size / 2, 0.0F, 0.0F, size, size, size, size);
    }

    private MeditationHud() {
    }
}
