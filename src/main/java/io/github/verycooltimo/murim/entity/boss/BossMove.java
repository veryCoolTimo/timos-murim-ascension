package io.github.verycooltimo.murim.entity.boss;

/**
 * Приёмы хозяина крепости (docs/design/26-boss.md §4). Тайминги — договор с анимациями
 * {@code assets/murim/bedrock/fortress_master.animation.json}: длина замаха, удара и отката
 * здесь равна длине клипов {@link #windupClip()}, {@link #strikeClip()}, {@link #recoverClip()}
 * (юнит-тест {@code BossMoveTest}). Менять только вместе.
 *
 * <p>Окна отката — по второму мнению codex (04.10): обычные 30 т, тяжёлые 40–60 т; телеграфы
 * в фазе 3 не короче.
 *
 * @param windup  тиков замаха: поза, метка на земле, свой звук
 * @param strike  тиков удара (у вихря — один проход, у прыжка — полёт)
 * @param recover тиков отката — окно для игрока
 * @param damage  урон по игроку (сложность «Нормально»)
 * @param heavy   тяжёлый приём: любая техника в замах сбивает его в «оступился»
 * @param key     имя для клипов и журнала
 */
public record BossMove(int id, int windup, int strike, int recover, float damage, boolean heavy, String key) {

    /** Связка дао: три удара (сверху, наотмашь, снизу) на тиках {@link #CHAIN_HITS} удара. */
    public static final BossMove CHAIN = new BossMove(0, 14, 30, 30, 7.0F, false, "chain");

    /** Прыжок тигра: присед, полёт к точке, зафиксированной до отрыва; дао застревает. */
    public static final BossMove POUNCE = new BossMove(1, 18, 24, 40, 10.0F, true, "pounce");

    /** Таран плечом по прямой — против тех, кто бегает и лечится. */
    public static final BossMove RAM = new BossMove(2, 14, 10, 30, 7.0F, false, "ram");

    /** Рык Зелёного Леса: вдох, волна, 2 с давления первоклассного. */
    public static final BossMove ROAR = new BossMove(3, 36, 14, 20, 4.0F, true, "roar");

    /** Волна раскола земли (Myst гл. 288): три линии трещин веером, дао застревает. */
    public static final BossMove SPLIT = new BossMove(4, 40, 12, 60, 10.0F, true, "split");

    /** Вихрь семидесяти двух крепостей: {@link #WHIRL_PASSES} прохода, потом колено. */
    public static final BossMove WHIRL = new BossMove(5, 16, 12, 60, 7.0F, true, "whirl");

    public static final BossMove[] ALL = {CHAIN, POUNCE, RAM, ROAR, SPLIT, WHIRL};

    /** Тики удара связки, на которых проходит урон: три удара через 10 тиков. */
    public static final int[] CHAIN_HITS = {4, 14, 24};
    /** Урон трёх ударов связки. */
    public static final float[] CHAIN_DAMAGE = {7.0F, 6.0F, 7.0F};
    /** Дальность и дуга трёх ударов связки. */
    public static final double CHAIN_REACH = 3.2D;
    public static final double[] CHAIN_ARC = {90.0D, 160.0D, 60.0D};

    /** Радиус кольца приземления прыжка. */
    public static final double POUNCE_RADIUS = 3.0D;
    /** Дистанция прыжка: ближе — не прыгает, дальше — не достаёт. */
    public static final double POUNCE_MIN = 4.0D;
    public static final double POUNCE_MAX = 12.0D;

    /** Таран: длина полосы и полуширина. */
    public static final double RAM_LENGTH = 7.0D;
    public static final double RAM_HALF_WIDTH = 1.3D;
    /** С какой дистанции таранит. */
    public static final double RAM_FROM = 8.0D;

    /** Радиус рыка (метка на земле на весь вдох). */
    public static final double ROAR_RADIUS = 5.0D;
    /** Сколько тиков после рыка аура давит как первоклассная. */
    public static final int ROAR_PRESSURE_TICKS = 40;

    /** Раскол: три линии через 30°, длина и полуширина. */
    public static final double SPLIT_LENGTH = 14.0D;
    public static final double SPLIT_HALF_WIDTH = 0.9D;
    public static final double SPLIT_SPREAD = 30.0D;
    /** За сколько тиков замаха линии загораются (до этого дао вонзается). */
    public static final int SPLIT_PLANT = 16;

    /** Вихрь: проходов, полуширина полосы и предельная длина прохода. */
    public static final int WHIRL_PASSES = 3;
    public static final double WHIRL_HALF_WIDTH = 1.6D;
    public static final double WHIRL_MAX_LENGTH = 14.0D;

    /** «Оступился»: тиков; «сбит» в прыжке — тиков на земле. */
    public static final int STAGGER_TICKS = 12;
    public static final int DOWNED_TICKS = 40;
    /** Встаёт с кресла, тиков. */
    public static final int RISE_TICKS = 30;
    /** Смерть: клип, тиков. */
    public static final int DEATH_TICKS = 40;
    /** Оглушение техникой у босса — не дольше (правило техник: босс 0,5 с). */
    public static final int STUN_CAP = 10;
    /** Невосприимчивость к оглушению и «оступился» после них. */
    public static final int CONTROL_IMMUNITY = 80;

    public int total() {
        return windup + strike + recover;
    }

    public String windupClip() {
        return switch (key) {
            case "roar" -> "roar_inhale";
            default -> "windup_" + key;
        };
    }

    public String strikeClip() {
        return switch (key) {
            case "chain" -> "attack_chain";
            case "pounce" -> "air_pounce";
            default -> key;
        };
    }

    public String recoverClip() {
        return switch (key) {
            case "pounce" -> "stuck";
            case "split" -> "stuck_split";
            case "whirl" -> "kneel";
            default -> "recover_" + key;
        };
    }

    public static BossMove byId(int id) {
        return id >= 0 && id < ALL.length ? ALL[id] : CHAIN;
    }
}
