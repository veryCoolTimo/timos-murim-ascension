package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.BladeTrailRenderer;
import io.github.verycooltimo.murim.client.vfx.ImpactScreenLayer;
import io.github.verycooltimo.murim.client.vfx.TechniqueNameLayer;
import io.github.verycooltimo.murim.combat.TechniquePhase;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import io.github.verycooltimo.murim.network.StartTechniquePayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Клиентская сторона техники: чтение клавиши и реакция на события от сервера.
 *
 * <p>Здесь нет ни одного игрового решения. Нажатие превращается в запрос к серверу, а вся картинка
 * рисуется по ответу — правило 03.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ClientTechniqueHandler {

    /**
     * Техника, привязанная к клавише на этапе 1.
     *
     * <p>Временно и осознанно: единый язык управления для десятков техник — отдельное
     * решение (ADR-80), и до него клавиша запускает одну технику по идентификатору,
     * а не по зашитому описанию. Идентификатор строкой, потому что описание живёт в датапаке
     * и на клиенте появляется только после синхронизации.
     */

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        boolean wheelClicked = TechniqueWheel.consumeClicks();
        boolean pressed = false;
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/KeyMapping.java#consumeClick,isDown
        while (ModKeyMappings.TECHNIQUE.consumeClick()) {
            pressed = true;
        }
        // Resolve the chord before either release check can consume a sub-tick tap.
        boolean chord = pressed && (wheelClicked || ModKeyMappings.WHEEL.isDown());
        if (chord) TechniqueWheel.cancel();
        TechniqueWheel.tick();

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            bambooHeld = null;
            return;
        }

        // consumeClick вычитывает накопленные нажатия. Вычерпываем очередь полностью, но
        // отправляем не более одного запроса за тик: иначе при лагах уходит пачка пакетов,
        // из которых сервер всё равно примет первый — остальные упрутся в кулдаун.
        TechniqueWheel.finishSelectionIfReleased();
        tickPending();
        if (bambooHeld != null && (!ModKeyMappings.TECHNIQUE.isDown() || minecraft.screen != null)) {
            PacketDistributor.sendToServer(new StartTechniquePayload(bambooHeld, 1, true));
            bambooHeld = null;
        }
        tickDodge(minecraft);

        if (chord) {
            // V+R — закреплённый боевой шаг (или форма слота шагов), меч остаётся активным.
            java.util.Optional<net.minecraft.resources.ResourceLocation> step = pinnedStep.isPresent() ? pinnedStep
                    : footworkStyle().flatMap(st -> ClientLoadoutState.slots().stream().flatMap(java.util.Optional::stream)
                            .filter(st.forms()::contains).findFirst());
            if (step.isPresent()) {
                PacketDistributor.sendToServer(new io.github.verycooltimo.murim.network.TraversePayloads.Request(
                        step.get(), footworkInput(minecraft)));
                CombatMode.engage();
            }
            pressed = false;
        }
        if (pressed) {
            // Применяется техника выбранного слота (кольцо выбора, автор 01.10). Пустой слот —
            // короткий звук отказа; пустой слот виден в раскладке.
            java.util.Optional<net.minecraft.resources.ResourceLocation> active = ClientLoadoutState.activeTechnique();
            io.github.verycooltimo.murim.MurimMod.LOGGER.info("Кольцо (клиент): R применяет {} из слота {}", active.orElse(null),
                    ClientLoadoutState.active());
            // A Seven Stars link/opening can override a Hua sword cooldown on the server.
            // Always send that intent; the client has no authority to predict the timing window.
            if (active.isPresent() && !io.github.verycooltimo.murim.technique.SevenStarsRules.huaSword(active.get())
                    && ClientCooldowns.remaining(active.get()) > 0 && minecraft.player != null) {
                // The wheel shows the cooldown; a short sound acknowledges the refused press.
                Sfx.ui(() -> net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.25F, 0.7F);
            } else if (active.isPresent()) {
                // Техника шага — семейство подтехник: вместе с R уходит контекст ввода,
                // подтехнику выбирает сервер (docs/design/21-footwork-families.md).
                if (io.github.verycooltimo.murim.combat.FootworkService.family(
                        io.github.verycooltimo.murim.technique.TechniqueLoader.get(active.get())) != null) {
                    PacketDistributor.sendToServer(new io.github.verycooltimo.murim.network.TraversePayloads.Request(
                            active.get(), footworkInput(minecraft)));
                } else {
                    int side = minecraft.options.keyLeft.isDown() ? -1 : 1;
                    PacketDistributor.sendToServer(new StartTechniquePayload(active.get(), side, false));
                    if (io.github.verycooltimo.murim.technique.BambooRules.form(active.get()) == 4) bambooHeld = active.get();
                }
                CombatMode.engage();
            } else if (Minecraft.getInstance().player != null) {
                Sfx.ui(() -> net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.25F, 0.7F);
            }
        }
    }

    /** Двойное A/D/S: начало и конец последнего короткого нажатия (клиентские тики). */
    private static final long[] TAP_DOWN = new long[3];
    private static final long[] TAP_UP = {-100, -100, -100};
    private static final boolean[] TAP_WAS = new boolean[3];
    private static long clientTicks;
    private static net.minecraft.resources.ResourceLocation bambooHeld;
    /** Окно между нажатиями и предельная длина первого нажатия (codex 03.10: 0,22 с и 0,15 с). */
    private static final int TAP_GAP = 5;
    private static final int TAP_HOLD = 3;
    private static int sprintTicks;
    private static boolean runSent;
    private static java.util.Optional<net.minecraft.resources.ResourceLocation> passiveForm = java.util.Optional.empty();
    /** Закреплённый боевой шаг (выбран на кольце, слот не переключается): V+R. */
    private static java.util.Optional<net.minecraft.resources.ResourceLocation> pinnedStep = java.util.Optional.empty();

    public static void pinStep(net.minecraft.resources.ResourceLocation form) {
        pinnedStep = java.util.Optional.of(form);
    }

    public static java.util.Optional<net.minecraft.resources.ResourceLocation> pinnedStep() {
        return pinnedStep;
    }

    /**
     * Шаги без кольца (автор 03.10: «удобно и используемо», разбор codex):
     * двойное A/D/S — выбранное уклонение; спринт 0,5 с — выбранный бег.
     * Всё — стиля шагов из раскладки, даже когда активен меч.
     */
    private static void tickDodge(Minecraft minecraft) {
        clientTicks++;
        net.minecraft.client.Options o = minecraft.options;
        boolean blocked = minecraft.player == null || minecraft.screen != null || TechniqueWheel.open()
                || ClientMeditationState.state().active() || minecraft.player.isInWater() || minecraft.player.onClimbable()
                || minecraft.player.isFallFlying()
                // Sect drills: the trail sprint is without qi (05.10); a carried stone is no run.
                || io.github.verycooltimo.murim.client.training.ClientTraining.suppressFootwork();
        java.util.Optional<io.github.verycooltimo.murim.technique.Styles.Style> style = footworkStyle();
        var selected = ClientLoadoutState.slots().stream().flatMap(java.util.Optional::stream)
                .filter(id -> style.isPresent() && style.get().forms().contains(id)).findFirst();
        if (!selected.equals(passiveForm)) {
            passiveForm = selected;
            sprintTicks = 0;
            runSent = false;
        }
        boolean[] now = {o.keyLeft.isDown(), o.keyRight.isDown(), o.keyDown.isDown()};
        for (int i = 0; i < 3; i++) {
            boolean down = now[i] && !TAP_WAS[i];
            boolean up = !now[i] && TAP_WAS[i];
            TAP_WAS[i] = now[i];
            if (up) {
                // Короткое нажатие запоминается; долгое (обычная ходьба) — нет.
                TAP_UP[i] = clientTicks - TAP_DOWN[i] <= TAP_HOLD ? clientTicks : -100;
            }
            if (!down) {
                continue;
            }
            TAP_DOWN[i] = clientTicks;
            if (!blocked && !minecraft.player.isShiftKeyDown() && clientTicks - TAP_UP[i] <= TAP_GAP && style.isPresent()) {
                TAP_UP[i] = -100;
                net.minecraft.resources.ResourceLocation evade = style.get().forms().get(0);
                if (selected.filter(evade::equals).isPresent() && TechniqueSlotsHud.mastery(evade) != null) {
                    PacketDistributor.sendToServer(new io.github.verycooltimo.murim.network.TraversePayloads.Request(
                            evade, footworkInput(minecraft)));
                    CombatMode.engage();
                }
            }
        }
        if (blocked || style.isEmpty()) {
            sprintTicks = 0;
            return;
        }
        // Автобег: полсекунды спринта вперёд — бег стиля (Тропа / Молния); спринт кончился — сервер гасит сам.
        if (minecraft.player.isSprinting() && o.keyUp.isDown()
                && style.flatMap(io.github.verycooltimo.murim.technique.Styles::footworkRun).equals(selected)) {
            if (++sprintTicks >= 10 && !runSent) {
                runSent = true;
                io.github.verycooltimo.murim.technique.Styles.footworkRun(style.get())
                        .filter(f -> TechniqueSlotsHud.mastery(f) != null)
                        .ifPresent(f -> PacketDistributor.sendToServer(new io.github.verycooltimo.murim.network.TraversePayloads.Request(
                                f, io.github.verycooltimo.murim.combat.FootworkService.SPRINT | io.github.verycooltimo.murim.combat.FootworkService.FORWARD
                                        | io.github.verycooltimo.murim.combat.FootworkService.PASSIVE)));
            }
        } else {
            sprintTicks = 0;
            runSent = false;
        }

    }

    /** Стиль шагов, стоящий в раскладке (первый найденный). */
    private static java.util.Optional<io.github.verycooltimo.murim.technique.Styles.Style> footworkStyle() {
        for (java.util.Optional<net.minecraft.resources.ResourceLocation> slot : ClientLoadoutState.slots()) {
            if (slot.isEmpty()) {
                continue;
            }
            for (io.github.verycooltimo.murim.technique.Styles.Style style : io.github.verycooltimo.murim.technique.Styles.FOOTWORK) {
                if (style.forms().contains(slot.get())) {
                    return java.util.Optional.of(style);
                }
            }
        }
        return java.util.Optional.empty();
    }

    /** Биты контекста ввода для шагов: спринт, присед, направления (по действиям, не клавишам). */
    public static int footworkInput(Minecraft minecraft) {
        net.minecraft.client.Options o = minecraft.options;
        int input = 0;
        if (o.keySprint.isDown() || minecraft.player != null && minecraft.player.isSprinting()) {
            input |= io.github.verycooltimo.murim.combat.FootworkService.SPRINT;
        }
        if (o.keyShift.isDown()) {
            input |= io.github.verycooltimo.murim.combat.FootworkService.SNEAK;
        }
        if (o.keyUp.isDown()) {
            input |= io.github.verycooltimo.murim.combat.FootworkService.FORWARD;
        }
        if (o.keyDown.isDown()) {
            input |= io.github.verycooltimo.murim.combat.FootworkService.BACK;
        }
        if (o.keyLeft.isDown()) {
            input |= io.github.verycooltimo.murim.combat.FootworkService.LEFT;
        }
        if (o.keyRight.isDown()) {
            input |= io.github.verycooltimo.murim.combat.FootworkService.RIGHT;
        }
        return input;
    }

    /**
     * Реакция на серверное событие техники. Вызывается из сетевого моста уже в главном потоке.
     */
    public static String tracedTechnique = "";
    public static long tracedStart;

    public static void onTechniqueEvent(TechniqueEventPayload payload) {
        if (isLocalPlayer(payload.sourceId()) && Minecraft.getInstance().level != null) {
            if (payload.event() == TechniqueEventPayload.Event.STARTED) {
                tracedTechnique = payload.techniqueId().toString();
                tracedStart = Minecraft.getInstance().level.getGameTime();
            } else if ((payload.event() == TechniqueEventPayload.Event.FINISHED
                    || payload.event() == TechniqueEventPayload.Event.CANCELLED)
                    && tracedTechnique.equals(payload.techniqueId().toString())) tracedTechnique = "";
        }
        if (io.github.verycooltimo.murim.combat.FragranceAirService.isForm(payload.techniqueId())) {
            io.github.verycooltimo.murim.client.vfx.FootworkVfx.airEvent(payload);
            return;
        }
        switch (payload.event()) {
            case COOLDOWN_RESET -> {
                if (isLocalPlayer(payload.sourceId())) ClientCooldowns.restartOwn(payload.techniqueId());
            }
            case STARTED -> {
                if (Minecraft.getInstance().player != null && payload.sourceId() == Minecraft.getInstance().player.getId()) {
                    ClientCooldowns.started(payload.techniqueId());
                    if (io.github.verycooltimo.murim.technique.SevenStarsRules.isForm(payload.techniqueId())) FootworkMotion.startSevenStars(io.github.verycooltimo.murim.technique.SevenStarsRules.dipper(payload.techniqueId()) ? 28 : 12);
                }
                scheduleAnimation(payload);
                TechniqueDefinition started = TechniqueLoader.get(payload.techniqueId());
                // У ладони собственный набор слоёв: общая схема дуги её не описывает.
                //
                // Рендереры ВЗАИМОИСКЛЮЧАЮЩИЕ. Раньше общая дуга запускалась и для ладони
                // тоже — вопреки этому самому комментарию, — и размашистый веер накладывался
                // поверх сбора в ладони. Отсюда и «эффекты вышли за орбиты», и «ураган в
                // руке»: две разные постановки в одном кадре. Заодно это делало правки
                // ладони невидимыми для измерения — большую часть энергии давала дуга.
                boolean palm = started != null && started.behavior().type().equals(
                        io.github.verycooltimo.murim.technique.TechniqueBehavior.PALM_BLAST);
                // Старые уличные искусства без VFX; у трёх танцев — только шуточное свечение и пыль.
                if (io.github.verycooltimo.murim.technique.SevenStarsRules.isForm(payload.techniqueId())) {
                    // Seven Stars uses only grounded dust/skid traverse events.
                } else if (payload.executionTier() <= 0) {
                    // Learned form at zero mastery: animation only, no qi VFX.
                } else if (io.github.verycooltimo.murim.technique.JunkArts.isJunk(payload.techniqueId())) {
                    io.github.verycooltimo.murim.client.vfx.FunnyJunkVfx.start(payload.sourceId(), started);
                    MurimMod.LOGGER.debug("Искусство третьего сорта {}", payload.techniqueId());
                } else if (io.github.verycooltimo.murim.technique.BambooRules.is(payload.techniqueId())) {
                    // Hand qi and contacts use BambooVfx, never a sword trail.
                } else if (palm) {
                    io.github.verycooltimo.murim.client.vfx.PalmVfxRenderer.start(
                            payload.sourceId(), started);
                } else {
                    BladeTrailRenderer.start(payload.sourceId(), started, payload.hitStopTicks());
                }
                // Название объявляет только тот, кто применяет: чужие имена техник поверх
                // своего экрана — это шум, а не постановка.
                // Семь Цветков Сливы объявляют себя надписью манхвы (TechniqueCaption) в момент удара.
                if (isLocalPlayer(payload.sourceId()) && !payload.techniqueId().getPath().startsWith("seven_plum_")) {
                    // Имя вспыхивает к концу ритуала, а не в его начале: на пике, как
                    // в раскадровке. Задержка берётся из данных техники, а не зашита числом.
                    TechniqueDefinition definition = TechniqueLoader.get(payload.techniqueId());
                    int ritual = definition == null ? 0 : definition.ticksOf(TechniquePhase.RITUAL);
                    TechniqueNameLayer.show(net.minecraft.network.chat.Component.translatable(
                            "technique." + payload.techniqueId().getNamespace()
                                    + "." + payload.techniqueId().getPath()),
                            Math.max(0, ritual - 12));
                }
            }
            case HIT -> {
                // Hit stop только тому, кто ударил. Заморозка чужого экрана из-за попадания
                // соседа — это гриферство с обычного клиента, а не эффект.
                if (isLocalPlayer(payload.sourceId())) {
                    TechniqueDefinition hitDefinition = TechniqueLoader.get(payload.techniqueId());
                    var kind = hitDefinition != null && hitDefinition.foundation()
                            ? io.github.verycooltimo.murim.combat.StrikeKind.FOUNDATION
                            : io.github.verycooltimo.murim.combat.StrikeKind.TECHNIQUE_FINALE;
                    if (kind.allowsHitStop()) HitStopHandler.request(payload.hitStopTicks());
                    // Уличный удар — без импакт-кадра и с лёгкой тряской: это не техника мастера.
                    boolean junk = io.github.verycooltimo.murim.technique.JunkArts.isJunk(payload.techniqueId());
                    if (payload.executionTier() > 0) CameraShakeHandler.request(junk ? 0.3F : 1.0F);
                    if (kind.allowsImpactFrames() && !junk && payload.executionTier() > 0) {
                        ImpactScreenLayer.trigger();
                    }
                }
            }
            case CANCELLED -> {
                if (isLocalPlayer(payload.sourceId()) && io.github.verycooltimo.murim.technique.SevenStarsRules.isForm(payload.techniqueId())) FootworkMotion.stopSevenStars();
                // Прерванная техника не должна оставлять после себя ни висящий след,
                // ни отложенный взмах, который выстрелит уже после отмены.
                PENDING.remove(payload.sourceId());
                if (io.github.verycooltimo.murim.technique.JunkArts.FUNNY.contains(payload.techniqueId())
                        || io.github.verycooltimo.murim.technique.SevenStarsRules.isForm(payload.techniqueId())
                        || io.github.verycooltimo.murim.technique.BambooRules.is(payload.techniqueId())) {
                    io.github.verycooltimo.murim.client.vfx.FunnyJunkVfx.cancel(payload.sourceId());
                    if (Minecraft.getInstance().level != null
                            && Minecraft.getInstance().level.getEntity(payload.sourceId()) instanceof AbstractClientPlayer dancer) {
                        var controller = MurimPlayerAnimations.techniqueController(dancer);
                        TechniqueDefinition cancelled = TechniqueLoader.get(payload.techniqueId());
                        if (controller != null && cancelled != null
                                && (MurimPlayerAnimations.owns(controller, cancelled.animation())
                                || io.github.verycooltimo.murim.technique.SevenStarsRules.isForm(payload.techniqueId())
                                && MurimPlayerAnimations.owns(controller, net.minecraft.resources.ResourceLocation
                                        .fromNamespaceAndPath("murim", "seven_stars_shift_right")))) {
                            MurimPlayerAnimations.stopBlended(dancer, AnimationTransitions.STEP_FADE_OUT_TICKS);
                        }
                    }
                }
                BladeTrailRenderer.cancel(payload.sourceId());
                io.github.verycooltimo.murim.client.vfx.PalmVfxRenderer.cancel(payload.sourceId());
                MurimMod.LOGGER.debug("Техника {} прервана", payload.techniqueId());
            }
            case FINISHED -> {
                if (io.github.verycooltimo.murim.technique.BambooRules.is(payload.techniqueId())
                        && Minecraft.getInstance().level != null
                        && Minecraft.getInstance().level.getEntity(payload.sourceId()) instanceof AbstractClientPlayer hand) {
                    MurimPlayerAnimations.stop(hand);
                }
                if (isLocalPlayer(payload.sourceId()) && io.github.verycooltimo.murim.technique.SevenStarsRules.isForm(payload.techniqueId())) FootworkMotion.stopSevenStars();
                if (io.github.verycooltimo.murim.technique.SevenStarsRules.isForm(payload.techniqueId())
                        && Minecraft.getInstance().level != null
                        && Minecraft.getInstance().level.getEntity(payload.sourceId()) instanceof AbstractClientPlayer dancer) {
                    var controller = MurimPlayerAnimations.techniqueController(dancer);
                    var definition = TechniqueLoader.get(payload.techniqueId());
                    if (AnimationTransitions.fadeFootworkOnFinish(
                            io.github.verycooltimo.murim.technique.SevenStarsRules.dipper(payload.techniqueId()),
                            controller != null && definition != null && MurimPlayerAnimations.owns(controller, definition.animation())))
                        MurimPlayerAnimations.stopBlended(dancer, AnimationTransitions.STEP_FADE_OUT_TICKS);
                }
                MurimMod.LOGGER.debug("Техника {} завершена", payload.techniqueId());
            }
        }
    }

    /**
     * Отложенные запуски анимации: идентификатор сущности и сколько тиков ждать.
     *
     * <p>Анимация тела обязана начинаться на замахе, а не на старте техники. Событие
     * {@code STARTED} приходит в первый тик, но перед замахом идут три секунды ритуала,
     * и запуск по событию проигрывал взмах во время концентрации, задолго до удара.
     * Поймано на кадрах 2026-08-10: на пятом тике персонаж уже махал мечом.
     */
    private static final java.util.Map<Integer, Pending> PENDING = new java.util.HashMap<>();

    /**
     * Отложенный запуск анимации.
     *
     * <p>Хранится идентификатор анимации, а не только задержка: техник стало несколько,
     * и раньше здесь была зашита анимация выхвата — ладонь махала бы мечом.
     */
    private record Pending(int ticksLeft, net.minecraft.resources.ResourceLocation animation) {
    }

    /** Снимает отложенные запуски: смена мира не должна выстрелить анимацией в новом. */
    public static void reset() {
        tracedTechnique = ""; tracedStart = 0;
        PENDING.clear();
        FootworkMotion.stopSevenStars();
        io.github.verycooltimo.murim.client.vfx.FunnyJunkVfx.reset();
    }

    private static void scheduleAnimation(TechniqueEventPayload payload) {
        // Задержка берётся из данных техники, а не зашита числом: правится длина ритуала —
        // анимация едет следом.
        TechniqueDefinition definition = TechniqueLoader.get(payload.techniqueId());
        if (definition == null) return;
        if (payload.hitStopTicks() > 0) {
            PENDING.remove(payload.sourceId());
            playAnimation(payload, definition.animation());
            return;
        }
        if (definition.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.SixForm) {
            return; // SixFormAnimations owns the star-dependent/trigger-dependent clock.
        }
        int delay = definition.startTickOf(TechniquePhase.WINDUP);
        net.minecraft.resources.ResourceLocation animation = definition.animation();
        if (payload.rightStep() && io.github.verycooltimo.murim.technique.SevenStarsRules.isForm(payload.techniqueId())
                && !io.github.verycooltimo.murim.technique.SevenStarsRules.follows(payload.techniqueId())
                && !io.github.verycooltimo.murim.technique.SevenStarsRules.dipper(payload.techniqueId())) {
            // API: MC 1.21.1 ResourceLocation#fromNamespaceAndPath.
            animation = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "seven_stars_shift_right");
        }
        if (delay <= 0) {
            playAnimation(payload, animation);
            return;
        }
        PENDING.put(payload.sourceId(), new Pending(delay, animation));
    }

    private static void tickPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        var iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Pending pending = entry.getValue();
            int left = pending.ticksLeft() - 1;
            if (left > 0) {
                entry.setValue(new Pending(left, pending.animation()));
                continue;
            }
            iterator.remove();
            if (level != null
                    && level.getEntity(entry.getKey()) instanceof AbstractClientPlayer player) {
                MurimPlayerAnimations.play(player, pending.animation());
            }
        }
    }

    /**
     * Запускает анимацию у того, кто применил технику. Пакет приходит и наблюдателям, поэтому
     * анимация проигрывается у чужих игроков тоже — иначе техника была бы видна только себе.
     */
    private static void playAnimation(TechniqueEventPayload payload,
                                      net.minecraft.resources.ResourceLocation animation) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        if (level.getEntity(payload.sourceId()) instanceof AbstractClientPlayer player) {
            TechniqueDefinition definition = TechniqueLoader.get(payload.techniqueId());
            int skip = definition == null ? 0 : Math.max(0, payload.hitStopTicks() - definition.startTickOf(TechniquePhase.WINDUP));
            if (io.github.verycooltimo.murim.technique.SevenStarsRules.isForm(payload.techniqueId()) && skip == 0)
                MurimPlayerAnimations.playBlended(player, animation, AnimationTransitions.CROSSFADE_TICKS);
            else MurimPlayerAnimations.play(player, animation, skip);
        }
    }

    private static boolean isLocalPlayer(int entityId) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.getId() == entityId;
    }

    private ClientTechniqueHandler() {
    }
}
