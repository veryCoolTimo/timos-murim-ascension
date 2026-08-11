package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Тряска камеры на удар техники.
 *
 * <p>Углы <b>прибавляются</b> к текущим, а не назначаются: камера в этот момент уже повёрнута
 * игроком и другими эффектами, и перезапись стёрла бы их (правило 04). По той же причине
 * амплитуда обязана затухать до настоящего нуля — иначе смещения накапливаются, и после
 * серии ударов камера остаётся косой.
 *
 * <p>Смещение считается суммой синусов несовпадающих частот, а не случайными числами:
 * шум по кадрам даёт дрожание, зависящее от частоты кадров, и на слабой машине выглядит иначе,
 * чем на быстрой. Гладкая функция времени от кадровой частоты не зависит.
 *
 * <p>Сила берётся из настройки доступности. Значение 0 полностью отключает эффект.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class CameraShakeHandler {

    /** Сколько тиков живёт тряска от одного удара. */
    private static final float DURATION_TICKS = 7.0F;

    /** Максимальный угол отклонения в градусах при полной силе. */
    private static final float MAX_YAW = 0.85F;
    private static final float MAX_PITCH = 0.65F;
    private static final float MAX_ROLL = 1.35F;

    private static float remaining;
    private static float strength;

    /**
     * Тики с начала текущей тряски, а не с загрузки игры. Счётчик от загрузки терял точность:
     * {@code float} перестаёт различать единицу выше 2^24, и через несколько суток аптайма
     * тряска выродилась бы в постоянное смещение. Отсчёт от нуля попутно чинит и фазу —
     * иначе первый кадр начинался со случайного значения синуса, то есть со скачка.
     */
    private static int elapsed;

    /**
     * Запрашивает тряску.
     *
     * @param intensity сила от 0 до 1; повторный запрос не складывается с текущим,
     *                  а берёт максимум — иначе серия попаданий раскачивает камеру до тошноты
     */
    public static void request(float intensity) {
        // Во время автоматической съёмки камера НЕ трясётся.
        //
        // Метрики эффекта строятся на вычитании опорного кадра, а тряска приходится ровно
        // на кульминацию — то есть портит именно те кадры, ради которых съёмка и делается:
        // сдвиг камеры засчитывается как «энергия эффекта». Сама тряска проверяется
        // отдельно и не нуждается в этом стенде.
        if ("true".equals(System.getProperty("murim.capture"))) {
            return;
        }
        float clamped = Mth.clamp(intensity, 0.0F, 1.0F);
        if (clamped <= 0.0F) {
            return;
        }
        // Сравнивается не исторический максимум, а то, что осталось от предыдущей тряски:
        // иначе один сильный удар задаёт амплитуду всем последующим слабым, и при частых
        // попаданиях камера навсегда остаётся на максимуме.
        float current = strength * decay(remaining, 0.0F);
        strength = Math.max(current, clamped);
        remaining = DURATION_TICKS;
        elapsed = 0;
    }

    /** Мгновенно останавливает тряску: смена мира не должна тащить за собой качающуюся камеру. */
    public static void reset() {
        remaining = 0.0F;
        strength = 0.0F;
    }

    /** Квадратичное затухание: удар должен ощущаться резким, а успокоение — быстрым. */
    private static float decay(float left, float partial) {
        float linear = Mth.clamp((left - partial) / DURATION_TICKS, 0.0F, 1.0F);
        return linear * linear;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (remaining <= 0.0F) {
            return;
        }
        elapsed++;
        if (--remaining <= 0.0F) {
            reset();
        }
    }

    @SubscribeEvent
    static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (remaining <= 0.0F || strength <= 0.0F) {
            return;
        }
        double setting = ClientConfig.cameraShake();
        if (setting <= 0.0D) {
            return;
        }

        float partial = (float) event.getPartialTick();
        float time = elapsed + partial;
        float amplitude = (float) (strength * setting * decay(remaining, partial));

        event.setYaw(event.getYaw() + Mth.sin(time * 2.7F) * MAX_YAW * amplitude);
        event.setPitch(event.getPitch() + Mth.sin(time * 3.9F + 1.3F) * MAX_PITCH * amplitude);
        event.setRoll(event.getRoll() + Mth.sin(time * 3.1F + 2.6F) * MAX_ROLL * amplitude);
    }

    private CameraShakeHandler() {
    }
}
