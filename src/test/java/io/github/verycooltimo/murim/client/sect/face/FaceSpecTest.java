package io.github.verycooltimo.murim.client.sect.face;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pick of a person's face parts (docs/design/58-face-constructor.md): stable, spread, and equal to the python tool. */
class FaceSpecTest {

    private static FaceSpec pick(String key, int salt) {
        return FaceSpec.of(key, salt, 4, 3, 3, 4);
    }

    @Test
    @DisplayName("Один ключ - одно лицо: выбор не зависит от сессии и порядка вызовов")
    void sameKeySameFace() {
        for (String key : List.of("ham_doyun", "yul_jin", "seo_rang", "")) {
            assertEquals(pick(key, 0), pick(key, 0));
        }
    }

    @Test
    @DisplayName("Выбор совпадает с tools/art/face_parts.py pick (проверено на живых ключах секты)")
    void matchesPythonTool() {
        // python3 tools/art/face_parts.py pick ... : (eyes, brows, nose, mouth), zero-based
        assertEquals(new FaceSpec(3, 0, 2, 2), pick("disciple_a", 0));
        assertEquals(new FaceSpec(0, 1, 0, 0), pick("yul_jin", 0));
        assertEquals(new FaceSpec(1, 0, 0, 3), pick("yul_rim", 0));
        assertEquals(new FaceSpec(2, 1, 2, 1), pick("yul_bin", 0));
        assertEquals(new FaceSpec(2, 0, 1, 0), pick("yul_san", 0));
        assertEquals(new FaceSpec(0, 2, 1, 2), pick("yul_ak", 0));
    }

    @Test
    @DisplayName("Шесть людей стенда лиц различаются по всем четырём частям")
    void faceStandKeysAreDistinct() {
        List<String> keys = List.of("disciple_a", "yul_jin", "yul_rim", "yul_bin", "yul_san", "yul_ak");
        Set<FaceSpec> all = new HashSet<>();
        Set<Integer> eyes = new HashSet<>();
        Set<Integer> mouths = new HashSet<>();
        for (String k : keys) {
            FaceSpec s = pick(k, 0);
            all.add(s);
            eyes.add(s.eyes());
            mouths.add(s.mouth());
        }
        assertEquals(6, all.size());
        assertEquals(4, eyes.size());
        assertEquals(4, mouths.size());
    }

    @Test
    @DisplayName("Соль перебрасывает лицо (команда cycle), индексы всегда в пределах рядов")
    void saltRerolls() {
        assertNotEquals(pick("yul_jin", 0), pick("yul_jin", 1));
        Set<FaceSpec> seen = new HashSet<>();
        for (int salt = 0; salt < 200; salt++) {
            FaceSpec s = pick("ham_doyun", salt);
            assertTrue(s.eyes() >= 0 && s.eyes() < 4 && s.brows() >= 0 && s.brows() < 3 && s.nose() >= 0 && s.nose() < 3
                    && s.mouth() >= 0 && s.mouth() < 4, s.toString());
            seen.add(s);
        }
        // 144 combinations exist; 200 rolls must reach many of them (a stuck hash would give a handful).
        assertTrue(seen.size() > 80, "only " + seen.size() + " distinct faces in 200 rolls");
    }

    @Test
    @DisplayName("Ряд части пустой или один вариант - не падает, индекс ноль")
    void degenerateCounts() {
        FaceSpec s = FaceSpec.of("x", 0, 0, 1, 1, 1);
        assertEquals(new FaceSpec(0, 0, 0, 0), s);
        assertEquals("e0b0n0m0", s.code());
    }

    @Test
    @DisplayName("29 человек секты получают лица, и лица не сливаются в одно")
    void rosterSpread() {
        Set<FaceSpec> seen = new HashSet<>();
        for (String k : List.of("ham_doyun", "bok_manseok", "disciple_a", "disciple_b", "yul_jin", "yul_seok", "yul_pyo", "yul_il", "yul_yeon",
                "yul_gwang", "yul_hae", "yul_do", "yul_rim", "yul_su", "yul_bin", "yul_san", "yul_ak", "seo_rang", "mok_hayeon", "seo_gyu",
                "seo_ho", "seo_mu", "seo_ryeong", "seo_gi", "seo_jin", "gyeong_on", "gyeong_dan", "gyeong_ha", "gyeong_ryun")) {
            seen.add(pick(k, 0));
        }
        // 29 people, 144 combinations: expect a couple of collisions at most.
        assertTrue(seen.size() >= 25, "only " + seen.size() + " distinct faces among 29 people");
    }
}
