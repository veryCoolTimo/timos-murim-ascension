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
            .defineInRange("accessibility.hitStopPercent", 0, 0, 100);

    /**
     * Directed camera in rank breakthroughs (closeup, low angle, over the shoulder, orbit, crane). Off = the player's
     * own camera stays as it is. The shake slider above still applies: at 0 each shot holds a still pose.
     */
    public static final ModConfigSpec.BooleanValue BREAKTHROUGH_CAMERA = BUILDER
            .comment("Directed camera during rank breakthroughs. Off keeps your own camera.")
            .define("accessibility.breakthroughCamera", true);

    public static final ModConfigSpec.BooleanValue SCREEN_FLASHES = BUILDER
            .comment("Экранные вспышки при ударах техник.")
            .define("accessibility.screenFlashes", true);

    public static final ModConfigSpec.BooleanValue DISTORTION_EFFECTS = BUILDER
            .comment("Искажения и пост-эффекты. При отключении техника остаётся читаемой.")
            .define("accessibility.distortionEffects", true);

    public static final ModConfigSpec.EnumValue<VfxQuality> VFX_QUALITY = BUILDER
            .comment("Качество эффектов: детализация лент и плотность частиц.")
            .defineEnum("vfx.quality", VfxQuality.HIGH);



    /** The faint jade mote over the head of the goal's person, within sixteen blocks. */
    public static final ModConfigSpec.BooleanValue GUIDE_MARKER = BUILDER
            .comment("Faint jade mote over the head of the person of your sect goal, within sixteen blocks.")
            .define("hud.guideMarker", true);

    /** Name and role of a sect person while you look at him closely (six blocks). */
    public static final ModConfigSpec.BooleanValue NAME_PLATES = BUILDER
            .comment("Small name plate (name and role) over a sect person you look at closely.")
            .define("hud.namePlates", true);

    /**
     * The faces of the sect students built from parts (docs/design/58-face-constructor.md): each person gets eyes, brows, nose and
     * mouth by his member key, and the face changes with what he does. Enabled by default (author 27.10); the dev
     * command {@code /murim dev face on} switches it for one session.
     */
    public static final ModConfigSpec.BooleanValue FACE_CONSTRUCTOR = BUILDER
            .comment("Sect students get faces built from parts (eyes, brows, nose, mouth) that follow the mood. Off = the faces drawn into the skins.")
            .define("sect.faceConstructor", true);

    // API: read-only neoforge-src/net/neoforged/neoforge/common/ModConfigSpec.java#Builder.
    public static final ModConfigSpec.BooleanValue RAIN_SKY_TINT = BUILDER
            .comment("Night-blue sky and crimson horizon during nearby 24 Plum Rainfall illusions.")
            .translation("murim.configuration.rainSkyTint")
            .define("vfx.rainSkyTint", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Сила тряски долей единицы. Ноль означает ноль. */
    public static double cameraShake() {
        return CAMERA_SHAKE_PERCENT.get() / 100.0D;
    }

    /** Сила замирания кадра долей единицы. Ноль означает полное отключение. */
    public static double hitStop() {
        // Author decision, 6 Nov: disabled even for existing configs until player and effects can freeze together.
        return 0.0D;
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
