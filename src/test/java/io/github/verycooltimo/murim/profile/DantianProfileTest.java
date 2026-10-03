package io.github.verycooltimo.murim.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Профиль даньтяня: арифметика ци и границы значений. */
class DantianProfileTest {

    @Test
    @DisplayName("Новичок не пробуждён: техники до ритуала недоступны")
    void initialNotAwakened() {
        assertFalse(DantianProfile.INITIAL.isAwakened());
    }

    @Test
    @DisplayName("Циркулирующая ци не превышает ёмкости центра")
    void circulatingClamped() {
        DantianProfile profile = DantianProfile.INITIAL.withAxes(20.0D, 0.5D, 0.5D);
        DantianProfile filled = profile.withCirculating(9999.0D);
        assertEquals(profile.maxCirculating(), filled.circulating(), 1.0E-9D,
                "переполнение центра дало бы бесконечную ци");
    }

    @Test
    @DisplayName("Циркулирующая ци не уходит в минус")
    void circulatingNotNegative() {
        assertEquals(0.0D, DantianProfile.INITIAL.withCirculating(-50.0D).circulating(), 1.0E-9D);
    }

    @Test
    @DisplayName("Чистая энергия плотнее: тот же центр вмещает её больше")
    void purityRaisesCapacity() {
        DantianProfile dirty = DantianProfile.INITIAL.withAxes(20.0D, 0.0D, 0.5D);
        DantianProfile clean = DantianProfile.INITIAL.withAxes(20.0D, 1.0D, 0.5D);
        assertTrue(clean.maxCirculating() > dirty.maxCirculating(),
                "иначе размен «чистота стоит скорости» ничем не окупается");
    }

    @Test
    @DisplayName("Забитые каналы восстанавливают медленнее, сколько бы ни было в запасе")
    void meridiansGateRecovery() {
        DantianProfile blocked = DantianProfile.INITIAL.withAxes(20.0D, 0.5D, 0.0D);
        DantianProfile open = DantianProfile.INITIAL.withAxes(20.0D, 0.5D, 1.0D);
        assertTrue(open.circulationRate() > blocked.circulationRate());
    }

    @Test
    @DisplayName("Оси и фундамент зажимаются в свои границы, а не уходят за них")
    void axesClamped() {
        DantianProfile over = DantianProfile.INITIAL.withAxes(20.0D, 5.0D, -3.0D);
        assertAll(
                () -> assertEquals(1.0D, over.purity(), 1.0E-9D),
                () -> assertEquals(0.0D, over.meridians(), 1.0E-9D),
                () -> assertEquals(0.0D, DantianProfile.INITIAL.withFoundation(-1.0D).foundation(), 1.0E-9D),
                () -> assertEquals(1.0D, DantianProfile.INITIAL.withFoundation(9.0D).foundation(), 1.0E-9D));
    }

    @Test
    @DisplayName("Мусор из сохранения отвергается, включая NaN")
    void invalidRejected() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DantianProfile(0.0D, 0.5D, 0.5D, "a", "b", 0, 0, 1, 0, 0),
                        "нулевая ёмкость"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DantianProfile(10.0D, Double.NaN, 0.5D, "a", "b", 0, 0, 1, 0, 0),
                        "NaN в чистоте"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DantianProfile(10.0D, 0.5D, 0.5D, "a", "b", -1, 0, 1, 0, 0),
                        "отрицательный запас"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DantianProfile(10.0D, 0.5D, 0.5D, "a", "b", 0, 0, 1, -1, 0),
                        "отрицательный ранг"));
    }

    @Test
    @DisplayName("Сохранение без подступени (до 03.10) читается как начальная")
    void oldSaveMigratesToInitialStage() {
        com.google.gson.JsonObject old = new com.google.gson.JsonObject();
        old.addProperty("capacity", 30.0D);
        old.addProperty("purity", 0.5D);
        old.addProperty("meridians", 0.5D);
        old.addProperty("nature", "fire");
        old.addProperty("imprint", "plum");
        old.addProperty("pool", 10.0D);
        old.addProperty("circulating", 5.0D);
        old.addProperty("foundation", 1.0D);
        old.addProperty("rank", 4);
        DantianProfile read = DantianProfile.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, old)
                .getOrThrow();
        assertAll(
                () -> assertEquals(4, read.rank()),
                () -> assertEquals(0, read.stage(), "старый Пик — начальная подступень"));
    }

    @Test
    @DisplayName("Подступень переживает запись и чтение")
    void stageRoundTrip() {
        DantianProfile peak = DantianProfile.INITIAL.withTags("fire", "plum").withRank(4).withStage(1);
        com.google.gson.JsonElement json = DantianProfile.CODEC
                .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, peak).getOrThrow();
        assertEquals(peak, DantianProfile.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, json).getOrThrow());
    }

    @Test
    @DisplayName("Подступень есть только на Пике и сбрасывается сменой ранга")
    void stageOnlyAtPeak() {
        DantianProfile peak = DantianProfile.INITIAL.withRank(4);
        assertAll(
                () -> assertEquals(0, DantianProfile.INITIAL.withRank(3).withStage(2).stage(), "ниже Пика"),
                () -> assertEquals(2, peak.withStage(2).stage()),
                () -> assertEquals(2, peak.withStage(9).stage(), "выше вершины не бывает"),
                () -> assertEquals(2, peak.withStage(2).withRank(4).stage(), "тот же ранг — подступень та же"),
                () -> assertEquals(0, peak.withStage(2).withRank(5).stage(), "новый ранг — с начальной"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DantianProfile(10.0D, 0.5D, 0.5D, "a", "b", 0, 0, 1, 4, 3)));
    }
}
