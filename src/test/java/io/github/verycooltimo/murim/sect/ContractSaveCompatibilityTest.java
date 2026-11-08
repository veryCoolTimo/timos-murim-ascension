package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.compat.SavedEnums;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ContractSaveCompatibilityTest {
    // API: minecraft-src/net/minecraft/nbt/{CompoundTag,ListTag}.java.
    @Test void unknownOffersAndRunsKeepTheirNeighboursAndDay() {
        CompoundTag saved = new CompoundTag();
        saved.putLong("day", 7);
        ListTag offers = new ListTag();
        offers.add(offer("HERBS", 1));
        offers.add(offer("FUTURE_KIND", 2));
        offers.add(offer("CAMP", 3));
        saved.put("offers", offers);
        ListTag runs = new ListTag();
        UUID good = UUID.randomUUID();
        runs.add(run(good, "HERBS"));
        runs.add(run(UUID.randomUUID(), "FUTURE_KIND"));
        CompoundTag badAccepted = run(UUID.randomUUID(), "CAMP");
        badAccepted.put("accepted", offer("FUTURE_KIND", 3));
        runs.add(badAccepted);
        saved.put("runs", runs);
        var data = SectContracts.Data.load(saved, null);
        assertEquals(7, data.day);
        assertEquals(2, data.offers.size());
        assertEquals(SectContracts.Kind.HERBS, data.offers.get(0).kind());
        assertEquals(SectContracts.Kind.CAMP, data.offers.get(1).kind());
        assertEquals(1, data.runs.size());
        assertEquals(SectContracts.Kind.HERBS, data.runs.get(good).kind);
        assertEquals(9, data.runs.get(good).leg);
        assertEquals(1, data.runs.get(good).members.size());
        var reread = SectContracts.Data.load(data.save(new CompoundTag(), null), null);
        assertEquals(data.offers, reread.offers);
        assertEquals(data.runs.keySet(), reread.runs.keySet());
    }

    @Test void correctionNamesAreLenientAndSavedOrdinalsStayFrozen() {
        assertTrue(SavedEnums.read(SectCorrection.Mistake.class, "FUTURE_MISTAKE").isEmpty());
        String[] frozen = {"WINDUP_HIT", "PUNISHED", "WHIFF", "ONE_FORM", "EDGE", "QI", "CLOSE", "HITS", "FAR", "PASSIVE"};
        for (int i = 0; i < frozen.length; i++) {
            assertEquals(frozen[i], SectCorrection.Mistake.at(i + 1L).name());
            assertEquals(SectCorrection.Mistake.at(i + 1L), SavedEnums.read(SectCorrection.Mistake.class, frozen[i]).orElseThrow());
        }
        assertNull(SectCorrection.Mistake.at(0));
        assertNull(SectCorrection.Mistake.at(Long.MAX_VALUE));
    }

    private static CompoundTag offer(String kind, int slot) {
        CompoundTag t = new CompoundTag();
        t.putString("kind", kind);
        t.putInt("slot", slot);
        t.putString("giver", "healer");
        return t;
    }

    private static CompoundTag run(UUID id, String kind) {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", id);
        t.putString("kind", kind);
        t.putInt("slot", 1);
        t.putLong("day", 7);
        t.putInt("leg", 9);
        ListTag members = new ListTag();
        CompoundTag member = new CompoundTag();
        member.putUUID("p", UUID.randomUUID());
        members.add(member);
        t.put("members", members);
        return t;
    }
}
