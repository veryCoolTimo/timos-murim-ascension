package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.cultivation.flow.FlowSpec;
import io.github.verycooltimo.murim.cultivation.flow.FlowSpec.Special;

/**
 * Пилюли (docs/design/19b-pills-places-of-power.md §1; numbers of 25.10 - author's rules, mockup plan-site/extra/mockups/pill-flow).
 *
 * <p>The qi of a pill is FIXED and does not depend on the wall of the player: a share of the wall was a cheat (keep a pill until
 * a high rank). Common pills carry 25-50, middle 200-500, high 1500-3500. Every pill has its own designed rank
 * ({@link Realm} index: THIRD 1, SECOND 2, FIRST 3, PEAK 4, TRANSCENDENT 5; the ranks above Peak exist) and a stream character for
 * the game «Провести поток» ({@link FlowSpec}).
 *
 * @param qi     fixed qi of the pill (0 for the utility pills)
 * @param rank   the rank the pill is made for ({@link Realm} index)
 * @param tier   ступень (обычная / средняя / высшая): rarity, and who lifts the player after a flawless absorption ({@link PillTier})
 * @param colour цвет сгустка (стихия, канон «Пять Ци возвращаются к истоку»), 0xRRGGBB
 * @param flow   the character of the stream in the game; {@code null} for the utility pills
 */
public enum PillKind {
    /**
     * Пилюля Цветка Снежной Сливы: канон «даже не лекарство» (гл. 39–41). Белый — металл. Ступень средняя (автор 24.10: «снежная
     * слива считается ой какой неплохой»). Cold: the stream is slow, the spots pulse unhurriedly; the risk is to run out of time.
     */
    SNOW_PLUM("pill_snow_plum", 250, Realm.SECOND, PillTier.MIDDLE, 0xF4F0F2,
            new FlowSpec(0xDFE9F5, 0xFFFFFF, 1.5D, 2, 0.08D, 0, Special.COLD, 0.8D, 20)),
    /** Origin Energy (в романе — Пилюля Жизненной Силы Души). Жёлтый — земля. Golden and even: three narrow spots and a fork. */
    ORIGIN_ENERGY("pill_origin_energy", 1500, Realm.PEAK, PillTier.HIGH, 0xF2D25A,
            new FlowSpec(0xF2D25A, 0xFFFBE6, 1.15D, 3, 0.30D, 1, Special.NONE, 1.0D, 22)),
    /** Thousand-Poison (Пилюля Небесного Яда). Зелёный — дерево. Poison: the green spans burn when the flow runs long. */
    THOUSAND_POISON("pill_thousand_poison", 1800, Realm.PEAK, PillTier.HIGH, 0x5FCB5A,
            new FlowSpec(0x5FCB5A, 0xEAFFE6, 1.2D, 3, 0.45D, 1, Special.POISON, 1.0D, 22)),
    /**
     * Heavenly Elixir (Небесный Эликсир, AR гл. 34-39, 150): the blue pill the Blade Demon of the Blood Heavens kept for an heir;
     * stronger than Origin Energy ("лекарство мощнее Эликсира Демонической Сущности"), calm. Cyan/blue - water. Calm and cold, but long.
     */
    BLUE("pill_blue", 2200, Realm.TRANSCENDENT, PillTier.HIGH, 0x70E7FF,
            new FlowSpec(0x70E7FF, 0xECFDFF, 1.35D, 4, 0.25D, 2, Special.COLD, 1.0D, 26)),
    /**
     * Blood Divine Pill (Кровавая Божественная Пилюля, AR гл. 66, 71): the red pill of the Heavenly Wind Cult, "the greatest pill of the
     * Wastelands"; potent and violent, but still below Beauty's Tear. Crimson - blood. Hot: frequent hard surges, release fast.
     */
    BLOOD("pill_blood", 2800, Realm.TRANSCENDENT, PillTier.HIGH, 0xC8141F,
            new FlowSpec(0xE0283A, 0xFFE3E6, 1.0D, 3, 0.75D, 2, Special.HOT, 1.0D, 22)),
    /** Beauty's Tear (Слёзы Красоты): the rarest, everything at once - poison, surges, forks. Сине-чёрный — вода. */
    BEAUTY_TEAR("beauty_tear", 3500, Realm.TRANSCENDENT, PillTier.HIGH, 0x3FD6C8,
            new FlowSpec(0x3FD6C8, 0xE6FFFB, 0.95D, 4, 0.60D, 2, Special.POISON, 1.0D, 26)),
    /** Thousand-year ginseng (22.10): a rare herb, eaten like a pill, middle-like; has a cooldown. */
    THOUSAND_GINSENG("thousand_ginseng", 300, Realm.SECOND, PillTier.MIDDLE, 0xD9B77A,
            new FlowSpec(0xD9B77A, 0xFFF4DC, 1.45D, 2, 0.12D, 0, Special.NONE, 0.85D, 20)),
    /** Thousand-year lingzhi (22.10): the rarer herb, still below any high pill. */
    THOUSAND_LINGZHI("thousand_lingzhi", 350, Realm.SECOND, PillTier.MIDDLE, 0xA8261E,
            new FlowSpec(0xD9524A, 0xFFE6DF, 1.4D, 2, 0.18D, 1, Special.NONE, 0.9D, 21)),

