package io.github.verycooltimo.murim.client.npc;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.bandit.BanditRenderer;
import io.github.verycooltimo.murim.client.trade.PeddlerRenderer;
import io.github.verycooltimo.murim.npc.GuestFighter;
import io.github.verycooltimo.murim.npc.ModNpcs;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Existing author rig, UVs, textures and clips. The two false figures are render passes, never entities. */
public final class GuestRenderer extends BanditRenderer<GuestFighter> {
    public GuestRenderer(EntityRendererProvider.Context context) {
        super(context, SWORDSMAN_LAYER, "bandit", false);
    }

    @Override public ResourceLocation getTextureLocation(GuestFighter entity) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/" + entity.profile().texture + ".png");
    }

    @Override protected float getWhiteOverlayProgress(GuestFighter entity, float partial) {
        float left = entity.windupDuration() - entity.stateAge(partial);
        return entity.state() == io.github.verycooltimo.murim.entity.Bandit.WINDUP && left >= 0 && left <= 3 ? 0.35F : 0;
    }

    @Override public void render(GuestFighter entity, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        super.render(entity, yaw, partial, pose, buffers, light);
        if (entity.figuresTicks() <= 0) return;
        // Render model only: no copied names, shadows, colliders, targets or damage sources.
        for (int side : new int[] {-1, 1}) {
            pose.pushPose();
            pose.translate(side * 1.4, 0, 0.5);
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180 - yaw));
            pose.scale(-1, -1, 1);
            pose.translate(0, -1.501, 0);
            var translucent = buffers.getBuffer(net.minecraft.client.renderer.RenderType.entityTranslucent(getTextureLocation(entity)));
            model.renderToBuffer(pose, translucent, light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0x5597ABC7);
            pose.popPose();
        }
    }

    /**
     * Listener lives in its own class: NeoForge refuses to register a subclass of BanditRenderer (which has its own
     * {@code @SubscribeEvent}) as a listener — the client crashed on load (11.10).
     */
    @EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Events {
        @SubscribeEvent
        static void renderers(EntityRenderersEvent.RegisterRenderers event) {
            ModNpcs.FIGHTERS.values().forEach(t -> event.registerEntityRenderer(t.get(), GuestRenderer::new));
            event.registerEntityRenderer(ModNpcs.INFORMANT.get(), PeddlerRenderer::new);
        }

        private Events() {
        }
    }
}
