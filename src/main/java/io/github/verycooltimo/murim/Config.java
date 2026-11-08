package io.github.verycooltimo.murim;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Конфигурация мода: отладка, правила секты и размещение горы Хуа.
 *
 * <p>Тип конфига выбирается по тому, кто владеет значением: COMMON — общие настройки,
 * SERVER — то, что определяет поведение мира и уезжает в сейв, CLIENT — визуал и ввод.
 * Настройки качества VFX и доступности (отключение тряски камеры и вспышек) при появлении
 * пойдут в CLIENT-конфиг, а не сюда.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue DEV_COMMANDS = BUILDER
            .comment("Operator test tree /murim dev ... (docs/design/44-commands.md). Dev runs (runClient, runServer, GameTest) always have it;",
                    "in a release build it is hidden unless this is true. /murim help is never affected.")
            .define("devCommands", false);

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
    public static final ModConfigSpec.BooleanValue SECT_WAR_ENABLED = BUILDER
            .comment("Hitting the sect can start a sect war. When false, blows only warn (damage stays at the warning cap).")
            .define("sectWarEnabled", true);
    public static final ModConfigSpec.BooleanValue PVP_ONLY_IN_DUEL = BUILDER
            .comment("Default false: vanilla PvP. When true, damage is denied outside consented duels only when both players are inside Mount Hua.")
            .define("pvpOnlyInDuel", false);
    public static final ModConfigSpec.BooleanValue SWORD_GUARD_VANILLA_SWORDS = BUILDER
            .comment("Allow vanilla swords to guard (default false). Usable off-hand items always keep vanilla use priority.")
            .define("swordGuardVanillaSwords", false);
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

    public static final ModConfigSpec.BooleanValue SECT_RELOCATE_WHEN_UNSEEN = BUILDER
            .comment("Last recovery step for a sect NPC stuck for 480 ticks after re-path, step back and another spot:",
                    "move him to the nearest waypoint of the sect layout, only when no player can see him. Author allowed it 17.10.")
            .define("relocateWhenUnseen", true);

    public static final ModConfigSpec.BooleanValue SECT_REPAIRS = BUILDER
            .comment("The sect's servants mend visible damage in the baked sect (explosions, fire, mobs, a broken wall) and the sect treasury pays.",
                    "They only fill holes; they never touch a player's room, anything a player built or placed, containers, beds or workstations.",
                    "Set false to switch repairs off completely.")
            .define("sectRepairs", true);
    public static final ModConfigSpec.IntValue SECT_REPAIR_GRACE = BUILDER
            .comment("Ticks a hole stays before the servants take it up (6000 = a quarter of an in-game day; the work itself is in daylight only).",
                    "The key is new (it was sectRepairGraceTicks = 24000): a config file keeps the default it was first written with,",
                    "so a changed default needs a new key or the old value stays for everybody who ran the game before.")
            .defineInRange("sectRepairGrace", 6000, 0, 720000);

    // API: reference/neoforge-src/net/neoforged/neoforge/common/ModConfigSpec.java#Builder.defineInRange.
    public static final ModConfigSpec.IntValue HUA_MIN_DISTANCE = BUILDER
            .comment("Minimum Mount Hua centre distance from spawn, blocks; new worlds only.")
            .defineInRange("huaMinDistance", 2700, 1000, 100000);
    public static final ModConfigSpec.IntValue HUA_MAX_DISTANCE = BUILDER
            .comment("Maximum Mount Hua centre distance; values below the minimum use the minimum. New worlds only.")
            .defineInRange("huaMaxDistance", 4800, 1000, 100000);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
