package io.github.verycooltimo.murim.client.bandit;

import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.entity.Bandit;
import net.minecraft.client.model.geom.ModelPart;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StunPoseTest {
    @Test
    void stunSelectsItsClipAndRecoveryLeavesIt() {
        assertEquals("stun", BanditModel.combatClip(Bandit.STUN, "", false));
        assertEquals("stun", BanditModel.combatClip(Bandit.STUN, "_heavy", true));
        assertNull(BanditModel.combatClip(Bandit.IDLE, "", false));
        assertEquals("windup", BanditModel.combatClip(Bandit.WINDUP, "", false));
    }

    @Test
    void blendsInOverThreeTicksAndOutOverFourIncludingInterruptedEntry() {
        var part = new ModelPart(List.of(), Map.of());
        var parts = List.of(part);
        var blend = new StunPoseBlend();
        blend.apply(parts, 0, false);
        part.xRot = 1;
        blend.apply(parts, 1, true);
        assertEquals(0, part.xRot, 0.0001);
        part.xRot = 1;
        blend.apply(parts, 2.5F, true);
        assertEquals(0.5, part.xRot, 0.0001);
        part.xRot = 0;
        blend.apply(parts, 2.5F, false);
        assertEquals(0.5, part.xRot, 0.0001);
        part.xRot = 0;
        blend.apply(parts, 4.5F, false);
        assertEquals(0.25, part.xRot, 0.0001);
        part.xRot = 0;
        blend.apply(parts, 6.5F, false);
        assertEquals(0, part.xRot, 0.0001);

        part.xRot = 1;
        blend.apply(parts, 7, true);
        part.xRot = 1;
        blend.apply(parts, 10, true);
        assertEquals(1, part.xRot, 0.0001);
    }

    @Test
    void sharedModelPartsDoNotMixDifferentEntitiesTransitions() {
        var part = new ModelPart(List.of(), Map.of());
        var parts = List.of(part);
        var first = new StunPoseBlend();
        var second = new StunPoseBlend();
        first.apply(parts, 0, false);
        part.xRot = -1;
        second.apply(parts, 0, false);
        part.xRot = 1;
        first.apply(parts, 1, true);
        assertEquals(0, part.xRot, 0.0001);
        part.xRot = -1;
        second.apply(parts, 2, false);
        assertEquals(-1, part.xRot, 0.0001);
        part.xRot = 1;
        first.apply(parts, 2.5F, true);
        assertEquals(0.5, part.xRot, 0.0001);
    }

    @Test
    void banditAssetsKeepSlumpAndClosedEightTenthsSecondLoop() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path relative = Path.of("src/main/resources/assets/murim/bedrock");
        while (root != null && !Files.isDirectory(root.resolve(relative))) root = root.getParent();
        assertNotNull(root, "Bandit asset source tree not found");
        for (String name : List.of("bandit", "bandit_archer")) {
            try (var reader = Files.newBufferedReader(root.resolve(relative).resolve(name + ".animation.json"))) {
                var clip = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("animations")
                        .getAsJsonObject("animation." + name + ".stun");
                assertTrue(clip.get("loop").getAsBoolean());
                assertEquals(0.8, clip.get("animation_length").getAsDouble(), 0.0001);
                var bones = clip.getAsJsonObject("bones");
                assertEquals(35, bones.getAsJsonObject("head").getAsJsonArray("rotation").get(0).getAsInt());
                assertEquals(28, bones.getAsJsonObject("torso").getAsJsonArray("rotation").get(0).getAsInt());
                for (String leg : List.of("leg_r", "leg_l"))
                    assertEquals(-20, bones.getAsJsonObject(leg).getAsJsonArray("rotation").get(0).getAsInt());
                var sway = bones.getAsJsonObject("waist").getAsJsonObject("rotation");
                assertEquals(sway.get("0.0"), sway.get("0.8"));
                assertEquals(-7, sway.getAsJsonArray("0.0").get(2).getAsInt());
                assertEquals(7, sway.getAsJsonArray("0.4").get(2).getAsInt());
                if (name.equals("bandit"))
                    assertEquals(-45, bones.getAsJsonObject("weapon").getAsJsonArray("rotation").get(0).getAsInt());
            }
        }
    }
}
