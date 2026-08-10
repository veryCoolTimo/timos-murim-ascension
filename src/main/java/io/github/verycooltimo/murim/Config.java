package io.github.verycooltimo.murim;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Конфигурация мода. Пока содержит только отладочный переключатель — реальные параметры
 * добавляются вместе с механиками, которые они настраивают.
 *
 * <p>Тип конфига выбирается по тому, кто владеет значением: COMMON — общие настройки,
 * SERVER — то, что определяет поведение мира и уезжает в сейв, CLIENT — визуал и ввод.
 * Настройки качества VFX и доступности (отключение тряски камеры и вспышек) при появлении
 * пойдут в CLIENT-конфиг, а не сюда.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Подробное логирование этапов загрузки мода. По умолчанию выключено.")
            .define("debugLogging", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
