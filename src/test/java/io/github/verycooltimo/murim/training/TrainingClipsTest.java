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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @DisplayName("Клипы игрока есть; планка и отжимание держат последний кадр; повтор короче такта сервера")
    void playerClips() throws IOException {
        Map<String, String> loops = Map.of("training_squat", "false", "training_horse", "true",
                "training_plank", "\"hold_on_last_frame\"", "training_pushup", "\"hold_on_last_frame\"",
                "training_pushup_weighted", "\"hold_on_last_frame\"", "training_plank_up", "false", "training_carry", "true");
        for (Map.Entry<String, String> e : loops.entrySet()) {
            JsonObject a = clip(e.getKey());
            assertEquals(e.getValue(), a.get("loop").toString(), e.getKey());
            for (String bone : a.getAsJsonObject("bones").keySet()) {
                assertTrue(PLAYER_BONES.contains(bone), e.getKey() + ": bone " + bone);
            }
        }
        assertTrue(clip("training_squat").get("animation_length").getAsFloat() * 20.0F <= Exercise.SQUAT.beat());
        assertTrue(clip("training_pushup").get("animation_length").getAsFloat() * 20.0F <= Exercise.PUSHUP.beat());
        assertTrue(clip("training_pushup_weighted").get("animation_length").getAsFloat() * 20.0F <= Exercise.PUSHUP_WEIGHTED.beat());
        for (String b : clip("training_carry").getAsJsonObject("bones").keySet()) {
            assertTrue(!b.endsWith("_leg") && !b.equals("body"), "carry is upper body, legs walk: " + b);
        }
    }

    @Test
    @DisplayName("Ученики: у каждого упражнения на месте своя поза; соседи по списку делают разное")
    void discipleHook() {
        Set<SectPose> poses = new HashSet<>();
        for (Exercise e : DiscipleTraining.MORNING) {
            SectPose p = DiscipleTraining.pose(e);
            assertNotEquals(SectPose.NONE, p);
            assertTrue(p.training() && p.hidesWeapon(), e.id());
            poses.add(p);
        }
        assertEquals(DiscipleTraining.MORNING.size(), poses.size());
        assertNull(DiscipleTraining.pose(Exercise.PEAK_CLIMB));
        assertNotEquals(DiscipleTraining.morning(0, 7), DiscipleTraining.morning(1, 7));
        assertNotEquals(DiscipleTraining.morning(3, 7), DiscipleTraining.morning(3, 8));
        assertTrue(SectPose.CARRY_STONE.upperBody(), "the stone is carried while walking up the stair");
    }
}
