package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class TechniqueGroundLight {
    private TechniqueGroundLight() {}
    // API: supplied neoforge RenderLevelStageEvent; minecraft MultiBufferSource#immediate/endBatch.
    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        var lights = TechniqueLight.active();
        if (lights.isEmpty() || TechniqueBodyLight.failed()) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        try (ByteBufferBuilder memory = new ByteBufferBuilder(4096)) {
            var buffers = MultiBufferSource.immediate(memory);
            var out = buffers.getBuffer(TechniqueLightRenderTypes.GROUND);
            for (var light : lights) for (var cell : light.cells()) {
                double y = cell.y() + .004;
                vertex(out, light, cell.x0(), y, cell.z0(), camera, partial);
                vertex(out, light, cell.x0(), y, cell.z1(), camera, partial);
                vertex(out, light, cell.x1(), y, cell.z1(), camera, partial);
                vertex(out, light, cell.x1(), y, cell.z0(), camera, partial);
            }
            buffers.endBatch();
        }
    }
    private static void vertex(VertexConsumer out, TechniqueLight.Light light, double x, double y, double z, Vec3 camera, float partial) {
        float alpha = TechniqueLightMath.groundAlpha(light.power(partial),
                light.position().distanceTo(new Vec3(x, y, z)), light.radius);
        out.addVertex((float) (x - camera.x), (float) (y - camera.y), (float) (z - camera.z))
                .setColor((light.rgb >> 16) & 255, (light.rgb >> 8) & 255, light.rgb & 255, (int) (255 * alpha));
    }
}
