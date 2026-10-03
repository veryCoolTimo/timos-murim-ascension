package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Скрытое Оружие Клана Тан — четыре формы метательных кинжалов (docs/design/techniques/tang-daggers-spec.md,
 * рефы «daggers techniques» и «hidden dagger/dagger1–4»). Общие для сервера (шкала, урон) и клиента (рисунок).
 *
 * <p>Тики форм — от начала IMPACT ({@code since}); на клиенте подготовка считается от события START.
 */
public final class TangRules {

    public static final int FIVE = 0;
    public static final int TWELVE = 1;
    public static final int STARS = 2;
    public static final int BURST = 3;
    /** Новые формы по роману (docs/design/techniques/tang-daggers-2-spec.md, одобрено 03.10). */
    public static final int THREE = 4;
    public static final int FLASH = 5;
    public static final int RETURN = 6;

    public static final ResourceLocation FIVE_ID = id("tang_five_thunders");
    public static final ResourceLocation TWELVE_ID = id("tang_twelve_daggers");
    public static final ResourceLocation STARS_ID = id("tang_seven_stars");
    public static final ResourceLocation BURST_ID = id("tang_dark_burst");
    public static final ResourceLocation THREE_ID = id("tang_three_instant");
    public static final ResourceLocation FLASH_ID = id("tang_flash_life");
    public static final ResourceLocation RETURN_ID = id("tang_sleeve_return");

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    /** Форма по id техники или −1. */
    public static int form(ResourceLocation technique) {
        return FIVE_ID.equals(technique) ? FIVE : TWELVE_ID.equals(technique) ? TWELVE
                : STARS_ID.equals(technique) ? STARS : BURST_ID.equals(technique) ? BURST
                : THREE_ID.equals(technique) ? THREE : FLASH_ID.equals(technique) ? FLASH
                : RETURN_ID.equals(technique) ? RETURN : -1;
    }

    public static ResourceLocation technique(int form) {
        return switch (form) {
            case FIVE -> FIVE_ID;
            case TWELVE -> TWELVE_ID;
            case STARS -> STARS_ID;
            case THREE -> THREE_ID;
            case FLASH -> FLASH_ID;
            case RETURN -> RETURN_ID;
            default -> BURST_ID;
        };
    }

    /** Подготовка формы (тики до IMPACT) — совпадает с windup в JSON. */
    public static int windup(int form) {
        return switch (form) {
            case FIVE -> 12;
            case TWELVE -> 38;
            case STARS -> 14;
            case THREE -> 10;
            case FLASH -> 3;
            case RETURN -> 10;
            default -> 20;
        };
    }

    /** Метательный кинжал — железо: без оружия в руке база как от железного клинка (урон руки 6). */
    public static final double DAGGER_HAND = 6.0D;

    /** Сила слоя: слой 7 = 1, слой 8 сильнее. */
    public static double power(int layer) {
        return layer <= 0 ? 0.3D : layer >= 8 ? 1.15D : 0.3D + 0.1D * layer;
    }

    /** Плотность эффектов по слою (доля от рисунка слоя 7); слой 0 — ничего. */
    public static double density(int layer) {
        return switch (Math.max(0, Math.min(8, layer))) {
            case 0 -> 0.0D;
            case 1 -> 0.15D;
            case 2 -> 0.3D;
            case 3 -> 0.45D;
            case 4 -> 0.6D;
            case 5 -> 0.72D;
            case 6 -> 0.86D;
            case 7 -> 1.0D;
            default -> 1.2D;
        };
    }

    // ------------------------------------------------------------ Пять Громов

    /**
     * Выпуск кинжала {@code k} от начала IMPACT: почти одновременно — по тику на кинжал (автор 03.10:
     * «кидать 5 почти одновременно, не как бараж»), на 8-м слое — все за два тика.
     */
    public static int fiveRelease(int k, int layer) {
        return layer >= 8 ? k / 3 : k;
    }

    /**
     * Вылет кинжала {@code k} — веером в разные стороны {рысканье°, подъём°} от прицела; дальше каждый
     * доворачивает к цели и приходит «немного из разных сторон» (автор 03.10). Несимметрично.
     */
    public static final double[][] FIVE_FAN = {{-24, 7}, {20, -6}, {-9, -15}, {30, 12}, {6, 19}};
    /** Доворот к цели, градусов за тик: дуга читается, но кинжал не промахивается вблизи. */
    public static final double FIVE_TURN = 9.0D;

