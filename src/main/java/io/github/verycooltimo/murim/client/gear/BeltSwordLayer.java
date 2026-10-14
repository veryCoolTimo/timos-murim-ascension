package io.github.verycooltimo.murim.client.gear;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.gear.BeltSword;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;

/**
 * The belt sword on the player's left hip (gear/BeltSword): the server says which sword; a sword in either hand
 * hides it at once (hand items are synced, so this needs no round trip). Placement is the sect disciple's sheath —
 * bone {@code guard} of sect_disciple.geo.json, pivot (4.55, 12.95, −1.75), rotation (−90, 45, 90), as BedrockGeo
 * bakes it: root (0, 24, 0) → waist/torso (0, 12, 0) → guard at (4.55, 11.05, −1.75) model px. The player's body
 * part pivots at the neck (model origin at rest), so the same offset from its frame puts the sword on the hip, and
 * crouch / walk / PAL clips that move the body carry it along (as with armour). First person draws no player model,
 * so the layer is not seen there.
 *
 * <p>The sword is the item's own model through the item renderer, as on the weapon racks (WeaponStandRenderer):
 * our Huashan and wooden swords are the author's Blockbench mesh, any other sword its flat sprite. Context NONE:
 * the item renderer shifts (−0.5, −0.5, −0.5), our BEWLR (+0.5, 0, +0.5), and the mesh is in Blockbench space
 * (x mirrored against the geo file). The disciple draws the same geo as a ModelPart (x as in the file, y down from
 * 24) shifted −16.725 px, i.e. mesh point y ↦ 7.275 − y: a turn of 180° about z and +7.275 px gives the same.
 *
 * <p>API: reference/minecraft-src/net/minecraft/client/model/geom/ModelPart.java#translateAndRotate (rotationZYX),
 * reference/minecraft-src/net/minecraft/client/renderer/entity/ItemRenderer.java#renderStatic
 */
