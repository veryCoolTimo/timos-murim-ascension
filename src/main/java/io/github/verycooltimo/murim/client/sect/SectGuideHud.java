package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientConfig;
import io.github.verycooltimo.murim.network.SectGuidePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.List;

/**
 * "Who do I need?" shown on the person only (the author 20.10: no arrow overlay and no "who is where" lines on the screen,
 * the point over the person is enough). The server sends the person of the goal; a jade mote shows over his head within
 * sixteen blocks ({@link #moteAlpha}, drawn by {@link DiscipleRenderer}); the goal's person only, never a daily task's.
 * It can be turned off in the client config.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class SectGuideHud {
    /** The spots of the last payload; the first is the goal's when it has one. */
    private static List<SectGuidePayload.Entry> spots = List.of();

    private SectGuideHud() { }

    public static void accept(SectGuidePayload payload) {
        spots = payload.spots();
    }

    /** Where the person of the goal ({@code goal}) or of the daily task with this id is now, in words; empty when the guide has none. */
    public static net.minecraft.network.chat.Component whereOf(boolean goal, String id) {
        for (SectGuidePayload.Entry e : spots) {
            if (e.goal() == goal && (goal || e.id().equals(id))) return e.where();
        }
        return net.minecraft.network.chat.Component.empty();
    }

    public static boolean brokenTrial(Entity e) {
        return !spots.isEmpty() && spots.getFirst().entity() == e.getId() && spots.getFirst().id().equals("trial_broken");
    }

    /** Whether the mote shows over this person now: the goal's person, near, the setting on. */
    public static float moteAlpha(Entity e) {
        if (!ClientConfig.GUIDE_MARKER.get() || spots.isEmpty() || !spots.getFirst().goal() || spots.getFirst().entity() != e.getId()) return 0.0F;
        if (e instanceof io.github.verycooltimo.murim.entity.SectDisciple npc
                && (npc.isSleeping() || npc.pose() == io.github.verycooltimo.murim.entity.SectPose.SLEEP
                || npc.pose() == io.github.verycooltimo.murim.entity.SectPose.EAT)) return 0.0F;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return 0.0F;
        double d = Math.sqrt(mc.player.distanceToSqr(e));
        if (d > io.github.verycooltimo.murim.sect.SectGuide.MARK_RANGE || d < 1.5D) return 0.0F;
        // softer with distance, and gone at the edge
        return (float) Mth.clamp((io.github.verycooltimo.murim.sect.SectGuide.MARK_RANGE - d) / 12.0D, 0.0D, 1.0D);
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) spots = List.of();
    }
}
