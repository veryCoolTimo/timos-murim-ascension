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

    public static final ModConfigSpec.IntValue SECT_GUARD_STRIKES = BUILDER
            .comment("Warnings before walking an outsider out of a restricted zone.")
            .defineInRange("sectGuardStrikes", 3, 1, 20);
    public static final ModConfigSpec.IntValue SECT_GUARD_LINGER = BUILDER
            .comment("Ticks in a restricted zone before an escort is ordered.")
            .defineInRange("sectGuardLingerTicks", 300, 40, 24000);
    public static final ModConfigSpec.IntValue SECT_GUARD_REPEAT = BUILDER
            .comment("Return within this many ticks escalates to nonlethal guard combat.")
            .defineInRange("sectGuardRepeatTicks", 2400, 100, 24000);
    public static final ModConfigSpec.IntValue SECT_GUARD_REFUSAL = BUILDER
            .comment("Ticks allowed to follow an escort out before refusal escalates.")
            .defineInRange("sectGuardRefusalTicks", 200, 40, 2400);
    public static final ModConfigSpec.IntValue SECT_JAIL_THRESHOLD = BUILDER
            .comment("Recent penance sentences before renewed trespass provokes guard combat.")
            .defineInRange("sectJailThreshold", 3, 1, 64);
    public static final ModConfigSpec.IntValue SECT_JAIL_DAYS = BUILDER
            .comment("Rolling in-game days of sentence history; absence never counts as an offence.")
            .defineInRange("sectJailWindowDays", 7, 1, 365);

    public static final ModConfigSpec.IntValue SECT_RAMPAGE_WINDOW = BUILDER
            .comment("Rolling ticks for repeated attacks on sect NPCs; sparring partners are exempt.")
            .defineInRange("sectRampageWindowTicks", 200, 20, 24000);
    public static final ModConfigSpec.IntValue SECT_RAMPAGE_TARGETS = BUILDER
            .comment("Different sect NPCs attacked within the rampage window.")
            .defineInRange("sectRampageTargets", 2, 2, 64);
    public static final ModConfigSpec.IntValue SECT_RAMPAGE_HITS = BUILDER
            .comment("Attack attempts within the rampage window, including hits stopped by an NPC.")
            .defineInRange("sectRampageHits", 3, 2, 100);
    public static final ModConfigSpec.DoubleValue SECT_RAMPAGE_GUARD_DAMAGE = BUILDER
            .comment("Damage of one incoming hit on an enforcing guard that calls an elder (before nonlethal clamp).")
            .defineInRange("sectRampageGuardDamage", 8.0D, 1.0D, 1000.0D);
    public static final ModConfigSpec.IntValue SECT_SUPPRESSION_TICKS = BUILDER
            .comment("Online ticks pinned by the elder before transfer to the penance cave.")
            .defineInRange("sectSuppressionTicks", 100, 80, 120);
    public static final ModConfigSpec.IntValue SECT_ELDER_RANGE = BUILDER
            .comment("Search radius for an available loaded elder, then the sect leader.")
            .defineInRange("sectElderSearchRange", 192, 32, 512);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
