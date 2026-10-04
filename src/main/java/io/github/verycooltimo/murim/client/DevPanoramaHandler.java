package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.hua.MountHuaCommand;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Dev-only panorama capture of Mount Hua ({@code -Pmurim.technique=mounthua} with the capture run).
 * Flies the (spectator) player through a list of viewpoints, waits until the terrain around is
 * built, and saves one screenshot per viewpoint as {@code screenshots/murim_pano_<name>.png}.
 *
 * <p>Viewpoints: env {@code MURIM_PANO_SHOTS} — comma list of names known to
 * {@code /murim mounthua tp} or custom {@code name@u:v:nominalY:lookU:lookV:pitch} in the
 * mountain's local frame. Env {@code MURIM_PANO_RD} — render distance (default 24),
 * {@code MURIM_PANO_STABLE} — ticks of unchanged sections before a shot (default 80; low for
 * fly-through frames), {@code MURIM_CAPTURE_FOV} — field of view, {@code MURIM_PANO_TIME} — day time (default 4000).
 *
 * <p>The integrated server is driven with {@code server.execute(...)} (its own thread); nothing
 * here runs without {@code -Dmurim.capture=true}.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class DevPanoramaHandler {

    private static final boolean ENABLED = Boolean.getBoolean("murim.capture")
            && "mounthua".equals(System.getProperty("murim.capture.technique"));

    private static List<String> shots;
    private static int shot = -1;
    private static int wait;
    private static int stable;
    private static int lastSections = -1;
    private static boolean setup;

    private DevPanoramaHandler() {
    }

    private static int env(String name, int fallback) {
        try {
            String raw = System.getenv(name);
            return raw == null ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (mc.player == null || mc.level == null || server == null) {
            return;
        }
        if (!setup) {
            setup = true;
            mc.options.hideGui = true;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.renderDistance().set(env("MURIM_PANO_RD", 24));
            // The server keeps the view distance the client announced on join (options.txt) until the
            // client broadcasts its options again — without this only ~6 chunks around arrive.
            mc.options.broadcastOptions();
            mc.options.fov().set(Math.max(30, Math.min(110, env("MURIM_CAPTURE_FOV", 70))));
            if (System.getenv("MURIM_PANO_CLOUDS") == null) {
                mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            }
            String raw = System.getenv().getOrDefault("MURIM_PANO_SHOTS", "view,view_east,view_west,gorge,ridge,main");
            shots = new ArrayList<>(List.of(raw.split(",")));
            int time = env("MURIM_PANO_TIME", 4000);
            server.execute(() -> {
                ServerLevel level = server.overworld();
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                level.setWeatherParameters(12000, 0, false, false);
                level.setDayTime(time);
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    p.setGameMode(GameType.SPECTATOR);
                }
            });
            next(mc, server);
            return;
        }
        if (shot < 0 || shot >= shots.size()) {
            return;
        }
        wait++;
        int sections = mc.levelRenderer.countRenderedSections();
        if (sections == lastSections && mc.levelRenderer.hasRenderedAllSections()) {
            stable++;
        } else {
            stable = 0;
        }
        lastSections = sections;
        boolean ready = wait >= env("MURIM_PANO_MIN", 300) && stable >= env("MURIM_PANO_STABLE", 80) && sections >= env("MURIM_PANO_SECTIONS", 300);
        if (ready || wait >= env("MURIM_PANO_MAX", 2400)) {
            String name = shots.get(shot).split("@")[0];
            MurimMod.LOGGER.info("Panorama shot {} after {} ticks ({} sections, stable {})", name, wait, sections, stable);
            Screenshot.grab(mc.gameDirectory, "murim_pano_" + name + ".png", mc.getMainRenderTarget(), m -> {
            });
            next(mc, server);
        }
    }

    private static void next(Minecraft mc, IntegratedServer server) {
        shot++;
        wait = 0;
        stable = 0;
        lastSections = -1;
        if (shot >= shots.size()) {
            MurimMod.LOGGER.info("Panorama: all {} shots done", shots.size());
            return;
        }
        String spec = shots.get(shot);
        server.execute(() -> {
            MountHuaSite site = MountHuaSites.get(server);
            if (site == null) {
                MurimMod.LOGGER.warn("Panorama: no Mount Hua site");
                return;
            }
            double[] t;
            if (spec.contains("@")) {
                String[] p = spec.split("@")[1].split(":");
                double u = Double.parseDouble(p[0]);
                double v = Double.parseDouble(p[1]);
                int[] w = site.toWorld(u, v);
                t = new double[] {w[0] + 0.5, site.worldY(Double.parseDouble(p[2])), w[1] + 0.5,
                        MountHuaCommand.yawTowards(site, Double.parseDouble(p[3]), Double.parseDouble(p[4])),
                        Double.parseDouble(p[5])};
            } else {
                t = MountHuaCommand.viewpoint(site, spec);
            }
            if (t == null) {
                MurimMod.LOGGER.warn("Panorama: unknown viewpoint {}", spec);
                return;
            }
            MountHuaCommand.resolveSurface(server.overworld(), t);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.teleportTo(server.overworld(), t[0], t[1], t[2], (float) t[3], (float) t[4]);
            }
            MurimMod.LOGGER.info("Panorama: {} at {} {} {}", spec, (int) t[0], (int) t[1], (int) t[2]);
        });
    }
}
