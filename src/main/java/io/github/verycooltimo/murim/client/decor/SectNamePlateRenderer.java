package io.github.verycooltimo.murim.client.decor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.SectNamePlate;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Hanging room tablet, with fixed English ink and a fixed 14 × 9 pixel baked wooden frame.
 * API: client/decor/GateDoorRenderer#render (baked model), HallFurnitureClient.BellBodyRenderer (bounds),
 * reference/minecraft-src/net/minecraft/client/gui/Font.java#drawInBatch.
 * [НЕПРОВЕРЕНО: visual size/readability; check in game at 3–4 blocks on the tester's machine.]
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class SectNamePlateRenderer implements BlockEntityRenderer<SectNamePlate.Entity> {
    private static final int NAME_COLOUR = 0xFF2A1D17;
    // Model pixels: x=1..15, y=3..12, paper x=2.5..13.5, y=4.5..10.5, front z=2.5.
    private static final float TEXT_WIDTH = 11.0F / 16.0F; // Inside the 1.5 px frame; also below the 14 px cap.
    private static final float TEXT_SCALE = 0.012F;

    static float textScale(float width, boolean main) {
        return Math.min(main ? TEXT_SCALE : 0.008F, TEXT_WIDTH / Math.max(1.0F, width));
    }

    private final net.minecraft.client.renderer.block.BlockRenderDispatcher blocks;
    private final Font font;

    public SectNamePlateRenderer(BlockEntityRendererProvider.Context context) {
        font = context.getFont();
        blocks = context.getBlockRenderDispatcher();
    }

    @SubscribeEvent
    static void onRegister(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(SectNamePlate.ENTITY.get(), SectNamePlateRenderer::new);
    }

    @Override
    public void render(SectNamePlate.Entity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        RoomPlateText.Lines text = RoomPlateText.resolve(be.isWayfinding(), be.ownerName());
        float yaw = be.getBlockState().getValue(HorizontalDirectionalBlock.FACING).toYRot();
        pose.pushPose();
        // Same local +z-facing frame as the existing text. Model's south face is the panel.
        pose.translate(0.5F, 0.5F, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
        // Pivot at the nail's upper front edge so even the tilted suspension stays inside the cell.
        // Negative X rotation brings the tablet bottom away from the wall.
        // API: client/decor/PillBoxClient#render uses Axis.XP.rotationDegrees with this PoseStack.
        pose.translate(0.0F, 8.0F / 16.0F, -0.5F + 2.75F / 16.0F);
        pose.mulPose(Axis.XP.rotationDegrees(-4.0F));
        pose.translate(0.0F, -8.0F / 16.0F, 0.5F - 2.75F / 16.0F);
        pose.pushPose();
        pose.translate(-0.5F, -0.5F, -0.5F);
        var north = be.getBlockState().setValue(HorizontalDirectionalBlock.FACING, net.minecraft.core.Direction.NORTH);
        blocks.getModelRenderer().renderModel(pose.last(), buffers.getBuffer(net.minecraft.client.renderer.Sheets.cutoutBlockSheet()),
                north, blocks.getBlockModel(north), 1.0F, 1.0F, 1.0F, light, overlay,
                net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null);
        pose.popPose();
        // Paper south face: z=2.5/16; the ink shares the model tilt and sits 0.002 blocks in front.
        pose.translate(0.0F, 0.0F, -0.5F + 2.5F / 16.0F + 0.002F);
        light = net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
        if (!be.ownerName().isEmpty()) {
            line(pose, buffers, light, Component.literal(text.title()), 0.045F, true);
            line(pose, buffers, light, Component.literal(text.name()), -0.13F, false);
        }
        pose.popPose();
    }

    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox(SectNamePlate.Entity be) {
        return new net.minecraft.world.phys.AABB(be.getBlockPos());
    }

    private void line(PoseStack pose, MultiBufferSource buffers, int light, Component text, float y, boolean main) {
        float width = font.width(text);
        float scale = textScale(width, main);
        pose.pushPose();
        pose.translate(0.0F, y, 0.0F);
        pose.scale(scale, -scale, scale);
        font.drawInBatch(text, -width / 2.0F, -font.lineHeight / 2.0F, NAME_COLOUR, false, pose.last().pose(), buffers,
                Font.DisplayMode.NORMAL, 0, light);
        pose.popPose();
    }
}
