package io.github.verycooltimo.murim.client.armor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.client.bedrock.BedrockGeo;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * Author armor groups baked by the same loader as DiscipleRenderer. The vanilla armor layer copies the player's
 * final limb transforms (including PAL's seated/meditation pose) before querying the extension. Draw under those
 * limbs, removing only their rest offsets: no second setupAnim call that would overwrite the animated pose.
 */
public final class UniformArmorModel extends Model {
    private final ModelPart geometry;
    private final EquipmentSlot slot;
    private HumanoidModel<?> pose;

    public UniformArmorModel(String generation, EquipmentSlot slot) {
        super(RenderType::armorCutoutNoCull);
        this.slot = slot;
        geometry = BedrockGeo.bake("/assets/murim/bedrock/uniform_" + generation + ".geo.json");
    }

    public UniformArmorModel posed(HumanoidModel<?> original) {
        pose = original;
        return this;
    }

    @Override
    public void renderToBuffer(PoseStack ps, VertexConsumer buffer, int light, int overlay, int color) {
        if (pose == null) return;
        if (slot == EquipmentSlot.CHEST) {
            draw("chest", pose.body, 0, 0, ps, buffer, light, overlay, color);
            draw("right_arm", pose.rightArm, -5, 2, ps, buffer, light, overlay, color);
            draw("left_arm", pose.leftArm, 5, 2, ps, buffer, light, overlay, color);
        } else if (slot == EquipmentSlot.LEGS) {
            draw("right_leg", pose.rightLeg, -1.9F, 12, ps, buffer, light, overlay, color);
            draw("left_leg", pose.leftLeg, 1.9F, 12, ps, buffer, light, overlay, color);
        } else if (slot == EquipmentSlot.FEET) {
            draw("right_boot", pose.rightLeg, -1.9F, 12, ps, buffer, light, overlay, color);
            draw("left_boot", pose.leftLeg, 1.9F, 12, ps, buffer, light, overlay, color);
        }
    }

    private void draw(String name, ModelPart limb, float x, float y, PoseStack ps, VertexConsumer buffer,
                      int light, int overlay, int color) {
        if (!limb.visible) return;
        ps.pushPose();
        try {
            limb.translateAndRotate(ps);
            ps.translate(-x / 16.0F, -y / 16.0F, 0);
            geometry.getChild(name).render(ps, buffer, light, overlay, color);
        } finally {
            ps.popPose();
        }
    }
}
