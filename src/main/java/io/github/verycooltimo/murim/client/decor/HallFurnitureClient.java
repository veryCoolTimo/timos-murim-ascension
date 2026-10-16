package io.github.verycooltimo.murim.client.decor;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.HallFurniture;
import io.github.verycooltimo.murim.decor.SectBellBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Quaternionf;

/**
 * Client side of the hall furniture: the cushion seat draws nothing (NoopRenderer), the sect bell's body (the
 * standalone OBJ model models/block/sect_bell_body.json from tools/art/hall_furniture.py) is drawn by a block entity
 * renderer and swings about the pin (y 13.6 px) with the vanilla bell's damped curve, its mouth moving towards the
 * direction the striker faced.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/client/event/ModelEvent.java#RegisterAdditional (standalone
 * variant), reference/minecraft-src/net/minecraft/client/resources/model/ModelManager.java#getModel,
 * client/renderer/blockentity/BellRenderer.java#render (swing curve), client/renderer/block/ModelBlockRenderer.java
 * #renderModel (NeoForge overload, as client/decor/GateDoorRenderer), client/renderer/entity/NoopRenderer.java.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class HallFurnitureClient {

    public static final ModelResourceLocation BELL_BODY =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "block/sect_bell_body"));
    private static final float PIVOT_Y = 14.2F / 16.0F;

    private HallFurnitureClient() {
    }

    @SubscribeEvent
    static void onModels(ModelEvent.RegisterAdditional event) {
        event.register(BELL_BODY);
    }

    @SubscribeEvent
    static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(HallFurniture.SEAT.get(), NoopRenderer::new);
        event.registerBlockEntityRenderer(HallFurniture.SECT_BELL_ENTITY.get(), BellBodyRenderer::new);
    }

    static final class BellBodyRenderer implements BlockEntityRenderer<SectBellBlockEntity> {

        private final BlockRenderDispatcher blocks;

        BellBodyRenderer(BlockEntityRendererProvider.Context context) {
            blocks = context.getBlockRenderDispatcher();
        }

        @Override
        public AABB getRenderBoundingBox(SectBellBlockEntity be) {
            return new AABB(be.getBlockPos()).expandTowards(0.0D, -1.2D, 0.0D).inflate(0.5D, 0.0D, 0.5D);
        }

        @Override
        public void render(SectBellBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            BakedModel model = Minecraft.getInstance().getModelManager().getModel(BELL_BODY);
            pose.pushPose();
            if (be.shaking()) {
                float t = be.ticks() + partialTick;
                float angle = Mth.sin(t / (float) Math.PI) / (4.0F + t / 3.0F);
                Direction d = be.towards();
                // axis = down x d turns the mouth (below the pin) towards d for a positive angle
                float ax = -d.getStepZ();
                float az = d.getStepX();
                pose.translate(0.5F, PIVOT_Y, 0.5F);
                pose.mulPose(new Quaternionf().rotateAxis(angle, ax, 0.0F, az));
                pose.translate(-0.5F, -PIVOT_Y, -0.5F);
            }
            blocks.getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()), be.getBlockState(),
                    model, 1.0F, 1.0F, 1.0F, light, overlay, ModelData.EMPTY, null);
            pose.popPose();
        }
    }
}
