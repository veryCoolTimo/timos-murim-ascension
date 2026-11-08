package io.github.verycooltimo.murim.client.vfx.bt;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
public record BtCtx(Minecraft mc,BtBody body,Vec3 camera,MultiBufferSource.BufferSource buffers,PoseStack stack,double labSeconds,float partial) {
    public PoseStack.Pose pose() { return stack.last(); }
}
