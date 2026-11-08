package io.github.verycooltimo.murim.client.sect;

import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.client.bedrock.BedrockItemRenderer;
import io.github.verycooltimo.murim.sect.BladeExamGeometry;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BladeExamHoldTest {
    @Test
    void solvedHoldKeepsHandAndRenderedBladeOnShoulderTargetLine() throws Exception {
        var ends = BedrockItemRenderer.HUASHAN_SWORD.bladeEndpoints();
        assertNotNull(ends, "Sword mesh must expose its rendered endpoints");
        assertTrue(Math.abs(ends[1].x - ends[0].x) > 1E-5,
                "Imported Huashan tip offset must not be discarded by the endpoint helper");
        // Read the actual display transform, independently of the solver's display conversion.
        var display = display();
        var correction = BladeExamHold.solve(1);
        var arm = new Matrix4f().rotateY(BladeExamGeometry.HOLD_YAW).rotateX(BladeExamGeometry.HOLD_PITCH);
        var held = new Matrix4f(arm).translate(correction.translation()).rotate(correction.rotation()).mul(display);
        Vector3f base = held.transformPosition(new Vector3f((float) ends[0].x, (float) ends[0].y, (float) ends[0].z));
        Vector3f tip = held.transformPosition(new Vector3f((float) ends[1].x, (float) ends[1].y, (float) ends[1].z));
        Vector3f hand = arm.transformPosition(new Vector3f(0, (float) BladeExamGeometry.ARM_LENGTH, 0));
        Vector3f forearm = new Vector3f(hand).normalize();
        Vector3f blade = new Vector3f(tip).sub(base).normalize();
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, forearm.dot(blade)))));
        assertTrue(angle < 2, "Wrist angle in degrees: " + angle);
        assertTrue(new Vector3f(forearm).cross(blade).length() < 1E-5, "Blade axis must be parallel to forearm");
        assertTrue(new Vector3f(tip).cross(forearm).length() < 1E-5, "Tip must lie on shoulder-hand line");

        // Model Y is down; forward is -Z. Forehead is in front of the pupil's center.
        Vector3f forehead = new Vector3f((float) -BladeExamGeometry.SHOULDER_X,
                (float) -(BladeExamGeometry.FOREHEAD_Y - BladeExamGeometry.SHOULDER_Y),
                (float) -(BladeExamGeometry.STOP_DISTANCE - BladeExamGeometry.FACE_FORWARD));
        float gap = tip.z - forehead.z;
        assertTrue(gap >= 0.1F && gap <= 0.2F, "Tip gap in front of forehead: " + gap);
        assertEquals(forehead.x, tip.x, 0.002, "Tip must aim at forehead horizontally");
        assertEquals(forehead.y, tip.y, 0.002, "Tip must aim at forehead vertically");
        assertTrue(tip.distance(forehead) >= 0.1F && tip.distance(forehead) <= 0.2F);
    }

    @Test
    void windupRetainsOriginalGripAndItemOrientation() {
        var correction = BladeExamHold.solve(0);
        assertEquals(0, correction.rotation().angle(), 1E-6);
        assertEquals(0, correction.translation().x, 1E-6);
        assertEquals(0, correction.translation().z, 1E-6);
        double oldGrip = 6.75 / 16 + 1.5 * (BladeExamGeometry.GRIP_Y_PIXELS / 16 - 0.5);
        assertEquals(BladeExamGeometry.ARM_LENGTH - oldGrip, correction.translation().y, 1E-6);
    }

    private static Matrix4f display() throws Exception {
        try (var stream = BladeExamHoldTest.class.getResourceAsStream("/assets/murim/models/item/huashan_sword.json")) {
            assertNotNull(stream);
            var transform = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject()
                    .getAsJsonObject("display").getAsJsonObject("thirdperson_righthand");
            var t = transform.getAsJsonArray("translation");
            var r = transform.getAsJsonArray("rotation");
            var s = transform.getAsJsonArray("scale");
            return new Matrix4f().translate(t.get(0).getAsFloat() / 16, t.get(1).getAsFloat() / 16, t.get(2).getAsFloat() / 16)
                    .rotateXYZ((float) Math.toRadians(r.get(0).getAsFloat()), (float) Math.toRadians(r.get(1).getAsFloat()),
                            (float) Math.toRadians(r.get(2).getAsFloat()))
                    .scale(s.get(0).getAsFloat(), s.get(1).getAsFloat(), s.get(2).getAsFloat())
                    .translate(-0.5F, -0.5F, -0.5F).translate(0.5F, 0, 0.5F);
        }
    }
}
