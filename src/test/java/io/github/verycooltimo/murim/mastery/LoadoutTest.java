package io.github.verycooltimo.murim.mastery;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Раскладка техник по слотам (решение автора 01.10). */
class LoadoutTest {

    private static final ResourceLocation PALM = ResourceLocation.fromNamespaceAndPath("murim", "demon_palm");

    @Test
    @DisplayName("Слотов больше с прогрессией, но не больше восьми")
    void slotsGrow() {
        assertEquals(2, Loadout.slotsFor(0.0D));
        assertTrue(Loadout.slotsFor(12.0D) > Loadout.slotsFor(4.0D));
        assertEquals(Loadout.MAX_SLOTS, Loadout.slotsFor(1000.0D));
    }

    @Test
    @DisplayName("Одна техника — один слот: при переносе старый слот пустеет")
    void oneTechniqueOneSlot() {
        Loadout loadout = Loadout.EMPTY.with(0, Optional.of(PALM)).with(3, Optional.of(PALM));
        assertEquals(Optional.empty(), loadout.at(0));
        assertEquals(Optional.of(PALM), loadout.at(3));
    }

    @Test
    @DisplayName("Выбранный слот даёт свою технику")
    void activeTechnique() {
        Loadout loadout = Loadout.EMPTY.with(1, Optional.of(PALM)).select(1);
        assertEquals(Optional.of(PALM), loadout.activeTechnique());
    }
}
