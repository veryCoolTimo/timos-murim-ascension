package io.github.verycooltimo.murim.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.vault.VaultDoorBlock;
import io.github.verycooltimo.murim.sect.vault.VaultDoorBlockEntity;
import io.github.verycooltimo.murim.sect.vault.VaultRegistry;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Draws the two leaves of the vault door (author 24.10, round 4: «the door must open normally, swinging on its hinges»). One large
 * texture ({@code textures/entity/vault_door_base.png}, 6 x 7 blocks, tools/art/vault_door_art.py) spans the closed door; each leaf is
 * a slab 0.3 thick that turns inward about its outer edge, 96 degrees in 40 ticks, with the seal drawn on both faces all the way. The
 * white and the pink glow of the seal are two more textures drawn full-bright over the stone with the alpha of the block entity.
 *
 * <p>The drawing frame is the door facing SOUTH, x to the right of whoever looks at it (columns), y up, the controller cell at the origin;
 * the pose turns it to the block's facing, like GateDoorRenderer. API: reference/minecraft-src/net/minecraft/client/renderer/blockentity/BlockEntityRenderer.java,
 * RenderType#entityCutoutNoCull, #entityTranslucentEmissive, VertexConsumer#addVertex.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class VaultDoorRenderer implements BlockEntityRenderer<VaultDoorBlockEntity> {

    private static final ResourceLocation BASE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/vault_door_base.png");
    private static final ResourceLocation BACK = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/vault_door_back.png");
    private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/vault_door_glow_white.png");
    private static final ResourceLocation CORE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/vault_door_core.png");
    private static final ResourceLocation CORE_CENTRE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/vault_door_core_centre.png");
    private static final ResourceLocation CENTRE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/vault_door_glow_centre.png");
    private static final ResourceLocation PINK = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/vault_door_glow_pink.png");

    /** The leaf: its back and front face (z), the hinge line, and the cell columns it covers. */
    private static final float Z_BACK = 0.48F, Z_FRONT = 0.88F, HINGE_Z = 0.68F, HINGE_PAD = 0.12F;
    private static final float W = 6.0F, H = 7.0F;
    /** Height in blocks of each of the six columns: the top row is four cells wide (VaultPlan: a cell counts with 96 of 256 px inside the arch circle). */
    private static final float[] COLUMN_TOP = {6, 7, 7, 7, 7, 6};
    /** A point of plain stone in the texture, for the edges of the slab. */
    private static final float EDGE_U = 0.02F, EDGE_V = 0.97F;

    public VaultDoorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @SubscribeEvent
    static void onRegister(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(VaultRegistry.VAULT_DOOR_ENTITY.get(), VaultDoorRenderer::new);
    }

    @Override
    public AABB getRenderBoundingBox(VaultDoorBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(7.0D, 0.0D, 7.0D).expandTowards(0.0D, 8.0D, 0.0D);
    }

    @Override
    public boolean shouldRenderOffScreen(VaultDoorBlockEntity be) {
        return false;
    }

    @Override
    public void render(VaultDoorBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        BlockState state = be.getBlockState();
        Level level = be.getLevel();
        if (!(state.getBlock() instanceof VaultDoorBlock) || level == null) {
            return;
        }
        Direction facing = state.getValue(VaultDoorBlock.FACING);
        int front = LevelRenderer.getLightColor(level, be.getBlockPos().relative(facing));
        int back = LevelRenderer.getLightColor(level, be.getBlockPos().relative(facing.getOpposite()));
        float phi = VaultDoorBlockEntity.OPEN_DEGREES * be.swing(partial);
        float white = be.white(partial), pink = be.pink(partial);
        float progress = state.getValue(VaultDoorBlock.STATE) == VaultDoorBlock.State.CLOSED ? be.progress(partial) : 1.0F;
        float flicker = state.getValue(VaultDoorBlock.STATE) == VaultDoorBlock.State.CLOSED
                ? 0.96F + 0.04F * (float) Math.sin((level.getGameTime() + partial) * 0.22D) : 1.0F;
        white *= flicker;
        pink *= flicker;
        int fl = Math.max(front & 0xFFFF, (int) (white * 0xA0)) | (front & 0xFFFF0000);
        int bl = Math.max(back & 0xFFFF, (int) (white * 0xA0)) | (back & 0xFFFF0000);

        pose.pushPose();
        pose.translate(0.5F, 0.0F, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.translate(-0.5F, 0.0F, -0.5F);
        plate(buffers.getBuffer(RenderType.entityCutoutNoCull(BASE)), pose, fl, bl, overlay, false);
        plate(buffers.getBuffer(RenderType.entityCutoutNoCull(BACK)), pose, fl, bl, overlay, true);
        for (int leaf = 0; leaf < 2; leaf++) {
            float hx = leaf == 0 ? HINGE_PAD : W - HINGE_PAD;
            pose.pushPose();
            pose.translate(hx, 0.0F, HINGE_Z);
            pose.mulPose(Axis.YP.rotationDegrees(leaf == 0 ? phi : -phi));
            pose.translate(-hx, 0.0F, -HINGE_Z);
            int c0 = leaf * 3;
            VertexConsumer stone = buffers.getBuffer(RenderType.entityCutoutNoCull(BASE));
            stone(stone, pose, c0, fl, bl, overlay, false);
            stone(buffers.getBuffer(RenderType.entityCutoutNoCull(BACK)), pose, c0, fl, bl, overlay, true);
            // the halos first (translucent), then the cores on top: opaque pure white lines, never blended with the halo or the smoke
            if (white > 0.01F) {
                glow(buffers.getBuffer(RenderType.entityTranslucentEmissive(WHITE)), pose, c0, progress, white * (1.0F - pink), 1.0F, 1.0F, 1.0F, 0.004F);
            }
            float beat = 0.6F + 0.4F * (float) Math.sin((level.getGameTime() + partial) * 0.35D);
            if (pink > 0.01F) {
                glow(buffers.getBuffer(RenderType.entityTranslucentEmissive(PINK)), pose, c0, progress, pink, 1.0F, 1.0F, 1.0F, 0.004F);
                glow(buffers.getBuffer(RenderType.entityTranslucentEmissive(CENTRE)), pose, c0, progress, pink * beat, 1.0F, 1.0F, 1.0F, 0.005F);
            }
            if (white > 0.01F || pink > 0.01F) {
                float k = pink > 0.01F ? 1.0F : Math.min(1.0F, white);
                glow(buffers.getBuffer(RenderType.entityTranslucentEmissive(CORE)), pose, c0, progress, 1.0F, k, k, k, 0.007F);
                glow(buffers.getBuffer(RenderType.entityTranslucentEmissive(CORE_CENTRE)), pose, c0, progress, 1.0F, k, k, k, 0.008F);
            }
            pose.popPose();
        }
        pose.popPose();
    }

    // ------------------------------------------------------------------ geometry

    private static void vertex(VertexConsumer vc, PoseStack pose, float x, float y, float z, float u, float v, float r, float g, float b, float a,
            int light, int overlay, float nx, float ny, float nz) {
        vc.addVertex(pose.last(), x, y, z).setColor(r, g, b, a).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(pose.last(), nx, ny, nz);
    }

    private static void quad(VertexConsumer vc, PoseStack pose, float[] p, float u0, float v0, float u1, float v1, float r, float g, float b, float a,
            int light, int overlay, float nx, float ny, float nz) {
        vertex(vc, pose, p[0], p[1], p[2], u0, v1, r, g, b, a, light, overlay, nx, ny, nz);
        vertex(vc, pose, p[3], p[4], p[5], u1, v1, r, g, b, a, light, overlay, nx, ny, nz);
        vertex(vc, pose, p[6], p[7], p[8], u1, v0, r, g, b, a, light, overlay, nx, ny, nz);
        vertex(vc, pose, p[9], p[10], p[11], u0, v0, r, g, b, a, light, overlay, nx, ny, nz);
    }

    /** Strips per block across a leaf: the top of the leaf follows the arch circle in steps of 1/8 block. */
    private static final int STRIPS = 8;

    /** Height of the arch circle (centre 3 blocks over the straight part, radius 3) above x, at most the cell column's top. */
    private static float archTop(float xa, float xb, int column) {
        float h = COLUMN_TOP[column];
        for (float x : new float[] {xa, xb}) {
            float d = x - 3.0F;
            float y = 4.0F + (float) Math.sqrt(Math.max(0.0F, 9.0F - d * d));
            h = Math.min(h, y);
        }
        return h;
    }

    /** A leaf's stone: front face and slab edges ({@code back} false, the front texture) or the back face (the back texture), in fine strips following the arch. */
    private static void stone(VertexConsumer vc, PoseStack pose, int c0, int fl, int bl, int overlay, boolean back) {
        float prev = -1.0F;
        for (int c = c0; c < c0 + 3; c++) {
            for (int k = 0; k < STRIPS; k++) {
                float xa = c + k / (float) STRIPS, xb = c + (k + 1) / (float) STRIPS;
                float top = archTop(xa, xb, c);
                float u0 = xa / W, u1 = xb / W, v0 = 1.0F - top / H;
                if (back) {
                    quad(vc, pose, new float[] {xb, 0, Z_BACK, xa, 0, Z_BACK, xa, top, Z_BACK, xb, top, Z_BACK}, u1, v0, u0, 1.0F, 1, 1, 1, 1, bl, overlay, 0, 0, -1);
                } else {
                    quad(vc, pose, new float[] {xa, 0, Z_FRONT, xb, 0, Z_FRONT, xb, top, Z_FRONT, xa, top, Z_FRONT}, u0, v0, u1, 1.0F, 1, 1, 1, 1, fl, overlay, 0, 0, 1);
                    quad(vc, pose, new float[] {xa, top, Z_BACK, xb, top, Z_BACK, xb, top, Z_FRONT, xa, top, Z_FRONT}, EDGE_U, EDGE_V, EDGE_U, EDGE_V, 0.8F, 0.8F, 0.85F, 1, fl, overlay, 0, 1, 0);
                    if (prev >= 0.0F && prev != top) {
                        edge(vc, pose, xa, Math.min(prev, top), Math.max(prev, top), prev > top ? 1 : -1, fl, overlay);
                    }
                }
                prev = top;
            }
        }
        if (!back) {
            float xo = c0 == 0 ? 0.0F : W, xs = W / 2.0F;
            edge(vc, pose, xo, 0, archTop(xo, xo, c0 == 0 ? 0 : 5), c0 == 0 ? -1 : 1, fl, overlay);
            edge(vc, pose, xs, 0, 7, c0 == 0 ? 1 : -1, fl, overlay);
        }
    }

    /** The fixed frame plate: the part of the door cells above the arch circle, seen from the front or (back) from behind. */
    private static void plate(VertexConsumer vc, PoseStack pose, int fl, int bl, int overlay, boolean back) {
        for (int c = 0; c < 6; c++) {
            for (int k = 0; k < STRIPS; k++) {
                float xa = c + k / (float) STRIPS, xb = c + (k + 1) / (float) STRIPS;
                float lo = archTop(xa, xb, c), hi = COLUMN_TOP[c];
                if (hi - lo < 0.001F) {
                    continue;
                }
                float u0 = xa / W, u1 = xb / W, v0 = 1.0F - hi / H, v1 = 1.0F - lo / H;
                if (back) {
                    quad(vc, pose, new float[] {xb, lo, Z_BACK, xa, lo, Z_BACK, xa, hi, Z_BACK, xb, hi, Z_BACK}, u1, v0, u0, v1, 1, 1, 1, 1, bl, overlay, 0, 0, -1);
                } else {
                    quad(vc, pose, new float[] {xa, lo, Z_FRONT, xb, lo, Z_FRONT, xb, hi, Z_FRONT, xa, hi, Z_FRONT}, u0, v0, u1, v1, 1, 1, 1, 1, fl, overlay, 0, 0, 1);
                }
            }
        }
    }

    private static void edge(VertexConsumer vc, PoseStack pose, float x, float y0, float y1, float nx, int light, int overlay) {
        quad(vc, pose, new float[] {x, y0, Z_BACK, x, y0, Z_FRONT, x, y1, Z_FRONT, x, y1, Z_BACK}, EDGE_U, EDGE_V, EDGE_U, EDGE_V, 0.75F, 0.75F, 0.8F, 1, light, overlay, nx, 0, 0);
    }

    /** The glowing lines over the stone: the same strips, a hair off the faces, full bright. */
    /** Glow layers use an upward normal on purpose: the emissive entity shader shades by the normal, and a normal facing the viewer dims pure white to about 72%. */
    private static void glow(VertexConsumer vc, PoseStack pose, int c0, float progress, float a, float r, float g, float b, float lift) {
        int full = 0xF000F0;
        int ov = OverlayTexture.NO_OVERLAY;
        float zf = Z_FRONT + lift, zb = Z_BACK - lift;
        for (int c = c0; c < c0 + 3; c++) {
            for (int k = 0; k < STRIPS; k++) {
                float xa = c + k / (float) STRIPS, xb = c + (k + 1) / (float) STRIPS;
                float top = archTop(xa, xb, c);
                float u0 = xa / W, u1 = xb / W, v0 = 1.0F - top / H;
                // Eight mirrored bands light from the seam outward, using the server's cast progress.
                float segment = Math.abs((xa + xb) * 0.5F - W * 0.5F) / (W * 0.5F);
                float alpha = a * Math.max(0, Math.min(1, progress * 8 - (int) (segment * 8)));
                quad(vc, pose, new float[] {xa, 0, zf, xb, 0, zf, xb, top, zf, xa, top, zf}, u0, v0, u1, 1.0F, r, g, b, alpha, full, ov, 0, 1, 0);
                quad(vc, pose, new float[] {xb, 0, zb, xa, 0, zb, xa, top, zb, xb, top, zb}, u1, v0, u0, 1.0F, r, g, b, alpha, full, ov, 0, 1, 0);
            }
        }
    }
}
