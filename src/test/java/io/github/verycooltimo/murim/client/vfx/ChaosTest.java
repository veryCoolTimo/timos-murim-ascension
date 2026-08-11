package io.github.verycooltimo.murim.client.vfx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Источник беспорядка: детерминированность, диапазоны и отсутствие регулярности. */
class ChaosTest {

    @Test
    @DisplayName("Одна и та же прядь всегда получает одно значение")
    void deterministic() {
        // Иначе эффект дрожал бы от кадра к кадру, и дрожание зависело бы
        // от частоты кадров — на быстрой машине иначе, чем на медленной.
        for (int i = 0; i < 20; i++) {
            assertEquals(Chaos.unit(i, 42L), Chaos.unit(i, 42L), 0.0F);
        }
    }

    @Test
    @DisplayName("Соседние пряди не похожи друг на друга")
    void neighboursDiffer() {
        // Регулярность соседей — это и есть симметрия, от которой мы уходим.
        for (int i = 0; i < 50; i++) {
            assertNotEquals(Chaos.unit(i, 7L), Chaos.unit(i + 1, 7L),
                    "прядь " + i + " повторяет соседнюю");
        }
    }

    @Test
    @DisplayName("Значения лежат в единичном отрезке и покрывают его")
    void unitRangeCovered() {
        int low = 0;
        int high = 0;
        for (int i = 0; i < 500; i++) {
            float value = Chaos.unit(i, 3L);
            assertTrue(value >= 0.0F && value < 1.0F, "вне диапазона: " + value);
            if (value < 0.5F) {
                low++;
            } else {
                high++;
            }
        }
        // Перекос сильнее чем два к одному означал бы плохое перемешивание.
        assertTrue(low > 150 && high > 150, "распределение перекошено: " + low + " и " + high);
    }

    @Test
    @DisplayName("Часть прядей крутится встречно, но меньшая")
    void spinMixed() {
        int reverse = 0;
        for (int i = 0; i < 200; i++) {
            if (Chaos.spin(i, 11L, 0.3F) < 0.0F) {
                reverse++;
            }
        }
        // Однонаправленные витки читаются как вихрь, поровну — как каша.
        assertTrue(reverse > 30 && reverse < 100, "обратных прядей " + reverse + " из 200");
    }

    @Test
    @DisplayName("Профиль ширины асимметричен: пик смещён от середины")
    void widthProfileAsymmetric() {
        float peak = 0.82F;
        float atPeak = Chaos.widthProfile(peak, peak);
        assertTrue(atPeak > Chaos.widthProfile(0.5F, peak),
                "пик обязан быть не в середине, иначе получается веретено");
        assertEquals(0.0F, Chaos.widthProfile(0.0F, peak), 1.0E-6F);
        assertEquals(0.0F, Chaos.widthProfile(1.0F, peak), 1.0E-6F);
        // Подъём короче спада: у живого следа длинный тонкий хвост.
        float before = Chaos.widthProfile(peak - 0.3F, peak);
        float after = Chaos.widthProfile(peak + 0.15F, peak);
        assertTrue(before > after, "спад должен быть положе подъёма");
    }

    @Test
    @DisplayName("Плотность растёт к средоточию, но без регулярного сгущения")
    void densityBiasedToCentre() {
        int count = 12;
        int nearCentre = 0;
        for (int i = 0; i < count; i++) {
            if (Chaos.densityBias(i, count, 5L) < 0.5F) {
                nearCentre++;
            }
        }
        // Больше половины прядей ближе к средоточию — это и просил автор:
        // чем ближе к ладони, тем больше линий.
        assertTrue(nearCentre > count / 2, "к средоточию тяготеет лишь " + nearCentre);
    }
}
