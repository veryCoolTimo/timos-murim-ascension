package io.github.verycooltimo.murim.client.vfx.bt;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * The breakthrough fire (and the Peak's shell and essences) light the body. Author 31.10: «огонь супер, но можно добавить свечение от него, чтобы герой чутка подсвечивался»;
 * the lab's br1LightOn puts the fire's blue on the robe and never on the face. The player's own model is drawn once more, additively,
 * with the fire's blue times its own skin colour (lit = albedo x light): strongest where the surface turns away from the camera, towards
 * the fire at the sides and behind, a little over the rest of the body, and on the head only at its sides, so the face stays clear.
 * Base parts only: the overlay layers have see-through pixels whose colour would light up as a ghost layer.
 * API: RenderType#eyes (additive; the same geometry passes the LEQUAL depth test), ModelPart#render(PoseStack, VertexConsumer, int, int),
 * VertexConsumer#addVertex(FFFIFFIIFFF) as ModelPart.Cube#compile calls it (camera-relative positions, unit world normals).
 */
public final class BtFireLight extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    /** Light over the whole body and the extra light at the rim, at full fire. */
    static final float BASE = .30F, RIM = 1.05F, HEAD_RIM = .55F;

    /** The light of the running scene on the body: a gain (1 = the steady fire) and its colour. */
    public record Light(float gain, float r, float g, float b) {
        public static final Light NONE = new Light(0, 0, 0, 0);
        /** The breakthrough fire's blue. */
        static Light fire(double gain) { return new Light((float) gain, .36F, .66F, 1F); }
        /** The Peak's cold light of the shell and the essences. */
        static Light cold(double gain) { return new Light((float) gain, .62F, .86F, 1F); }
    }

    public BtFireLight(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing,
                       float limbSwingAmount, float partialTick, float ageInTicks, float yaw, float pitch) {
        if (player != Minecraft.getInstance().player || player.isInvisible()) {
            return;
        }
        Light scene = BtScenes.light(partialTick);
        float gain = scene.gain();
        if (gain <= .001F) {
            return;
        }
        PlayerModel<AbstractClientPlayer> model = getParentModel();
        VertexConsumer out = buffers.getBuffer(RenderType.eyes(getTextureLocation(player)));
        model.head.render(pose, new Lit(out, scene, gain * HEAD_RIM, 0), light, OverlayTexture.NO_OVERLAY);
        Lit body = new Lit(out, scene, gain * RIM, gain * BASE);
        for (ModelPart part : new ModelPart[]{model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg}) {
            part.render(pose, body, light, OverlayTexture.NO_OVERLAY);
        }
    }

    static int to255(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255)));
    }

    /** The light of one vertex: {@code base} everywhere plus {@code rim} times (1 - |normal . view|) squared. */
    static float weight(float x, float y, float z, float nx, float ny, float nz, float rim, float base) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        float facing = len < 1e-5F ? 1 : Math.abs(nx * x + ny * y + nz * z) / len;
        float edge = 1 - facing;
        return base + rim * edge * edge;
    }

    /** Recolours each vertex of the parts by its normal; everything else passes through. */
    private record Lit(VertexConsumer out, Light colour, float rim, float base) implements VertexConsumer {
        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            float w = weight(x, y, z, nx, ny, nz, rim, base);
            int lit = 0xFF000000 | to255(colour.r() * w) << 16 | to255(colour.g() * w) << 8 | to255(colour.b() * w);
            out.addVertex(x, y, z, lit, u, v, overlay, light, nx, ny, nz);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return out.addVertex(x, y, z);
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return out.setColor(r, g, b, a);
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return out.setUv(u, v);
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return out.setUv1(u, v);
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return out.setUv2(u, v);
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return out.setNormal(x, y, z);
        }
    }
}