    /** Скорость кинжала {@code k}: разная (гл. 195), на 8-м слое +15 %. */
    public static double fiveSpeed(int k, int layer) {
        return FIVE_SPEED[k] * (layer >= 8 ? 1.15D : 1.0D);
    }
    /** Разная скорость: поздние догоняют ранние — прилёт плотной очередью. */
    public static final double[] FIVE_SPEED = {1.35D, 1.45D, 1.55D, 1.65D, 1.75D};
    public static final double FIVE_RANGE = 24.0D;
    /** Урон кинжала и прибавка за каждое предыдущее подряд попадание по той же цели. */
    public static final double FIVE_DMG = 0.45D;
    public static final double FIVE_CHAIN = 0.25D;
    public static final int FIVE_STUN = 12;

    public static int fiveCount(int layer) {
        return layer <= 0 ? 1 : Math.min(5, layer + 1);
    }

    /** «Сила броска падает с расстоянием» (гл. 195): до 14 блоков полная, к 24 — 60 %. */
    public static double falloff(double distance) {
        return distance <= 14.0D ? 1.0D : Math.max(0.6D, 1.0D - 0.4D * (distance - 14.0D) / 10.0D);
    }

    // ------------------------------------------------------------ Двенадцать Летящих Кинжалов

    /** Розетка: кинжалы по одному встают между ладонями с этого тика подготовки до {@link #ROSETTE_FULL}. */
    public static final int ROSETTE_FROM = 6;
    public static final int ROSETTE_FULL = 22;
    /** Готовая розетка держится до этого тика подготовки, затем натяжение до IMPACT (38). */
    public static final int ROSETTE_HOLD = 32;
    public static final double TWELVE_RANGE = 20.0D;
    public static final double TWELVE_CONE = 60.0D;
    public static final double TWELVE_DMG = 0.6D;
    /** С 4-го кинжала по той же цели — четверть: веер — оружие толпы, а не одной цели. */
    public static final double TWELVE_EXTRA = 0.15D;
    public static final int TWELVE_FULL_HITS = 3;
    /** Яд кромок: импульсов, доля H за импульс (раз в 20 тиков). */
    public static final int TWELVE_POISON_PULSES = 3;
    public static final double TWELVE_POISON = 0.08D;
    /** Двенадцатый с неба: взлёт на тике {@link #LEAP}, бросок на {@link #SKY_THROW} (от IMPACT). */
    public static final int LEAP = 8;
    public static final int LEAP_TICKS = 7;
    public static final double LEAP_HEIGHT = 4.5D;
    public static final int SKY_THROW = 18;
    public static final double SKY_SPEED = 2.4D;
    public static final double SKY_DMG = 1.2D;
    /** Поворот кинжала к цели не быстрее, градусов за тик: дуга, а не самонаведение. */
    public static final double TWELVE_TURN = 5.0D;

    public static int twelveCount(int layer) {
        return switch (Math.max(0, Math.min(8, layer))) {
            case 0 -> 3;
            case 1 -> 5;
            case 2 -> 7;
            case 3 -> 9;
            default -> 11;
        };
    }

    /** Двенадцатый с неба — с 5-го слоя. */
    public static boolean sky(int layer) {
        return layer >= 5;
    }

    public static boolean poison(int layer) {
        return layer >= 4;
    }

    /** Скорость кинжала веера: 1,4–1,8, у каждого своя (не различить, какой какой). */
    public static double twelveSpeed(int k) {
        return 1.4D + 0.4D * ((k * 0.618D) % 1.0D);
    }

    /** Угол раскрытия веера кинжала {@code k} из {@code n}: ±55°, поочерёдно в стороны. */
    public static double fanYaw(int k, int n) {
        if (n <= 1) {
            return 0.0D;
        }
        double u = k / (double) (n - 1);
        double side = (k % 2 == 0 ? -1.0D : 1.0D);
        return side * (18.0D + 32.0D * u);
    }

    // ------------------------------------------------------------ Семь Звёзд