    // ---- Everyday pills (24.10, author: «обычных всяких пилюль как пилюля жизни»). Canon names. The utility pills (last flag) never
    // enter the qi window: they act when eaten, see PillEffects, and need no dantian.

    /** Vitality Pill (Пилюля Жизненной Силы, Return of Mount Hua ch. 38-39, «каталась по полу горы Хуа»): a little qi and a little regeneration. */
    VITALITY("pill_vitality", 35, Realm.THIRD, PillTier.COMMON, 0xB5707A,
            new FlowSpec(0xFF9AA8, 0xFFF3F5, 1.7D, 1, 0.0D, 0, Special.NONE, 0.55D, 14)),
    /** Fasting Pill (Пилюля голодания, Return of Mount Hua ch. 73): eaten on the road instead of food; no qi. */
    FASTING("pill_fasting", 0, Realm.NONE, PillTier.COMMON, 0xC9AE7A, null, true),
    /** Antidote Pill (Пилюля-противоядие, Absolute Regression): cures poison, a short resistance after; no qi. */
    ANTIDOTE("pill_antidote", 0, Realm.NONE, PillTier.COMMON, 0xDCEBD2, null, true),
    /** Blood Replenishing Pill (Пилюля Восполнения Крови, original game addition): a strong heal after heavy blood loss. */
    BLOOD_REPLENISHING("pill_blood_replenishing", 0, Realm.NONE, PillTier.MIDDLE, 0x9E2230, null, true),
    /** Quick Recovery Pill (Пилюля Быстрого Восстановления, Absolute Regression ch. 141): qi comes back 2.5 times faster for a while. */
    QUICK_RECOVERY("pill_quick_recovery", 0, Realm.NONE, PillTier.MIDDLE, 0x9A9AE0, null, true),

    /** Absolute Regression ch. 113–115: concentrated treasure, dangerous to weaker meridians. */
    BLACK_LINGZHI("black_lingzhi", 1500, Realm.FIRST, PillTier.HIGH, 0x381F25,
            new FlowSpec(0x381F25, 0xD9524A, 1.2D, 3, 0.45D, 1, Special.NONE, 1.0D, 22)),
    /** Return of Mount Hua ch. 245–248: clean qi, safe even for the youngest disciples. */
    PURPLE_HEAVEN("pill_purple_heaven", 1600, Realm.FIRST, PillTier.HIGH, 0xB599CB,
            new FlowSpec(0xB599CB, 0xF9EDFF, 1.4D, 3, 0.15D, 1, Special.NONE, 0.9D, 22)),

    // Canon rare finds (30.10): Myst ch. 95, 110, 148, 305, 308; Mount Hua ch. 42, 137.
    SMALL_RESTORATION("pill_small_restoration", 200, Realm.THIRD, PillTier.MIDDLE, 0xC99747,
            new FlowSpec(0xC99747, 0xFFF0C4, 1.4D, 2, 0.18D, 1, Special.NONE, 0.9D, 22)),
    HEAVENLY_EARTH("pill_heavenly_earth", 250, Realm.SECOND, PillTier.MIDDLE, 0x879D46,
            new FlowSpec(0x879D46, 0xF8E5AA, 1.4D, 2, 0.18D, 1, Special.NONE, 0.9D, 22)),
    MOONLIGHT("pill_moonlight", 250, Realm.SECOND, PillTier.MIDDLE, 0xAFCDE8,
            new FlowSpec(0xAFCDE8, 0xF5FBFF, 1.4D, 2, 0.18D, 1, Special.NONE, 0.9D, 22)),
    ASCENDING_DRAGON("pill_ascending_dragon", 400, Realm.SECOND, PillTier.MIDDLE, 0xA92D22,
            new FlowSpec(0xA92D22, 0xFFD878, 1.4D, 2, 0.18D, 1, Special.NONE, 0.9D, 22)),
    GREAT_RESTORATION("pill_great_restoration", 1500, Realm.FIRST, PillTier.HIGH, 0xF2C342,
            new FlowSpec(0xF2C342, 0xFFFFFF, 1.4D, 3, 0.18D, 1, Special.NONE, 0.9D, 22)),
    /** N05, original game medicine approved 31.10; no canonical origin is claimed. */
    MENDING("pill_mending", 0, Realm.NONE, PillTier.COMMON, 0x97885D, null, true),

