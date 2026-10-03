package io.github.verycooltimo.murim.technique;

/**
 * Шаг Невидимого Аромата по слоям освоения (Хуашань гл. 134: тело «плыло сквозь проливную ци
 * меча, как лепестки сливы, тихо летящие сквозь тёмную ночь»).
 *
 * <p>Слой 0 — простой рывок; дальше длиннее; со второго — на полсекунды сквозь удары;
 * на высшем — три рывка подряд.
 */
public final class StepRules {

    /** Тиков между рывками серии на высшем слое. */
    public static final int CHAIN_GAP = 5;

    public static double distance(int layer) {
        return layer <= 0 ? 4.0D : layer <= 2 ? 5.0D : 6.0D;
    }

    /** Неуязвимость в тиках после рывка: проход сквозь чужую ци. */
    public static int invulnerableTicks(int layer) {
        return layer >= 2 ? 10 : 0;
    }

    public static int dashes(int layer) {
        return layer >= 4 ? 3 : 1;
    }

    private StepRules() {
    }
}
