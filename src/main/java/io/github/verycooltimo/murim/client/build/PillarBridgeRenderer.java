package io.github.verycooltimo.murim.client.build;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModBlocks;
import io.github.verycooltimo.murim.world.build.PillarBridgeBlockEntity;
import io.github.verycooltimo.murim.world.build.RedPillarBlock;
import io.github.verycooltimo.murim.world.build.TimberOffset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Draws the bridge beams of a column (PillarBridgeBlockEntity) with the standalone models
 * {@code murim:block/pillar_bridge_<axis>_<offset>_<level>_<slot>} (tools/art/building_kit.py), registered as
 * additional models so they bake once each — not once per blockstate as the old bridge properties did.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/client/event/ModelEvent.java#RegisterAdditional,
 * reference/minecraft-src/net/minecraft/client/resources/model/ModelResourceLocation.java#standalone,
 * ModelManager#getModel(ModelResourceLocation); drawing as in GateDoorRenderer (ModelBlockRenderer#renderModel).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class PillarBridgeRenderer implements BlockEntityRenderer<PillarBridgeBlockEntity> {

    private static final String[] LEVEL = {"low", "mid", "high"};
    private static final String[] SLOT = {"near", "mid", "far"};

    private final BlockRenderDispatcher blocks;

    public PillarBridgeRenderer(BlockEntityRendererProvider.Context context) {
        blocks = context.getBlockRenderDispatcher();
    }

    static ModelResourceLocation model(Direction.Axis axis, TimberOffset offset, RedPillarBlock.Bridge b) {
        String name = "block/pillar_bridge_" + (axis == Direction.Axis.X ? "x" : "z") + "_" + offset.getSerializedName()
                + "_" + LEVEL[b.level.ordinal()] + "_" + SLOT[b.otherSlot + 1];
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, name));
    }

    @SubscribeEvent
    static void onModels(ModelEvent.RegisterAdditional event) {
        for (Direction.Axis axis : new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z}) {
            for (TimberOffset o : TimberOffset.values()) {
                for (RedPillarBlock.Bridge b : RedPillarBlock.Bridge.values()) {
                    if (b != RedPillarBlock.Bridge.NONE) {
                        event.register(model(axis, o, b));
                    }
                }
            }
        }
    }

    /** One line per model bake: proof the column models baked (the 07.10 out-of-memory happened right here). */
    @SubscribeEvent
    static void onBaked(ModelEvent.BakingCompleted event) {
        MurimMod.LOGGER.info("Kit models baked: {} models in total, bridge model present: {}", event.getModels().size(),
                event.getModels().containsKey(model(Direction.Axis.X, TimberOffset.CENTER, RedPillarBlock.Bridge.MID_MID)));
    }

    @SubscribeEvent
    static void onRegister(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlocks.PILLAR_BRIDGE_ENTITY.get(), PillarBridgeRenderer::new);
    }

    @Override
    public AABB getRenderBoundingBox(PillarBridgeBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(1.0D, 0.0D, 1.0D);
    }

    @Override
    public void render(PillarBridgeBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof RedPillarBlock) || !state.getValue(RedPillarBlock.BRIDGED)) {
            return;
        }
        VertexConsumer buffer = buffers.getBuffer(Sheets.cutoutBlockSheet());
        for (Direction.Axis axis : new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z}) {
            RedPillarBlock.Bridge b = be.get(axis);
            if (b == RedPillarBlock.Bridge.NONE) {
                continue;
            }
            BakedModel m = Minecraft.getInstance().getModelManager().getModel(model(axis, state.getValue(RedPillarBlock.OFFSET), b));
            blocks.getModelRenderer().renderModel(pose.last(), buffer, state, m, 1.0F, 1.0F, 1.0F, light, overlay, ModelData.EMPTY, null);
        }
    }
}
