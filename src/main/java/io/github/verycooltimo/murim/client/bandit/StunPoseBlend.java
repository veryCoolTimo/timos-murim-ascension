package io.github.verycooltimo.murim.client.bandit;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.util.Mth;

import java.util.List;

/** A displayed-pose snapshot, owned by one entity in one model (including interrupted fades).
 * API verified against Minecraft 1.21.1 ModelPart#storePose, PartPose and Mth#lerp.
 */
final class StunPoseBlend {
    private boolean stunned;
    private float start;
    private float duration;
    private List<PartPose> previous;
    private List<PartPose> from;

    void apply(List<ModelPart> parts, float now, boolean active) {
        if (stunned != active) {
            stunned = active;
            start = now;
            duration = active ? 3.0F : 4.0F;
            from = previous;
            // First observed already stunned: enter from the model's authored rest pose.
            if (from == null) from = parts.stream().map(ModelPart::getInitialPose).toList();
        }
        if (from != null && now - start < duration) {
            float weight = Mth.clamp((now - start) / duration, 0.0F, 1.0F);
            for (int i = 0; i < parts.size(); i++) {
                ModelPart part = parts.get(i);
                PartPose pose = from.get(i);
                part.x = Mth.lerp(weight, pose.x, part.x);
                part.y = Mth.lerp(weight, pose.y, part.y);
                part.z = Mth.lerp(weight, pose.z, part.z);
                part.xRot = Mth.lerp(weight, pose.xRot, part.xRot);
                part.yRot = Mth.lerp(weight, pose.yRot, part.yRot);
                part.zRot = Mth.lerp(weight, pose.zRot, part.zRot);
            }
        } else {
            from = null;
        }
        previous = parts.stream().map(ModelPart::storePose).toList();
    }
}