    /** MVP K2: gentle raw roots; append to preserve existing saved ordinal ids. */
    HUNDRED_GINSENG("hundred_ginseng", 40, Realm.THIRD, PillTier.COMMON, 0xD1BD86,
            new FlowSpec(0xD1BD86, 0xFFF4DC, 1.7D, 1, 0.0D, 0, Special.NONE, 0.55D, 14)),
    PURPLE_GINSENG("purple_ginseng", 50, Realm.THIRD, PillTier.COMMON, 0x9463A8,
            new FlowSpec(0x9463A8, 0xF5DEFF, 1.7D, 1, 0.0D, 0, Special.NONE, 0.55D, 14)),
    /** Return of Mount Hua ch. 39–41: ordinary medicine, once used for hangovers; no qi. Appended to preserve ordinals. */
    PLUM_BLOSSOM("pill_plum_blossom", 0, Realm.NONE, PillTier.COMMON, 0xE8B8CB, null, true);

    private final String itemId;
    private final int qi;
    private final int rank;
    private final PillTier tier;
    private final int colour;
    private final FlowSpec flow;
    private final boolean utility;

    PillKind(String itemId, int qi, int rank, PillTier tier, int colour, FlowSpec flow) {
        this(itemId, qi, rank, tier, colour, flow, false);
    }

    PillKind(String itemId, int qi, int rank, PillTier tier, int colour, FlowSpec flow, boolean utility) {
        this.utility = utility;
        this.itemId = itemId;
        this.qi = qi;
        this.rank = rank;
        this.tier = tier;
        this.colour = colour;
        this.flow = flow;
    }

    public String itemId() {
        return itemId;
    }

    /** Fixed qi of the pill. */
    public int qi() {
        return qi;
    }

    /** The rank ({@link Realm} index) the pill is made for. */
    public int rank() {
        return rank;
    }

    public PillTier tier() {
        return tier;
    }

    /** How the stream of this pill behaves in the game; {@code null} for the utility pills. */
    public FlowSpec flow() {
        return flow;
    }

    public int colour() {
        return colour;
    }

    /** Not a qi pill: acts when eaten (feeds, cures, heals, speeds qi) and never opens the window or the absorption. */
    public boolean utility() {
        return utility;
    }

    /** Author 31.10: the finite great elixirs; Soul Vitality is the existing Origin Energy item. */
    public boolean greatElixir() {
        return this == BLACK_LINGZHI || this == PURPLE_HEAVEN || this == GREAT_RESTORATION || this == ORIGIN_ENERGY;
    }

    /** Высшая пилюля: только она даёт подлёт при безупречном усвоении. */
    public boolean good() {
        return tier.lifts();
    }

    /** Ядовитые пилюли: метод с природой «яд» (Тан) усваивает их легче. */
    public boolean poisonous() {
        return this == THOUSAND_POISON || this == BEAUTY_TEAR;
    }

    /** Herbs are eaten through the pill pipeline but have a cooldown. */
    public boolean herb() {
        return this == THOUSAND_GINSENG || this == THOUSAND_LINGZHI || this == BLACK_LINGZHI
                || this == HUNDRED_GINSENG || this == PURPLE_GINSENG;
    }

    /** Canon safety exception; Black Lingzhi deliberately retains rank risk (author 30.10). */
    public boolean gentle() {
        return this == PURPLE_HEAVEN || (herb() && this != BLACK_LINGZHI);
    }

    /** Cooldown shared by all herbs, ticks (5 minutes). */
    public static final int HERB_COOLDOWN = 6000;

    public static PillKind byId(int ordinal) {
        PillKind[] all = values();
        return all[Math.max(0, Math.min(all.length - 1, ordinal))];
    }
}
