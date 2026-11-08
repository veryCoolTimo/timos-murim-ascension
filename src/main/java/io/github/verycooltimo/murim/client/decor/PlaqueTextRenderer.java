package io.github.verycooltimo.murim.client.decor;

import com.mojang.math.Axis;
import io.github.verycooltimo.murim.decor.HallPlaqueGrandBlock;
import io.github.verycooltimo.murim.decor.SectPlaqueBlock;
import io.github.verycooltimo.murim.decor.SectPlaqueWideBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;

/** Localized labels on the existing calligraphy boards, including already generated sect chunks.
 * No block entities or saved text are needed. Scan loaded blocks on ticks, never fetch chunks during rendering.
 * API: client/sect/SpeechBubbles#onRenderStage, client/DevPlaceCheckHandler (BlockPos.betweenClosed).
 */
public final class PlaqueTextRenderer {
    private record Plaque(BlockPos pos, BlockState state, float width) { }
    private final List<Plaque> plaques = new ArrayList<>();
    private net.minecraft.client.multiplayer.ClientLevel world;
    private int refresh;

    @SubscribeEvent
    public void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != world) {
            plaques.clear();
            world = mc.level;
            refresh = 0;
        }
        if (world == null || mc.player == null || --refresh > 0) return;
        refresh = 10;
        plaques.clear();
        BlockPos at = mc.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(at.offset(-16, -10, -16), at.offset(16, 12, 16))) {
            if (!world.isLoaded(pos)) continue;
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof SectPlaqueBlock && state.getValue(SectPlaqueBlock.PART) == SectPlaqueBlock.Part.LEFT) {
                plaques.add(new Plaque(pos.immutable(), state, 2.0F));
            } else if (state.getBlock() instanceof SectPlaqueWideBlock && state.getValue(SectPlaqueWideBlock.PART) == SectPlaqueWideBlock.ANCHOR) {
                plaques.add(new Plaque(pos.immutable(), state, 4.0F));
            } else if (state.getBlock() instanceof HallPlaqueGrandBlock && state.getValue(HallPlaqueGrandBlock.PART) == HallPlaqueGrandBlock.ANCHOR) {
                plaques.add(new Plaque(pos.immutable(), state, 8.0F));
            }
        }
    }

    @SubscribeEvent
    public void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || world == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != world) return;
        var pose = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        var camera = event.getCamera().getPosition();
        for (Plaque plaque : plaques) {
            var facing = plaque.state().getValue(HorizontalDirectionalBlock.FACING);
            var right = facing.getCounterClockWise();
            double x = plaque.pos().getX() + 0.5D + right.getStepX() * 0.5D;
            double z = plaque.pos().getZ() + 0.5D + right.getStepZ() * 0.5D;
            // A small scroll below the calligraphy, on the board's front plane, with the same inset.
            float inset = plaque.state().hasProperty(SectPlaqueBlock.INSET) ? plaque.state().getValue(SectPlaqueBlock.INSET) / 32.0F : 0;
            Component text = Component.translatable(plaque.state().getBlock().getDescriptionId() + ".inscription");
            float width = mc.font.width(text);
            float scale = Math.min(0.018F, (plaque.width() - 0.25F) / Math.max(1, width + 12));
            pose.pushPose();
            try {
                pose.translate(x - camera.x, plaque.pos().getY() + 0.17D - camera.y, z - camera.z);
                pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
                pose.translate(0, 0, -0.5F + 0.14F - inset);
                pose.scale(scale, -scale, scale);
                ScrollPanel.draw(pose, buffers, -width / 2 - 6, -4, width / 2 + 6, 13, 1, 1);
                mc.font.drawInBatch(text, -width / 2, 0, 0xFF302013, false, pose.last().pose(), buffers,
                        Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            } finally {
                pose.popPose();
            }
        }
        if (!plaques.isEmpty()) buffers.endBatch();
    }
}
