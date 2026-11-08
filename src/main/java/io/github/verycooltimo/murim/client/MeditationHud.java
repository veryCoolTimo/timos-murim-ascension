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
        event.registerBelow(VanillaGuiLayers.CAMERA_OVERLAYS,
                ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "bt_impact"),
                (graphics, delta) -> io.github.verycooltimo.murim.client.vfx.bt.BtImpactFrame.render(graphics, delta.getGameTimeDeltaPartialTick(false)));
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
            seedScene(graphics, minecraft, age);
        }
        if(io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.benchActive())return;
        // Прорыв: только виньетка — полная заливка спрятала бы светящийся каркас тела.
        int breakAge = ClientMeditationState.breakthroughAge();
        if (breakAge >= 0) {
            if (io.github.verycooltimo.murim.client.vfx.bt.BtScenes.handles(ClientMeditationState.breakthroughTarget())) labVignette(graphics, partial);
            else vignette(graphics, breakAge + partial, io.github.verycooltimo.murim.cultivation.Realm.breakthroughTicks(
                    ClientMeditationState.breakthroughTarget()));
        }
        int warnAge = ClientMeditationState.warningAge();
        if (warnAge >= 0) {
            heartbeat(graphics, warnAge + partial);
        }
        int rankAge = ClientMeditationState.rankUpAge();
        if (rankAge >= 0) {
            if (io.github.verycooltimo.murim.client.vfx.bt.BtScenes.handles(ClientMeditationState.rankUpRank())) labVignette(graphics, partial);
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
        if (warnAge >= 0 || breakAge >= 0) {
            int left = warnAge >= 0
                    ? io.github.verycooltimo.murim.cultivation.Realm.WARNING_TICKS - warnAge
                    : io.github.verycooltimo.murim.cultivation.Realm.breakthroughTicks(
                            ClientMeditationState.breakthroughTarget()) - breakAge;
            HudText.centered(graphics, minecraft.font, Component.translatable(warnAge >= 0
                    ? "murim.rank.breakthrough.warning_exit" : "murim.rank.breakthrough.exit",
                    Math.max(0, (left + 19) / 20), ModKeyMappings.MEDITATION.getTranslatedKeyMessage()),
                    graphics.guiWidth() / 2, TOP + 8, 0xFFE8D9AF, 0.75F);
        }
        SyncMeditationPayload state = ClientMeditationState.state();
        if (!state.active()) {
            return;
        }
        if (state.beats() >= 3) {
            // Во время прорыва виджет ци не нужен: идёт сцена, игроку нечего нажимать.
            if (breakAge < 0 && rankAge < 0 && warnAge < 0 && !ClientPillState.absorbing() && ClientPillState.finaleAge() < 0) {
                seeded(graphics, minecraft, partial);
                ResourceLocation method = ResourceLocation.tryParse(ClientProfileState.profile().imprint());
                if (method != null) {
                    ResourceLocation art = io.github.verycooltimo.murim.mastery.Arts.methodArt(method);
                    var entry = TechniqueSlotsHud.mastery(art);
                    if (entry != null) {
                        Component stars = MasteryText.summary(art, entry);
                        graphics.drawString(minecraft.font, stars,
                                (graphics.guiWidth() - minecraft.font.width(stars)) / 2,
                                TOP + 28, 0xFFA8E4FF, true);
                    }
                }
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
        // The control hint for the first five seconds of forming the dantian (playtest 22.10: the action-bar line faded in two).
        if (ClientMeditationState.sessionTicks() < 100) {
            HudText.centered(graphics, minecraft.font, Component.translatable("murim.meditation.hint.feel",
                    minecraft.options.keyShift.getTranslatedKeyMessage()), width / 2, TOP + 8, 0xE0C8DCEC, 0.75F);
        }
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

        // Stability is a thin line below the ring; the control hint stays throughout the game (UX #24).
        int by = cy + GAME_RADIUS + 6;
        int half = GAME_RADIUS + 3;
        graphics.fill(cx - half, by, cx + half, by + 1, 0x60FFFFFF);
        graphics.fill(cx - half, by, cx - half + (int) (2 * half * Mth.clamp(ring.stability(), 0.0F, 1.0F)), by + 1,
                off ? 0xFFFF7060 : 0xFF9FF0DC);
        { // UX #24: keep the control explanation visible throughout the ring game.
            Font font = minecraft.font;
            Component hint = Component.translatable("murim.meditation.game.key",
                    minecraft.options.keyJump.getTranslatedKeyMessage());
            HudText.centered(graphics, font, hint, cx, by + 5, 0xE0C8DCEC, 0.75F);
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
        // Сколько добавилось — видно; сколько до прорыва — нет (автор 01.10). Круг больше не шкала
        // запаса до стены: ядро дышит, и яркость дыхания — скорость притока (docs/design/19b §4).
        boolean full = io.github.verycooltimo.murim.cultivation.Realm.atWall(profile);
        boolean stunned = ClientPlaceState.stunned();
        // The inflow does not fade inside a session any more (hua-main-1810 §1): the core breathes at one steady rate,
        // brighter only at a place of power.
        double place = ClientPlaceState.place() != null && !stunned ? ClientPlaceState.place().gain() : 1.0D;
        float rate = full || stunned ? 0.0F : (float) Mth.clamp(place / 2.0D, 0.0D, 1.0D);
        float breath = 0.5F + 0.5F * Mth.sin(ticks * 0.08F);

        annulus(graphics, cx, cy, 0.0F, GAME_RADIUS + 3.0F, 0x0, 0x70060A14);
        annulus(graphics, cx, cy, GAME_RADIUS + 2.5F, GAME_RADIUS + 3.5F, 0x0, 0xFF3A5A88);
        float r = GAME_RADIUS * (0.35F + 0.25F * rate + 0.12F * breath * (0.3F + rate));
        int alpha = (int) (0x50 + 0x90 * (0.3F + 0.7F * rate));
        annulus(graphics, cx, cy, 0.0F, r, 0x0, (alpha << 24) | 0x3C78D8);
        annulus(graphics, cx, cy, Math.max(0.0F, r - 1.2F), r, 0x0, 0xFF9FD8FF);
        annulus(graphics, cx, cy, 0.0F, 1.2F + breath * 0.8F, 0x0, 0xFFE8FBFF);
        graphics.flush();

        int by = cy + GAME_RADIUS + 6;
        int half = GAME_RADIUS + 3;
        float circ = (float) Mth.clamp(profile.circulating() / Math.max(1.0E-6D, profile.maxCirculating()), 0.0D, 1.0D);
        graphics.fill(cx - half, by, cx + half, by + 1, 0x60FFFFFF);
        graphics.fill(cx - half, by, cx - half + (int) (2 * half * circ), by + 1, 0xFF9FF0DC);

        // «За сессию: +N ци» — одна обновляемая строка вместо скорости в секунду.
        double total = profile.pool() + profile.circulating();
        if (Double.isNaN(sessionStart)) {
            sessionStart = total;
        }
        sessionGain = Math.max(0.0D, total - sessionStart);
        Font font = minecraft.font;
        int ty = cy - GAME_RADIUS - 11;
        small(graphics, font, Component.translatable("murim.meditation.session",
                String.format(java.util.Locale.ROOT, "%.1f", sessionGain)), cx, ty, 0xE0BFE6FF);
        // Сколько всего ци (автор 03.10: «хотелось бы знать, сколько всего у тебя ци»): запас и боевая,
        // числами вместе с пределами: после прорыва видно расширение центра.
        small(graphics, font, Component.translatable("murim.meditation.total",
                String.format(java.util.Locale.ROOT, "%.1f", profile.pool()),
                String.format(java.util.Locale.ROOT, "%.1f", io.github.verycooltimo.murim.cultivation.Realm.wall(profile)),
                String.format(java.util.Locale.ROOT, "%.1f", profile.circulating()),
                String.format(java.util.Locale.ROOT, "%.1f", profile.maxCirculating())), cx, ty - LINE, 0xF0E8F6FF);
        int line = ty - 2 * LINE;
        // Почему рост стоит — одна причина, короткая подпись.
        String reason = full ? "murim.meditation.reason.full" : stunned ? "murim.meditation.reason.stunned" : null;
        Component wallReason = ClientMeditationState.state().wallReason().orElse(null);
        if (wallReason != null || reason != null) {
            small(graphics, font, wallReason != null ? wallReason : Component.translatable(reason), cx, line, 0xC0A0A8B8);
            line -= LINE;
        }
        if (ClientPlaceState.node() != null) {
            float warn = ClientPlaceState.warning();
            small(graphics, font, warn > 0.0F ? Component.translatable("murim.place.warning")
                            : Component.translatable("murim.place.near_kind", Component.translatable(
                                    "murim.place.kind." + ClientPlaceState.place().kind().getSerializedName())),
                    cx, line, warn > 0.0F ? 0xF0FFB070 : 0xE0A8F0D0);
            line -= LINE;
        }
        // Занятие сессии: «Очищение» или «Эхо» выбранного искусства (то же, что показывает двойник). Без чисел и звёзд.
        net.minecraft.resources.ResourceLocation echo = ClientMeditationState.state().echo().orElse(null);
        small(graphics, font, echo == null ? Component.translatable("murim.meditation.activity.cleanse")
                : Component.translatable("murim.meditation.activity.echo",
                        Component.translatable(io.github.verycooltimo.murim.mastery.Arts.nameKey(echo))),
                cx, line, echo == null ? 0xC0A8E4FF : 0xE0CFEFFF);
        line -= LINE;
        if (ClientMeditationState.sessionTicks() < 80) {
            small(graphics, font, Component.translatable("murim.meditation.seeded.leave",
                    minecraft.options.keyShift.getTranslatedKeyMessage()), cx, line, 0xB0C8DCEC);
        }
    }

    /** Запас + циркулирующая на начале сессии: от него считается «за сессию». */
    private static double sessionStart = Double.NaN;
    /** Step between seated captions: 0.75 text plus its backdrop. */
    private static final int LINE = 10;
    private static double sessionGain;

    /** Начало сессии: вызывается при посадке, до поглощения пилюли — её прибавка тоже «за сессию». */
    static void markSessionStart() {
        io.github.verycooltimo.murim.profile.DantianProfile p = ClientProfileState.profile();
        sessionStart = p.pool() + p.circulating();
        sessionGain = 0.0D;
    }

    /** Сколько накоплено за последнюю сессию — для итога при вставании. */
    public static double sessionGain() {
        return sessionGain;
    }

    /** Small caption in the shared HUD style ({@link HudText}): backdrop, shadow, kept inside the screen. */
    private static void small(GuiGraphics graphics, Font font, Component text, int cx, int y, int colour) {
        HudText.centered(graphics, font, text, cx, y, colour, 0.75F);
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
    /** Lab hush is subtle edge darkening; the room remains visible. */
    private static void labVignette(GuiGraphics g, float partial) {
        int target = ClientMeditationState.rankUpAge()>=0?ClientMeditationState.rankUpRank():ClientMeditationState.breakthroughTarget();
        double t = io.github.verycooltimo.murim.client.vfx.bt.BtScenes.labSeconds(target, partial);
        double k = target == 2
                ? io.github.verycooltimo.murim.client.vfx.bt.BtMath.smooth(14.6,14.75,t) * (1-io.github.verycooltimo.murim.client.vfx.bt.BtMath.smooth(15.2,15.3,t)) * .22
                : io.github.verycooltimo.murim.client.vfx.bt.BtMath.smooth(10.1,10.75,t) * (1-io.github.verycooltimo.murim.client.vfx.bt.BtMath.smooth(10.8,10.85,t)) * .16;
        if(target==3)k=io.github.verycooltimo.murim.client.vfx.bt.BtRank1Math.level(t).gather()*.22;
        if(target==4)k=(.14-.07*io.github.verycooltimo.murim.client.vfx.bt.BtMath.smooth(8,13.6,t))
                *(1-io.github.verycooltimo.murim.client.vfx.bt.BtMath.smooth(25.5,26,t));
        int w=g.guiWidth(), h=g.guiHeight(), edge=Math.max(1,Math.min(w,h)/6);
        for(int i=0;i<edge;i++) {
            int a=(int)(255*k*(1-i/(double)edge)*(1-i/(double)edge));
            g.fill(i,0,i+1,h,a<<24);g.fill(w-i-1,0,w-i,h,a<<24);
            g.fill(edge,i,w-edge,i+1,a<<24);g.fill(edge,h-i-1,w-edge,h-i,a<<24);
        }
    }

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

    /** API verified in /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/gui/GuiGraphics.java:
     * fill(int,int,int,int,int), pose(), drawString(Font,String,int,int,int,boolean), guiWidth/Height;
     * Font.java#width(String), Component.java#translatable/getString. Pose transforms follow
     * GuiGraphics.java#renderItem. The world dimmer is drawn before the creation VFX.
     */
    private static final ResourceLocation DANTIAN_TITLE = ResourceLocation.fromNamespaceAndPath(
            MurimMod.MODID,"textures/gui/dantian_title.png");

    private static void seedScene(GuiGraphics graphics, Minecraft minecraft, float age) {
        double seconds=age/20.0;
        int w=graphics.guiWidth(),h=graphics.guiHeight();
        // GuiGraphics.java#flush: submit pending GUI geometry before the immediate impact graphic.
        graphics.flush();
        io.github.verycooltimo.murim.client.vfx.bt.BtImpactFrame.renderCreation(graphics,seconds);
        double fade=io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.title(seconds);
        double stamp=io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.stampAlpha(seconds);
        if(stamp<=.002)return;
        Font font=minecraft.font;
        String name=Component.translatable("murim.meditation.seed_title").getString();
        String sub=Component.translatable("murim.meditation.seed_subtitle").getString();
        int textWidth=Math.max(font.width(name)*2,font.width(sub));
        int hanziHeight=font.lineHeight*5,hanziWidth=Math.round(hanziHeight*110F/192F);
        int x=(w-hanziWidth-12-textWidth)/2,y=Math.max(2,h/5-hanziHeight/2);
        double stampScale=io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.stampScale(seconds);
        double inkAge=seconds-io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.TITLE_START
                -io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.STAMP_SECONDS;
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/gui/GuiGraphics.java
        // blit(ResourceLocation,int,int,int,int,float,float,int,int,int,int), setColor, pose, fill.
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(x+hanziWidth/2F,y+hanziHeight/2F,0);
            graphics.pose().scale((float)stampScale,(float)stampScale,1);
            graphics.setColor(.025F,.06F,.09F,(float)stamp*.45F);
            graphics.blit(DANTIAN_TITLE,-hanziWidth/2+1,-hanziHeight/2+1,hanziWidth,hanziHeight,0F,0F,110,192,110,192);
            graphics.setColor(.87F,.98F,1,(float)stamp);
            graphics.blit(DANTIAN_TITLE,-hanziWidth/2,-hanziHeight/2,hanziWidth,hanziHeight,0F,0F,110,192,110,192);
        } finally {
            graphics.setColor(1,1,1,1);graphics.pose().popPose();RenderSystem.disableBlend();
        }
        if(inkAge>=0&&inkAge<.3) {
            int inkAlpha=(int)(36*(1-inkAge/.3));
            for(int i=0;i<48;i++) {
                double angle=i*Math.PI/24,r=hanziHeight*(.45+.25*inkAge/.3)*(1+.08*Math.sin(i*2.7));
                int px=x+hanziWidth/2+(int)(Math.cos(angle)*r*.7);
                int py=y+hanziHeight/2+(int)(Math.sin(angle)*r);
                graphics.fill(px,py,px+1,py+1,(inkAlpha<<24)|0xA8DDE8);
            }
        }
        if(fade<=.02)return;
        int alpha=(int)(fade*255)<<24;
        int textCentre=x+hanziWidth+12+textWidth/2;
        seedText(graphics,font,name,textCentre-font.width(name),y+7,2,alpha,0xDFF8FF);
        seedText(graphics,font,sub,textCentre-font.width(sub)/2,y+29,1,alpha,0xA8DDE8);
    }

    private static void seedText(GuiGraphics graphics,Font font,String text,int x,int y,int scale,int alpha,int rgb) {
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(x,y,0);
            graphics.pose().scale(scale,scale,1);
            // Fractional logical offsets become exactly one GUI pixel after scaling.
            for(int[] d:new int[][]{{-1,0},{1,0},{0,-1},{0,1}})
                graphics.drawString(font,text,d[0]/(float)scale,d[1]/(float)scale,alpha|0x07121C,false);
            graphics.drawString(font,text,0,0,alpha|rgb,false);
        } finally { graphics.pose().popPose(); }
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
        float total = ClientMeditationState.rankUpLength();
        if (ClientConfig.SCREEN_FLASHES.get() && ClientMeditationState.rankUpRank() != 2 && ClientMeditationState.rankUpRank() != 3 && ClientMeditationState.rankUpRank() != 4) {
            float flash = ClientMeditationState.rankUpRank() == 1
                    ? (age < 7 ? (float)(.32 * Math.exp(-age/20.0*12)) : 0)
                    : age < 2.0F ? age / 2.0F : Mth.clamp(1.0F - (age - 2.0F) / 8.0F, 0.0F, 1.0F);
            if (flash > 0.0F) {
                // Вспышка слабее и в цвете ранга: серо-белая заливка на 0xB0 съедала фигуру
                // на кадре выхода ауры (второе мнение codex 01.10).
                int tint = switch (ClientMeditationState.rankUpRank()) {
                    case 2 -> 0xFFE6A8; case 3 -> 0xFFD8EE; case 4 -> 0xF2F0FF; default -> 0xC8F5FF; };
                graphics.fill(0, 0, w, h, ((int) (ClientMeditationState.rankUpRank() == 1 ? flash * 255 : flash * flash * 0x58) << 24) | tint);
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
