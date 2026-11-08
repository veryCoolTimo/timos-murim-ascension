package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.camp.BanditCampCommand;
import io.github.verycooltimo.murim.world.hua.MountHuaOverlay;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Dev-only stand of the location round trip ({@code -Pmurim.technique=location}, docs/design/28-location-capture.md):
 * flies to the nearest bandit camp (the captured template in a world generated after the capture) and to Mount Hua
 * (F3 open: the biome line reads murim:mount_hua / murim:mount_hua_foothills), a frame per view by day and the
 * aerial ones again at night. Frames: {@code screenshots/murim_loc_<stage>_<n>.png}. Stages: env
 * {@code MURIM_LOC_STAGES} (default {@code camp,biome}; the old showcase and edit stages are gone, see docs/design/44-commands.md). Polling is done from this client tick, never with
 * self-rescheduling TickTasks (CLAUDE.md §2a).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class DevLocationHandler {

    private static final boolean ENABLED = Boolean.getBoolean("murim.capture")
            && "location".equals(System.getProperty("murim.capture.technique"));

    private static final String[] STAGES = System.getenv().getOrDefault("MURIM_LOC_STAGES", "camp,biome").split(",");

    /** One camera stop: position, look, F3 on, night. */
    private record View(String stage, double x, double y, double z, float yaw, float pitch, boolean debug, boolean night) {
    }

    private static volatile List<View> views;
    private static boolean setup;
    private static int index;
    private static int wait;
    private static int armed;
    private static int stable;
    private static int lastSections = -1;
    private static int frame;
    private static boolean done;

    private DevLocationHandler() {
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED || done) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (mc.player == null || mc.level == null || server == null) {
            return;
        }
        if (!setup) {
            setup = true;
            mc.options.renderDistance().set(16);
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            server.execute(() -> plan(server));
            return;
        }
        List<View> vs = views;
        if (vs == null) {
            return;
        }
        if (index >= vs.size()) {
            done = true;
            MurimMod.LOGGER.info("Location stand: all views done ({} frames)", frame);
            mc.stop();
            return;
        }
        View v = vs.get(index);
        mc.getToasts().clear();
        if (wait == 0) {
            mc.options.hideGui = !v.debug();
            // F3 stays off while travelling: it caches the chunk under the camera, and an empty one (not arrived
            // yet) would keep «Waiting for chunk…» until the camera changes chunk.
            if (mc.getDebugOverlay().showDebugScreen()) {
                mc.getDebugOverlay().toggleOverlay();
            }
            armed = 0;
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                server.overworld().setDayTime(v.night() ? 18000L : 6000L);
                p.teleportTo(server.overworld(), v.x(), v.y(), v.z(), v.yaw(), v.pitch());
            });
        }
        wait++;
        int sections = mc.levelRenderer.countRenderedSections();
        stable = sections == lastSections && mc.levelRenderer.hasRenderedAllSections() ? stable + 1 : 0;
        lastSections = sections;
        // The mountain's chunks are generated on arrival (heavy): wait for the column under the camera and a calm
        // renderer; F3 shows «Waiting for chunk…» and no biome line until the chunk arrives.
        boolean here = mc.level.hasChunk(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4);
        if ((wait >= 60 && here && stable >= 40) || wait >= 1800 || armed > 0) {
            if (v.debug() && armed < 8) {
                if (armed == 0 && !mc.getDebugOverlay().showDebugScreen()) {
                    mc.getDebugOverlay().toggleOverlay();
                }
                armed++;
                return;
            }
            Screenshot.grab(mc.gameDirectory, String.format("murim_loc_%s_%03d.png", v.stage(), frame++), mc.getMainRenderTarget(), m -> {
            });
            MurimMod.LOGGER.info("Location stand: view {} {} at {} {} {} (waited {} ticks)", index, v.stage(),
                    (int) v.x(), (int) v.y(), (int) v.z(), wait);
            index++;
            wait = 0;
            stable = 0;
            lastSections = -1;
        }
    }

    /** Server side: where to look. */
    private static void plan(IntegratedServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        p.setGameMode(GameType.SPECTATOR);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.setWeatherParameters(12000, 0, false, false);
        List<View> out = new ArrayList<>();
        for (String stage : STAGES) {
            switch (stage.trim()) {
                case "camp" -> {
                    BanditCampCommand.Found f = BanditCampCommand.nearest(level, p.blockPosition());
                    if (f == null) {
                        MurimMod.LOGGER.warn("Location stand: no bandit camp");
                        continue;
                    }
                    double cx = f.piece().centreX() + 0.5;
                    double cz = f.piece().centreZ() + 0.5;
                    double cy = f.piece().height(0);
                    MurimMod.LOGGER.info("Location stand: camp at {} {} {}, template {}", (int) cx, (int) cy, (int) cz, f.piece().shell());
                    out.add(view("camp", cx, cy + 34, cz - 30, cx, cy, cz, false, false));
                    out.add(view("camp", cx + 30, cy + 22, cz, cx, cy, cz, false, false));
                    out.add(view("camp", cx - 26, cy + 14, cz + 18, cx, cy, cz, false, false));
                    out.add(view("camp", cx, cy + 3, cz - 20, cx, cy + 2, cz, false, false));
                    out.add(view("camp", cx, cy + 34, cz - 30, cx, cy, cz, false, true));
                }
                case "biome" -> {
                    MountHuaSite site = MountHuaSites.get(server);
                    if (site == null) {
                        continue;
                    }
                    int[] t = site.toWorld(6, -16);
                    int[] look = site.toWorld(6, 60);
                    double ty = MountHuaOverlay.refY(site) + 2.6;
                    out.add(view("biome", t[0] + 0.5, ty, t[1] + 0.5, look[0], ty + 6, look[1], true, false));
                    int[] g = site.toWorld(-106, -446);
                    int[] gl = site.toWorld(-104, -380);
                    level.getChunk(g[0] >> 4, g[1] >> 4);
                    double gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, g[0], g[1]) + 1.6;
                    out.add(view("biome", g[0] + 0.5, gy, g[1] + 0.5, gl[0], gy + 40, gl[1], true, false));
                    // Over the approach valley towards the scarp and the North Peak (within the render distance).
                    int[] a = site.toWorld(-110, -560);
                    int[] al = site.toWorld(-60, -340);
                    double ay = site.worldY(45);
                    out.add(view("biome", a[0] + 0.5, ay, a[1] + 0.5, al[0], site.worldY(105), al[1], false, false));
                    out.add(view("biome", a[0] + 0.5, ay, a[1] + 0.5, al[0], site.worldY(105), al[1], false, true));
                    out.add(view("biome", t[0] + 0.5, ty, t[1] + 0.5, look[0], ty + 6, look[1], true, true));
                }
                default -> MurimMod.LOGGER.warn("Location stand: unknown stage {}", stage);
            }
        }
        views = List.copyOf(out);
        MurimMod.LOGGER.info("Location stand: {} views", out.size());
    }

    private static View view(String stage, double x, double y, double z, double lx, double ly, double lz, boolean debug, boolean night) {
        double dx = lx - x;
        double dy = ly - y;
        double dz = lz - z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new View(stage, x, y, z, yaw, pitch, debug, night);
    }
}
