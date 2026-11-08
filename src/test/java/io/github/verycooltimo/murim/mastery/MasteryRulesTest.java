package io.github.verycooltimo.murim.mastery;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Изучение, освоение и мудрость (docs/design/19 §3г, решения автора 30.09). */
class MasteryRulesTest {

    private static final ResourceLocation BASICS = ResourceLocation.fromNamespaceAndPath("murim", "plum_basics");

    private static TechniqueProgress fresh(int cap) {
        return TechniqueProgress.learned(0, cap);
    }

    @Test
    @DisplayName("Бой качает напрямую: 80 % сразу, 20 % уходят в резерв искусства")
    void fightGrowsDirectly() {
        MasteryRules.Gain gain = MasteryRules.experience(fresh(5), MasteryRules.Source.FIGHT, 4.0D, 0.0D);
        assertEquals(3.2D, gain.progress().progress(), 1.0E-9D, "80 % — сразу в освоение");
        assertEquals(0.8D, gain.progress().unprocessed(), 1.0E-9D, "20 % — резерв");
        assertEquals(0.8D, MasteryRules.DIRECT_SHARE, 1.0E-12D);
    }

    @Test
    @DisplayName("Тренировка качает, но меньше боя")
    void trainingIsWeaker() {
        double fight = MasteryRules.experience(fresh(5), MasteryRules.Source.FIGHT, 4.0D, 0.0D).progress().progress();
        double training = MasteryRules.experience(fresh(5), MasteryRules.Source.TRAINING, 4.0D, 0.0D).progress().progress();
        assertTrue(training > 0.0D && training < fight);
    }

    @Test
    @DisplayName("Эхо или сон обрабатывают весь резерв без множителя: сумма опыта та же, что заработана")
    void reserveIsProcessedWhole() {
        TechniqueProgress afterFight = MasteryRules.experience(fresh(5), MasteryRules.Source.FIGHT, 40.0D, 0.0D).progress();
        assertEquals(8.0D, afterFight.unprocessed(), 1.0E-9D);
        MasteryRules.Gain gain = MasteryRules.process(afterFight, 12);
        assertEquals(0.0D, gain.progress().unprocessed(), 1.0E-9D, "резерв обработан целиком");
        // Ничего не прибавлено сверх заработанного: 32 + 8 = 40 из 40 очков первой звезды.
        assertEquals(1, gain.progress().stars());
        assertEquals(0.0D, gain.progress().progress(), 1.0E-9D);
        TechniqueProgress partial = MasteryRules.experience(fresh(5), MasteryRules.Source.FIGHT, 10.0D, 0.0D).progress();
        TechniqueProgress done = MasteryRules.process(partial, 12).progress();
        assertEquals(10.0D, done.progress(), 1.0E-9D, "без множителя: что заработано, то и осмыслено");
        // Повторная обработка пустого резерва ничего не меняет.
        assertEquals(done, MasteryRules.process(done, 12).progress());
    }

    @Test
    @DisplayName("Резерв не сгорает: правила не знают ни дня, ни остывания, он ждёт сколько угодно")
    void reserveNeverBurns() {
        TechniqueProgress p = MasteryRules.experience(fresh(5), MasteryRules.Source.FIGHT, 4.0D, 0.0D).progress();
        for (int i = 0; i < 1000; i++) {
            p = MasteryRules.experience(p, MasteryRules.Source.TRAINING, 0.0D, 0.0D).progress();
        }
        assertEquals(0.8D, p.unprocessed(), 1.0E-9D);
        // Резерв копится от боя к бою и не теряется при росте звезды.
        TechniqueProgress big = MasteryRules.experience(fresh(5), MasteryRules.Source.FIGHT, 100.0D, 0.0D).progress();
        assertEquals(20.0D, big.unprocessed(), 1.0E-9D);
        assertTrue(big.stars() >= 1);
    }

    @Test
    @DisplayName("Слои идут по порядку и упираются в предел манускрипта")
    void layersInOrderUpToCap() {
        MasteryRules.Gain gain = MasteryRules.experience(fresh(2), MasteryRules.Source.FIGHT, 1000.0D, 0.0D);
        assertEquals(List.of(1, 2), gain.starsReached());
        assertEquals(2, gain.progress().stars());
        assertTrue(gain.progress().atCap());
    }

    @Test
    @DisplayName("Высота вершины даёт новому искусству звёзды, но не выше стены и не секретному (19.10, вместо мудрости)")
    void heightGivesStartingStars() {
        assertEquals(0, MasteryRules.startStars(TechniqueTier.BASIC, 5, 0, 12));
        assertEquals(2, MasteryRules.startStars(TechniqueTier.BASIC, 5, 2, 12));
        assertEquals(3, MasteryRules.startStars(TechniqueTier.ADVANCED, 12, 3, 12), "продвинутое тоже стартует с высоты");
        assertEquals(4, MasteryRules.startStars(TechniqueTier.BASIC, 5, 100, 12), "последний слой всегда своим трудом");
        assertEquals(0, MasteryRules.startStars(TechniqueTier.SECRET, 10, 100, 12));
        assertEquals(0, MasteryRules.startStars(TechniqueTier.BASIC, 12, -3, 12));
    }

