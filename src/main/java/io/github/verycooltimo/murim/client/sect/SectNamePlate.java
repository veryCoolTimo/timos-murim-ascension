package io.github.verycooltimo.murim.client.sect;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientConfig;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.sect.SectRoleNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.util.TriState;
import org.joml.Matrix4f;

/**
 * The name and role of a sect person while the player looks at him closely (playtest 19.10: "you do not know the names").
 * A small vanilla-style plate: the name, and the role under it in a dimmer, smaller line. Only the one person under the
 * crosshair, within {@link #RANGE} blocks, after the crosshair has rested on him for {@link NamePlateDwell#DELAY} ticks (a short
 * fade-in); it goes the moment the look moves off (author 21.10). Never a crowd of plates.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class SectNamePlate {
    /** How close the player has to be (blocks). */
    public static final double RANGE = 6.0D;
    private static final NamePlateDwell DWELL = new NamePlateDwell();

    private SectNamePlate() { }

    /** The person whose plate shows now (the crosshair has rested on him for a second), or -1. */
    public static int focused() {
        return DWELL.shown();
    }

    /** How far above the name tag point the plate starts (blocks). */
    private static final double RISE = 0.62D;

    /** Dwell opacity of this person (0: none). Rendering suppresses the plate while a bubble is active. */
    public static float alphaOf(int id) {
        return DWELL.alpha(id);
    }

    /** Height above the feet where the plate of this person starts (its top edge), in blocks. */
    public static double top(SectDisciple d, float partial) {
        Vec3 at = d.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, d.getViewYRot(partial));
        return (at == null ? d.getBbHeight() : at.y) + RISE;
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui || mc.screen != null || !ClientConfig.NAME_PLATES.get()) {
            DWELL.reset();
            return;
        }
        DWELL.tick(aimed(mc));
    }

    /** The sect person under the crosshair within {@link #RANGE} blocks (nothing solid in between), or -1. */
    private static int aimed(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(mc.player.getLookAngle().scale(RANGE));
        HitResult block = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
        double limit = block.getType() == HitResult.Type.MISS ? RANGE * RANGE : block.getLocation().distanceToSqr(eye);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(mc.player, eye, end,
                mc.player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0D),
                e -> e instanceof SectDisciple d && d.isAlive() && !d.isInvisible() && !d.memberKey().isEmpty(), limit);
        return hit == null ? -1 : hit.getEntity().getId();
    }

    @SubscribeEvent
    static void render(RenderNameTagEvent event) {
        if (HeadIntroCamera.active()) {
            event.setCanRender(TriState.FALSE);
            return;
        }
        if (!(event.getEntity() instanceof SectDisciple d) || d.memberKey().isEmpty()) return;
        if (!ClientConfig.NAME_PLATES.get() || SpeechBubbles.activeFor(d.getId()) || DWELL.alpha(d.getId()) <= 0.0F) {
            // a sect person has no name tag of his own; nothing but the plate below is drawn
            event.setCanRender(TriState.FALSE);
            return;
        }
        event.setCanRender(TriState.FALSE);
        plate(event, d, DWELL.alpha(d.getId()));
    }

    private static void plate(RenderNameTagEvent event, SectDisciple d, float alpha) {
        Component name = Component.translatable("npc.murim." + d.memberKey()).withStyle(net.minecraft.ChatFormatting.BOLD);
        Component role = SectRoleNames.of(d.memberKey(), d.role());
        Vec3 at = d.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, d.getViewYRot(event.getPartialTick()));
        if (at == null) at = new Vec3(0.0D, d.getBbHeight(), 0.0D);
        PoseStack pose = event.getPoseStack();
        Minecraft mc = Minecraft.getInstance();
        Font font = event.getEntityRenderer().getFont();
        pose.pushPose();
        pose.translate(at.x, at.y + RISE, at.z);
        pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        // a little smaller than a vanilla plate (0.025)
        pose.scale(0.025F, -0.025F, 0.025F);
        Matrix4f m = pose.last().pose();
        float halfWidth = Math.max(font.width(name), font.width(role) * 0.9F) / 2.0F;
        io.github.verycooltimo.murim.client.decor.ScrollPanel.draw(pose, event.getMultiBufferSource(),
                -halfWidth - 5, -4, halfWidth + 5, 23, 1, alpha);
        // lit like the sky shows it at night: the plate has to be readable in the dark halls too
        int light = net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
        float nx = -font.width(name) / 2.0F;
        float rx = -font.width(role) / 2.0F * 0.9F;
        font.drawInBatch(name, nx, 0.0F, fade(0xFF, alpha, 0x302013), false, m, event.getMultiBufferSource(), Font.DisplayMode.NORMAL, 0, light);
        // the role: a line below, smaller and dimmer
        pose.pushPose();
        pose.translate(rx, 10.0F, 0.0F);
        pose.scale(0.9F, 0.9F, 1.0F);
        Matrix4f rm = pose.last().pose();
        font.drawInBatch(role, 0.0F, 0.0F, fade(0xFF, alpha, 0x59412B), false, rm, event.getMultiBufferSource(), Font.DisplayMode.NORMAL, 0, light);
        pose.popPose();
        pose.popPose();
    }

    private static int fade(int a, float alpha, int rgb) {
        return Math.max(4, Math.round(a * alpha)) << 24 | rgb;
    }
}
