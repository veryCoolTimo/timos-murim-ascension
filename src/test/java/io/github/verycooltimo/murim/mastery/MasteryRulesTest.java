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
        assertTrue(total > afterFight.progress() * 3.0D, "осмысленное должно дать заметно больше");
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
}
