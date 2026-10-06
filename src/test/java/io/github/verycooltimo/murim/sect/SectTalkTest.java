package io.github.verycooltimo.murim.sect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Черты, личные фразы и слухи учеников (SectTalk): детерминированность и разнообразие. */
class SectTalkTest {

    /** Ученики со своим диалогом: чертой не говорят. */
    private static final Set<String> OWN = Set.of("seo_rang", "mok_hayeon", "seo_gyu", "seo_ho", "ham_doyun", "bok_manseok");

    @Test
    @DisplayName("У учеников без своего диалога встречаются все восемь черт, ни одной больше трёх раз")
    void traitsSpread() {
        Map<SectTalk.Trait, Integer> count = new EnumMap<>(SectTalk.Trait.class);
        for (SectRoster m : SectRoster.ALL) {
            if (m.disciple() && !OWN.contains(m.key())) {
                count.merge(SectTalk.trait(m.key()), 1, Integer::sum);
            }
        }
        assertEquals(SectTalk.Trait.values().length, count.size(), count.toString());
        count.forEach((t, n) -> assertTrue(n <= 3, t + ": " + n));
        // Черта — функция ключа: одна и та же при каждом вызове.
        assertEquals(SectTalk.trait("yul_su"), SectTalk.trait("yul_su"));
    }

    @Test
    @DisplayName("Слухи: дежурные из ротации, победитель смотра, пропуск строя; игрок-победитель узнаёт себя")
    void rumours() {
        SectTalk.Context none = new SectTalk.Context("", "Tester", false, false, 4);
        List<SectTalk.Line> pool = SectTalk.rumours("yul_jin", 3, false, none);
        Set<String> keys = new HashSet<>();
        pool.forEach(l -> keys.add(l.key()));
        assertTrue(keys.contains("dialogue.murim.rumour.guard_night"), keys.toString());
        assertTrue(keys.contains("dialogue.murim.rumour.review_soon"), keys.toString());
        assertTrue(keys.contains("dialogue.murim.rumour.skipped"), keys.toString());
        SectTalk.Line night = pool.stream().filter(l -> l.key().endsWith("guard_night")).findFirst().orElseThrow();
        assertEquals(List.of("npc:" + SectRota.nightWatch(3, 0).key(), "npc:" + SectRota.nightWatch(3, 1).key()), night.args());

        // Ночной дежурный говорит о своей смене иначе.
        String watcher = SectRota.nightWatch(3, 0).key();
        assertTrue(SectTalk.rumours(watcher, 3, true, none).stream().anyMatch(l -> l.key().endsWith("guard_me")));

        SectTalk.Context won = new SectTalk.Context("@Tester", "Tester", true, true, 0);
        Set<String> wonKeys = new HashSet<>();
        SectTalk.rumours("yul_jin", 3, false, won).forEach(l -> wonKeys.add(l.key()));
        assertTrue(wonKeys.contains("dialogue.murim.rumour.review_you"), wonKeys.toString());
        assertTrue(wonKeys.contains("dialogue.murim.rumour.you_skipped"), wonKeys.toString());

        SectTalk.Context npcWon = new SectTalk.Context("yul_jin", "Tester", false, false, 0);
        assertTrue(SectTalk.rumours("yul_jin", 3, false, npcWon).stream().anyMatch(l -> l.key().endsWith("review_me")));
        assertTrue(SectTalk.rumours("yul_su", 3, false, npcWon).stream()
                .anyMatch(l -> l.key().endsWith("review_winner") && l.args().equals(List.of("npc:yul_jin"))));
    }

    @Test
    @DisplayName("Личная фраза — по имени; черта после победы игрока иногда говорит о ней")
    void personalAndWon() {
        SectTalk.Context ctx = new SectTalk.Context("", "Tester", true, false, 1);
        assertEquals("dialogue.murim.person.yul_ak", SectTalk.line(SectTalk.PERSONAL, "yul_ak", 0, false, 7, ctx).key());
        assertEquals(SectTalk.trait("yul_ak").wonLine(), SectTalk.line(SectTalk.TRAIT, "yul_ak", 0, false, 4, ctx).key());
        // Человек не из списка (старый NPC без ключа) — только черта, без личной фразы и слухов.
        assertTrue(SectTalk.line(SectTalk.RUMOUR, "", 0, false, 1, ctx).key().startsWith("dialogue.murim.trait."));
    }
}
