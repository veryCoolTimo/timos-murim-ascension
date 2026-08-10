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

    public static final ModConfigSpec.DoubleValue CAMERA_SHAKE_INTENSITY = BUILDER
            .comment("Сила тряски камеры. 0.0 — тряска полностью отключена.")
            .defineInRange("accessibility.cameraShakeIntensity", 1.0D, 0.0D, 1.0D);

    public static final ModConfigSpec.BooleanValue SCREEN_FLASHES = BUILDER
            .comment("Экранные вспышки при ударах техник.")
            .define("accessibility.screenFlashes", true);

    public static final ModConfigSpec.BooleanValue DISTORTION_EFFECTS = BUILDER
            .comment("Искажения и пост-эффекты. При отключении техника остаётся читаемой.")
            .define("accessibility.distortionEffects", true);

    public static final ModConfigSpec.EnumValue<VfxQuality> VFX_QUALITY = BUILDER
            .comment("Качество эффектов. Умножается на ванильную настройку частиц.")
            .defineEnum("vfx.quality", VfxQuality.HIGH);

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Сила тряски с учётом настройки. Ноль означает ноль. */
    public static double cameraShake() {
        return CAMERA_SHAKE_INTENSITY.get();
    }

    private ClientConfig() {
    }
}
