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
        // Cook and servants (18.10): the kitchen poses replace the talk gestures they used to borrow.
        assertEquals(SectPose.COOK_STIR, SectPose.forTask(Kind.COOK, true, false));
        assertEquals(SectPose.TRAY_EMPTY, SectPose.forTask(Kind.SERVE, true, false));
        assertEquals(SectPose.HOE, SectPose.forTask(Kind.TEND, true, false));
        assertEquals(SectPose.NONE, SectPose.forTask(Kind.TEND, false, false));
        for (Kind k : Kind.values()) {
            assertNotNull(SectPose.forTask(k, true, false), k.name());
            assertTrue(SectPose.forTask(k, true, false) != SectPose.TALK || k == Kind.WORK || k == Kind.INSPECT || k == Kind.REPORT || k == Kind.LECTURE || k == Kind.ARGUE,
                    k.name() + " still falls back to the talk pose");
        }
        for (SectPose p : SectPose.values()) {
            assertEquals(p, SectPose.of(p.id()));
        }
    }

    @Test
    @DisplayName("Cook and servants: loaded carry poses, the bowls leave the tray inside the set-down clip, one-shots are one-shots")
    void kitchenPoses() throws IOException {
        assertEquals(SectPose.SACK, SectPose.loadPose(io.github.verycooltimo.murim.sect.SectRole.PORTER, "porter_jang"));
        assertEquals(SectPose.JAR, SectPose.loadPose(io.github.verycooltimo.murim.sect.SectRole.PORTER, "porter_oh"));
        assertEquals(SectPose.CARRY, SectPose.loadPose(io.github.verycooltimo.murim.sect.SectRole.WATER_CARRIER, "water_gu"));
        assertTrue(SectPose.TRAY_SET.propShown("tray_bowls", 1.0F) && !SectPose.TRAY_SET.propShown("tray_bowls", SectPose.SET_DROP + 0.01F)
                && SectPose.TRAY_SET.propShown("tray", 2.5F) && SectPose.TRAY.propShown("tray_bowls", 9.0F));
        assertTrue(clip(SectPose.TRAY_SET).get("animation_length").getAsFloat() > SectPose.SET_DROP + 0.5F, "the bowls would leave after the clip");
        for (SectPose p : new SectPose[] {SectPose.COOK_TASTE, SectPose.COOK_LADLE, SectPose.TRAY_SET}) {
            assertTrue(!p.loop() && p.upperBody(), p.id() + " is a one-shot of the upper body");
        }
        for (SectPose p : new SectPose[] {SectPose.COOK_STIR, SectPose.COOK_CHOP, SectPose.TRAY, SectPose.WIPE, SectPose.SACK, SectPose.JAR}) {
            assertTrue(p.loop() && p.upperBody() && !p.props().isEmpty(), p.id() + " is a looping upper-body pose with a prop");
        }
        // The tray follows its own one-shot without a dip into the rest pose; the rest blend in as before.
        assertTrue(SectPose.TRAY.blendIn() < 0.2F && SectPose.TRAY_EMPTY.blendIn() < 0.2F && SectPose.WIPE.blendIn() == 0.35F);
        // The empty tray carries the same clip as the loaded one: only the props differ.
        assertEquals(SectPose.TRAY.clip(), SectPose.TRAY_EMPTY.clip());
        assertTrue(SectPose.TRAY_EMPTY.props().equals(java.util.List.of("tray")));
        // The herbalist hoes with a vanilla hoe in the hand: legs come from the clip, no sword.
        assertTrue(!SectPose.HOE.upperBody() && SectPose.HOE.hidesWeapon());
    }

    private static JsonObject seatBones(String name) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(res("npc_animations/" + name + ".json"))).getAsJsonObject();
        JsonObject a = root.getAsJsonObject("animations").getAsJsonObject(name);
        assertNotNull(a, name);
        return a.getAsJsonObject("bones");
    }

    private static float first(JsonObject bones, String bone, String channel, int axis) {
        return bones.getAsJsonObject(bone).getAsJsonObject(channel).getAsJsonObject("0.0").getAsJsonArray("vector").get(axis).getAsFloat();
    }

    @Test
    @DisplayName("Seat bases: one clip per way of sitting, lower body only, a hold; the chair keeps the legs forward, the lotus crosses them")
    void seatBases() throws IOException {
        for (String name : new String[] {"sect_seat_chair", "sect_seat_lotus"}) {
            JsonObject bones = seatBones(name);
            assertEquals(Set.of("body", "right_leg", "left_leg"), bones.keySet(), name + " owns the lower body only");
            for (String bone : bones.keySet()) {
                for (Map.Entry<String, JsonElement> ch : bones.getAsJsonObject(bone).entrySet()) {
                    String value = null;
                    for (Map.Entry<String, JsonElement> k : ch.getValue().getAsJsonObject().entrySet()) {
                        String v = k.getValue().getAsJsonObject().get("vector").toString();
                        value = value == null ? v : value;
                        assertEquals(value, v, name + " " + bone + " " + ch.getKey() + " is not a hold");
                    }
                }
            }
        }
        // chair: the thighs lie on the seat (legs forward about 74 degrees, level), the hips come down a little more than the lotus
        JsonObject chair = seatBones("sect_seat_chair");
        float rightX = first(chair, "right_leg", "rotation", 0);
        assertTrue(rightX < -65.0F && rightX > -90.0F && rightX == first(chair, "left_leg", "rotation", 0), "the legs of a chair sitter are forward and a little down, level: " + rightX);
        JsonObject lotus = seatBones("sect_seat_lotus");
        float chairDrop = first(chair, "body", "position", 1);
        float lotusDrop = first(lotus, "body", "position", 1);
        assertTrue(chairDrop < lotusDrop && lotusDrop == -9.0F, "hips: chair " + chairDrop + ", lotus " + lotusDrop);
        assertTrue(first(lotus, "right_leg", "rotation", 2) > 60.0F, "the lotus crosses the legs sideways");
        // the poses the renderer lays over a base: the full-body seated ones carry lotus legs of their own (replaced by the base),
        // reading and counting are upper-body clips
        assertTrue(SectPose.EAT.seated() && SectPose.MEDITATE.seated() && SectPose.SIT.seated() && SectPose.GRIND.seated());
        assertTrue(SectPose.READ.upperBody() && SectPose.COUNT.upperBody() && SectPose.TALK.upperBody());
    }
}
