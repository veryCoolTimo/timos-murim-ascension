package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.Techniques;
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

        if (warmup > 0 && --warmup == 0) {
            PacketDistributor.sendToServer(new StartTechniquePayload(Techniques.CEREMONIAL_DRAW.id()));
            startCapture();
        }

        if (framesLeft > 0 && tickCounter++ % FRAME_INTERVAL_TICKS == 0) {
            MurimMod.LOGGER.debug("Кадр {} на клиентском тике {}", frameIndex, tickCounter - 1);
            grabFrame(minecraft);
            framesLeft--;
        }
    }

    private static void grabFrame(Minecraft minecraft) {
        // Номер кадра в имени — с ведущими нулями, иначе сортировка перепутает 2 и 10,
        // а по серии кадров важен именно порядок.
        String prefix = "front".equalsIgnoreCase(System.getProperty(CAMERA_PROPERTY, "back"))
                ? "front" : "back";
        String name = String.format("murim_%s_%03d.png", prefix, frameIndex++);
        Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(), message -> {
        });
    }

    private DevCaptureHandler() {
    }
}
