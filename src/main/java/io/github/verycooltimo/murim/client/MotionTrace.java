package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Dev-only frame sampling. Excluded by tools/ci/release-excludes.txt.
 * API: NeoForge RenderFrameEvent#getPartialTick; MC DeltaTracker#getGameTimeDeltaPartialTick,
 * Entity#getPosition; LivingEntity old/current body/head yaw; Mth#rotLerp (1.21.1 local sources). */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class MotionTrace {
    private static BufferedWriter writer;
    private static boolean failed;

    @SubscribeEvent
    static void frame(RenderFrameEvent.Post event) {
        if (failed || !"1".equals(System.getenv("MURIM_CAPTURE_TRACE"))) return;
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || mc.level == null || mc.isPaused()) return;
        try {
            if (writer == null) {
                // Dev launches use run/ as their working directory.
                Path file = mc.gameDirectory.toPath().resolve("motion-trace.csv");
                Files.createDirectories(file.toAbsolutePath().getParent());
                writer = Files.newBufferedWriter(file);
                writer.write("game_time,partial_tick,x,y,z,body_yaw,head_yaw,technique,age\n");
            }
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            var position = player.getPosition(partial);
            long time = mc.level.getGameTime();
            writer.write(String.format(Locale.ROOT, "%d,%.6f,%.9f,%.9f,%.9f,%.6f,%.6f,%s,%.6f%n",
                    time, partial, position.x, position.y, position.z,
                    Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot),
                    Mth.rotLerp(partial, player.yHeadRotO, player.yHeadRot),
                    ClientTechniqueHandler.tracedTechnique,
                    time - ClientTechniqueHandler.tracedStart + partial));
            writer.flush();
        } catch (IOException exception) {
            failed = true;
            MurimMod.LOGGER.warn("Cannot write motion trace", exception);
            if (writer != null) try { writer.close(); } catch (IOException ignored) { }
        }
    }
    private MotionTrace() { }
}
