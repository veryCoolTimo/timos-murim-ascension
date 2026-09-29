package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.profile.DantianProfile;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Три такта создания даньтяня и цена смены метода (docs/design/19-dantian-qi-meditation.md). */
class SeedLogicTest {

    private static CultivationMethod method(String path, String nature, double purity, double impurity) {
        return new CultivationMethod(ResourceLocation.fromNamespaceAndPath("murim", path), nature,
                purity, 30.0D, impurity, "route", List.of(), false);
    }

    private static final CultivationMethod SECT = method("six_harmonies", "pure", 0.9D, 0.1D);
    private static final CultivationMethod STREET = method("street_strength", "pure", 0.55D, 0.7D);
    private static final CultivationMethod CULT = method("holy_fire_breath", "demonic", 0.6D, 0.5D);

    @Test
    @DisplayName("Без метода практиковать нечего")
    void noMethod() {
        assertEquals(SeedLogic.Outcome.NO_METHOD,
                SeedLogic.finishSession(CultivationState.NONE, true).outcome());
    }

    @Test
    @DisplayName("Три такта: ощущение, удержание, семя")
    void threeBeats() {
        CultivationState state = CultivationState.NONE.withMethod(SECT.id());
        SeedLogic.SessionResult first = SeedLogic.finishSession(state, false);
        assertEquals(SeedLogic.Outcome.FIRST_FEELING, first.outcome());
        SeedLogic.SessionResult second = SeedLogic.finishSession(first.state(), true);
        assertEquals(SeedLogic.Outcome.HELD, second.outcome());
        SeedLogic.SessionResult third = SeedLogic.finishSession(second.state(), false);
        assertEquals(SeedLogic.Outcome.SEED, third.outcome());
        assertTrue(third.state().seeded());
        assertEquals(SeedLogic.Outcome.ALREADY_SEEDED,
                SeedLogic.finishSession(third.state(), false).outcome());
    }

    @Test
    @DisplayName("Не удержал кольцо — второй такт не засчитан")
    void slippedRing() {
        CultivationState afterFirst = CultivationState.NONE.withMethod(SECT.id()).withBeats(1);
        SeedLogic.SessionResult result = SeedLogic.finishSession(afterFirst, false);
        assertEquals(SeedLogic.Outcome.SLIPPED, result.outcome());
        assertEquals(1, result.state().beats());
    }

    @Test
    @DisplayName("Семя берёт природу и ёмкость метода")
    void seedTakesMethod() {
        DantianProfile seeded = SeedLogic.seedProfile(DantianProfile.INITIAL, CULT, 1.0D);
        assertEquals("demonic", seeded.nature());
        assertEquals(30.0D, seeded.capacity(), 1.0E-9D);
        assertEquals(CULT.purity(), seeded.purity(), 1.0E-9D, "всё отсеяно — чистота метода");
        assertTrue(seeded.isAwakened());
    }

    @Test
    @DisplayName("Неотсеянные примеси мутят уличный метод сильнее сектового")
    void impuritiesHurtStreetMore() {
        double sectLoss = SECT.purity() - SeedLogic.seedProfile(DantianProfile.INITIAL, SECT, 0.0D).purity();
        double streetLoss = STREET.purity() - SeedLogic.seedProfile(DantianProfile.INITIAL, STREET, 0.0D).purity();
        assertTrue(streetLoss > sectLoss * 3.0D,
                "у метода с большой долей примесей отказ от отсева должен стоить заметно дороже");
    }

    @Test
    @DisplayName("До семени другой метод заменяет прежний, практика с нуля")
    void replaceBeforeSeed() {
        CultivationState practising = CultivationState.NONE.withMethod(SECT.id()).withBeats(2);
        SeedLogic.LearnResult result = SeedLogic.learn(practising, DantianProfile.INITIAL, STREET, SECT);
        assertEquals(SeedLogic.Change.REPLACED_BEFORE_SEED, result.change());
        assertEquals(0, result.state().beats());
    }

    @Test
    @DisplayName("После семени та же природа — часть запаса уходит")
    void sameNatureKeepsPart() {
        DantianProfile profile = SeedLogic.seedProfile(DantianProfile.INITIAL, SECT, 1.0D).withPool(100.0D);
        CultivationState seeded = CultivationState.NONE.withMethod(SECT.id()).withBeats(CultivationState.SEEDED);
        SeedLogic.LearnResult result = SeedLogic.learn(seeded, profile, STREET, SECT);
        assertEquals(SeedLogic.Change.SAME_NATURE, result.change());
        assertEquals(100.0D * SeedLogic.SAME_NATURE_KEEP, result.profile().pool(), 1.0E-9D);
        assertEquals("pure", result.profile().nature());
    }

    @Test
    @DisplayName("После семени другая природа — накопленное обнуляется")
    void otherNatureResets() {
        DantianProfile profile = SeedLogic.seedProfile(DantianProfile.INITIAL, SECT, 1.0D)
                .withPool(100.0D).withCirculating(10.0D);
        CultivationState seeded = CultivationState.NONE.withMethod(SECT.id()).withBeats(CultivationState.SEEDED);
        SeedLogic.LearnResult result = SeedLogic.learn(seeded, profile, CULT, SECT);
        assertEquals(SeedLogic.Change.OTHER_NATURE, result.change());
        assertEquals(0.0D, result.profile().pool(), 1.0E-9D);
        assertEquals(0.0D, result.profile().circulating(), 1.0E-9D);
        assertEquals("demonic", result.profile().nature());
    }

    @Test
    @DisplayName("Повторное изучение того же метода ничего не меняет")
    void sameMethod() {
        CultivationState practising = CultivationState.NONE.withMethod(SECT.id()).withBeats(1);
        SeedLogic.LearnResult result = SeedLogic.learn(practising, DantianProfile.INITIAL, SECT, SECT);
        assertEquals(SeedLogic.Change.SAME, result.change());
        assertEquals(practising, result.state());
    }

    @Test
    @DisplayName("Опечатка в природе энергии отклоняется сразу")
    void unknownNature() {
        assertThrows(IllegalArgumentException.class, () -> method("x", "yangg", 0.5D, 0.5D));
    }

    @Test
    @DisplayName("Такт вне диапазона отклоняется")
    void beatsRange() {
        assertThrows(IllegalArgumentException.class,
                () -> CultivationState.NONE.withBeats(CultivationState.SEEDED + 1));
        assertFalse(CultivationState.NONE.seeded());
    }
}
