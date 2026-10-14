package io.github.verycooltimo.murim.client.decor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.bedrock.BedrockItemRenderer;
import io.github.verycooltimo.murim.decor.WeaponRacks;
import io.github.verycooltimo.murim.decor.WeaponStandBlock;
import io.github.verycooltimo.murim.decor.WeaponStandBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Swords stored on the weapon stands, drawn with their own item models through the item renderer (our wooden and
 * Huashan swords are the author's Blockbench model via {@link BedrockItemRenderer}; any other sword is its flat
 * sprite). Rack: upright, tip down on the floor plank, broad side to the front. Stand: lying along x, edge up, in
 * the forks, pommel to +x. Placement is in the model's north-facing pixels, then turned like the blockstate.
 *
 * <p>Item context NONE: no display transform; the item renderer shifts by (−0.5, −0.5, −0.5) and our BEWLR by
 * (+0.5, 0, +0.5), so a mesh point (geo units) lands at (x, y − 8, z)/16 — compensated by +0.5 y before the call.
 * API: reference/minecraft-src/net/minecraft/client/renderer/entity/ItemRenderer.java#render, #renderStatic, #getModel;
 * reference/neoforge-src/net/neoforged/neoforge/client/extensions/IBlockEntityRendererExtension.java#getRenderBoundingBox
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class WeaponStandRenderer implements BlockEntityRenderer<WeaponStandBlockEntity> {

    /**
     * Real size (author 06.10: «мечи на них должны быть реального размера»): the size a sword has in the player's
     * hand in third person = item display thirdperson_righthand scale 1.5 (models/item/huashan_sword.json,
     * wooden_sword.json) × the player model scale 0.9375 (PlayerRenderer#scale) = 1.40625. The Huashan mesh is
     * 23.15 geo units long, so a stored sword is about 32.6 px (2 blocks), as in hand. (A disciple holds it at 1.5,
     * 7 % larger; the belt sword of the disciple model is 1.0 — smaller than the drawn one, the author's split.)
     */
    private static final float REAL = 1.5F * 0.9375F;
    /** Rack: tip on the floor plank (y 1.5), blades in the slotted rails, guards above the top rail. */
    private static final float RACK_FLOOR = 1.5F;
    /**
     * Stand (author 06.10: «меч не ровно лежит»): the blade lies level, edge up, its lower edge on the fork bottoms
     * (y 6.25) and its flat centred in the fork gap (z 8.5). Blade geometry from huashan_sword.geo.json (the
     * wooden sword is the same mesh): bone2 main cube x −0.65..0.65, z ±0.075 (ridge ±0.15), no rotation — the
     * blade axis is the mesh's x = z = 0 line along +y, so the axis sits 0.65 × scale above the fork bottoms.
     * The tip is at x −8.3 (sword centred on the block); the guard's blade-side face (geo y 5.75, the curled arms) is
     * then at x 15.82, just outside the hilt-side upright (14.92..15.67), as on the ref.
     */
    private static final float BLADE_HALF_WIDTH = 0.65F;
    private static final float STAND_TIP_X = -8.3F;
    private static final float STAND_FORK_Y = 6.25F;
    private static final float STAND_Z = 8.5F;
    /** Flat sprites (vanilla swords): handheld thirdperson scale 0.85 × 0.9375, turned so the tip points down / to −x. */
    private static final float SPRITE_SCALE = 0.85F * 0.9375F;

    /**
     * The real scabbard (author 06.10: «почему гарда не настоящая» — «гарда» is his name for the scabbard: the bone
     * {@code guard} of sect_disciple holds the sheath and the sheathed hilt). Under a stored Huashan sword the stand
     * shows that very scabbard, not a block-model stand-in: the {@code guard} bone of sect_disciple.geo.json without
     * its {@code handle}, with the disciple texture. In the bone's own space
     * (pose zeroed) the sheath runs along ModelPart y from the mouth (y +2.15, model y 10.8) to the chape (y −16.02,
     * model y 28.97), 1.6 wide (x) and 0.75 deep (z), fitted to the blade below. It lies on the front hooks (top y 3.0),
     * mouth to the hilt at x 15.6. Training (wooden) swords have no scabbard, as on the third-generation belt.
     */
    private static final String DISCIPLE_GEO = "/assets/murim/bedrock/sect_disciple.geo.json";
    private static final net.minecraft.resources.ResourceLocation SCABBARD_TEXTURE = net.minecraft.resources.ResourceLocation
            .fromNamespaceAndPath(MurimMod.MODID, "textures/entity/sect/second_rate_disciple.png");
    private static final float SCABBARD_MOUTH_MP_Y = 12.95F - 10.8F;
    private static final float SCABBARD_MOUTH_X = 15.6F;
    private static final float SCABBARD_HOOK_Y = 3.0F;
    private static final float SCABBARD_HALF_WIDTH = 0.8F;
    private static final float SCABBARD_Z = 5.5F;
    /**
     * The sheath fitted like a saya to the real-size blade (Huashan mesh: blade geo y 5.15..22.9 → 24.96 px long,
     * 1.3 → 1.83 px wide, ridge 0.3 → 0.42 px thick). Sheath model: 18.17 long, 1.6 wide (bands), 0.75 deep.
     * Length: from the mouth at the hilt fork (x 15.6) to 1 px past the tip (x −9.3) = 24.9 → ×1.37.
     * Width: the blade's width → ×1.14. Depth: the blade's thickness + 0.25 px a side → ×1.2.
     */
    private static final float SCABBARD_LENGTH_SCALE = 24.9F / 18.17F;
    private static final float SCABBARD_WIDTH_SCALE = 1.83F / 1.6F;
    private static final float SCABBARD_DEPTH_SCALE = 0.9F / 0.75F;
    private static net.minecraft.client.model.geom.ModelPart scabbard;

    private final ItemRenderer items;

    /** The disciple model's sheath, baked once: guard bone with its pose zeroed and the sheathed hilt hidden. */
    private static net.minecraft.client.model.geom.ModelPart scabbard() {
        if (scabbard == null) {
            net.minecraft.client.model.geom.ModelPart root = io.github.verycooltimo.murim.client.bedrock.BedrockGeo.bake(DISCIPLE_GEO);
            net.minecraft.client.model.geom.ModelPart guard = root.getChild("root").getChild("waist").getChild("torso").getChild("guard");
            guard.loadPose(net.minecraft.client.model.geom.PartPose.ZERO);
            guard.getChild("handle").visible = false;
            scabbard = guard;
        }
        return scabbard;
    }

    private static void renderScabbard(PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        // ModelPart y (mouth → chape) runs to −x; the sheath width (ModelPart x) stands up.
        pose.translate(SCABBARD_MOUTH_X / 16.0F, (SCABBARD_HOOK_Y + SCABBARD_WIDTH_SCALE * SCABBARD_HALF_WIDTH) / 16.0F,
                SCABBARD_Z / 16.0F);
        pose.mulPose(Axis.ZP.rotationDegrees(-90.0F));
        // Fitted to the sword, not a uniform scale (a uniform 1.4 made a fat log): length, width, depth on their own.
        pose.scale(SCABBARD_WIDTH_SCALE, SCABBARD_LENGTH_SCALE, SCABBARD_DEPTH_SCALE);
        pose.translate(0.0F, -SCABBARD_MOUTH_MP_Y / 16.0F, 0.0F);
        scabbard().render(pose, buffers.getBuffer(net.minecraft.client.renderer.RenderType.entityCutoutNoCull(SCABBARD_TEXTURE)),
                light, overlay);
        pose.popPose();
    }

    public WeaponStandRenderer(BlockEntityRendererProvider.Context context) {
        items = context.getItemRenderer();
    }

    @SubscribeEvent
    static void onRegister(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(WeaponRacks.WEAPON_STAND_ENTITY.get(), WeaponStandRenderer::new);
    }

    @Override
    public AABB getRenderBoundingBox(WeaponStandBlockEntity be) {
        // Real-size swords: rack hilts reach 2.2 blocks up, the stand sword up to 0.65 block past a side.
        return new AABB(be.getBlockPos()).inflate(0.7D, 0.0D, 0.7D).expandTowards(0.0D, 1.3D, 0.0D);
    }

    @Override
    public void render(WeaponStandBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof WeaponStandBlock block)) {
            return;
        }
        boolean rack = block.kind() == WeaponStandBlock.Kind.RACK;
        pose.pushPose();
        // Into the north-facing model frame (same turn as the blockstate y rotation).
        pose.translate(0.5F, 0.0F, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - state.getValue(WeaponStandBlock.FACING).toYRot()));
        pose.translate(-0.5F, 0.0F, -0.5F);
        for (int slot = 0; slot < be.items().size(); slot++) {
            ItemStack stack = be.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (!rack && stack.is(io.github.verycooltimo.murim.registry.ModItems.HUASHAN_SWORD.get())) {
                renderScabbard(pose, buffers, light, overlay);
            }
            pose.pushPose();
            boolean mesh = items.getModel(stack, be.getLevel(), null, 0).isCustomRenderer();
            if (mesh) {
                float minY = BedrockItemRenderer.HUASHAN_SWORD.minY();
                float maxY = BedrockItemRenderer.HUASHAN_SWORD.maxY();
                if (rack) {
                    float x = block.kind().slotX(slot);
                    pose.translate(x / 16.0F, (RACK_FLOOR + REAL * maxY) / 16.0F, 0.5F);
                    pose.scale(REAL, REAL, REAL);
                    pose.mulPose(Axis.ZP.rotationDegrees(180.0F));
                } else {
                    // Turned +90° about z: mesh +y (to the tip) runs to −x, mesh +x (blade width) to +y. Tip (maxY) at
                    // STAND_TIP_X, so the pommel (minY) is at tip + length; blade axis half a width above the forks.
                    float pommelX = STAND_TIP_X + REAL * (maxY - minY);
                    pose.translate((pommelX + REAL * minY) / 16.0F, (STAND_FORK_Y + REAL * BLADE_HALF_WIDTH) / 16.0F,
                            STAND_Z / 16.0F);
                    pose.scale(REAL, REAL, REAL);
                    pose.mulPose(Axis.ZP.rotationDegrees(90.0F));
                }
                pose.translate(0.0F, 0.5F, 0.0F);
            } else if (rack) {
                // Sprite sword spans about 0.75 of its 1-block square diagonally: centre so the tip meets the plank.
                pose.translate(block.kind().slotX(slot) / 16.0F, RACK_FLOOR / 16.0F + 0.75F * SPRITE_SCALE, 0.5F);
                pose.scale(SPRITE_SCALE, SPRITE_SCALE, SPRITE_SCALE);
                pose.mulPose(Axis.ZP.rotationDegrees(-135.0F));
            } else {
                pose.translate(8.0F / 16.0F, (STAND_FORK_Y + 0.5F) / 16.0F, STAND_Z / 16.0F);
                pose.scale(SPRITE_SCALE, SPRITE_SCALE, SPRITE_SCALE);
                pose.mulPose(Axis.ZP.rotationDegrees(135.0F));
            }
            items.renderStatic(stack, ItemDisplayContext.NONE, light, overlay, pose, buffers, be.getLevel(),
                    (int) be.getBlockPos().asLong() + slot);
            pose.popPose();
        }
        pose.popPose();
    }
}
