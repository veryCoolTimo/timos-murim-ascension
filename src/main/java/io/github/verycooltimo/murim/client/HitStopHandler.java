package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.TickRateManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.lang.ref.WeakReference;

/**
 * Hit stop — короткая остановка картинки в момент попадания.
 *
 * <p>Зачем: панель манхвы разглядывают секундами, а в игре тот же кадр пролетает за три тика.
 * Без остановки главный кадр эффекта просто не успевают увидеть.
 *
 * <p>Как: у {@link ClientLevel} собственный экземпляр {@link TickRateManager} без сетевой
 * синхронизации, поэтому заморозка чисто локальна и сервера не касается. Ваниль сама рисует
 * замороженные сущности с частичным тиком, равным единице, — получается чистый стоп-кадр
 * без дрожания. Ни миксина, ни access transformer не требуется.
 * Источник: docs/design/research/r4-hitstop-camera.md.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class HitStopHandler {

    /** Потолок длительности. Защита от «мир замёрз навсегда» при кривых данных техники. */
    private static final int MAX_TICKS = 6;

    /** Пауза между двумя hit stop подряд: заморозка глобальна, при спаме превращается в заикание. */
    private static final long COOLDOWN_NANOS = 400_000_000L;

    private static final long NANOS_PER_TICK = 50_000_000L;

    /**
     * Заморозили ли мир именно мы. Без этого флага мы снимали бы чужую заморозку — например,
     * ту, что оператор поставил командой, — и получали рассинхрон с сервером.
     */
    private static boolean frozenByUs;

    /**
     * Какой именно уровень мы заморозили. Одного флага мало: при смене измерения и при респавне
     * создаётся <b>новый</b> {@link ClientLevel} со своим менеджером, и снятие заморозки ушло бы
     * не туда — сняли бы чужую, например поставленную оператором командой.
     * Слабая ссылка, чтобы не держать выгруженный мир в памяти.
     */
    private static WeakReference<ClientLevel> frozenLevel;

    private static long deadlineNanos;
    private static long readyAtNanos;

    /**
     * Просит остановить картинку на {@code ticks} тиков.
     *
     * <p>Игнорируется, если заморозка уже идёт, не истёк кулдаун, или мир заморожен не нами —
     * например, оператором через штатную команду. Чужую заморозку снимать нельзя.
     */
    public static void request(int ticks) {
        if (ticks <= 0 || frozenByUs) {
            return;
        }
        long now = System.nanoTime();
        // Сравнение через разность: начало отсчёта у nanoTime произвольно и может быть
        // отрицательным, прямое «меньше» тогда врёт.
        if (now - readyAtNanos < 0) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level.tickRateManager().isFrozen()) {
            return;
        }

        level.tickRateManager().setFrozen(true);
        frozenByUs = true;
        frozenLevel = new WeakReference<>(level);
        deadlineNanos = now + Math.min(ticks, MAX_TICKS) * NANOS_PER_TICK;
        readyAtNanos = deadlineNanos + COOLDOWN_NANOS;
    }

    /**
     * Снимает заморозку по истечении срока.
     *
     * <p>Срок считается по реальному времени, а не по тикам: во время заморозки тиковые события
     * ненадёжны как часы, и просчёт оставил бы мир стоять навсегда.
     */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!frozenByUs) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || frozenLevel == null || frozenLevel.get() != level) {
            // Мир сменился или выгрузился вместе с нашей заморозкой. Снимать нечего: тот
            // менеджер уже выброшен. Кулдаун при этом НЕ сбрасываем — он защищает от спама,
            // а не привязан к миру.
            frozenByUs = false;
            frozenLevel = null;
            return;
        }
        if (System.nanoTime() - deadlineNanos >= 0) {
            level.tickRateManager().setFrozen(false);
            frozenByUs = false;
            frozenLevel = null;
        }
    }

    private HitStopHandler() {
    }
}