public final class BeltSwordLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    private static final float GUARD_X = 4.55F;
    private static final float GUARD_Y = 11.05F;
    private static final float GUARD_Z = -1.75F;
    private static final Quaternionf GUARD_TURN = new Quaternionf().rotationZYX(
            (float) Math.toRadians(90.0F), (float) Math.toRadians(45.0F), (float) Math.toRadians(-90.0F));
    /** BeltWoodSword: the geo drawn 16.725 px up its bone, so mesh y ↦ 7.275 − y in the guard frame. */
    private static final float MESH_SHIFT = 24.0F - 16.725F;
    /**
     * Size of our mesh swords: exactly the disciple's belt sword (BeltWoodSword draws the geo at 1.0 in its model
     * space — the author's split: the sheathed sword is about 1.5× smaller than the drawn one, item display
     * thirdperson scale 1.5), so a player and a disciple wear the same sword alike.
     */
    private static final float MESH_SCALE = 1.0F;
    /**
     * Crossguard of the Huashan mesh (huashan_sword.geo.json, guard cubes at geo y ≈ 6.5): 7.275 − 6.5 px down the
     * guard frame. Sprite swords put their own crossguard here too.
     */
    private static final float CROSSGUARD_Y = (MESH_SHIFT - 6.5F) / 16.0F;
    /**
     * Flat sprites (vanilla swords): handheld thirdperson scale 0.85, shrunk by the same 1/1.5 as the mesh
     * (drawn 1.5 → belt 1.0).
     */
    private static final float SPRITE_SCALE = 0.85F / 1.5F;
    /**
     * Vanilla sword sprites: the crossguard sits near pixel (4.5, 11.5) of the 16×16 icon, about 0.31 block from the
     * icon centre along the handle–tip diagonal. [НЕПРОВЕРЕНО: per-sprite; checked by eye on the iron sword frame.]
     */
    private static final float SPRITE_GUARD_FROM_CENTRE = 0.31F;

    /**
     * Huashan sword: sheathed exactly like a second-generation disciple (author 07.10: «он просто на поясе без
     * гарды» — «гарда» is the disciple model's bone {@code guard}: scabbard with the hilt, child {@code handle}).
     * The bone is drawn from sect_disciple.geo.json with the second-generation texture, through the same bone
     * chain as on the disciple (root → waist → torso → guard), so place and size match his.
     */
    private static final String DISCIPLE_GEO = "/assets/murim/bedrock/sect_disciple.geo.json";
    private static final String[] SHEATH_CHAIN = {"root", "waist", "torso"};
    private static final net.minecraft.resources.ResourceLocation SHEATH_TEXTURE = net.minecraft.resources.ResourceLocation
            .fromNamespaceAndPath(io.github.verycooltimo.murim.MurimMod.MODID, "textures/entity/sect/second_rate_disciple.png");
    /** Baked once on first use: the disciple model's root (only the guard chain is drawn). */
    private net.minecraft.client.model.geom.ModelPart disciple;

    public BeltSwordLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing,
                       float limbSwingAmount, float partial, float age, float headYaw, float headPitch) {
        if (player.isInvisible() || player.isSpectator()) {
            return;
        }
        net.minecraft.world.item.Item huashan = io.github.verycooltimo.murim.registry.ModItems.HUASHAN_SWORD.get();
        if (player.getMainHandItem().is(huashan) || player.getOffhandItem().is(huashan)) {
            // Drawn Huashan sword: the scabbard stays on the hip, only the hilt is gone — as on a disciple (author
            // 07.10: «почему когда меч в руках, гарда пропадает»). Hand items are synced, so no belt data is needed.
            pose.pushPose();
            try {
                getParentModel().body.translateAndRotate(pose);
                renderSheath(pose, buffers, light, false);
            } finally {
                pose.popPose();
            }
            return;
        }
        if (BeltSword.isSword(player.getMainHandItem()) || BeltSword.isSword(player.getOffhandItem())) {
            return;
        }
        ItemStack stack = ClientBeltSwords.of(player);
        if (stack.isEmpty()) {
            return;
        }
        ItemRenderer items = Minecraft.getInstance().getItemRenderer();
        pose.pushPose();
        try {
            getParentModel().body.translateAndRotate(pose);
            if (stack.is(io.github.verycooltimo.murim.registry.ModItems.HUASHAN_SWORD.get())) {
                renderSheath(pose, buffers, light, true);
                return;
            }
            pose.translate(GUARD_X / 16.0F, GUARD_Y / 16.0F, GUARD_Z / 16.0F);
            pose.mulPose(GUARD_TURN);
            if (items.getModel(stack, player.level(), player, 0).isCustomRenderer()) {
                pose.scale(MESH_SCALE, MESH_SCALE, MESH_SCALE);
                pose.translate(0.0F, MESH_SHIFT / 16.0F, 0.0F);
                pose.mulPose(Axis.ZP.rotationDegrees(180.0F));
                // Undo the net −0.5 y of the item renderer + BEWLR (WeaponStandRenderer).
                pose.translate(0.0F, 0.5F, 0.0F);
            } else {
                // The sprite's blade runs along its diagonal (handle low-left, tip up-right): turned −135° about z the
                // tip points along −y (down the sheath line); the centre goes tip-wards so the crossguard meets the
                // mesh crossguard point.
                pose.translate(0.0F, CROSSGUARD_Y - SPRITE_GUARD_FROM_CENTRE * SPRITE_SCALE, 0.0F);
                pose.scale(SPRITE_SCALE, SPRITE_SCALE, SPRITE_SCALE);
                pose.mulPose(Axis.ZP.rotationDegrees(-135.0F));
            }
            items.renderStatic(stack, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY, pose, buffers,
                    player.level(), player.getId());
        } finally {
            pose.popPose();
        }
    }

    /**
     * The disciple's scabbard, with the hilt (child {@code handle}) when sheathed, in the player's body frame (= model
     * frame at rest). The disciple hides the same child when his sword is drawn (DiscipleRenderer#setupAnim).
     */
    private void renderSheath(PoseStack pose, MultiBufferSource buffers, int light, boolean hilt) {
        if (disciple == null) {
            disciple = io.github.verycooltimo.murim.client.bedrock.BedrockGeo.bake(DISCIPLE_GEO);
        }
        net.minecraft.client.model.geom.ModelPart part = disciple;
        for (String bone : SHEATH_CHAIN) {
            if (!part.hasChild(bone)) {
                return;
            }
            part = part.getChild(bone);
            part.translateAndRotate(pose);
        }
        if (part.hasChild("guard")) {
            net.minecraft.client.model.geom.ModelPart guard = part.getChild("guard");
            if (guard.hasChild("handle")) {
                guard.getChild("handle").visible = hilt;
            }
            // API: reference/minecraft-src/net/minecraft/client/model/geom/ModelPart.java#render (applies its own pose)
            guard.render(pose,
                    buffers.getBuffer(net.minecraft.client.renderer.RenderType.entityCutoutNoCull(SHEATH_TEXTURE)),
                    light, OverlayTexture.NO_OVERLAY);
        }
    }
}
