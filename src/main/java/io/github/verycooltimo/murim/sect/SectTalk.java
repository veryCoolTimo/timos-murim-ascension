package io.github.verycooltimo.murim.sect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Личные реплики учеников (задача «Личные диалоги жителей горы», 05.10): у каждого ученика черта характера по ключу,
 * личная фраза по имени и слухи о других людях горы. Диалоги роли (disciple.json, second.json) ссылаются на них
 * особыми строками в {@code random}: {@code @trait}, {@code @personal}, {@code @rumour} — {@link DialogueService}
 * подставляет реплику при показе узла.
 *
 * <p>Чистые функции: выбор зависит от ключа говорящего, дня секты, броска кубика и {@link Context}, а не от мира.
 * Аргументы реплики — {@code npc:<ключ>} (имя человека), {@code player} (имя игрока), {@code name:<строка>}
 * (имя как есть) или число.
 */
public final class SectTalk {

    /** Особые строки в {@code random} узла. */
    public static final String TRAIT = "@trait";
    public static final String PERSONAL = "@personal";
    public static final String RUMOUR = "@rumour";

    /** Флаг игрока: выиграл смотр учеников хотя бы раз ({@link SectReview}). */
    public static final String REVIEW_WON = "review.won";

    /** Реплик на черту (без реплики о победе игрока на смотре). */
    public static final int TRAIT_LINES = 5;

