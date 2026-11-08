package io.github.verycooltimo.murim.client.training;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.training.Exercise;
import io.github.verycooltimo.murim.training.TrainingRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * The training stone on the player model (third person and for others): held at the chest while carrying. Attached to
 * the torso part, so it follows the PAL clip (PAL poses the model parts before layers render, as with armour).
 *
 * <p>API: reference/minecraft-src/net/minecraft/client/renderer/entity/ItemRenderer.java#renderStatic,
 * reference/minecraft-src/net/minecraft/client/model/geom/ModelPart.java#translateAndRotate
 */
public final class TrainingPropLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    public TrainingPropLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing,
                       float limbSwingAmount, float partial, float age, float headYaw, float headPitch) {
        Exercise e = ClientTraining.of(player.getId());
        if (e == null || player.isInvisible()) {
            return;
        }
        if (e != Exercise.CARRY_STONE) {
            return;
        }
        ItemStack stack = new ItemStack(TrainingRegistry.TRAINING_STONE_ITEM.get());
        pose.pushPose();
        getParentModel().body.translateAndRotate(pose);
        // In front of the belly, between the forearms of the carry clip.
        pose.translate(0.0D, 0.55D, -0.42D);
        pose.scale(0.85F, 0.85F, 0.85F);
        Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY,
                pose, buffers, player.level(), player.getId());
        pose.popPose();
    }
}
