package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.client.bedrock.BedrockItemRenderer;
import io.github.verycooltimo.murim.sect.BladeExamGeometry;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Exam-only correction derived from the rendered mesh, after the normal item display transform. */
public final class BladeExamHold {
    private BladeExamHold() { }

    public record Correction(Quaternionf rotation, Vector3f translation) { }

    public static Correction solve(float blend) {
        var ends = BedrockItemRenderer.HUASHAN_SWORD.bladeEndpoints();
        Vector3f base = display(ends[0].x, ends[0].y, ends[0].z);
        Vector3f tip = display(ends[1].x, ends[1].y, ends[1].z);
        Vector3f axis = new Vector3f(tip).sub(base);
        Quaternionf rotation = new Quaternionf().slerp(
                new Quaternionf().rotationTo(new Vector3f(axis).normalize(), new Vector3f(0, 1, 0)), blend);
        float gripY = display(0, BladeExamGeometry.GRIP_Y_PIXELS / 16, 0).y;
        // Intersect the blade line with the grip plane so shoulder, hand and tip share one line.
        Vector3f grip = new Vector3f(base).fma((gripY - base.y) / axis.y, axis);
        grip.lerp(new Vector3f(0, gripY, 0), 1 - blend);
        rotation.transform(grip);
        return new Correction(rotation, new Vector3f(0, (float) BladeExamGeometry.ARM_LENGTH, 0).sub(grip));
    }

    /** ItemTransform#apply -> ItemRenderer centering -> BedrockItemRenderer mesh origin.
     * API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/renderer/block/model/ItemTransform.java#apply;
     * /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/renderer/entity/ItemRenderer.java#render.
     * huashan_sword.json: T(0,6.75/16,0) Ry(90) S(1.5); mesh origin cancels X/Z centering.
     */
    private static Vector3f display(double x, double y, double z) {
        return new Vector3f((float) (1.5 * z), (float) (6.75 / 16 + 1.5 * (y - 0.5)), (float) (-1.5 * x));
    }
}
