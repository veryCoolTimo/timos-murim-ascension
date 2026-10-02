package io.github.verycooltimo.murim.technique;

/**
 * Техника шага — семейство подтехник в одном слоте (автор 02.10: «шаги Бога Ветров — там 6
 * подтехник»; решение docs/design/21-footwork-families.md). Подтехника выбирается контекстом
 * ввода, а открывается слоем освоения семейства.
 *
 * <ul>
 *   <li><b>Хуашань</b> (Шаг Невидимого Аромата, 5 слоёв): сближение и уклонение — L0, лёгкий бег —
 *       L1, перелёт лепестка — L2, поворот в воздухе — L3. Стен нет: в каноне их нет.</li>
 *   <li><b>Бог Ветров</b> (Шаги Бога Ветров, 8 слоёв): Миг и Молния — L0, Тень — L1, Перелёт
 *       ветра — L2, Смерть — L3, отталкивание от стены — L4, поворот ветра — L7.</li>
 * </ul>
 */
public enum FootworkFamily {
    HUASHAN("huashan", 5, new int[] {0, 0, 1, 2, 2}, 1, 2, -1, 3),
    WIND_GOD("wind_god", 8, new int[] {0, 0, 1, 1, 2, 3, 3, 4}, 0, 2, 4, 7);

    private final String id;
    private final int layers;
    private final int[] travelTier;
    private final int runUnlock;
    private final int leapUnlock;
    private final int wallUnlock;
    private final int airTurnUnlock;

    FootworkFamily(String id, int layers, int[] travelTier, int runUnlock, int leapUnlock, int wallUnlock, int airTurnUnlock) {
        this.id = id;
        this.layers = layers;
        this.travelTier = travelTier;
        this.runUnlock = runUnlock;
        this.leapUnlock = leapUnlock;
        this.wallUnlock = wallUnlock;
        this.airTurnUnlock = airTurnUnlock;
    }

    public String id() {
        return id;
    }

    public int layers() {
        return layers;
    }

    public static FootworkFamily byId(String id) {
        for (FootworkFamily f : values()) {
            if (f.id.equals(id)) {
                return f;
            }
        }
        return WIND_GOD;
    }

    /** Индекс таблицы бега {@link TraverseRules} для слоя семейства — не второе освоение. */
    public int tier(int layer) {
        return travelTier[Math.max(0, Math.min(travelTier.length - 1, layer))];
    }

    public boolean canRun(int layer) {
        return layer >= runUnlock;
    }

    public boolean canLeap(int layer) {
        return layer >= leapUnlock;
    }

    /** Отталкиваний от стены за отрыв от земли: у Хуашань нет вовсе. */
    public int wallKicks(int layer) {
        if (wallUnlock < 0 || layer < wallUnlock) {
            return 0;
        }
        return layer >= 7 ? 2 : 1;
    }

    public boolean canAirTurn(int layer) {
        return layer >= airTurnUnlock;
    }

    /** Шаг Мига Бога Ветров: дальность по таблице бега (3/4/5/5/6 блоков). */
    public static double evadeDistance(int tier) {
        return switch (Math.max(0, Math.min(4, tier))) {
            case 0 -> 3.0D;
            case 1 -> 4.0D;
            case 2, 3 -> 5.0D;
            default -> 6.0D;
        };
    }

    /** Окно уклонения Мига, тиков: 0/2/3/4/4. */
    public static int evadeInvulnerable(int tier) {
        return switch (Math.max(0, Math.min(4, tier))) {
            case 0 -> 0;
            case 1 -> 2;
            case 2 -> 3;
            default -> 4;
        };
    }
}
