package io.github.verycooltimo.murim.entity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.sect.SectSchedule.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Позы секты: распорядок → поза, договор с файлами клипов (npc_animations) и реквизита (sect_props.geo.json). */
class SectPoseTest {

    /** Кости клипа позы — скелет NPC (PalClips: body → waist и т. д.); опечатка молча игнорировалась бы. */
    private static final Set<String> BONES = Set.of("body", "torso", "head", "right_arm", "left_arm", "right_leg", "left_leg");

    private static Path res(String path) {
        Path p = Path.of("src/main/resources/assets/murim/" + path);
        return Files.exists(p) ? p : Path.of("../../src/main/resources/assets/murim/" + path);
    }

    private static JsonObject clip(SectPose pose) throws IOException {
        String name = pose.clip().getPath();
        JsonObject root = JsonParser.parseString(Files.readString(res("npc_animations/" + name + ".json"))).getAsJsonObject();
        JsonObject a = root.getAsJsonObject("animations").getAsJsonObject(name);
        assertNotNull(a, name);
        return a;
    }

    @Test
    @DisplayName("У каждой позы с клипом есть файл, цикл совпадает, кости — скелета NPC")
    void clipsMatchPoses() throws IOException {
        for (SectPose pose : SectPose.values()) {
            if (pose.clip() == null) {
                continue;
            }
            JsonObject a = clip(pose);
            JsonElement loop = a.get("loop");
            assertEquals(pose.loop(), loop.isJsonPrimitive() && loop.getAsJsonPrimitive().isBoolean() && loop.getAsBoolean(), pose.id());
            for (String bone : a.getAsJsonObject("bones").keySet()) {
                assertTrue(BONES.contains(bone), pose.id() + ": кость " + bone);
            }
            if (pose.upperBody()) {
                for (String bone : a.getAsJsonObject("bones").keySet()) {
                    assertTrue(!bone.endsWith("_leg") && !bone.equals("body"), pose.id() + ": ноги у позы корпуса " + bone);
                }
            }
        }
    }

    @Test
    @DisplayName("Цикл без скачка: первый и последний ключ каждого канала совпадают")
    void loopsAreSeamless() throws IOException {
        for (SectPose pose : SectPose.values()) {
            if (pose.clip() == null || !pose.loop()) {
                continue;
            }
            JsonObject a = clip(pose);
            float length = a.get("animation_length").getAsFloat();
            for (Map.Entry<String, JsonElement> b : a.getAsJsonObject("bones").entrySet()) {
                for (Map.Entry<String, JsonElement> ch : b.getValue().getAsJsonObject().entrySet()) {
                    JsonObject keys = ch.getValue().getAsJsonObject();
                    String first = null;
                    String last = null;
                    float lastT = -1.0F;
                    for (Map.Entry<String, JsonElement> k : keys.entrySet()) {
                        float t = Float.parseFloat(k.getKey());
                        String v = k.getValue().getAsJsonObject().get("vector").toString();
                        if (first == null) {
                            first = v;
                        }
                        if (t > lastT) {
                            lastT = t;
                            last = v;
                        }
                    }
                    assertEquals(length, lastT, 1e-4, pose.id() + " " + b.getKey() + " " + ch.getKey() + ": последний ключ не в конце");
                    assertEquals(first, last, pose.id() + " " + b.getKey() + " " + ch.getKey());
                }
            }
        }
    }

    @Test
    @DisplayName("Реквизит поз есть в sect_props.geo.json")
    void propsExist() throws IOException {
        JsonObject geo = JsonParser.parseString(Files.readString(res("bedrock/sect_props.geo.json"))).getAsJsonObject()
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        Set<String> bones = new HashSet<>();
        geo.getAsJsonArray("bones").forEach(b -> bones.add(b.getAsJsonObject().get("name").getAsString()));
        for (SectPose pose : SectPose.values()) {
            for (String prop : pose.props()) {
                assertTrue(bones.contains(prop), pose.id() + ": " + prop);
            }
        }
        assertTrue(Files.exists(res("textures/entity/sect_props.png")));
    }

    @Test
    @DisplayName("Распорядок → поза: на месте — дело, в пути — шаг, носильщик несёт вёдра и на ходу")
    void taskMapping() {
        assertEquals(SectPose.EAT, SectPose.forTask(Kind.EAT, true, false));
        assertEquals(SectPose.NONE, SectPose.forTask(Kind.EAT, false, false));
        assertEquals(SectPose.SLEEP, SectPose.forTask(Kind.SLEEP, true, false));
        assertEquals(SectPose.MEDITATE, SectPose.forTask(Kind.MEDITATE, true, false));
        assertEquals(SectPose.FORM, SectPose.forTask(Kind.FORM_ROW, true, false));
        assertEquals(SectPose.FORM, SectPose.forTask(Kind.DRILL, true, false));
        assertEquals(SectPose.POLES, SectPose.forTask(Kind.POLES, true, false));
        assertEquals(SectPose.POLE_STEP, SectPose.forTask(Kind.POLES, false, false));
        assertEquals(SectPose.SWEEP, SectPose.forTask(Kind.CHORE, true, false));
        assertEquals(SectPose.CARRY, SectPose.forTask(Kind.CHORE, false, true));
        assertEquals(SectPose.CARRY, SectPose.forTask(Kind.CHORE, true, true));
        assertEquals(SectPose.GUARD, SectPose.forTask(Kind.GUARD, true, false));
        assertEquals(SectPose.TALK, SectPose.forTask(Kind.WORK, true, false));
        assertEquals(SectPose.NONE, SectPose.forTask(Kind.SPAR, true, false));
        for (Kind k : Kind.values()) {
            assertNotNull(SectPose.forTask(k, true, false), k.name());
        }
        for (SectPose p : SectPose.values()) {
            assertEquals(p, SectPose.of(p.id()));
        }
    }
}
