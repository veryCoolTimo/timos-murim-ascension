package io.github.verycooltimo.murim.mastery;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.combat.TechniqueState;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlumFormMigrationTest {
    private static final String PROGRESS = "{\"version\":5,\"stars\":8,\"progress\":17,\"unprocessed\":9,\"day\":4,\"cap\":12,\"help\":3}";

    @Test void retiredFormsLeaveTheMasteryWithoutRefund() {
        MasteryState state = MasteryState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"techniques":{"murim:twenty_four_plum_dome": %s, "murim:twenty_four_plum_full_bloom": %s,
                 "murim:twenty_four_plum_rainfall": %s},
                 "wisdom":6,"comprehended":["murim:twenty_four_plum_dome"],"fired":["murim:twenty_four_plum#1"]}
                """.formatted(PROGRESS, PROGRESS, PROGRESS))).getOrThrow();
        assertFalse(state.knows(PlumFormMigration.DOME));
        assertFalse(state.knows(PlumFormMigration.BLOOM));
        assertTrue(state.knows(ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum_rainfall")));
        assertEquals(6, state.wisdom());
    }

    @Test void oldKnownListAlsoDropsThem() {
        MasteryState state = MasteryState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"version":5,"arts":{"murim:twenty_four_plum": %s},
                 "known":["murim:twenty_four_plum_dome","murim:twenty_four_plum_full_bloom"]}
                """.formatted(PROGRESS))).getOrThrow();
        assertTrue(state.techniques().isEmpty());
    }

    @Test void retiredCastStopsButKeepsCooldownTimestamp() {
        for (String id : new String[]{"twenty_four_plum_dome", "twenty_four_plum_full_bloom"}) {
            var state = TechniqueState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                    "{\"technique\":\"murim:" + id + "\",\"tick\":23,\"impact_done\":false,\"last_start\":12345}")).getOrThrow();
            assertFalse(state.isActive());
            assertEquals(12345, state.lastStartGameTime());
        }
    }

    @Test void slotsEmptyWithoutBreakingTheWheel() {
        Loadout l = Loadout.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"slots\":[{\"technique\":\"murim:twenty_four_plum_full_bloom\"},{\"technique\":\"murim:twenty_four_plum_dome\"}],\"active\":0}"))
                .getOrThrow();
        assertTrue(l.slots().stream().noneMatch(java.util.Optional::isPresent));
    }
}
