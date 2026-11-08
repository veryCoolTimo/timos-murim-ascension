package io.github.verycooltimo.murim.mastery;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.combat.TechniqueState;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlumFormMigrationTest {
    @Test void oldFormSaveKeepsAllProgressAndDoesNotGrantUnlearnedForms() {
        MasteryState state = MasteryState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"techniques":{"murim:twenty_four_plum_dome":
                 {"version":5,"stars":8,"progress":17,"unprocessed":9,"day":4,"cap":12,"help":3}},
                 "wisdom":6,"comprehended":["murim:twenty_four_plum_dome"],"fired":["murim:twenty_four_plum#1"]}
                """)).getOrThrow();
        assertEquals(Set.of(PlumFormMigration.BLOOM), state.techniques().keySet());
        assertEquals(new TechniqueProgress(8, 17, 9, 4, 12, TechniqueProgress.VERSION, 3), state.progress(PlumFormMigration.BLOOM));
        assertEquals(Set.of(ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum")), state.comprehended());
        assertEquals(6, state.wisdom());
        assertTrue(state.hasFired(PlumFormMigration.BLOOM, 1));
        assertEquals(state, MasteryState.CODEC.parse(JsonOps.INSTANCE,
                MasteryState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow()).getOrThrow());
        assertFalse(MasteryState.EMPTY.knows(PlumFormMigration.BLOOM));
    }

    @Test void oldAndNewIdsDoNotDoubleMastery() {
        var low = new TechniqueProgress(4, 5, 1, 0, 12, 5, 0);
        var high = new TechniqueProgress(8, 17, 9, 4, 12, 5, 3);
        var state = new MasteryState(Map.of(PlumFormMigration.DOME, high, PlumFormMigration.BLOOM, low), 0, Set.of());
        assertEquals(Map.of(PlumFormMigration.BLOOM,
                new TechniqueProgress(8, 17, 9, 4, 12, TechniqueProgress.VERSION, 3)), state.techniques());
    }

    @Test void retiredCastStopsButKeepsCooldownTimestamp() {
        var state = TechniqueState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"technique":"murim:twenty_four_plum_dome","tick":23,"impact_done":false,"last_start":12345}
                """)).getOrThrow();
        assertFalse(state.isActive());
        assertEquals(12345, state.lastStartGameTime());
    }
}
