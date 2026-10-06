package io.github.verycooltimo.murim.sect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Люди секты Хуашань на горе (docs/design/23-mount-hua-sect.md §1.1, §4.1, «Состав секты — принято автором 05.10»).
 * Все имена свои (docs/design/31-originality.md, решение автора 06.10): персонажей романов не берём. Слоги поколений
 * тоже свои — 태 Тэ (глава, старейшины), 경 Гён (первое), 서 Со (второе), 율 Юль (третье); часть учеников носит
 * мирскую фамилию без слога поколения (Мок Хаён, Хам Доюн, Бок Мансок).
 *
 * <p>Порядок списка важен: по нему строятся ряды утреннего строя и пары спарринга, а значит, порядок
 * детерминирован и одинаков у сервера и тестов.
 *
 * @param key        ключ человека: имя {@code npc.murim.<key>}, свой диалог {@code murim_dialogues/<key>.json}, если есть
 * @param role       функция (диалог по умолчанию, спарринг, бой)
 * @param generation 0 — Тэ (глава, старейшины), 1 — Гён, 2 — Со, 3 — Юль
 * @param home       площадка горы ({@code MountHuaPlan.ZONES}), где человек живёт днём
 * @param look       облик: {@code textures/entity/sect/<look>.png}
 * @param temper     привычка в бою: 0 — ровный, 1 — напористый, 2 — осторожный (план §5.3)
 */
public record SectRoster(String key, SectRole role, int generation, String home, String look, int temper) {

    /** Поколение мирян при секте (слуги, управляющий): вне линии учителей. */
    public static final int LAY = -1;

