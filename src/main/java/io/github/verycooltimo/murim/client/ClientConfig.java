package io.github.verycooltimo.murim.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Клиентские настройки: качество эффектов и доступность.
 *
 * <p>Пункты доступности здесь не «приятное дополнение», а условие приёмки эффекта
 * (правило 04). Ползунок тряски обязан доходить до настоящего нуля: значение 0 должно
 * означать полное отсутствие движения камеры, а не «немного поменьше».
 *
 * <p>Вспышки и искажения — <b>раздельные</b> переключатели. Игроку, которому противопоказаны
 * мерцания, не должно приходиться отключать заодно и всё остальное; при выключенных
 * искажениях эффект обязан оставаться читаемым.
 *
 * <p>Формулировка «безопасно при эпилепсии» в текстах мода запрещена: гарантировать это
 * невозможно, а обещание вводит в заблуждение.
 */
public final class ClientConfig {

    /** Ступени качества. Умножаются на ванильную настройку частиц, а не заменяют её. */
    public enum VfxQuality {
        LOW, MEDIUM, HIGH
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /**
     * Сила тряски в процентах, а не долей единицы.
     *
     * <p>Тип целочисленный намеренно: экран настроек NeoForge рисует ползунок только
     * для {@code IntValue} с заданным диапазоном, а {@code DoubleValue} превращается
     * в поле ввода числа. Правило 04 требует именно ползунок вплоть до нуля.
     */
    public static final ModConfigSpec.IntValue CAMERA_SHAKE_PERCENT = BUILDER
            .comment("Сила тряски камеры в процентах. 0 — тряска полностью отключена.")
            .defineInRange("accessibility.cameraShakePercent", 100, 0, 100);

    /** Доля силы hit stop в процентах. 0 — замирание кадра полностью отключено. */
    public static final ModConfigSpec.IntValue HIT_STOP_PERCENT = BUILDER
            .comment("Сила замирания кадра при попадании, в процентах. 0 — отключено.")
            .defineInRange("accessibility.hitStopPercent", 100, 0, 100);

    public static final ModConfigSpec.BooleanValue SCREEN_FLASHES = BUILDER
            .comment("Экранные вспышки при ударах техник.")
            .define("accessibility.screenFlashes", true);

    public static final ModConfigSpec.BooleanValue DISTORTION_EFFECTS = BUILDER
            .comment("Искажения и пост-эффекты. При отключении техника остаётся читаемой.")
            .define("accessibility.distortionEffects", true);

    public static final ModConfigSpec.EnumValue<VfxQuality> VFX_QUALITY = BUILDER
            .comment("Качество эффектов: детализация лент и плотность частиц.")
            .defineEnum("vfx.quality", VfxQuality.HIGH);

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Сила тряски долей единицы. Ноль означает ноль. */
    public static double cameraShake() {
        return CAMERA_SHAKE_PERCENT.get() / 100.0D;
    }

    /** Сила замирания кадра долей единицы. Ноль означает полное отключение. */
    public static double hitStop() {
        return HIT_STOP_PERCENT.get() / 100.0D;
    }

    /**
     * Число сегментов ленты для текущего качества. Настройка обязана на что-то влиять:
     * опция в меню, которую код не читает, вводит игрока в заблуждение сильнее,
     * чем её отсутствие.
     */
    public static int trailSegments() {
        return switch (VFX_QUALITY.get()) {
            case LOW -> 10;
            case MEDIUM -> 18;
            case HIGH -> 28;
        };
    }

    private ClientConfig() {
    }
}
