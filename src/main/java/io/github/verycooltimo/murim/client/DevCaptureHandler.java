package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;

import io.github.verycooltimo.murim.network.StartTechniquePayload;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
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
    private static final int FRAME_INTERVAL_TICKS = 1;

    /** Сколько кадров снять. 100 кадров по тику перекрывают технику в 92 тика вместе с ритуалом. */
    private static final int FRAME_COUNT = 100;

    private static boolean armed = Boolean.getBoolean(ENABLE_PROPERTY);
    private static int warmup;
    private static int framesLeft;
    private static int tickCounter;
    private static int frameIndex;

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
            minecraft.options.setCameraType(
                    front ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK);
            MurimMod.LOGGER.info("Автосъёмка: техника через {} тиков", WARMUP_TICKS);
        }

        // Опорный кадр: сцена в той же позе и с той же камерой, но БЕЗ эффекта.
        if (warmup == BASELINE_BEFORE_TICKS) {
            Screenshot.grab(minecraft.gameDirectory, "baseline_" + anglePrefix() + ".png",
                    minecraft.getMainRenderTarget(), message -> {
                    });
        }

        if (warmup > 0 && --warmup == 0) {
            PacketDistributor.sendToServer(new StartTechniquePayload(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                            MurimMod.MODID, System.getProperty(TECHNIQUE_PROPERTY, "ceremonial_draw"))));
            startCapture();
        }

        if (framesLeft > 0 && tickCounter++ % FRAME_INTERVAL_TICKS == 0) {
            MurimMod.LOGGER.debug("Кадр {} на клиентском тике {}", frameIndex, tickCounter - 1);
            writeTelemetry(minecraft, frameIndex);
            grabFrame(minecraft);
            framesLeft--;
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
            telemetry.printf(java.util.Locale.ROOT,
                    "{\"frame\":%d,\"tick\":%d,\"px\":%.4f,\"py\":%.4f,\"pz\":%.4f,"
                            + "\"eye\":%.4f,\"yaw\":%.3f,\"bodyYaw\":%.3f,"
                            + "\"cx\":%.4f,\"cy\":%.4f,\"cz\":%.4f,"
                            + "\"cyaw\":%.3f,\"cpitch\":%.3f,\"fov\":%.3f,"
                            + "\"w\":%d,\"h\":%d}%n",
                    frame, tickCounter - 1,
                    player.getX(), player.getY(), player.getZ(), player.getEyeHeight(),
                    player.getYRot(), player.yBodyRot,
                    cam.x, cam.y, cam.z,
                    camera.getYRot(), camera.getXRot(),
                    minecraft.options.fov().get().doubleValue(),
                    minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
        } catch (java.io.IOException exception) {
            MurimMod.LOGGER.warn("Телеметрия не пишется: {}", exception.getMessage());
        }
    }

    /** Метка ракурса в именах файлов: back, front или угол поворота игрока. */
    private static String anglePrefix() {
        String camera = System.getProperty(CAMERA_PROPERTY, "back");
        String yaw = System.getProperty("murim.capture.yaw", "0");
        return camera + "_" + yaw.replace("-", "m");
    }

    private static void grabFrame(Minecraft minecraft) {
        // Номер кадра в имени — с ведущими нулями, иначе сортировка перепутает 2 и 10,
        // а по серии кадров важен именно порядок.
        String name = String.format("murim_%s_%03d.png", anglePrefix(), frameIndex++);
        Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(), message -> {
        });
    }

    private DevCaptureHandler() {
    }
}