    @Test
    @DisplayName("Мудрость больше не даёт старта: только широта, не больше +0,10")
    void wisdomIsOnlyBreadth() {
        assertEquals(1.0D, MasteryRules.wisdomFactor(0.0D), 1.0E-9D);
        assertEquals(1.05D, MasteryRules.wisdomFactor(1.0D), 1.0E-9D);
        assertEquals(1.10D, MasteryRules.wisdomFactor(2.0D), 1.0E-9D);
        assertEquals(1.10D, MasteryRules.wisdomFactor(52.0D), 1.0E-9D, "13 постигнутых искусств были ×3,6");
        assertEquals(1.10D, MasteryRules.wisdomFactor(1.0E9D), 1.0E-9D);
        assertEquals(1.0D, MasteryRules.wisdomFactor(-5.0D), 1.0E-9D);
    }

    @Test
    @DisplayName("Крутая техника постигается на трёх пятых слоёв")
    void comprehension() {
        assertTrue(MasteryRules.comprehends(TechniqueTier.ADVANCED, 6, 10));
        assertTrue(!MasteryRules.comprehends(TechniqueTier.ADVANCED, 5, 10));
        assertTrue(!MasteryRules.comprehends(TechniqueTier.BASIC, 5, 5));
    }

    @Test
    @DisplayName("Секретную технику не выучить без основ")
    void hardRequirement() {
        List<TechniqueRequirement> requires = List.of(new TechniqueRequirement(BASICS, 3));
        assertEquals(1, MasteryRules.missing(requires, Map.of()).size(), "основ нет вовсе");
        assertEquals(1, MasteryRules.missing(requires, Map.of(BASICS, 2)).size(), "основы не дотянуты");
        assertTrue(MasteryRules.missing(requires, Map.of(BASICS, 3)).isEmpty());
    }

    @Test
    @DisplayName("Мудрость ускоряет освоение")
    void wisdomSpeedsUp() {
        double plain = MasteryRules.experience(fresh(5), MasteryRules.Source.FIGHT, 4.0D, 0.0D).progress().progress();
        double wise = MasteryRules.experience(fresh(5), MasteryRules.Source.FIGHT, 4.0D, 10.0D).progress().progress();
        assertTrue(wise > plain);
    }

    @Test
    @DisplayName("Корявая техника дороже и слабее, обжитая — дешевле и сильнее")
    void layerChangesCostAndPower() {
        assertTrue(MasteryRules.costFactor(0, 5) > 1.0D && MasteryRules.powerFactor(0, 5) < 1.0D);
        assertTrue(MasteryRules.costFactor(5, 5) < 1.0D && MasteryRules.powerFactor(5, 5) > 1.0D);
        assertTrue(MasteryRules.powerFactor(2, 5) > MasteryRules.powerFactor(1, 5));
    }

    @Test
    @DisplayName("Без даньтяня техника не учится; после семени — учится (автор 03.10)")
    void noDantianNoLearning() {
        io.github.verycooltimo.murim.profile.DantianProfile none = io.github.verycooltimo.murim.profile.DantianProfile.INITIAL;
        assertTrue(!MasteryRules.canLearn(none), "новичок без даньтяня выучил технику из книги");
        assertTrue(!MasteryRules.canLearn(null), "нет профиля — нет изучения");
        assertTrue(MasteryRules.canLearn(none.withTags("orthodox", "murim:test_method")),
                "после семени техника должна учиться");
    }

    @Test
    @DisplayName("Сокровенные (24 Движения) учатся только с Пика; остальные — с любого ранга (автор 03.10)")
    void secretOnlyFromPeak() {
        io.github.verycooltimo.murim.profile.DantianProfile awake =
                io.github.verycooltimo.murim.profile.DantianProfile.INITIAL.withTags("orthodox", "murim:test_method");
        for (int rank = 0; rank < io.github.verycooltimo.murim.cultivation.Realm.PEAK; rank++) {
            assertTrue(!MasteryRules.rankAllows(awake.withRank(rank), TechniqueTier.SECRET),
                    "сокровенная выучилась на ранге " + rank);
            assertTrue(MasteryRules.rankAllows(awake.withRank(rank), TechniqueTier.ADVANCED));
            assertTrue(MasteryRules.rankAllows(awake.withRank(rank), TechniqueTier.BASIC));
        }
        assertTrue(MasteryRules.rankAllows(awake.withRank(io.github.verycooltimo.murim.cultivation.Realm.PEAK), TechniqueTier.SECRET));
        assertTrue(MasteryRules.rankAllows(awake.withRank(io.github.verycooltimo.murim.cultivation.Realm.VOID), TechniqueTier.SECRET));
        assertTrue(!MasteryRules.rankAllows(null, TechniqueTier.BASIC));
        assertEquals(io.github.verycooltimo.murim.cultivation.Realm.PEAK, MasteryRules.rankNeed(TechniqueTier.SECRET));
    }
}