    /** Черта характера ученика. */
    public enum Trait {
        LAZY, EAGER, HOMESICK, GOSSIP, RIVAL, ADMIRER, TIMID, GLUTTON;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Реплика {@code i} (1…{@link #TRAIT_LINES}). */
        public String line(int i) {
            return "dialogue.murim.trait." + id() + "." + i;
        }

        /** Реплика после того, как игрок выиграл смотр. */
        public String wonLine() {
            return "dialogue.murim.trait." + id() + ".won";
        }

        /** Слух о человеке с этой чертой (имя — аргумент). */
        public String rumourLine() {
            return "dialogue.murim.rumour.trait." + id();
        }
    }

    /** Слухи без черты: ключ {@code dialogue.murim.rumour.<id>}. */
    public static final List<String> RUMOURS = List.of("guard_night", "guard_me", "guard_day", "slept", "review_winner",
            "review_me", "review_you", "review_soon", "review_today", "skipped", "you_skipped");

    /**
     * Что говорящий знает о мире.
     *
     * @param champion       победитель последнего смотра: ключ ученика, {@code @<имя игрока>} или пусто
     * @param playerName     имя собеседника (для {@code champion} вида {@code @имя})
     * @param playerWon      собеседник выигрывал смотр
     * @param playerSkipped  собеседник пропустил сегодняшний строй
     * @param reviewDays     дней до ближайшего смотра
     */
    public record Context(String champion, String playerName, boolean playerWon, boolean playerSkipped, long reviewDays) {
    }

    /** Реплика: lang-ключ и аргументы. */
    public record Line(String key, List<Object> args) {
    }

    /** Ученицы: о них не говорят репликами, где в русском нужен род. */
    static final java.util.Set<String> FEMALE = java.util.Set.of("mok_hayeon");

    private SectTalk() {
    }

    /**
     * Черта по ключу: детерминированно (хеш строки задан JLS, одинаков на любой JVM; перемешивание Фибоначчи — старшие
     * три бита). Соль подобрана так, чтобы у учеников без своего диалога встретились все восемь черт и ни одной больше
     * трёх раз (тест {@code SectTalkTest}).
     */
    public static Trait trait(String key) {
        // Хеш от прежнего ключа (SectRoster.seed): после переименования 06.10 у человека та же черта, что была.
        int h = (SALT + ":" + SectRoster.seed(key)).hashCode() * 0x9E3779B1;
        return Trait.values()[h >>> 29];
    }

    static final String SALT = "s21";

    /** Личная фраза человека. */
    public static String personalKey(String key) {
        return "dialogue.murim.person." + key;
    }

    /** Все ключи, которые может выдать {@link #line} (для проверки lang-файлов), кроме личных фраз. */
    public static List<String> allKeys() {
        List<String> out = new ArrayList<>();
        for (Trait t : Trait.values()) {
            for (int i = 1; i <= TRAIT_LINES; i++) {
                out.add(t.line(i));
            }
            out.add(t.wonLine());
            out.add(t.rumourLine());
        }
        for (String r : RUMOURS) {
            out.add("dialogue.murim.rumour." + r);
        }
        return out;
    }

    /**
     * Реплика по особой строке.
     *
     * @param token   {@link #TRAIT}, {@link #PERSONAL} или {@link #RUMOUR}
     * @param speaker ключ говорящего (пусто — человек без имени: только черта)
     * @param day     день секты ({@link SectSchedule#day})
     * @param night   сейчас ночь (кто стоит на посту — ночная смена)
     * @param roll    случайное неотрицательное число
     */
    public static Line line(String token, String speaker, long day, boolean night, int roll, Context ctx) {
        int r = Math.abs(roll);
        boolean known = SectRoster.of(speaker).map(SectRoster::disciple).orElse(false);
        if (PERSONAL.equals(token) && known) {
            return new Line(personalKey(speaker), List.of());
        }
        if (RUMOUR.equals(token) && known) {
            List<Line> pool = rumours(speaker, day, night, ctx);
            if (!pool.isEmpty()) {
                return pool.get(r % pool.size());
            }
        }
        Trait t = trait(speaker);
        if (ctx.playerWon() && r % 4 == 0) {
            return new Line(t.wonLine(), List.of("player"));
        }
        return new Line(t.line(1 + r % TRAIT_LINES), List.of());
    }

    /** Слухи, которые говорящий может пересказать сегодня (о себе — другими словами). */
    static List<Line> rumours(String speaker, long day, boolean night, Context ctx) {
        List<Line> out = new ArrayList<>();
        // Кто на посту: ночью — ночная смена, днём — сегодняшняя ночная (кто заступит вечером).
        SectRoster n0 = SectRota.nightWatch(day, 0);
        SectRoster n1 = SectRota.nightWatch(day, 1);
        if (speaker.equals(n0.key()) || speaker.equals(n1.key())) {
            out.add(new Line("dialogue.murim.rumour.guard_me", List.of(npc(speaker.equals(n0.key()) ? n1 : n0))));
        } else {
            out.add(new Line("dialogue.murim.rumour.guard_night", List.of(npc(n0), npc(n1))));
        }
        if (!night) {
            SectRoster d0 = SectRota.dayWatch(day, 0);
            SectRoster d1 = SectRota.dayWatch(day, 1);
            if (!speaker.equals(d0.key()) && !speaker.equals(d1.key())) {
                out.add(new Line("dialogue.murim.rumour.guard_day", List.of(npc(d0), npc(d1))));
            }
            SectRoster s0 = SectRota.nightWatch(day - 1L, 0);
            SectRoster s1 = SectRota.nightWatch(day - 1L, 1);
            if (!speaker.equals(s0.key()) && !speaker.equals(s1.key())) {
                out.add(new Line("dialogue.murim.rumour.slept", List.of(npc(s0), npc(s1))));
            }
        }
        // Смотр: кто выиграл последний; если ещё не было — сколько ждать.
        String champ = ctx.champion();
        if (champ.isEmpty()) {
            out.add(ctx.reviewDays() == 0 ? new Line("dialogue.murim.rumour.review_today", List.of())
                    : new Line("dialogue.murim.rumour.review_soon", List.of(ctx.reviewDays())));
        } else if (champ.startsWith("@")) {
            String name = champ.substring(1);
            out.add(name.equals(ctx.playerName()) ? new Line("dialogue.murim.rumour.review_you", List.of("player"))
                    : new Line("dialogue.murim.rumour.review_winner", List.of("name:" + name)));
        } else if (champ.equals(speaker)) {
            out.add(new Line("dialogue.murim.rumour.review_me", List.of()));
        } else if (SectRoster.of(champ).isPresent()) {
            out.add(new Line("dialogue.murim.rumour.review_winner", List.of("npc:" + champ)));
        }
        // Проспал строй: игрок (если пропустил — об этом говорят все) или кто-то из третьего поколения.
        if (ctx.playerSkipped()) {
            out.add(new Line("dialogue.murim.rumour.you_skipped", List.of("player")));
            out.add(new Line("dialogue.murim.rumour.you_skipped", List.of("player")));
        }
        SectRoster late = other(SectRoster.generation(3), speaker, day * 7L + 3L);
        if (late != null) {
            out.add(new Line("dialogue.murim.rumour.skipped", List.of(npc(late))));
        }
        // Пересуды о характере другого ученика.
        // Мок Хаён в пересуды не попадает: в русских репликах о ней нужен женский род.
        List<SectRoster> all = new ArrayList<>(SectRoster.generation(2));
        all.addAll(SectRoster.generation(3));
        all.removeIf(m -> FEMALE.contains(m.key()));
        SectRoster who = other(all, speaker, day * 13L + speaker.length() * 5L);
        if (who != null) {
            out.add(new Line(trait(who.key()).rumourLine(), List.of(npc(who))));
        }
        return out;
    }

    /** Человек из списка по смещению, не сам говорящий. */
    private static SectRoster other(List<SectRoster> list, String speaker, long offset) {
        if (list.isEmpty()) {
            return null;
        }
        SectRoster m = list.get((int) Math.floorMod(offset, (long) list.size()));
        if (m.key().equals(speaker)) {
            m = list.size() > 1 ? list.get((int) Math.floorMod(offset + 1L, (long) list.size())) : null;
        }
        return m;
    }

    private static String npc(SectRoster m) {
        return "npc:" + m.key();
    }
}