    public static final List<SectRoster> ALL = List.of(
            // Состав секты — принято автором 05.10 (docs/design/23-mount-hua-sect.md «Состав секты»): около 40 человек.
            // Глава и три старейшины (Тэ): казна, дисциплина и Зал писаний, третий — для совета.
            new SectRoster("tae_hwi", SectRole.LEADER, 0, "main_hall", "leader", 0),
            new SectRoster("tae_gyun", SectRole.ELDER, 0, "treasury", "elder_treasury", 0),
            new SectRoster("tae_rok", SectRole.ELDER, 0, "scriptures", "elder_martial", 1),
            new SectRoster("tae_seong", SectRole.ELDER, 0, "elders", "elder_council", 0),
            // Первое поколение (Гён): лекарь, правая рука главы у ворот, наставник.
            new SectRoster("gyeong_cho", SectRole.ELDER, 1, "alchemy", "elder_healer", 2),
            new SectRoster("gyeong_tak", SectRole.ELDER, 1, "sect_gate", "first_gate", 0),
            new SectRoster("gyeong_pil", SectRole.MENTOR, 1, "mentor", "mentor", 1),
            // Второе поколение (Со), 8 человек: старший Со Ран — партнёр урока «три чистых удара»; остальные семеро
            // по очереди дежурят на постах (SectRota: 2 днём, 2 ночью). Со Му, Рён, Ги, Чин — бывшая стража первой версии.
            new SectRoster("seo_rang", SectRole.SENIOR, 2, "sparring", "second_0", 1),
            new SectRoster("mok_hayeon", SectRole.SECOND, 2, "sparring", "second_2", 2),
            new SectRoster("seo_gyu", SectRole.SECOND, 2, "sparring", "second_3", 0),
            new SectRoster("seo_ho", SectRole.SECOND, 2, "sparring", "second_4", 0),
            new SectRoster("seo_mu", SectRole.SECOND, 2, "sparring", "guard_0", 1),
            new SectRoster("seo_ryeong", SectRole.SECOND, 2, "sparring", "guard_1", 0),
            new SectRoster("seo_gi", SectRole.SECOND, 2, "sparring", "guard_2", 2),
            new SectRoster("seo_jin", SectRole.SECOND, 2, "sparring", "guard_3", 1),
            // Третье поколение (Юль), 17 человек. Его младшее место занимает игрок.
            new SectRoster("ham_doyun", SectRole.DISCIPLE, 3, "camp", "third_0", 0),
            new SectRoster("bok_manseok", SectRole.DISCIPLE, 3, "camp", "third_1", 1),
            new SectRoster("disciple_a", SectRole.DISCIPLE, 3, "camp", "third_2", 2),
            new SectRoster("disciple_b", SectRole.DISCIPLE, 3, "camp", "third_3", 1),
            new SectRoster("yul_jin", SectRole.DISCIPLE, 3, "camp", "third_4", 0),
            new SectRoster("yul_seok", SectRole.DISCIPLE, 3, "camp", "third_5", 1),
            new SectRoster("yul_pyo", SectRole.DISCIPLE, 3, "camp", "third_6", 2),
            new SectRoster("yul_il", SectRole.DISCIPLE, 3, "camp", "third_7", 0),
            new SectRoster("yul_yeon", SectRole.DISCIPLE, 3, "camp", "third_8", 2),
            new SectRoster("yul_gwang", SectRole.DISCIPLE, 3, "camp", "third_9", 1),
            new SectRoster("yul_hae", SectRole.DISCIPLE, 3, "camp", "third_0", 0),
            new SectRoster("yul_do", SectRole.DISCIPLE, 3, "camp", "third_3", 1),
            new SectRoster("yul_rim", SectRole.DISCIPLE, 3, "camp", "third_6", 2),
            new SectRoster("yul_su", SectRole.DISCIPLE, 3, "camp", "third_8", 0),
            new SectRoster("yul_bin", SectRole.DISCIPLE, 3, "camp", "third_5", 1),
            new SectRoster("yul_san", SectRole.DISCIPLE, 3, "camp", "third_1", 2),
            new SectRoster("yul_ak", SectRole.DISCIPLE, 3, "camp", "third_4", 1),
            // Привратник у подножия тропы Юль Ын — живёт там всегда.
            new SectRoster("gatekeeper", SectRole.GATEKEEPER, 3, "gate", "third_7", 0),
            // Миряне при секте (поколение −1): управляющий хозяйством и слуги. Имена — простые мирские, не каноничные.
            new SectRoster("steward_mun", SectRole.STEWARD, LAY, "treasury", "lay_steward", 0),
            new SectRoster("cook_kim", SectRole.COOK, LAY, "dining", "lay_cook", 0),
            new SectRoster("water_gu", SectRole.WATER_CARRIER, LAY, "dining", "lay_water", 0),
            new SectRoster("porter_jang", SectRole.PORTER, LAY, "treasury", "lay_porter_0", 0),
            new SectRoster("porter_oh", SectRole.PORTER, LAY, "treasury", "lay_porter_1", 0),
            new SectRoster("herbalist_han", SectRole.GARDENER, LAY, "alchemy", "lay_herbalist", 0),
            new SectRoster("sweeper_ma", SectRole.SWEEPER, LAY, "training", "lay_sweeper", 0));

    /**
     * Ключи людей прежних составов, которых больше нет (миграция сохранений): старший первой версии ({@code senior})
     * и восемь стражников С3, часть 2 (стражу заменило дежурство второго поколения). Такой NPC при первом тике уходит
     * с горы — без дублей и без «статистов» в форме охраны.
     */
    public static final java.util.Set<String> RETIRED = java.util.Set.of("senior", "baek_won", "baek_seo", "baek_un", "baek_ik",
            "baek_ryu", "baek_gang", "baek_gyu", "baek_seung");