    /** Кинжалы встают в звёзды по одному каждые 2 тика (на 8-м слое — каждый тик). */
    public static int starPlace(int layer) {
        return layer >= 8 ? 1 : 2;
    }

    /** Расстановка идёт до этого тика (от IMPACT); второе R принимается в окне [{@link #STAR_EARLY}, {@link #STAR_SET}). */
    public static final int STAR_SET = 14;
    public static final int STAR_EARLY = 4;
    /** Вспышка-телеграф: точка горла фиксируется, венцы колют шире. */
    public static final int STAR_FLARE = 30;
    /** Схождение без второго нажатия (T50). */
    public static final int STAR_STRIKE = 36;
    public static final double STAR_RADIUS = 1.6D;
    public static final double STAR_DMG = 0.7D;
    public static final double STAR_RANGE = 22.0D;
    /** Схождение за 3–4 тика (codex 03.10): по вспышке шаг вперёд ещё успевает. */
    public static final double STAR_SPEED = 0.5D;

    public static int starCount(int layer) {
        return switch (Math.max(0, Math.min(8, layer))) {
            case 0, 1 -> 3;
            case 2 -> 4;
            case 3 -> 5;
            case 4, 5 -> 6;
            default -> 7;
        };
    }

    /**
     * Точка звезды {@code k} вокруг цели: полусфера, открытая к мастеру (звёзды сзади, по бокам и одна
     * сверху — «кто отступает, попадёт в поток»). {@code back} — направление от мастера к цели.
     * В воздухе — полная сфера.
     */
    public static Vec3 starOffset(int k, Vec3 back, boolean air) {
        Vec3 b = new Vec3(back.x, 0.0D, back.z);
        b = b.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : b.normalize();
        Vec3 side = new Vec3(-b.z, 0.0D, b.x);
        // Ковш Большой Медведицы, развёрнутый вокруг цели: угол по горизонту (0 — точно сзади) и высота.
        double[][] dipper = {{-100, 0.35}, {-62, 0.85}, {-25, 0.55}, {8, 1.05}, {42, 0.6}, {78, 0.95}, {112, 0.4}};
        // Дуга ~230° позади и по бокам, сектор ~130° к мастеру открыт (codex 03.10).
        double a = Math.toRadians(dipper[k % 7][0] * (air ? 1.45D : 1.08D));
        double h = dipper[k % 7][1] - (air && k % 2 == 1 ? 1.6D : 0.0D);
        Vec3 flat = b.scale(Math.cos(a)).add(side.scale(Math.sin(a)));
        return flat.scale(STAR_RADIUS).add(0.0D, h, 0.0D);
    }

    // ------------------------------------------------------------ Тёмный Взрыв

    public static final double CARP_SPEED = 0.22D;
    public static final double CARP_TURN = 2.5D;
    /** Сколько тиков максимум длится медленная фаза. */
    public static final int CARP_TICKS = 36;
    /** Телеграф: рывок не раньше этого тика медленной фазы, даже вблизи. */
    public static final int CARP_MIN = 16;
    /** Рывок раньше, если до цели ближе. */
    public static final double BURST_DIST = 3.5D;
    public static final double BURST_SPEED = 2.6D;
    public static final double BURST_DMG = 1.6D;
    public static final double SPLASH_DMG = 0.8D;
    public static final double SPLASH_EDGE = 0.3D;
    public static final double SPLASH_RADIUS = 2.8D;
    /** Отзыв пронзает всех на линии, но слабее прямого попадания со взрывом (2,4H). */
    public static final double RECALL_DMG = 1.4D;
    public static final double RECALL_SPEED = 2.6D;
    public static final int HANG_TICKS = 60;
    public static final double BURST_RANGE = 24.0D;

    /** Амплитуда извивания (блоки поперёк хода) на тике медленной фазы: «шире — ближе рывок». */
    public static double carpAmplitude(int age) {
        double k = Math.max(0.0D, Math.min(1.0D, age / (double) CARP_TICKS));
        return 0.05D + 0.45D * k * k;
    }

    /** Частота S-волны растёт к рывку: рад/тик. */
    public static double carpPhase(int age) {
        return age * 0.45D + age * age * 0.006D;
    }

