package io.github.verycooltimo.murim.client.decor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.GateDoorBlock;
import io.github.verycooltimo.murim.decor.GateDoorBlockEntity;
import io.github.verycooltimo.murim.decor.GateDoors;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Draws the whole 4x4 gate from its origin part: the sixteen part models (the blockstate's own, north-facing ones,
 * tools/art/gate_doors.py) placed in the north frame and turned like the blockstate. Each leaf (COL 0-1 and COL 2-3)
 * swings inwards (+z in the north frame) about its outer back edge: the left hinge at x 1, the right at x −3
 * (columns run to −x, the viewer's right in a north-facing frame), z 9.5 px (the leaf's back face).
 *
 * <p>API: reference/minecraft-src/net/minecraft/client/renderer/block/ModelBlockRenderer.java#renderModel (NeoForge
 * overload with ModelData and RenderType), BlockRenderDispatcher.java#renderSingleBlock (how a block model is drawn into a
 * BER buffer: RenderTypeHelper#getEntityRenderType = Sheets.cutoutBlockSheet), LevelRenderer.java#getLightColor;
 * reference/neoforge-src/net/neoforged/neoforge/client/extensions/IBlockEntityRendererExtension.java#getRenderBoundingBox.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class GateDoorRenderer implements BlockEntityRenderer<GateDoorBlockEntity> {

    private static final float HINGE_Z = 9.5F / 16.0F;

    private final BlockRenderDispatcher blocks;

    public GateDoorRenderer(BlockEntityRendererProvider.Context context) {
        blocks = context.getBlockRenderDispatcher();
    }

    @SubscribeEvent
    static void onRegister(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(GateDoors.GATE_DOOR_ENTITY.get(), GateDoorRenderer::new);
    }

    @Override
    public AABB getRenderBoundingBox(GateDoorBlockEntity be) {
        // 4 wide to either side (any facing), 4 high, open leaves reach 2 blocks behind.
        return new AABB(be.getBlockPos()).inflate(4.0D, 0.0D, 4.0D).expandTowards(0.0D, 4.0D, 0.0D);
    }

    @Override
    public void render(GateDoorBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        BlockState state = be.getBlockState();
        Level level = be.getLevel();
        if (!(state.getBlock() instanceof GateDoorBlock) || level == null) {
            return;
        }
        Direction facing = state.getValue(GateDoorBlock.FACING);
        float angle = 90.0F * be.openness(partialTick);
        VertexConsumer buffer = buffers.getBuffer(Sheets.cutoutBlockSheet());
        BlockState north = state.setValue(GateDoorBlock.FACING, Direction.NORTH).setValue(GateDoorBlock.OPEN, false);

        pose.pushPose();
        // Into the north-facing model frame (same turn as the blockstate y rotation, see WeaponStandRenderer).
        pose.translate(0.5F, 0.0F, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - facing.toYRot()));
        pose.translate(-0.5F, 0.0F, -0.5F);
        for (int leaf = 0; leaf < 2; leaf++) {
            float hingeX = leaf == 0 ? 1.0F : -3.0F;
            pose.pushPose();
            pose.translate(hingeX, 0.0F, HINGE_Z);
            pose.mulPose(Axis.YP.rotationDegrees(leaf == 0 ? angle : -angle));
            pose.translate(-hingeX, 0.0F, -HINGE_Z);
            for (int c = leaf * 2; c < leaf * 2 + 2; c++) {
                for (int r = 0; r < GateDoorBlock.SIZE; r++) {
                    BlockPos at = GateDoorBlock.partPos(be.getBlockPos(), facing, c, r);
                    BlockState part = north.setValue(GateDoorBlock.COL, c).setValue(GateDoorBlock.ROW, r);
                    pose.pushPose();
                    pose.translate(-c, r, 0.0F);
                    blocks.getModelRenderer().renderModel(pose.last(), buffer, part, blocks.getBlockModel(part),
                            1.0F, 1.0F, 1.0F, LevelRenderer.getLightColor(level, at), overlay, ModelData.EMPTY, null);
                    pose.popPose();
                }
            }
            pose.popPose();
        }
        pose.popPose();
    }
}
