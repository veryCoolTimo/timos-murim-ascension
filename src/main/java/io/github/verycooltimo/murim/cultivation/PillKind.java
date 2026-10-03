package io.github.verycooltimo.murim.cultivation;

/**
 * Пилюли MVP (docs/design/19b-pills-places-of-power.md §1).
 *
 * <p>Прибавка к запасу задаётся долей стены текущего ранга, чтобы пилюля значила одно и то же
 * на любом ранге. Норов — вероятность, что сгусток на развилке окажется бурным (§2).
 *
 * @param wallShare доля стены, которую даёт полностью поглощённая пилюля (×10)
 * @param forks     развилок на сгусток: частая пилюля — короче, чтобы ритуал не стал работой
 * @param wild      вероятность бурного сгустка на развилке
 * @param rare      редкая: идёт в составы и даёт пятицветный финал
 * @param colour    цвет сгустка (стихия, канон «Пять Ци возвращаются к истоку»), 0xRRGGBB
 */
public enum PillKind {
    /** Простая пилюля ци: канон «даже не лекарство» (гл. 39–41). Белый — металл. */
    SNOW_PLUM("pill_snow_plum", 0.05D, 2, 0.10D, false, 0xF4F0F2),
    /** Origin Energy (в романе — Пилюля Жизненной Силы Души). Жёлтый — земля. */
    ORIGIN_ENERGY("pill_origin_energy", 0.15D, 3, 0.30D, true, 0xF2D25A),
    /** Thousand-Poison (Пилюля Небесного Яда). Зелёный — дерево. */
    THOUSAND_POISON("pill_thousand_poison", 0.10D, 3, 0.50D, true, 0x5FCB5A),
    /** Beauty's Tear (Слёзы Красоты): прорывная, почти всегда бурная. Сине-чёрный — вода. */
    BEAUTY_TEAR("beauty_tear", 0.35D, 3, 0.80D, true, 0x3FD6C8);

    private final String itemId;
    private final double wallShare;
    private final int forks;
    private final double wild;
    private final boolean rare;
    private final int colour;

    PillKind(String itemId, double wallShare, int forks, double wild, boolean rare, int colour) {
        this.itemId = itemId;
        this.wallShare = wallShare;
        this.forks = forks;
        this.wild = wild;
        this.rare = rare;
        this.colour = colour;
    }

    public String itemId() {
        return itemId;
    }

    public double wallShare() {
        return wallShare;
    }

    public int forks() {
        return forks;
    }

    public double wild() {
        return wild;
    }

    public boolean rare() {
        return rare;
    }

    public int colour() {
        return colour;
    }

    /** Ядовитые пилюли: метод с природой «яд» (Тан) усваивает их легче. */
    public boolean poisonous() {
        return this == THOUSAND_POISON || this == BEAUTY_TEAR;
    }

    public static PillKind byId(int ordinal) {
        PillKind[] all = values();
        return all[Math.max(0, Math.min(all.length - 1, ordinal))];
    }
}
