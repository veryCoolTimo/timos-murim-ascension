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

    /**
     * Техники ломают землю (падение дерева Семи Цветков Сливы и т. п.): только природные блоки
     * — земля, песок, гравий, камень; руды, контейнеры и механизмы не трогаются.
     */
    public static final ModConfigSpec.BooleanValue TECHNIQUE_TERRAIN = BUILDER
            .comment("Техники разрушают природные блоки (земля, песок, гравий, камень). Сундуки, руды и механизмы не трогаются.")
            .define("techniqueTerrainDamage", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
