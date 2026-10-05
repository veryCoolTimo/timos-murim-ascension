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
    @DisplayName("Бой качает напрямую, остаток ждёт осмысления")
    void fightGrowsDirectly() {
        MasteryRules.Gain gain = MasteryRules.experience(fresh(5), MasteryRules.Source.FIGHT, 4.0D, 0.0D, 0L);
        assertEquals(2.0D, gain.progress().progress(), 1.0E-9D, "половина — сразу в освоение");
        assertEquals(2.0D, gain.progress().unprocessed(), 1.0E-9D, "половина — неосмысленное");
    }

    @Test
    @DisplayName("Тренировка качает, но меньше боя")
    void trainingIsWeaker() {
        double fight = MasteryRules.experience(fresh(5), MasteryRules.Source.FIGHT, 4.0D, 0.0D, 0L).progress().progress();
        double training = MasteryRules.experience(fresh(5), MasteryRules.Source.TRAINING, 4.0D, 0.0D, 0L).progress().progress();
        assertTrue(training > 0.0D && training < fight);
    }

    @Test
    @DisplayName("Медитация после боя выгоднее, чем только бой")
    void meditationPays() {
        TechniqueProgress afterFight = MasteryRules.experience(fresh(5), MasteryRules.Source.FIGHT, 4.0D, 0.0D, 0L).progress();
        TechniqueProgress p = afterFight;
        for (int i = 0; i < 200; i++) {
            p = MasteryRules.meditate(p, 0.0D, 0L).progress();
        }
        assertEquals(0.0D, p.unprocessed(), 1.0E-9D);
        double total = p.layer() > 0 ? MasteryRules.need(0) + p.progress() : p.progress();
        assertTrue(total > afterFight.progress() * 2.2D, "осмысленное должно дать заметно больше");
    }

    @Test
    @DisplayName("Неосмысленное остывает к следующему дню")
    void unprocessedCools() {
        TechniqueProgress p = MasteryRules.experience(fresh(5), MasteryRules.Source.FIGHT, 4.0D, 0.0D, 3L).progress();
        assertEquals(0.0D, MasteryRules.cool(p, 4L).unprocessed(), 1.0E-9D);
        assertEquals(p.unprocessed(), MasteryRules.cool(p, 3L).unprocessed(), 1.0E-9D);
    }

    @Test
    @DisplayName("Слои идут по порядку и упираются в предел манускрипта")
    void layersInOrderUpToCap() {
        MasteryRules.Gain gain = MasteryRules.experience(fresh(2), MasteryRules.Source.FIGHT, 1000.0D, 0.0D, 0L);
        assertEquals(List.of(1, 2), gain.layersReached());
        assertEquals(2, gain.progress().layer());
        assertTrue(gain.progress().atCap());
    }

    @Test
    @DisplayName("Мудрый проходит нижние слои простой техники сразу, крутую — нет")
    void wisdomSkipsBasicLayers() {
        assertEquals(0, MasteryRules.startLayer(TechniqueTier.BASIC, 5, 0.0D));
        assertEquals(2, MasteryRules.startLayer(TechniqueTier.BASIC, 5, 12.0D));
        assertEquals(4, MasteryRules.startLayer(TechniqueTier.BASIC, 5, 100.0D), "последний слой всегда своим трудом");
        assertEquals(0, MasteryRules.startLayer(TechniqueTier.SECRET, 10, 100.0D));
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
        double plain = MasteryRules.experience(fresh(5), MasteryRules.Source.FIGHT, 4.0D, 0.0D, 0L).progress().progress();
        double wise = MasteryRules.experience(fresh(5), MasteryRules.Source.FIGHT, 4.0D, 10.0D, 0L).progress().progress();
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
