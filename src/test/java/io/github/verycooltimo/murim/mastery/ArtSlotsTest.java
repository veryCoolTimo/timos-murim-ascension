package io.github.verycooltimo.murim.mastery;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.github.verycooltimo.murim.mastery.ArtSlots.*;
import static org.junit.jupiter.api.Assertions.*;

class ArtSlotsTest {
    @Test
    void specialArtsCannotUseAnyTechniqueSlot() {
        for (int slot = 0; slot < Loadout.MAX_SLOTS; slot++) {
            assertFalse(accepts(slot, Kind.BREATHING, false));
            assertFalse(accepts(slot, Kind.BODY_PROTECTION, false));
            assertTrue(accepts(slot, Kind.SWORD, false));
        }
        assertTrue(accepts(BREATHING, Kind.BREATHING, false));
        assertTrue(accepts(BODY_PROTECTION, Kind.BODY_PROTECTION, false));
        for (Kind kind : Kind.values()) {
            if (kind != Kind.BREATHING) assertFalse(accepts(BREATHING, kind, false));
            if (kind != Kind.BODY_PROTECTION) assertFalse(accepts(BODY_PROTECTION, kind, false));
        }
        assertFalse(accepts(FOUNDATION, Kind.BREATHING, true));
        assertFalse(accepts(FOUNDATION, Kind.BODY_PROTECTION, true));
        assertFalse(accepts(0, Kind.SWORD, true));
        assertFalse(accepts(-4, Kind.SWORD, false));
    }

    @Test
    void swordGuardAndBurstBreathAreNotPassiveSlots() {
        assertEquals(Kind.SWORD, kindOfForm("guard"));
        assertEquals(Kind.OTHER, kindOfForm("stance"));
        assertEquals(Kind.BODY_PROTECTION, kindOfForm("body_protection"));
        assertEquals(Kind.FIST_PALM, kindOfForm("open_palm"));
        assertEquals(Kind.FIST_PALM, kindOfForm("straight_punch"));
        assertEquals(Kind.HIDDEN_WEAPON, kindOfForm("throw"));
        assertEquals(Kind.STEPS, kindOfForm("step"));
    }

    @Test
    void oldSavesDefaultToEmptyProtectionAndNewSavesRoundTrip() {
        Loadout old = Loadout.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"active\":0}"))
                .getOrThrow(error -> new AssertionError(error));
        assertTrue(old.bodyProtection().isEmpty());
        var guard = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "guard");
        Loadout equipped = old.withBodyProtection(Optional.of(guard)).select(1).withFoundation(Optional.empty());
        var saved = Loadout.CODEC.encodeStart(JsonOps.INSTANCE, equipped)
                .getOrThrow(error -> new AssertionError(error));
        assertEquals(equipped, Loadout.CODEC.parse(JsonOps.INSTANCE, saved)
                .getOrThrow(error -> new AssertionError(error)));
    }
}
