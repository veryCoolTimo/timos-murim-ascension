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
 * {@code MURIM_LOC_STAGES} (default {@code camp,biome}). Polling is done from this client tick, never with
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
            mc.options.renderDistance().set(12);
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
            if (mc.getDebugOverlay().showDebugScreen() != v.debug()) {
                mc.getDebugOverlay().toggleOverlay();
            }
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
        if ((wait >= 60 && stable >= 25) || wait >= 600) {
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
                case "showcase" -> {
                    // The showcase world as the author finds it: each location from above its sign, the spawn row.
                    var data = io.github.verycooltimo.murim.world.location.ShowcaseWorld.showcase(server);
                    for (String id : new String[] {"spawn", "camp", "archive", "fortress"}) {
                        BlockPos s = data.pos(id);
                        Float yaw = data.yaw(id);
                        if (s == null || yaw == null) {
                            continue;
                        }
                        double fx = -Math.sin(Math.toRadians(yaw));
                        double fz = Math.cos(Math.toRadians(yaw));
                        double lx = s.getX() + fx * 26;
                        double lz = s.getZ() + fz * 26;
                        out.add(view("showcase", s.getX() - fx * 14 + 0.5, s.getY() + 26, s.getZ() - fz * 14 + 0.5, lx, s.getY(), lz, false, false));
                        if (!id.equals("spawn")) {
                            out.add(view("showcase", s.getX() + 0.5 - fx * 3, s.getY() + 1.2, s.getZ() + 0.5 - fz * 3, lx, s.getY() + 2, lz, false, false));
                        }
                    }
                }
                case "edit" -> {
                    // Round trip, part 1 (showcase world): change the camp the way the author would, mark it, capture it.
                    BlockPos at = io.github.verycooltimo.murim.world.location.ShowcaseWorld.showcase(server).pos("camp");
                    if (at == null) {
                        MurimMod.LOGGER.warn("Location stand: no showcase camp");
                        continue;
                    }
                    for (int dx = -6; dx <= 6; dx++) {
                        for (int dz = -6; dz <= 6; dz++) {
                            level.getChunk((at.getX() >> 4) + dx, (at.getZ() >> 4) + dz);
                        }
                    }
                    var region = io.github.verycooltimo.murim.world.location.LocationCommand.auto(level, "camp", at);
                    if (region == null || region.anchor() == null) {
                        MurimMod.LOGGER.warn("Location stand: showcase camp structure not found near {}", at);
                        continue;
                    }
                    BlockPos c = region.anchor().world();
                    editCamp(level, c);
                    double cx = c.getX() + 0.5;
                    double cz = c.getZ() + 0.5;
                    double cy = c.getY();
                    try {
                        var r = io.github.verycooltimo.murim.world.location.LocationCapture.capture(level, "camp", region.box(),
                                region.anchor(), null, true);
                        MurimMod.LOGGER.info("Location stand: captured camp {} markers {} into {}", r.location().size(),
                                r.location().markers(), r.sourceDir());
                    } catch (Exception e) {
                        MurimMod.LOGGER.error("Location stand: capture failed", e);
                    }
                    out.add(view("edit", cx, cy + 34, cz - 30, cx, cy, cz, false, false));
                    out.add(view("edit", cx + 18, cy + 8, cz - 14, cx, cy + 4, cz, false, false));
                }
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
                    int[] a = site.toWorld(-60, -620);
                    int[] al = site.toWorld(10, 60);
                    double ay = site.worldY(150);
                    out.add(view("biome", a[0] + 0.5, ay, a[1] + 0.5, al[0], site.worldY(170), al[1], false, false));
                    out.add(view("biome", a[0] + 0.5, ay, a[1] + 0.5, al[0], site.worldY(170), al[1], false, true));
                    out.add(view("biome", t[0] + 0.5, ty, t[1] + 0.5, look[0], ty + 6, look[1], true, true));
                }
                default -> MurimMod.LOGGER.warn("Location stand: unknown stage {}", stage);
            }
        }
        views = List.copyOf(out);
        MurimMod.LOGGER.info("Location stand: {} views", out.size());
    }

    /**
     * What the author might do to a camp: a red-and-gold banner tower in the middle of the yard, a chief's chest with
     * a loot sign on it, the chief's spot marked by a sign, a lantern ring.
     */
    private static void editCamp(ServerLevel level, BlockPos c) {
        var red = net.minecraft.world.level.block.Blocks.RED_WOOL.defaultBlockState();
        var gold = net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState();
        BlockPos base = c.offset(3, 1, 3);
        for (int y = 0; y < 10; y++) {
            for (int dx = 0; dx < 2; dx++) {
                for (int dz = 0; dz < 2; dz++) {
                    level.setBlock(base.offset(dx, y, dz), y == 9 ? gold : red, 3);
                }
            }
        }
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            BlockPos p = c.offset((int) Math.round(Math.cos(a) * 6), 0, (int) Math.round(Math.sin(a) * 6));
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
            level.setBlock(new BlockPos(p.getX(), top, p.getZ()), net.minecraft.world.level.block.Blocks.LANTERN.defaultBlockState(), 3);
        }
        BlockPos chest = c.offset(-3, 1, 3);
        level.setBlock(chest, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
        sign(level, chest.above(), "loot:chief");
        sign(level, c.offset(-1, 1, 0), "spawn:chief");
        sign(level, c.offset(1, 1, -4), "spawn:bandit_qi");
    }

    private static void sign(ServerLevel level, BlockPos at, String text) {
        level.setBlock(at, net.minecraft.world.level.block.Blocks.OAK_SIGN.defaultBlockState(), 3);
        if (level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.SignBlockEntity s) {
            s.setText(new net.minecraft.world.level.block.entity.SignText().setMessage(0, net.minecraft.network.chat.Component.literal(text)), true);
        }
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
