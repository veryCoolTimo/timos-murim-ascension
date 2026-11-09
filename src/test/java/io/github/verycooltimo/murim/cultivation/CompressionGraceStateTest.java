package io.github.verycooltimo.murim.cultivation;

import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one forgiven compression failure is a persistent flag (survives save/load and later beats). */
class CompressionGraceStateTest {
    private static final ResourceLocation M = ResourceLocation.fromNamespaceAndPath("murim", "six_harmonies");

    @Test void defaultsToUnused() {
        assertFalse(CultivationState.NONE.compressionGraceUsed());
        assertFalse(new CultivationState(Optional.of(M), 2).compressionGraceUsed());
    }

    @Test void flagSurvivesNbtRoundTrip() {
        var spent = new CultivationState(Optional.of(M), 2).withCompressionGraceUsed();
        var tag = CultivationState.CODEC.encodeStart(NbtOps.INSTANCE, spent).getOrThrow();
        var back = CultivationState.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        assertTrue(back.compressionGraceUsed());
        assertEquals(spent, back);
    }

    @Test void flagSurvivesJsonAndBeatChanges() {
        var spent = CultivationState.NONE.withCompressionGraceUsed();
        var json = CultivationState.CODEC.encodeStart(JsonOps.INSTANCE, spent).getOrThrow();
        assertTrue(CultivationState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().compressionGraceUsed());
        assertTrue(spent.withBeats(2).withMethod(M).withBeats(CultivationState.SEEDED).compressionGraceUsed());
    }

    @Test void legacySaveWithoutFlagReadsUnused() {
        var json = com.google.gson.JsonParser.parseString("{\"beats\":2}");
        assertFalse(CultivationState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().compressionGraceUsed());
    }
}
