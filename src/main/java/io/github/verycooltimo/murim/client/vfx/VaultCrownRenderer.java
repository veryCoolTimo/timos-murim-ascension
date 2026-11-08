package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.vault.VaultChestBlockEntity;
import io.github.verycooltimo.murim.sect.vault.VaultRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import java.util.function.IntPredicate;

/** Ambient archive crown, entirely client-side; no casts or gameplay particle spawning.
 * Reuses additive mote falloff, PlumVfx's petal atlas/billboards and existing VfxDraw geometry.
 * API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/renderer/blockentity/BlockEntityRenderer.java#render
 * /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/client/extensions/IBlockEntityRendererExtension.java#getRenderBoundingBox
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class VaultCrownRenderer implements BlockEntityRenderer<VaultChestBlockEntity> {
    static final double PETAL_SIZE = 0.075D;
    static final float PETAL_ALPHA = 0.65F * 0.85F;

    public VaultCrownRenderer(BlockEntityRendererProvider.Context context) {}

    @SubscribeEvent
    static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(VaultRegistry.VAULT_CHEST_ENTITY.get(), VaultCrownRenderer::new);
    }

    @Override public AABB getRenderBoundingBox(VaultChestBlockEntity chest) {
        // Conservative maximum avoids a second ceiling scan during frustum culling.
        return new AABB(chest.getBlockPos()).inflate(1.5D).expandTowards(0, 8, 0);
    }

    @Override public void render(VaultChestBlockEntity chest, float partial, PoseStack pose,
                                 MultiBufferSource buffers, int light, int overlay) {
        var level = chest.getLevel();
        if (level == null || !chest.crown()) return;
        double time = level.getGameTime() + partial;
        float fade = chest.crownTaken() ? (float) Math.max(0, 1 - (time - chest.crownTakenTime()) / 60.0D) : 1;
        if (fade <= 0) return;
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/renderer/GameRenderer.java#getMainCamera
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()
                .subtract(Vec3.atLowerCornerOf(chest.getBlockPos()));
        var beam = buffers.getBuffer(MurimRenderTypes.mote());
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/core/BlockPos.java#above(int)
        // Level.java#getBlockState; block/state/BlockBehaviour.java#BlockStateBase.isSolidRender
        double ceiling = ceilingHeight(offset -> {
            var pos = chest.getBlockPos().above(offset);
            return level.getBlockState(pos).isSolidRender(level, pos);
        });
        double bottom = 0.875D;
        float breath = 1 + 0.1F * (float) Math.sin(time * Math.PI * 2 / 80.0D);
        double distance = Math.hypot(camera.x - 0.5D, camera.z - 0.5D);
        float nearFade = nearCameraFade(distance);
        drawColumn(beam, pose.last(), camera, bottom, ceiling,
                fade * breath * nearFade * columnDistanceStrength(distance));
        drawPool(beam, pose.last(), ceiling - 0.005D, 0.6D, fade * nearFade * 0.3F, false);
        drawPool(beam, pose.last(), 0.005D, 1.2D, fade * nearFade * 0.25F, false);
        // sto_chest_closed.json: top tier is 12/16 high; patch fits both facing footprints.
        drawPool(beam, pose.last(), 0.755D, 1.2D, fade * nearFade * 0.25F, true);
        // AMBIENT: petals and dust share the remaining combat budget and particle option.
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/Options.java#particles
        int density = switch (Minecraft.getInstance().options.particles().get()) {
            case ALL -> 18;
            case DECREASED -> 7;
            case MINIMAL -> 0;
        };
        if (camera.lengthSqr() > 16 * 16) density = 0;
        int remaining = Math.max(0, VfxBudget.TOTAL - VfxBudget.live());
        int count = Math.min(density, remaining);
        int dustCount = Math.min(density == 18 ? 30 : density == 7 ? 12 : 0, remaining - count);
        for (int i = 0; i < dustCount; i++) {
            double phase = i * 2.39996D;
            double angle = phase + time * 0.002D;
            double radius = 0.12D + (i % 7) * 0.12D;
            double drift = 0.5D + 0.48D * Math.sin(time * 0.008D + phase);
            Vec3 at = new Vec3(0.5D + Math.cos(angle) * radius,
                    bottom + drift * (ceiling - bottom), 0.5D + Math.sin(angle) * radius);
            VfxDraw.billboard(beam, pose.last(), at, camera, (0.012D + (i % 4) * 0.006D) * 0.5D,
                    fade * 0.8F, 1.0F, 241 / 255.0F, 214 / 255.0F);
        }
        var petals = buffers.getBuffer(MurimRenderTypes.plumPetals());
        // Stable phased fall: no global mutable state; identical paths persist across chunk reloads.
        for (int i = 0; i < count; i++) {
            double fall = ((time + i * 137.0D) % 260.0D) / 260.0D;
            double angle = i * 2.39996D + time * 0.006D;
            double radius = 0.25D + (i % 5) * 0.19D;
            Vec3 at = new Vec3(0.5D + Math.cos(angle) * radius, ceiling - fall * (ceiling - bottom),
                    0.5D + Math.sin(angle) * radius);
            float alpha = fade * (float) Math.min(1, Math.min(fall, 1 - fall) * 9) * PETAL_ALPHA;
            PlumVfx.petal(petals, pose.last(), camera, at, PETAL_SIZE, i % 4,
                    (float) (time * 0.018D + i), alpha, 1.0F, 0.68F, 0.78F);
        }
    }

    /** First opaque ceiling underside, relative to the chest; at most eight block reads. */
    static int ceilingHeight(IntPredicate solidAtOffset) {
        for (int offset = 1; offset <= 8; offset++) {
            if (solidAtOffset.test(offset)) return offset;
        }
        return 8;
    }

    /** Cubic smoothstep over horizontal camera distance, independent of camera height. */
    static float nearCameraFade(double distance) {
        return smoothstep((distance - 1.5D) / 1.5D);
    }

    /** Round 3 review: 40% lighter through five blocks; preserve the far look from eight. */
    static float columnDistanceStrength(double distance) {
        return 0.6F + 0.4F * smoothstep((distance - 5.0D) / 3.0D);
    }

    /** Lowest 20% and highest 15% fade to zero at the endpoints. */
    static float endFeather(double heightFraction) {
        return smoothstep(heightFraction / 0.2D) * smoothstep((1 - heightFraction) / 0.15D);
    }

    private static float smoothstep(double value) {
        double t = Math.max(0, Math.min(1, value));
        return (float) (t * t * (3 - 2 * t));
    }

    /** Broad core with a cubic feather over the outer 30% of the half-width. */
    static float columnAlpha(double across) {
        double x = Math.min(1, Math.abs(across));
        // Clamp rounding overshoot at x == 1 so the edge is exactly transparent.
        double feather = Math.max(0, Math.min(1, (x - 0.7D) / 0.3D));
        double side = 1 - feather * feather;
        return (float) (0.35D * (1 - x / 3) * side * side * side);
    }

    private static void drawColumn(VertexConsumer buffer, PoseStack.Pose pose, Vec3 camera,
                                   double bottom, double top, float intensity) {
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/phys/Vec3.java
        // #subtract, normalize, cross, lengthSqr, scale, add.
        Vec3 normal = camera.subtract(0.5D, (bottom + top) * 0.5D, 0.5D).normalize();
        Vec3 side = new Vec3(0, 1, 0).cross(normal);
        if (side.lengthSqr() < 1.0E-6D) return;
        side = side.normalize().scale(1.45D);
        // Shared vertices, no overlapping layers. Subdivide both axes to feather every edge.
        for (int strip = 0; strip < 32; strip++) {
            double left = -1 + strip / 16.0D;
            double right = left + 0.0625D;
            for (int row = 0; row < 40; row++) {
                double low = row / 40.0D;
                double high = (row + 1) / 40.0D;
                columnVertex(buffer, pose, normal, side, left, low, bottom, top, intensity);
                columnVertex(buffer, pose, normal, side, left, high, bottom, top, intensity);
                columnVertex(buffer, pose, normal, side, right, high, bottom, top, intensity);
                columnVertex(buffer, pose, normal, side, right, low, bottom, top, intensity);
            }
        }
    }

    private static void columnVertex(VertexConsumer buffer, PoseStack.Pose pose, Vec3 normal,
                                     Vec3 side, double across, double height, double bottom,
                                     double top, float intensity) {
        float edge = (float) Math.abs(across);
        // Warm #FFDCA0 core, pale #FFF1D6 edges; core intensity is 1.5x before feathering.
        VfxDraw.vertex(buffer, pose,
                new Vec3(0.5D, bottom + height * (top - bottom), 0.5D).add(side.scale(across)), normal,
                0.5F, 0.5F, columnAlpha(across) * endFeather(height) * intensity,
                1.0F, (220 + 21 * edge) / 255.0F, (160 + 54 * edge) / 255.0F);
    }

    private static void drawPool(VertexConsumer buffer, PoseStack.Pose pose, double y,
                                 double radius, float intensity, boolean chestTop) {
        // Grid samples a radial cubic falloff, with zero alpha along the outer boundary.
        double extent = chestTop ? 0.26D : radius;
        for (int x = 0; x < 24; x++) {
            double left = -extent + 2 * extent * x / 24;
            double right = -extent + 2 * extent * (x + 1) / 24;
            for (int z = 0; z < 24; z++) {
                double low = -extent + 2 * extent * z / 24;
                double high = -extent + 2 * extent * (z + 1) / 24;
                poolVertex(buffer, pose, left, low, y, radius, intensity, chestTop);
                poolVertex(buffer, pose, left, high, y, radius, intensity, chestTop);
                poolVertex(buffer, pose, right, high, y, radius, intensity, chestTop);
                poolVertex(buffer, pose, right, low, y, radius, intensity, chestTop);
            }
        }
    }

    private static void poolVertex(VertexConsumer buffer, PoseStack.Pose pose, double x, double z,
                                   double y, double radius, float intensity, boolean chestTop) {
        double falloff = Math.max(0, 1 - (x * x + z * z) / (radius * radius));
        if (chestTop) {
            intensity *= smoothstep((0.26D - Math.abs(x)) / 0.08D)
                    * smoothstep((0.26D - Math.abs(z)) / 0.08D);
        }
        VfxDraw.vertex(buffer, pose, new Vec3(0.5D + x, y, 0.5D + z), new Vec3(0, 1, 0),
                0.5F, 0.5F, intensity * (float) (falloff * falloff * falloff),
                1, 220 / 255.0F, 160 / 255.0F);
    }
}
