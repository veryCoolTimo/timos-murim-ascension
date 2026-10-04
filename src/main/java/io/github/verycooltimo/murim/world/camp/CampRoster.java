package io.github.verycooltimo.murim.world.camp;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Состав лагеря (docs/design/24-bandit-camp.md §2): 5–8 бандитов — главарь, мечники и лучники;
 * примерно каждый пятый (кроме главаря) с ци, но хотя бы один. Из зерна лагеря, поэтому после
 * возвращения банды состав тот же, а не новый бросок.
 */
public final class CampRoster {

    /** Доля лучников среди рядовых. */
    static final double ARCHER_SHARE = 0.35D;

    /** Доля бандитов с ци (автор: «каждому пятому»). */
    static final double QI_SHARE = 0.2D;

    /**
     * Место в составе.
     *
     * @param slot  номер места (он же ключ в состоянии лагеря)
     * @param post  индекс поста в {@link CampLayout#posts()}
     */
    public record Member(int slot, boolean archer, boolean qi, boolean chief, int post) {
    }

    public static List<Member> plan(CampLayout layout) {
        Random r = new Random(layout.seed() ^ 0x5DEECE66DL);
        int total = 5 + r.nextInt(4);
        int others = total - 1;
        int archers = Math.max(1, (int) Math.round(others * ARCHER_SHARE));
        int qi = 0;
        for (int i = 0; i < others; i++) {
            if (r.nextDouble() < QI_SHARE) {
                qi++;
            }
        }
        qi = Math.max(1, qi);
        List<Member> out = new ArrayList<>();
        // Пост 0 — главарь у своего шатра, 1 — вышка (первый лучник), дальше ворота, костёр, шатры.
        out.add(new Member(0, false, true, true, 0));
        int nextPost = 2;
        int posts = layout.posts().size();
        for (int i = 0; i < others; i++) {
            boolean archer = i < archers;
            // С ци — первые мечники и последний лучник: и рывок, и выстрел ци встречаются в одном лагере.
            boolean hasQi = archer ? qi >= 2 && i == archers - 1 : i - archers < (qi >= 2 ? qi - 1 : qi);
            int post = i == 0 ? 1 : Math.min(posts - 1, nextPost++);
            out.add(new Member(i + 1, archer, hasQi, false, post));
        }
        return out;
    }

    /** Сколько бандитов с ци среди рядовых. */
    public static long qiCount(List<Member> roster) {
        return roster.stream().filter(m -> m.qi() && !m.chief()).count();
    }

    private CampRoster() {
    }
}
