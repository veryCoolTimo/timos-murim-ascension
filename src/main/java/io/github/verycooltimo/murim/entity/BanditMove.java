package io.github.verycooltimo.murim.entity;

/**
 * Удары бандита-мечника (этап M1, автор 03.10: «2–3 простых удара без сложных эффектов,
 * читаемый замах, реакция на попадание, восстановление»). Тайминги — договор с анимациями
 * {@code assets/murim/bedrock/bandit.animation.json}: длина замаха, удара и отката здесь
 * равна длине клипов {@code windup*}, {@code attack*}, {@code recover*}, а тик попадания —
 * ключевому кадру удара. Менять только вместе.
 *
 * @param windup   тиков замаха (телеграф: поза + блик + звук)
 * @param strike   тиков удара
 * @param hitTick  тик удара, на котором проходит урон (от начала удара)
 * @param recover  тиков отката — окно для контрудара, бандит стоит
 * @param damage   урон по игроку (на сложности «Нормально»; ваниль: лёгкая — /2+1, сложная — ×1,5)
 * @param reach    дальность, блоков (от центра до центра по горизонтали)
 * @param arcDeg   ширина дуги перед бандитом, градусов
 * @param clip     суффикс клипов анимации
 */
public record BanditMove(int windup, int strike, int hitTick, int recover, float damage, double reach,
                         double arcDeg, String clip) {

    /** Рубящий сверху: долгий высокий замах, сильный удар — срывает технику с порогом 6. */
    public static final BanditMove CHOP = new BanditMove(10, 6, 3, 12, 6.0F, 2.9D, 70.0D, "");

    /** Горизонтальный с правого плеча: быстрее, широкий, слабее порога срыва техник. */
    public static final BanditMove SWEEP = new BanditMove(8, 6, 3, 12, 4.0F, 2.7D, 150.0D, "_sweep");

    /** Укол: самый длинный замах и дальний узкий удар — наказывает отход по прямой. */
    public static final BanditMove THRUST = new BanditMove(12, 5, 2, 14, 5.0F, 3.4D, 40.0D, "_thrust");

    /**
     * Слабая техника ци (у каждого пятого): рывок-разрез с голубым следом. Удар — это сам рывок
     * длиной {@link #DASH_TICKS}; урон проходит при касании, один раз.
     */
    public static final BanditMove QI_DASH = new BanditMove(16, 6, 1, 16, 8.0F, 1.4D, 360.0D, "_dash");

    public static final BanditMove[] ALL = {CHOP, SWEEP, THRUST, QI_DASH};

    /** Длина рывка ци, тиков, и скорость, блоков за тик: 6 тиков × 1,0 = до 6 блоков. */
    public static final int DASH_TICKS = 6;
    public static final double DASH_SPEED = 1.0D;

    /** С какой дистанции элитный бандит начинает рывок. */
    public static final double DASH_MIN = 3.5D;
    public static final double DASH_MAX = 7.5D;

    /** Перезарядка рывка, тиков. */
    public static final int DASH_COOLDOWN = 120;

    /** Сколько урона за раз сбивает замах в «оступился» (реакция на попадание). */
    public static final float POISE = 5.0F;

    /** Длина «оступился», тиков: равна клипу {@code hit}. */
    public static final int STAGGER_TICKS = 8;

    /** Доля элитных бандитов (автор: «каждому пятому»). */
    public static final float ELITE_CHANCE = 0.2F;

    public int total() {
        return windup + strike + recover;
    }

    public static BanditMove byId(int id) {
        return id >= 0 && id < ALL.length ? ALL[id] : CHOP;
    }

    public int id() {
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i] == this) {
                return i;
            }
        }
        return 0;
    }

    /** Выбор обычного удара: рубящий 40 %, горизонтальный 35 %, укол 25 %. {@code roll} — [0, 1). */
    public static BanditMove pick(float roll) {
        return roll < 0.40F ? CHOP : roll < 0.75F ? SWEEP : THRUST;
    }

    /**
     * Попадает ли удар: цель в пределах дальности и дуги, и не выше/ниже чем на 2 блока.
     *
     * @param dx,dz   от бандита к цели по горизонтали
     * @param dy      разница высот ног
     * @param yawDeg  поворот тела бандита (ванильный: 0 — юг, +Z)
     */
    public boolean reaches(double dx, double dy, double dz, float yawDeg) {
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat > reach || Math.abs(dy) > 2.0D) {
            return false;
        }
        if (flat < 0.6D || arcDeg >= 360.0D) {
            return true;
        }
        double fx = -Math.sin(Math.toRadians(yawDeg));
        double fz = Math.cos(Math.toRadians(yawDeg));
        double cos = (dx * fx + dz * fz) / flat;
        return cos >= Math.cos(Math.toRadians(arcDeg * 0.5D));
    }
}
