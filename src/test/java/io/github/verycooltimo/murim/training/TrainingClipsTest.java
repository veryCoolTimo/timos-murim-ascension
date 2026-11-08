package io.github.verycooltimo.murim.training;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.entity.SectPose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract with the clip files (client/training/ClientTraining plays them) and the NPC hook. */
class TrainingClipsTest {

    private static final Set<String> PLAYER_BONES = Set.of("body", "torso", "head", "right_arm", "left_arm", "right_leg",
            "left_leg", "right_item", "left_item");

    private static Path res(String path) {
        Path p = Path.of("src/main/resources/assets/murim/" + path);
        return Files.exists(p) ? p : Path.of("../../src/main/resources/assets/murim/" + path);
    }

    private static JsonObject clip(String name) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(res("player_animations/" + name + ".json"))).getAsJsonObject();
        return root.getAsJsonObject("animations").getAsJsonObject(name);
    }

    @Test
    @DisplayName("Клип игрока: остался один, камень в руках (петля, только верх тела); клипов приседаний и отжиманий нет")
    void playerClips() throws IOException {
        JsonObject carry = clip("training_carry");
        assertEquals("true", carry.get("loop").toString());
        for (String bone : carry.getAsJsonObject("bones").keySet()) {
            assertTrue(PLAYER_BONES.contains(bone), "training_carry: bone " + bone);
            assertTrue(!bone.endsWith("_leg") && !bone.equals("body"), "carry is upper body, legs walk: " + bone);
        }
        for (String gone : new String[] {"squat", "horse", "plank", "plank_up", "pushup", "pushup_weighted"}) {
            assertTrue(!Files.exists(res("player_animations/training_" + gone + ".json")),
                    "the Shift-started sets are gone (25.10): training_" + gone);
        }
    }

    @Test
    @DisplayName("Ученики: у каждой позы утренней разминки своя поза; соседи по списку делают разное")
    void discipleHook() {
        Set<SectPose> poses = new HashSet<>();
        for (SectPose p : DiscipleTraining.MORNING) {
            assertNotEquals(SectPose.NONE, p);
            assertTrue(p.training() && p.hidesWeapon(), p.name());
            poses.add(p);
        }
        assertEquals(DiscipleTraining.MORNING.size(), poses.size());
        assertNotEquals(DiscipleTraining.morning(0, 7), DiscipleTraining.morning(1, 7));
        assertNotEquals(DiscipleTraining.morning(3, 7), DiscipleTraining.morning(3, 8));
        assertTrue(SectPose.CARRY_STONE.upperBody(), "the stone is carried while walking up the stair");
    }

    @Test
    @DisplayName("Остались три явных урока секты: камень в гору, подъём на пик, бег по тропе")
    void onlyTheSectDrillsRemain() {
        assertEquals(3, Exercise.values().length);
        assertTrue(Exercise.PEAK_CLIMB.route() && Exercise.TRAIL_SPRINT.route() && !Exercise.CARRY_STONE.route());
    }
}
