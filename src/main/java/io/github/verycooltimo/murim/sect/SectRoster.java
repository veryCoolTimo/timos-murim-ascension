package io.github.verycooltimo.murim.sect;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Люди секты Хуашань на горе (docs/design/23-mount-hua-sect.md §1.1, §4.1; автор 04.10: «20–25 учеников»).
 * Глава, старейшины, первое поколение и наставник — каноничные имена из «Возрождения Хуашань»; ученики
 * второго (Пэк) и третьего (Чхон) поколения — канон, где он есть (Пэк Чхон, Ю Исоль, Пэк Сан, Юн Чжон,
 * Чо Голь), остальные — сгенерированные имена на слог поколения (план §4.1 п. «Имена»).
 *
 * <p>Порядок списка важен: по нему строятся ряды утреннего строя и пары спарринга, а значит, порядок
 * детерминирован и одинаков у сервера и тестов.
 *
 * @param key        ключ человека: имя {@code npc.murim.<key>}, свой диалог {@code murim_dialogues/<key>.json}, если есть
 * @param role       функция (диалог по умолчанию, спарринг, бой)
 * @param generation 0 — Хён (глава, старейшины), 1 — Ун, 2 — Пэк, 3 — Чхон
 * @param home       площадка горы ({@code MountHuaPlan.ZONES}), где человек живёт днём
 * @param look       облик: {@code textures/entity/sect/<look>.png}
 * @param temper     привычка в бою: 0 — ровный, 1 — напористый, 2 — осторожный (план §5.3)
 */
public record SectRoster(String key, SectRole role, int generation, String home, String look, int temper) {

    /** Поколение мирян при секте (слуги, управляющий): вне линии учителей. */
    public static final int LAY = -1;

    public static final List<SectRoster> ALL = List.of(
            // Глава и старейшины (Хён), первое поколение (Ун).
            new SectRoster("hyun_jong", SectRole.LEADER, 0, "main_hall", "leader", 0),
            new SectRoster("hyun_young", SectRole.ELDER, 0, "treasury", "elder_young", 0),
            new SectRoster("hyun_sang", SectRole.ELDER, 0, "scriptures", "elder_sang", 1),
            new SectRoster("un_gak", SectRole.ELDER, 1, "alchemy", "elder_gak", 2),
            new SectRoster("un_am", SectRole.ELDER, 1, "sect_gate", "first_am", 0),
            new SectRoster("un_geom", SectRole.MENTOR, 1, "mentor", "mentor", 1),
            // Второе поколение (Пэк): старший — партнёр урока «три чистых удара».
            new SectRoster("senior", SectRole.SENIOR, 2, "sparring", "second_0", 1),
            new SectRoster("baek_cheon", SectRole.SECOND, 2, "sparring", "second_1", 1),
            new SectRoster("yu_iseol", SectRole.SECOND, 2, "sparring", "second_2", 2),
            new SectRoster("baek_sang", SectRole.SECOND, 2, "sparring", "second_3", 0),
            new SectRoster("baek_ho", SectRole.SECOND, 2, "sparring", "second_4", 0),
            // Третье поколение (Чхон).
            new SectRoster("yoon_jong", SectRole.DISCIPLE, 3, "camp", "third_0", 0),
            new SectRoster("jo_gol", SectRole.DISCIPLE, 3, "camp", "third_1", 1),
            new SectRoster("disciple_a", SectRole.DISCIPLE, 3, "camp", "third_2", 2),
            new SectRoster("disciple_b", SectRole.DISCIPLE, 3, "camp", "third_3", 1),
            new SectRoster("cheong_jin", SectRole.DISCIPLE, 3, "camp", "third_4", 0),
            new SectRoster("cheong_seok", SectRole.DISCIPLE, 3, "camp", "third_5", 1),
            new SectRoster("cheong_pyo", SectRole.DISCIPLE, 3, "camp", "third_6", 2),
            new SectRoster("cheong_il", SectRole.DISCIPLE, 3, "camp", "third_7", 0),
            new SectRoster("cheong_yeon", SectRole.DISCIPLE, 3, "camp", "third_8", 2),
            new SectRoster("cheong_gwang", SectRole.DISCIPLE, 3, "camp", "third_9", 1),
            new SectRoster("cheong_hae", SectRole.DISCIPLE, 3, "camp", "third_0", 0),
            new SectRoster("cheong_do", SectRole.DISCIPLE, 3, "camp", "third_3", 1),
            new SectRoster("cheong_rim", SectRole.DISCIPLE, 3, "camp", "third_6", 2),
            new SectRoster("cheong_su", SectRole.DISCIPLE, 3, "camp", "third_8", 0),
            new SectRoster("cheong_bin", SectRole.DISCIPLE, 3, "camp", "third_5", 1),
            // Привратник у подножия тропы — живёт там всегда.
            new SectRoster("gatekeeper", SectRole.GATEKEEPER, 3, "gate", "third_7", 0),
            // Охрана (С3, часть 2): второе поколение на постах у ворот и закрытых залов; в строй и пары не встаёт.
            new SectRoster("baek_mu", SectRole.GUARD, 2, "sect_gate", "guard_0", 1),
            new SectRoster("baek_ryeong", SectRole.GUARD, 2, "main_hall", "guard_1", 0),
            new SectRoster("baek_gi", SectRole.GUARD, 2, "main_hall", "guard_2", 2),
            new SectRoster("baek_jin", SectRole.GUARD, 2, "ancestors", "guard_3", 1),
            new SectRoster("baek_won", SectRole.GUARD, 2, "treasury", "guard_4", 0),
            new SectRoster("baek_seo", SectRole.GUARD, 2, "elders", "guard_0", 2),
            // Ночная смена (автор 05.10: «охрана меняется»): те же шесть постов, по одному сменщику на каждый, в том же
            // порядке. Днём спят в общежитии второго поколения, после полудня — отдыхают в лагере. Имена сгенерированы.
            new SectRoster("baek_un", SectRole.GUARD, 2, "sect_gate", "guard_2", 0),
            new SectRoster("baek_ik", SectRole.GUARD, 2, "main_hall", "guard_3", 1),
            new SectRoster("baek_ryu", SectRole.GUARD, 2, "main_hall", "guard_4", 0),
            new SectRoster("baek_gang", SectRole.GUARD, 2, "ancestors", "guard_0", 2),
            new SectRoster("baek_gyu", SectRole.GUARD, 2, "treasury", "guard_1", 1),
            new SectRoster("baek_seung", SectRole.GUARD, 2, "elders", "guard_2", 0),
            // Миряне при секте (поколение −1): управляющий хозяйством и слуги. Имена — простые мирские, не каноничные.
            new SectRoster("steward_mun", SectRole.STEWARD, LAY, "treasury", "lay_steward", 0),
            new SectRoster("cook_kim", SectRole.COOK, LAY, "dining", "lay_cook", 0),
            new SectRoster("water_gu", SectRole.WATER_CARRIER, LAY, "dining", "lay_water", 0),
            new SectRoster("porter_jang", SectRole.PORTER, LAY, "treasury", "lay_porter_0", 0),
            new SectRoster("porter_oh", SectRole.PORTER, LAY, "treasury", "lay_porter_1", 0),
            new SectRoster("herbalist_han", SectRole.GARDENER, LAY, "alchemy", "lay_herbalist", 0),
            new SectRoster("sweeper_ma", SectRole.SWEEPER, LAY, "training", "lay_sweeper", 0));