    /**
     * Прежние ключи людей, переименованных 06.10 (docs/design/31-originality.md: свои имена вместо имён из романов).
     * Старый ключ в сохранении NPC ({@code member}) и в флагах игрока ({@code favour.<key>}, {@code summon.<key>})
     * читается как новый: тот же человек, без дубля и без сироты.
     */
    public static final Map<String, String> RENAMED = Map.ofEntries(
            Map.entry("hyun_jong", "tae_hwi"), Map.entry("hyun_young", "tae_gyun"), Map.entry("hyun_sang", "tae_rok"),
            Map.entry("hyun_seong", "tae_seong"), Map.entry("un_gak", "gyeong_cho"), Map.entry("un_am", "gyeong_tak"),
            Map.entry("un_geom", "gyeong_pil"), Map.entry("baek_cheon", "seo_rang"), Map.entry("yu_iseol", "mok_hayeon"),
            Map.entry("baek_sang", "seo_gyu"), Map.entry("baek_ho", "seo_ho"), Map.entry("baek_mu", "seo_mu"),
            Map.entry("baek_ryeong", "seo_ryeong"), Map.entry("baek_gi", "seo_gi"), Map.entry("baek_jin", "seo_jin"),
            Map.entry("yoon_jong", "ham_doyun"), Map.entry("jo_gol", "bok_manseok"), Map.entry("cheong_jin", "yul_jin"),
            Map.entry("cheong_seok", "yul_seok"), Map.entry("cheong_pyo", "yul_pyo"), Map.entry("cheong_il", "yul_il"),
            Map.entry("cheong_yeon", "yul_yeon"), Map.entry("cheong_gwang", "yul_gwang"), Map.entry("cheong_hae", "yul_hae"),
            Map.entry("cheong_do", "yul_do"), Map.entry("cheong_rim", "yul_rim"), Map.entry("cheong_su", "yul_su"),
            Map.entry("cheong_bin", "yul_bin"), Map.entry("cheong_gyeong", "yul_san"), Map.entry("cheong_ak", "yul_ak"));

    /** Прежние имена обликов (текстуры переименованы вместе с людьми). */
    public static final Map<String, String> RENAMED_LOOKS = Map.of("elder_young", "elder_treasury", "elder_sang", "elder_martial",
            "elder_seong", "elder_council", "elder_gak", "elder_healer", "first_am", "first_gate");

    /** Нынешний ключ человека по ключу из сохранения (прежний переводится, остальные — как есть). */
    public static String renamed(String key) {
        return RENAMED.getOrDefault(key, key);
    }

    /**
     * Ключ, от которого считаются детерминированные черты человека ({@code SectTalk.trait}): прежний ключ, если человек
     * переименован, — характер не меняется вместе с именем.
     */
    public static String seed(String key) {
        for (Map.Entry<String, String> e : RENAMED.entrySet()) {
            if (e.getValue().equals(key)) {
                return e.getKey();
            }
        }
        return key;
    }

    /** Нынешний облик по облику из сохранения. */
    public static String renamedLook(String look) {
        return RENAMED_LOOKS.getOrDefault(look, look);
    }

    /** Флаг игрока с прежним ключом человека в любой части ({@code favour.jo_gol} → {@code favour.bok_manseok}). */
    public static String renamedFlag(String flag) {
        String[] parts = flag.split("\\.", -1);
        boolean changed = false;
        for (int i = 0; i < parts.length; i++) {
            String next = renamed(parts[i]);
            if (!next.equals(parts[i])) {
                parts[i] = next;
                changed = true;
            }
        }
        return changed ? String.join(".", parts) : flag;
    }

    /** Человек по ключу. */
    public static Optional<SectRoster> of(String key) {
        for (SectRoster m : ALL) {
            if (m.key.equals(key)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
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
            case LEADER -> of("tae_hwi");
            case MENTOR -> of("gyeong_pil");
            case SENIOR -> of("seo_rang");
            case GATEKEEPER -> of("gatekeeper");
            case DISCIPLE_A -> of("disciple_a");
            case DISCIPLE_B -> of("disciple_b");
            default -> Optional.empty();
        };
    }

    /** Члены одного поколения в порядке списка (без привратника: он не в строю и не в парах). */
    public static List<SectRoster> generation(int generation) {
        List<SectRoster> out = new ArrayList<>();
        for (SectRoster m : ALL) {
            if (m.generation == generation && m.role != SectRole.GATEKEEPER && !m.role.lay()) {
                out.add(m);
            }
        }
        return out;
    }

    /** Ученик (второе или третье поколение, без привратника). */
    public boolean disciple() {
        return (generation == 2 || generation == 3) && role != SectRole.GATEKEEPER;
    }

    /** Мирянин при секте: слуга или управляющий. */
    public boolean lay() {
        return generation == LAY;
    }

    /** Ключ имени. */
    public String nameKey() {
        return "npc.murim." + key;
    }

    /** Ранг культивации NPC по поколению (шкала Realm): Юль — 1, Со — 2, Гён — 3, Тэ — 4. */
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
