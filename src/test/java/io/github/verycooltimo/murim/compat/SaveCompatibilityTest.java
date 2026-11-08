package io.github.verycooltimo.murim.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.cultivation.CultivationState;
import io.github.verycooltimo.murim.cultivation.PillKind;
import io.github.verycooltimo.murim.cultivation.PillState;
import io.github.verycooltimo.murim.mastery.Loadout;
import io.github.verycooltimo.murim.profile.DantianProfile;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SaveCompatibilityTest {
    // API: minecraft-src/net/minecraft/nbt/NbtOps.java (INSTANCE).
    private static <T> T legacy(Codec<T> codec, String json) {
        JsonElement input = JsonParser.parseString(json);
        T decoded = codec.parse(JsonOps.INSTANCE, input).getOrThrow();
        assertEquals(decoded, codec.parse(NbtOps.INSTANCE, JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, input)).getOrThrow());
        assertEquals(decoded, codec.parse(NbtOps.INSTANCE, codec.encodeStart(NbtOps.INSTANCE, decoded).getOrThrow()).getOrThrow());
        assertEquals(1, codec.encodeStart(JsonOps.INSTANCE, decoded).getOrThrow().getAsJsonObject().get("version").getAsInt());
        return decoded;
    }

    @Test void unversionedProfileKeepsRankAndAxesWithoutStage() {
        var p = legacy(DantianProfile.CODEC, """
                {"capacity":120,"purity":0.8,"meridians":0.6,"nature":"plum","imprint":"hua",
                 "pool":42,"circulating":12,"foundation":0.9,"rank":4}
                """);
        assertEquals(4, p.rank());
        assertEquals(120, p.capacity());
        assertEquals(42, p.pool());
        assertEquals(12, p.circulating());
        assertEquals("hua", p.imprint());
        assertEquals(0, p.stage());
        assertEquals(DantianProfile.VERSION, p.version());
    }

    @Test void unversionedCultivationKeepsMethodAndBeats() {
        var s = legacy(CultivationState.CODEC, "{\"method\":\"murim:hua_method\",\"beats\":2}");
        assertEquals(ResourceLocation.parse("murim:hua_method"), s.method().orElseThrow());
        assertEquals(2, s.beats());
        assertEquals(CultivationState.VERSION, s.version());
        assertEquals(CultivationState.NONE, legacy(CultivationState.CODEC, "{}"));
    }

    @Test void unversionedLoadoutKeepsSlotsWithoutNewProtectionField() {
        var s = legacy(Loadout.CODEC, """
                {"slots":[{}, {"technique":"murim:six_equilibria"}],"active":1,"foundation":"murim:six_equilibria"}
                """);
        assertTrue(s.at(0).isEmpty());
        assertEquals(ResourceLocation.parse("murim:six_equilibria"), s.activeTechnique().orElseThrow());
        assertEquals(s.activeTechnique(), s.foundation());
        assertTrue(s.bodyProtection().isEmpty());
        assertEquals(Loadout.VERSION, s.version());
    }

    @Test void unknownPillSkipsOnlyItsPendingEntry() {
        var s = legacy(PillState.CODEC, """
                {"taken":{"SNOW_PLUM":3},"poison_resistant":true,
                 "pending":["SNOW_PLUM","FUTURE_PILL","ANTIDOTE"],"first":123,"deadline":456,
                 "streak":"SNOW_PLUM","streak_count":2,"streak_at":100,
                 "reserve":{"pool":7,"ticks":40}}
                """);
        assertEquals(List.of(PillKind.SNOW_PLUM, PillKind.ANTIDOTE), s.pending());
        assertEquals(3, s.taken(PillKind.SNOW_PLUM));
        assertTrue(s.poisonResistant());
        assertEquals(123, s.first());
        assertEquals(456, s.deadline());
        assertEquals(7, s.reserve().pool());
        assertEquals(40, s.reserve().ticks());
        assertEquals(new PillState.Tolerance(2, 100), s.tolerances().get("SNOW_PLUM"));
        assertEquals(PillState.VERSION, s.version());
        assertEquals(PillState.NONE, legacy(PillState.CODEC, "{}"));
    }

    @Test void futureOptionalFieldsDoNotEraseKnownData() {
        var s = legacy(CultivationState.CODEC, "{\"version\":1,\"beats\":2,\"future_field\":42}");
        assertEquals(2, s.beats());
    }
}
