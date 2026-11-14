package io.github.verycooltimo.murim.mastery;

import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.combat.TechniqueState;
import io.github.verycooltimo.murim.item.TechniqueCovers;
import io.github.verycooltimo.murim.technique.Styles;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Crush is a new learned form in the existing art, never a migration alias for retired forms.
 * API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/resources/ResourceLocation.java#fromNamespaceAndPath.
 */
class CrushSaveTest {
    private static final ResourceLocation CRUSH = ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum_crush");
    private static final ResourceLocation SEA = ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum_sea");
    @Test void newFormSharesArtAndSurvivesAllExistingSaveCodecs() {
        assertEquals(CRUSH, Styles.TWENTY_FOUR_PLUM.forms().getLast());
        assertEquals(SEA, Styles.previous(CRUSH).orElseThrow());
        assertEquals(Arts.masteryId(SEA), Arts.masteryId(CRUSH));
        assertEquals(12, Arts.cap(CRUSH));
        assertTrue(TechniqueCovers.index(CRUSH) > 0);
        assertEquals(TechniqueCovers.index(SEA), TechniqueCovers.index(CRUSH));
        var mastery = new MasteryState(java.util.Map.of(CRUSH, TechniqueProgress.learned(9, 12)), 1, Set.of());
        var restored = MasteryState.CODEC.parse(JsonOps.INSTANCE, MasteryState.CODEC.encodeStart(JsonOps.INSTANCE, mastery).getOrThrow()).getOrThrow();
        assertTrue(restored.knows(CRUSH));
        assertEquals(9, restored.progress(CRUSH).stars());
        var loadout = new Loadout(List.of(Optional.of(CRUSH)), 0, Optional.empty());
        assertEquals(loadout, Loadout.CODEC.parse(JsonOps.INSTANCE, Loadout.CODEC.encodeStart(JsonOps.INSTANCE, loadout).getOrThrow()).getOrThrow());
        var active = new TechniqueState(CRUSH, 52, false, 100);
        assertEquals(active, TechniqueState.CODEC.parse(JsonOps.INSTANCE, TechniqueState.CODEC.encodeStart(JsonOps.INSTANCE, active).getOrThrow()).getOrThrow());
        assertFalse(MasteryState.EMPTY.with(SEA, TechniqueProgress.learned(12, 12)).knows(CRUSH));
        assertFalse(RemovedArts.is(CRUSH));
    }
    @Test void iconIsAnAvailableFortyEightPixelInkGlyph() throws Exception {
        try (var stream = getClass().getResourceAsStream("/assets/murim/textures/gui/technique/twenty_four_plum_crush.png")) {
            assertNotNull(stream);
            var icon = javax.imageio.ImageIO.read(stream);
            assertEquals(48, icon.getWidth()); assertEquals(48, icon.getHeight());
        }
    }
}