    public static boolean recall(int layer) {
        return layer >= 5;
    }

    /**
     * Второй кинжал в первый (гл. 196, попытка 10: «импульс удвоился»): повторное R в медленной фазе
     * бросает второй кинжал вдогонку, он бьёт в первый — рывок сразу и вдвое быстрее. С 6-го слоя.
     */
    public static boolean doubled(int layer) {
        return layer >= 6;
    }

    public static final double SECOND_SPEED = 3.0D;

    // ------------------------------------------------------------ Три Лезвия Одного Мгновения

    /**
     * Гл. 195, попытка 3: «Один шел прямо на него, а два других вращались к нему по бокам. Скорость тех,
     * что вращались, была намного выше… все три лезвия достигли Чхон Мёна одновременно».
     * Время прихода — одно на все три: от дистанции, 6–14 тиков.
     */
    public static int threeTicks(double distance) {
        return (int) Math.max(6, Math.min(14, Math.round(distance / 1.6D)));
    }

    /** Боковой вынос дуги от прямой — доля дистанции. */
    public static final double THREE_BOW = 0.45D;
    public static final double THREE_DMG = 0.6D;
    public static final double THREE_RANGE = 22.0D;
    public static final int THREE_STUN = 10;

    public static int threeCount(int layer) {
        return layer <= 0 ? 1 : layer < 3 ? 2 : 3;
    }

    /** Излом под прямым углом (гл. 898) у одного бокового лезвия — с 6-го слоя. */
    public static boolean kink(int layer) {
        return layer >= 6;
    }

    /**
     * Точка лезвия {@code k} (0 — прямое, 1/2 — боковые) на доле пути {@code u}: прямая или квадратичная
     * дуга с выносом вбок; излом — две прямые через угол на 75 % глубины.
     */
    public static Vec3 threePoint(int k, double u, Vec3 from, Vec3 to, Vec3 side, double bow, boolean kinked) {
        if (k == 0) {
            return from.lerp(to, u);
        }
        double s = k == 1 ? -1.0D : 1.0D;
        Vec3 off = side.scale(s * bow);
        if (kinked) {
            Vec3 corner = from.lerp(to, 0.75D).add(off.scale(1.1D));
            return u < 0.7D ? from.lerp(corner, u / 0.7D) : corner.lerp(to, (u - 0.7D) / 0.3D);
        }
        Vec3 ctrl = from.lerp(to, 0.5D).add(off.scale(2.0D));
        double a = 1.0D - u;
        return from.scale(a * a).add(ctrl.scale(2.0D * a * u)).add(to.scale(u * u));
    }

    // ------------------------------------------------------------ Молниеносное Похищение Жизни

    /** Гл. 195: «не было огромной силы. Это была просто скорость. Кинжал, исчезнувший… появился прямо перед». */
    public static final double FLASH_SPEED = 4.0D;
    public static final double FLASH_DMG = 0.35D;
    public static final double FLASH_RANGE = 24.0D;
    /** Ближе этого к цели кинжал снова виден. */
    public static final double FLASH_REVEAL = 2.5D;
    public static final int FLASH_STUN = 8;

    public static boolean vanish(int layer) {
        return layer >= 3;
    }

    /** Срыв замаха врага — с 5-го слоя. */
    public static boolean interrupt(int layer) {
        return layer >= 5;
    }

    /** Второй кинжал «без ци» в тени первого (гл. 196) — 8-й слой. */
    public static boolean shadowed(int layer) {
        return layer >= 8;
    }

    // ------------------------------------------------------------ Возврат Лезвий в Рукав

    /** Промахнувшийся кинжал любой формы лежит воткнутым столько тиков (гл. 195: «брошенные лезвия трудно вернуть»). */
    public static final int STUCK_TICKS = 300;
    public static final double RETURN_RANGE = 20.0D;
    public static final double RETURN_SPEED = 2.4D;
    public static final double RETURN_DMG = 0.5D;
    /** С 6-го слоя лезвие на возврате загибает к врагу не дальше этого угла от линии. */
    public static final double RETURN_BEND = 15.0D;

    public static int returnCount(int layer) {
        return layer <= 0 ? 1 : layer < 3 ? 4 : 64;
    }

    private TangRules() {
    }
}
