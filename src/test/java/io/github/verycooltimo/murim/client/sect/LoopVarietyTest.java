package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.entity.SectPose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Свой ритм в цикле позы (автор 06.10: «как роботы… как хор»). */
class LoopVarietyTest {

    private static long seed(int i) {
        return LoopVariety.seed(new UUID(0x1234L + i * 7919L, 0xABCDL * (i + 1)));
    }

    @Test
    @DisplayName("Восемь человек сели за стол в один тик — кадры клипа у всех разные, и через полминуты тоже")
    void tableIsNotAChoir() {
        float len = 4.0F;
        for (float sec : new float[] {0.0F, 7.3F, 31.0F, 600.0F}) {
            float[] clip = new float[8];
            for (int i = 0; i < 8; i++) {
                LoopVariety.Frame f = LoopVariety.frame(seed(i), SectPose.EAT, sec, len);
                // Сидящий в паузе не движется — с ним в такт не попасть; у него своё время.
                clip[i] = f.pause() > 0.0F ? -10.0F * (i + 1) : f.clip();
            }
            int close = 0;
            for (int i = 0; i < 8; i++) {
                for (int j = i + 1; j < 8; j++) {
                    if (Math.abs(clip[i] - clip[j]) < 0.15F) {
                        close++;
                    }
                }
            }
            // 28 пар; в одном кадре движения (в пределах 0,15 с) — единицы, как у случайных людей.
            assertTrue(close <= 3, "слишком много людей в одном кадре на " + sec + " с: " + close);
        }
    }

    @Test
    @DisplayName("Один и тот же человек — один и тот же ритм; темп в пределах ±13 %; время клипа в пределах длины")
    void deterministicAndBounded() {
        long s = seed(3);
        assertEquals(LoopVariety.frame(s, SectPose.EAT, 12.5F, 4.0F), LoopVariety.frame(s, SectPose.EAT, 12.5F, 4.0F));
        boolean paused = false;
        for (int i = 0; i < 200; i++) {
            float tempo = LoopVariety.tempo(seed(i));
            assertTrue(tempo >= 0.87F && tempo <= 1.13F);
            for (float sec = 0.0F; sec < 60.0F; sec += 0.37F) {
                LoopVariety.Frame f = LoopVariety.frame(seed(i), SectPose.EAT, sec, 4.0F);
                assertTrue(f.clip() >= 0.0F && f.clip() <= 4.0F);
                paused |= f.pause() > 0.5F;
            }
        }
        assertTrue(paused, "за столом бывают паузы между глотками");
    }

    @Test
    @DisplayName("Поза без пауз (медитация) идёт без остановок")
    void meditationHasNoGaps() {
        for (float sec = 0.0F; sec < 120.0F; sec += 0.5F) {
            assertEquals(0.0F, LoopVariety.frame(seed(1), SectPose.MEDITATE, sec, 4.0F).pause());
        }
    }
}
