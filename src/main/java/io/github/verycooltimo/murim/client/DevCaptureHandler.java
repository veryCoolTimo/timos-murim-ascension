package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;

import io.github.verycooltimo.murim.network.StartTechniquePayload;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Съёмка серии кадров техники для отладки визуала.
 *
 * <p>Зачем это существует: агент не видит экран, а снимать экран операционной системы по SSH
 * нельзя — блокирует TCC на macOS, а на Linux-сервере нет графической сессии. Но игра умеет
 * фотографировать саму себя, и её собственные скриншоты никаких системных разрешений не требуют.
 * Серия кадров с шагом в пару тиков даёт не только композицию, но и <b>тайминг</b>: по полосе
 * кадров видно и замах, и момент удара, и затухание. Обоснование — ADR-74.
 *
 * <p>Запуск полностью автономный: клиент стартует под виртуальным дисплеем с флагом
 * {@code -Dmurim.capture=true}, сам входит в мир, сам применяет технику и сам снимает кадры.
 * Человек в цикле не нужен.
 *
 * <p><b>Только для разработки.</b> Класс не делает ничего, пока не выставлено системное свойство.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class DevCaptureHandler {

    /** Включает автосъёмку: {@code ./gw runClient -Dmurim.capture=true}. */
    private static final String ENABLE_PROPERTY = "murim.capture";

    /**
     * Ракурс съёмки: {@code back} — игровой, из-за спины; {@code front} — контрольный.
     *
     * <p>Одного ракурса мало. Рубящий удар проходит перед корпусом, и при виде из-за спины
     * вторую половину дуги закрывает сам игрок — по таким кадрам нельзя отличить неверную
     * геометрию от честного перекрытия. Игровой ракурс отвечает на вопрос «что увидит игрок»,
     * контрольный — «правильна ли дуга вообще». Нужны оба.
     */
    private static final String CAMERA_PROPERTY = "murim.capture.camera";

    /** Сколько тиков стенд бежит цингуном (Шаг Молнии). */
    private static final int TRAVEL_CAPTURE_TICKS = 70;
    private static int travelTicks;
    private static int sneakTicks;
    /** Бег без прыжков (Тень с 03.10 — бегом): спринт вперёд весь отрезок. */
    private static int dashRunTicks;

    /** Какую технику снимать: {@code -Pmurim.technique=wedge_fan}. По умолчанию первая. */
    private static final String TECHNIQUE_PROPERTY = "murim.capture.technique";

    /**
     * За сколько тиков до запуска техники снимается ОПОРНЫЙ кадр.
     *
     * <p>Без него нельзя отделить эффект от сцены: маска эффекта получается вычитанием
     * опорного кадра из каждого последующего. Всё измерение построено на этом.
     */
    private static final int BASELINE_BEFORE_TICKS = 3;

    /** Файл телеметрии рядом с кадрами: по нему python считает проекции и метрики. */
    private static java.io.PrintWriter telemetry;

    /** Сколько тиков ждать после входа в мир, прежде чем применять технику. */
    private static final int WARMUP_TICKS = 60;

    /**
     * Шаг между кадрами. Один тик — обязательное значение для разбора тайминга: при шаге
     * в два тика начало движения выпадает из выборки, и анимация выглядит незапустившейся.
     * Ошибочный вывод на этой почве пойман 2026-08-10, см. docs/agent-log.md.
     */
    /** Кадр раз в N тиков (env MURIM_CAPTURE_EVERY): длинные техники снимаются реже, но целиком. */
    private static final int FRAME_INTERVAL_TICKS = envInt("MURIM_CAPTURE_EVERY", 1);

    /** Сколько кадров снять. 100 кадров по тику перекрывают технику в 92 тика. */
    private static final int FRAME_COUNT = envInt("MURIM_CAPTURE_FRAMES", 100);
    private static int secondPressTicks;
    private static net.minecraft.resources.ResourceLocation secondPressId;

    private static int envInt(String name, int fallback) {
        String raw = System.getenv(name);
        try {
            return raw == null ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean armed = Boolean.getBoolean(ENABLE_PROPERTY);
    private static int warmup;
    private static int framesLeft;
    private static int tickCounter;
    private static int frameIndex;

    /**
     * Кадр запрошен тиком и будет снят ДВАЖДЫ за один проход рендера.
     *
     * <p>Эффект рисуется на стадии {@code AFTER_PARTICLES}. Снимок до неё и снимок после
     * отличаются ровно на эффект, причём поза тела в них совпадает побитово — это и есть
     * единственный способ отделить VFX от анимации. Два отдельных прогона так выровнять
     * нельзя: доля тика произвольна, а корпус в анимации поворачивается примерно на 21°
     * за тик, и промах в полтика оставляет остаток больше самого эффекта.
     */
    private static boolean framePending;

    /**
     * Начинает съёмку немедленно. Вызывается автозапуском и может быть вызвана вручную,
     * если понадобится снять что-то другое.
     */
    /**
     * Сбрасывает состояние съёмки при выходе из мира. Без этого повторный вход досчитывает
     * прогрев и отправляет пакет техники без действия игрока, а нумерация кадров продолжается
     * с прежнего места и затирает уже снятое.
     */
    public static void reset() {
        if (telemetry != null) {
            telemetry.close();
            telemetry = null;
        }
        armed = Boolean.getBoolean(ENABLE_PROPERTY);
        warmup = 0;
        framesLeft = 0;
        // Иначе запрошенный, но не снятый кадр «выстрелит» при следующем входе в мир
        // и запишет пару снимков с телеметрией, когда съёмка не запущена.
        framePending = false;
        meditationTicks = 0;
        meditationIdle = 0;
        uiTicks = 0;
        tickCounter = 0;
        frameIndex = 0;
    }

    public static void startCapture() {
        framesLeft = FRAME_COUNT;
        tickCounter = 0;
        frameIndex = 0;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        if (armed) {
            armed = false;
            warmup = WARMUP_TICKS;
            // Анимация тела видна только от третьего лица: в первом лице снимать нечего.
            boolean front = "front".equalsIgnoreCase(System.getProperty(CAMERA_PROPERTY, "back"));
            boolean side = "side".equalsIgnoreCase(System.getProperty(CAMERA_PROPERTY, "back"))
                    || "fp".equalsIgnoreCase(System.getProperty(CAMERA_PROPERTY, "back"));
            minecraft.options.setCameraType(side ? CameraType.FIRST_PERSON
                    : front ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK);
            // Сбоку снимаем глазами чужой сущности в первом лице: без скрытого интерфейса
            // в кадр лезут рука игрока и прицел.
            // От первого лица с MURIM_CAPTURE_HAND (empty — ци-меч, sword — меч для сравнения) снимается рука,
            // поэтому интерфейс (а с ним и рука) не прячется.
            boolean handShot = "fp".equalsIgnoreCase(System.getProperty(CAMERA_PROPERTY, "back"))
                    && System.getenv("MURIM_CAPTURE_HAND") != null;
            if (side && !handShot) {
                minecraft.options.hideGui = true;
            }
            // Приближение кадра без правки build.gradle: переменная окружения доходит до
            // клиента через демон Gradle. Камера третьего лица стоит в четырёх блоках, и при
            // обычных 70° кисть занимает на кадре 854x480 десяток пикселей — дуги не разобрать.
            // API: reference/minecraft-src/net/minecraft/client/Options.java#fov (IntRange 30..110)
            // Площадка стенда на высоте облаков: ночью они тёмными плитами ложатся поверх
            // высоких эффектов (дерево Сливы в 12 блоков) — в съёмке их нет.
            minecraft.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            String fov = System.getenv("MURIM_CAPTURE_FOV");
            if (fov != null) {
                minecraft.options.fov().set(Mth.clamp(Integer.parseInt(fov.trim()), 30, 110));
            }
            MurimMod.LOGGER.info("Автосъёмка: техника через {} тиков, FOV {}, стиль дуг {}",
                    WARMUP_TICKS, System.getenv("MURIM_CAPTURE_FOV"), System.getenv("MURIM_ARC_STYLE"));
        }

        // Камера «сбоку»: стойку ставит сервер с задержкой, поэтому ищем её каждый тик
        // прогрева, пока не найдём. Опорный кадр снимается уже с неё.
        if (warmup > 0 && minecraft.getCameraEntity() == minecraft.player
                && "side".equalsIgnoreCase(System.getProperty(CAMERA_PROPERTY, "back"))) {
            for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity instanceof net.minecraft.world.entity.decoration.ArmorStand
                        && entity.getCustomName() != null
                        && io.github.verycooltimo.murim.combat.DevSetupEvents.CAMERA_STAND_NAME
                                .equals(entity.getCustomName().getString())) {
                    minecraft.setCameraEntity(entity);
                    MurimMod.LOGGER.info("Автосъёмка: камера сбоку на {}", entity.position());
                    break;
                }
            }
        }

        // Камера на стойке: LocalPlayer шлёт позицию серверу только когда сам — камера
        // (LocalPlayer#sendPosition → isControlledCamera), и сервер видел игрока на старте
        // весь рывок: удары по дистанции не проходили. Шлём позицию сами.
        // API: reference/minecraft-src/net/minecraft/client/player/LocalPlayer.java#sendPosition
        if (minecraft.getCameraEntity() != minecraft.player && minecraft.getConnection() != null) {
            net.minecraft.client.player.LocalPlayer p = minecraft.player;
            minecraft.getConnection().send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                    p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.onGround()));
        }

        // Опорный кадр: сцена в той же позе и с той же камерой, но БЕЗ эффекта.
        if (warmup == BASELINE_BEFORE_TICKS) {
            Screenshot.grab(minecraft.gameDirectory, "baseline_" + anglePrefix() + ".png",
                    minecraft.getMainRenderTarget(), message -> {
                    });
        }

        if (warmup > 0 && --warmup == 0) {
            // Снимать можно не только технику, но и сцену создания даньтяня: у неё те же
            // требования к проверке — привязка к телу, фазы, отсутствие пересвета.
            String subject = System.getProperty(TECHNIQUE_PROPERTY, "ceremonial_draw");
            if (FOUNDATION.equals(subject)) {
                foundationTicks = FOUNDATION_CAPTURE_TICKS;
                return;
            }
            if (AURA.equals(subject)) {
                auraTicks = AURA_CAPTURE_TICKS;
                return;
            }
            if ("ui".equals(subject)) {
                uiTicks = UI_CAPTURE_TICKS;
                return;
            }
            if ("manual".equals(subject)) {
                manualTicks = 72;
                return;
            }
            if (MEDITATION.equals(subject)) {
                sitDown();
                meditationTicks = MEDITATION_CAPTURE_TICKS;
                return;
            }
            net.minecraft.resources.ResourceLocation subjectId = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    MurimMod.MODID, subject);
            // MURIM_CAPTURE_LOCK=1 — перед приёмом захватить цель (как клавиша Z).
            if ("1".equals(System.getenv("MURIM_CAPTURE_LOCK"))) {
                io.github.verycooltimo.murim.client.vfx.LockOn.lockNow();
            }
            String mode = System.getenv().getOrDefault("MURIM_CAPTURE_FOOTWORK", "");
            if ("autorun".equals(mode) || "autoshadow".equals(mode)) {
                // Автоматика шагов (03.10): стенд ничего не шлёт, только держит спринт или присед.
                if ("autorun".equals(mode)) {
                    travelTicks = TRAVEL_CAPTURE_TICKS;
                } else {
                    stillSneakTicks = TRAVEL_CAPTURE_TICKS;
                }
            } else if (!mode.isEmpty()) {
                // Шаги: стенд жмёт R с контекстом — run (спринт+вперёд, бежит и дважды прыгает),
                // left/back (уклонение в сторону), plain (простой R).
                int input = switch (mode) {
                    case "run" -> io.github.verycooltimo.murim.combat.FootworkService.SPRINT
                            | io.github.verycooltimo.murim.combat.FootworkService.FORWARD;
                    case "left" -> io.github.verycooltimo.murim.combat.FootworkService.LEFT;
                    case "back" -> io.github.verycooltimo.murim.combat.FootworkService.BACK;
                    case "shadow" -> io.github.verycooltimo.murim.combat.FootworkService.SNEAK;
                    case "pass" -> io.github.verycooltimo.murim.combat.FootworkService.FORWARD;
                    default -> 0;
                };
                PacketDistributor.sendToServer(new io.github.verycooltimo.murim.network.TraversePayloads.Request(subjectId, input));
                if ("run".equals(mode)) {
                    travelTicks = TRAVEL_CAPTURE_TICKS;
                }
                if ("shadowrun".equals(mode)) {
                    dashRunTicks = TRAVEL_CAPTURE_TICKS;
                }
                if ("shadow".equals(mode)) {
                    // Тень держится приседом: стенд крадётся вперёд мимо зомби.
                    sneakTicks = TRAVEL_CAPTURE_TICKS;
                }
            } else {
                PacketDistributor.sendToServer(new StartTechniquePayload(subjectId));
                // MURIM_CAPTURE_SECOND=<тики> — повторное R той же формы (кинжалы Тан: удар звёзд, отзыв).
                secondPressTicks = envInt("MURIM_CAPTURE_SECOND", 0);
                secondPressId = subjectId;
            }
            startCapture();
        }

        if (secondPressTicks > 0 && --secondPressTicks == 0 && secondPressId != null) {
            PacketDistributor.sendToServer(new StartTechniquePayload(secondPressId));
        }
        if (manualTicks > 0) {
            int t = 72 - manualTicks--;
            if (t == 4 && minecraft.player != null && minecraft.gameMode != null) {
                minecraft.gameMode.useItem(minecraft.player, net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            if ((t == 26 || t == 38 || t == 50 || t == 62) && minecraft.screen != null) {
                minecraft.screen.mouseScrolled(0, 0, 0, -1);
            }
            if (t >= 6) {
                grab(minecraft, String.format("murim_%s_%03d.png", anglePrefix(), frameIndex));
                grab(minecraft, String.format("clean_%s_%03d.png", anglePrefix(), frameIndex));
                frameIndex++;
            }
            if (manualTicks == 0) {
                minecraft.setScreen(null);
            }
        }
        if (stillSneakTicks > 0) {
            stillSneakTicks--;
            minecraft.options.keyShift.setDown(stillSneakTicks > 0);
        }
        // MURIM_CAPTURE_STEER=<градусы за тик> — рулить на ходу (бег, тень): повороты для лент шагов.
        if ((sneakTicks > 0 || travelTicks > 0 || dashRunTicks > 0) && minecraft.player != null && System.getenv("MURIM_CAPTURE_STEER") != null) {
            int t = TRAVEL_CAPTURE_TICKS - Math.max(Math.max(sneakTicks, travelTicks), dashRunTicks);
            float steer = Float.parseFloat(System.getenv("MURIM_CAPTURE_STEER").trim());
            // Змейка: 12 тиков прямо, 10 тиков поворот, смена стороны.
            int phase = t % 44;
            float turn = phase >= 12 && phase < 22 ? steer : phase >= 34 ? -steer : 0.0F;
            minecraft.player.setYRot(minecraft.player.getYRot() + turn);
        }
        // Камера на стойке: LocalPlayer не переносит ввод в xxa/zza, пока сам не камера
        // (LocalPlayer#serverAiStep → isControlledCamera) — бег и тень сбоку стояли бы на месте.
        // API: reference/minecraft-src/net/minecraft/client/player/LocalPlayer.java#serverAiStep
        if (dashRunTicks > 0) {
            dashRunTicks--;
            boolean on = dashRunTicks > 0;
            minecraft.options.keyUp.setDown(on);
            minecraft.options.keySprint.setDown(on);
        }
        if ((sneakTicks > 0 || travelTicks > 0 || dashRunTicks > 0) && minecraft.player != null && minecraft.getCameraEntity() != minecraft.player) {
            minecraft.player.zza = minecraft.player.input.forwardImpulse;
            minecraft.player.xxa = minecraft.player.input.leftImpulse;
            minecraft.player.setJumping(minecraft.player.input.jumping);
        }
        if (sneakTicks > 0) {
            sneakTicks--;
            boolean on = sneakTicks > 0;
            minecraft.options.keyShift.setDown(on);
            minecraft.options.keyUp.setDown(on);
        }
        if (travelTicks > 0) {
            int t = TRAVEL_CAPTURE_TICKS - travelTicks--;
            boolean run = travelTicks > 0;
            minecraft.options.keyUp.setDown(run);
            minecraft.options.keySprint.setDown(run);
            minecraft.options.keyJump.setDown(run && (t == 16 || t == 17 || t == 46 || t == 47));
        }

        if (meditationTicks > 0) {
            tickMeditation(minecraft);
        }
        if (foundationTicks > 0) {
            int t = FOUNDATION_CAPTURE_TICKS - foundationTicks--;
            // Удар формой каждые 14 тиков — чуть дольше перезарядки меча: цепочка идёт по
            // формам, как при размеренных кликах. Цель — ближайший манекен в досягаемости.
            if (t % 14 == 2 && minecraft.player != null && minecraft.gameMode != null) {
                FoundationClient.swing(minecraft, true);
                net.minecraft.world.entity.LivingEntity target = minecraft.level.getEntitiesOfClass(
                                net.minecraft.world.entity.LivingEntity.class, minecraft.player.getBoundingBox().inflate(3.5D),
                                e -> e != minecraft.player && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand))
                        .stream().min(java.util.Comparator.comparingDouble(minecraft.player::distanceToSqr)).orElse(null);
                if (target != null) {
                    minecraft.gameMode.attack(minecraft.player, target);
                }
                minecraft.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            if (t % 2 == 0) {
                grab(minecraft, String.format("murim_%s_%03d.png", anglePrefix(), frameIndex));
                frameIndex++;
            }
        }
        if (auraTicks > 0) {
            int t = AURA_CAPTURE_TICKS - auraTicks--;
            if (t % 3 == 0) {
                grab(minecraft, String.format("murim_%s_%03d.png", anglePrefix(), frameIndex));
                frameIndex++;
            }
        }
        if (uiTicks > 0) {
            tickUi(minecraft);
        }

        if (framesLeft > 0 && tickCounter++ % FRAME_INTERVAL_TICKS == 0) {
            MurimMod.LOGGER.debug("Кадр {} на клиентском тике {}", frameIndex, tickCounter - 1);
            // Сам снимок делается в проходе рендера, а не здесь: нужны две точки внутри
            // ОДНОГО кадра, до и после стадии эффектов. Счётчик уменьшается ТАМ ЖЕ,
            // когда кадр действительно снят: под Xvfb с программным OpenGL частота
            // кадров ниже двадцати в секунду, и списание по тику молча теряло бы
            // половину серии, оставляя в отчёте разрежённую и неравномерную выборку.
            if ("1".equals(System.getenv("MURIM_CAPTURE_GUI"))) {
                // Полный кадр с интерфейсом: титры и экранные вспышки видны только так.
                grab(minecraft, String.format("murim_%s_%03d.png", anglePrefix(), frameIndex));
                grab(minecraft, String.format("clean_%s_%03d.png", anglePrefix(), frameIndex));
                frameIndex++;
                framesLeft--;
            } else {
                framePending = true;
            }
        }
    }

    /**
     * Телеметрия кадра.
     *
     * <p>Позиции пишутся в МИРОВЫХ координатах вместе с параметрами камеры, а проекцию
     * на экран считает анализатор. Так проще: не надо доставать матрицы проекции из игры,
     * и та же телеметрия годится для любых будущих метрик.
     */
    private static void writeTelemetry(Minecraft minecraft, int frame) {
        try {
            if (telemetry == null) {
                java.io.File file = new java.io.File(minecraft.gameDirectory,
                        "screenshots/telemetry_" + anglePrefix() + ".jsonl");
                file.getParentFile().mkdirs();
                telemetry = new java.io.PrintWriter(new java.io.FileWriter(file, false), true);
            }
            net.minecraft.client.Camera camera = minecraft.gameRenderer.getMainCamera();
            net.minecraft.world.entity.player.Player player = minecraft.player;
            if (player == null) {
                return;
            }
            net.minecraft.world.phys.Vec3 cam = camera.getPosition();
            // Позиция кости руки — точка отсчёта для метрики привязки. Сравнивать эффект
            // с центром торса неверно: у техники ладони эффект И ДОЛЖЕН быть в стороне
            // от торса, и метрика штрафовала правильное поведение.
            net.minecraft.world.phys.Vec3 hand = player instanceof net.minecraft.client.player.AbstractClientPlayer client
                    ? io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.position(
                            client, io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.RIGHT_HAND)
                    : null;
            // Подмена кости позицией игрока помечается ЯВНО. Молча подставлять ступни
            // нельзя: метрика привязки и справка «центр эффекта в N px от кости» тогда
            // считаются от ступней, и об этом никто не узнаёт.
            boolean boneMissing = hand == null;
            if (boneMissing) {
                hand = player.position();
            }
            // Поле зрения в телеметрии берётся из НАСТРОЙКИ, а настоящее поле зрения
            // рендера домножается на модификатор: спринт, эффект скорости, натянутый
            // лук, погружение в жидкость. Прочитать его нельзя — GameRenderer#getFov
            // приватный, а access transformer в проекте требует отдельного согласования.
            // Поэтому пишутся сами состояния, и анализатор отказывается считать, если
            // хоть одно из них возникло: ошибка масштаба радиальна от центра кадра и
            // точкой на оси камеры не ловится.
            boolean fovDisturbed = player.isSprinting() || player.isInWater()
                    || player.isInLava() || player.isUsingItem()
                    || player.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED);
            // Возраст техники, а не номер кадра. Съёмка стартует в тик отправки запроса
            // на сервер, эффект — после ответа; задержка плавает, поэтому «тик 40» в разных
            // прогонах попадал в разные фазы, и метрики фаз сравнивали несравнимое.
            float[] phase = io.github.verycooltimo.murim.client.vfx.PalmVfxRenderer
                    .captureAgeOf(player.getId());
            // Фаза сцены медитации: по номеру кадра её не разметить.
            String scenePhase = ClientMeditationState.state().active()
                    ? "beat" + ClientMeditationState.state().beats() : "";
            // Сколько костей конечностей доступно в этом кадре. Ноль означает, что жилам
            // просто неоткуда расти, и это НЕ дефект рендерера — отличить одно от другого
            // по картинке невозможно.
            int limbs = 0;
            if (player instanceof net.minecraft.client.player.AbstractClientPlayer client) {
                for (io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone bone
                        : new io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone[] {
                        io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.RIGHT_HAND,
                        io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.LEFT_HAND,
                        io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.RIGHT_FOOT,
                        io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.LEFT_FOOT,
                        io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.HEAD}) {
                    if (io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer
                            .position(client, bone) != null) {
                        limbs++;
                    }
                }
            }
            int sceneTick = ClientMeditationState.state().active() ? ClientMeditationState.sessionTicks() : -1;
            // Куда эффект РЕАЛЬНО поставил ладонь. Сравнение с позицией кости отделяет
            // ошибку привязки от ошибки измерения — снаружи они выглядят одинаково.
            net.minecraft.world.phys.Vec3 drawn =
                    io.github.verycooltimo.murim.client.vfx.PalmVfxRenderer.drawnPalm(player.getId());
            telemetry.printf(java.util.Locale.ROOT,
                    "{\"frame\":%d,\"tick\":%d,\"px\":%.4f,\"py\":%.4f,\"pz\":%.4f,"
                            + "\"eye\":%.4f,\"yaw\":%.3f,\"bodyYaw\":%.3f,"
                            + "\"cx\":%.4f,\"cy\":%.4f,\"cz\":%.4f,"
                            + "\"cyaw\":%.3f,\"cpitch\":%.3f,\"fov\":%.3f,"
                            + "\"hx\":%.4f,\"hy\":%.4f,\"hz\":%.4f,"
                            + "\"age\":%.2f,\"impactAge\":%.2f,"
                            + "\"rx\":%.4f,\"ry\":%.4f,\"rz\":%.4f,"
                            + "\"boneMissing\":%b,\"fovDisturbed\":%b,"
                            + "\"scenePhase\":\"%s\",\"sceneTick\":%d,\"limbs\":%d,"
                            + "\"w\":%d,\"h\":%d}%n",
                    frame, tickCounter - 1,
                    player.getX(), player.getY(), player.getZ(), player.getEyeHeight(),
                    player.getYRot(), player.yBodyRot,
                    cam.x, cam.y, cam.z,
                    camera.getYRot(), camera.getXRot(),
                    minecraft.options.fov().get().doubleValue(),
                    hand.x, hand.y, hand.z,
                    phase == null ? -1.0F : phase[0], phase == null ? -1.0F : phase[1],
                    drawn == null ? 0.0D : drawn.x, drawn == null ? 0.0D : drawn.y,
                    drawn == null ? 0.0D : drawn.z,
                    boneMissing, fovDisturbed, scenePhase, sceneTick, limbs,
                    minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
        } catch (java.io.IOException exception) {
            MurimMod.LOGGER.warn("Телеметрия не пишется: {}", exception.getMessage());
        }
    }

    /** Основа меча: удары ЛКМ формами по очереди, кадр каждые два тика. */
    private static final String FOUNDATION = "foundation";
    private static final int FOUNDATION_CAPTURE_TICKS = 150;
    private static int foundationTicks;

    /** Давление ауры: игрок стоит перед противником, кадр каждые три тика. */
    private static final String AURA = "aura";
    private static final int AURA_CAPTURE_TICKS = 150;
    private static int auraTicks;

    /** Сценарий интерфейсов техник: слоты боя → кольцо → экран раскладки. */
    private static final int UI_CAPTURE_TICKS = 200;
    private static int uiTicks;
    private static int stillSneakTicks;
    private static int manualTicks;

    private static void tickUi(Minecraft minecraft) {
        int t = UI_CAPTURE_TICKS - uiTicks--;
        if (t < 60) {
            CombatMode.engage();
        }
        boolean ring2 = "2".equals(System.getenv("MURIM_CAPTURE_UI_STYLE"));
        if (ring2) {
            // Двойное кольцо: форма Казнь → наружу на слот «24 Движения» (внутри его Ливень) →
            // обратно внутрь на Ливень → отпустить.
            ModKeyMappings.WHEEL.setDown(t >= 60 && t < 124);
            float[][] path = {{0, 0}, {24, 16}, {24, 16}, {42, -44}, {42, -44}, {0, -29}, {0, -29}};
            int[] at = {66, 76, 88, 96, 104, 112, 124};
            for (int i = 0; i + 1 < at.length; i++) {
                if (t >= at[i] && t < at[i + 1]) {
                    float k = (t - at[i]) / (float) (at[i + 1] - at[i]);
                    TechniqueWheel.setCursor(Mth.lerp(k, path[i][0], path[i + 1][0]), Mth.lerp(k, path[i][1], path[i + 1][1]));
                }
            }
        } else {
            // Кольцо: зажать клавишу, повести курсор к третьему сектору, отпустить.
            ModKeyMappings.WHEEL.setDown(t >= 60 && t < 110);
            if (t >= 70 && t < 110) {
                float k = Math.min(1.0F, (t - 70) / 15.0F);
                TechniqueWheel.setCursor(44.0F * k, 22.0F * k);
            }
        }
        if (t == 130 && !ring2) {
            LoadoutScreen screen = new LoadoutScreen();
            minecraft.setScreen(screen);
            screen.pickForCapture(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "wedge_fan"));
        }
        boolean scrollShot = "1".equals(System.getenv("MURIM_CAPTURE_SCROLL"));
        if (scrollShot ? t >= 128 && t < 150 : ring2 ? t >= 56 && t < 140 && t % 2 == 0 : t % 5 == 0) {
            grab(minecraft, String.format("murim_%s_%03d.png", anglePrefix(), frameIndex));
            grab(minecraft, String.format("clean_%s_%03d.png", anglePrefix(), frameIndex));
            frameIndex++;
        }
        if (uiTicks == 0) {
            minecraft.setScreen(null);
        }
    }

    private static final String MEDITATION = "meditation";

    /** Две сессии по 30 с плюс вспышка семени и запас на пересадку. */
    private static final int MEDITATION_CAPTURE_TICKS = 1600;

    /** Медитация медленная: полный кадр раз в полсекунды, вместе с интерфейсом. */
    private static final int MEDITATION_FRAME_TICKS = 10;

    private static int meditationTicks;
    private static int meditationIdle;

    private static void sitDown() {
        PacketDistributor.sendToServer(new io.github.verycooltimo.murim.network.MeditationInputPayload(
                io.github.verycooltimo.murim.network.MeditationInputPayload.Action.TOGGLE, true));
    }

    /**
     * Сценарий медитации: удержание кольца идёт через ту же клавишу прыжка, что у игрока,
     * чтобы съёмка проверяла настоящий путь ввода, а не обходной пакет.
     */
    private static void tickMeditation(Minecraft minecraft) {
        meditationTicks--;
        var state = ClientMeditationState.state();
        // Бот мини-игры: держит, когда кольцо шире центра полосы. С 4-й по 6-ю секунду
        // каждой игры он нарочно перетягивает — в кадры должно попасть красное состояние.
        // MURIM_CAPTURE_FAIL=1 — не жмёт вовсе, чтобы снять искажение ци.
        boolean fail = "1".equals(System.getenv("MURIM_CAPTURE_FAIL"));
        boolean hold = false;
        if (ClientMeditationState.minigame() && !fail) {
            var ring = ClientMeditationState.ring(1.0F);
            int t = ClientMeditationState.sessionTicks();
            hold = (t >= 80 && t < 120) || ring.radius() > ring.centre();
        }
        minecraft.options.keyJump.setDown(hold);
        // MURIM_CAPTURE_TURN=1 — «крутить мышью» во время ритуала: тело обязано стоять.
        if ("1".equals(System.getenv("MURIM_CAPTURE_TURN")) && state.active()) {
            minecraft.player.turn(8.0D, 0.0D);
        }
        // MURIM_CAPTURE_LEAVE=1 — на десятой секунде нажать Shift: проверка выхода.
        if ("1".equals(System.getenv("MURIM_CAPTURE_LEAVE"))) {
            minecraft.options.keyShift.setDown(state.active() && ClientMeditationState.sessionTicks() >= 200);
        }
        // Сессия прервалась, а семени ещё нет — садимся снова через секунду.
        if (!state.active() && state.beats() < 3 && !fail) {
            if (++meditationIdle == 20) {
                sitDown();
            }
        } else {
            meditationIdle = 0;
        }
        // Сцена семени снимается чаще: вспышка длится полсекунды и между кадрами
        // по десять тиков пропадала целиком.
        // Прорыв — чаще, выход ауры — как вспышка семени: иначе короткие фазы между кадрами пропадают.
        int step = ClientMeditationState.seedSceneAge() >= 0 || ClientMeditationState.rankUpAge() >= 0 ? 2
                : ClientMeditationState.breakthroughAge() >= 0 ? 5 : MEDITATION_FRAME_TICKS;
        if (meditationTicks % step == 0) {
            // Снимок в тике берёт последний отрисованный кадр целиком, с интерфейсом:
            // HUD медитации — часть того, что проверяется.
            grab(minecraft, String.format("murim_%s_%03d.png", anglePrefix(), frameIndex));
            grab(minecraft, String.format("clean_%s_%03d.png", anglePrefix(), frameIndex));
            frameIndex++;
        }
        if (meditationTicks == 0) {
            minecraft.options.keyJump.setDown(false);
        }
    }

    /** Метка ракурса в именах файлов: back, front или угол поворота игрока. */
    private static String anglePrefix() {
        String camera = System.getProperty(CAMERA_PROPERTY, "back");
        String yaw = System.getProperty("murim.capture.yaw", "0");
        return camera + "_" + yaw.replace("-", "m");
    }

    /**
     * Две точки съёмки внутри одного кадра.
     *
     * <p>{@code AFTER_TRIPWIRE_BLOCKS} — последняя стадия перед {@code AFTER_PARTICLES},
     * на которой рисуются наши эффекты. Снимок здесь содержит сцену и позу игрока, но не
     * эффект. Второй снимок берётся на {@code AFTER_PARTICLES} с НАИНИЗШИМ приоритетом,
     * то есть после всех обработчиков этой стадии, включая наши рендереры.
     *
     * <p>Раньше второй снимок делался на {@code AFTER_LEVEL}, а между стадиями рисуются
     * ещё облака, погода, граница мира и отладочный слой. Всё это попадало в разницу и
     * засчитывалось как «энергия эффекта»: облака над сценой ползут каждый кадр.
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    static void onRenderStage(RenderLevelStageEvent event) {
        if (!framePending) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) {
            grab(minecraft, String.format("clean_%s_%03d.png", anglePrefix(), frameIndex));
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            // Телеметрия пишется здесь же: возраст техники должен относиться именно к тому
            // кадру, который снят, а не к моменту тика.
            writeTelemetry(minecraft, frameIndex);
            grab(minecraft, String.format("murim_%s_%03d.png", anglePrefix(), frameIndex));
            frameIndex++;
            framesLeft--;
            framePending = false;
        }
    }

    /** Номер кадра — с ведущими нулями: иначе сортировка ставит 10 перед 2. */
    private static void grab(Minecraft minecraft, String name) {
        Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(), message -> {
        });
    }

    private DevCaptureHandler() {
    }
}