    /** Человек по ключу. */
    public static Optional<SectRoster> of(String key) {
        for (SectRoster m : ALL) {
            if (m.key.equals(key)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /** Ключи дневной смены охраны по постам; ночная смена — {@link #NIGHT_WATCH} в том же порядке. */
    public static final List<String> DAY_WATCH = List.of("baek_mu", "baek_ryeong", "baek_gi", "baek_jin", "baek_won", "baek_seo");
    public static final List<String> NIGHT_WATCH = List.of("baek_un", "baek_ik", "baek_ryu", "baek_gang", "baek_gyu", "baek_seung");

    /** Номер поста охранника (0…5) или −1 — не охрана. */
    public int post() {
        int i = DAY_WATCH.indexOf(key);
        return i >= 0 ? i : NIGHT_WATCH.indexOf(key);
    }

    /** Охранник ночной смены. */
    public boolean nightWatch() {
        return NIGHT_WATCH.contains(key);
    }

    /** Сменщик на том же посту (другая смена) или пусто. */
    public Optional<SectRoster> relief() {
        int i = post();
        if (i < 0) {
            return Optional.empty();
        }
        return of(nightWatch() ? DAY_WATCH.get(i) : NIGHT_WATCH.get(i));
    }

    /** Номер в списке (−1 — нет такого). */
    public int index() {
        return ALL.indexOf(this);
    }

    /**
     * Кто стоял на горе в первой версии (роль без имени): старым NPC при загрузке выдаётся ключ, и они
     * становятся этими людьми, а не дублями.
     */
    public static Optional<SectRoster> legacy(SectRole role) {
        return switch (role) {
            case LEADER -> of("hyun_jong");
            case MENTOR -> of("un_geom");
            case SENIOR -> of("senior");
            case GATEKEEPER -> of("gatekeeper");
            case DISCIPLE_A -> of("disciple_a");
            case DISCIPLE_B -> of("disciple_b");
            default -> Optional.empty();
        };
    }

    /** Члены одного поколения в порядке списка (без привратника и охраны на постах: они не в строю и не в парах). */
    public static List<SectRoster> generation(int generation) {
        List<SectRoster> out = new ArrayList<>();
        for (SectRoster m : ALL) {
            if (m.generation == generation && m.role != SectRole.GATEKEEPER && m.role != SectRole.GUARD && !m.role.lay()) {
                out.add(m);
            }
        }
        return out;
    }

    /** Ученик (второе или третье поколение, без привратника и охраны на постах). */
    public boolean disciple() {
        return (generation == 2 || generation == 3) && role != SectRole.GATEKEEPER && role != SectRole.GUARD;
    }

    /** Мирянин при секте: слуга или управляющий. */
    public boolean lay() {
        return generation == LAY;
    }

    /** Ключ имени. */
    public String nameKey() {
        return "npc.murim." + key;
    }

    /** Ранг культивации NPC по поколению (шкала Realm): Чхон — 1, Пэк — 2, Ун — 3, Хён — 4. */
    public int rank() {
        return switch (generation) {
            case LAY, 3 -> 1;
            case 2 -> 2;
            case 1 -> 3;
            default -> 4;
        };
    }

    /** Здоровье по поколению: старшие крепче. */
    public double maxHealth() {
        return switch (generation) {
            case LAY -> 20.0D;
            case 3 -> 30.0D;
            case 2 -> 40.0D;
            default -> 60.0D;
        };
    }
}
