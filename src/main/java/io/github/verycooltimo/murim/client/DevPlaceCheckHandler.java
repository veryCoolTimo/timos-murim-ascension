package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.Place;
import io.github.verycooltimo.murim.world.PlaceKind;
import io.github.verycooltimo.murim.world.PlaceRules;
import io.github.verycooltimo.murim.world.PlaceService;
import io.github.verycooltimo.murim.world.hua.MountHuaShape;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Dev-only check of natural places of power on Mount Hua ({@code -Pmurim.technique=placecheck}).
 * Bug 04.10: «куда ни встанешь — везде места силы». The stand walks the stairs without meditating,
 * then meditates on a mid-mountain slope, on the real summit and in the pine forest, and saves
 * {@code screenshots/murim_place_<stage>_<n>.png}. Around each stage the server scans the surface
 * and logs how often each place kind fires (new rules) and how often the old peak rule fired.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class DevPlaceCheckHandler {

    private static final boolean ENABLED = Boolean.getBoolean("murim.capture")
            && "placecheck".equals(System.getProperty("murim.capture.technique"));

    private static final String[] STAGES = {"walk", "slope", "summit", "forest"};

    private static boolean setup;
    private static int stage = -1;
    private static int wait;
    private static int stable;
    private static int lastSections = -1;
    private static int act;
    private static int frame;
    private static volatile boolean targetReady;

    private DevPlaceCheckHandler() {
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
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            mc.options.renderDistance().set(12);
            mc.options.broadcastOptions();
            mc.options.autoJump().set(true);
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            server.execute(() -> {
                ServerLevel level = server.overworld();
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
                level.setWeatherParameters(12000, 0, false, false);
                level.setDayTime(Integer.parseInt(System.getenv().getOrDefault("MURIM_PLACE_TIME", "13000")));
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    p.setGameMode(GameType.CREATIVE);
                    seed(p);
                }
            });
            next(server);
            return;
        }
        if (stage < 0 || stage >= STAGES.length) {
            return;
        }
        String name = STAGES[stage];
        if (!targetReady) {
            return;
        }
        wait++;
        int sections = mc.levelRenderer.countRenderedSections();
        stable = sections == lastSections && mc.levelRenderer.hasRenderedAllSections() ? stable + 1 : 0;
        lastSections = sections;
        if (act == 0 && !((wait >= 120 && stable >= 40) || wait >= 1200)) {
            return;
        }
        act++;
        if ("walk".equals(name)) {
            mc.options.keyUp.setDown(act <= 160);
            if (act % 5 == 0) {
                grab(mc, name);
            }
            if (act >= 165) {
                mc.options.keyUp.setDown(false);
                next(server);
            }
            return;
        }
        if (act == 1) {
            PacketDistributor.sendToServer(new io.github.verycooltimo.murim.network.MeditationInputPayload(
                    io.github.verycooltimo.murim.network.MeditationInputPayload.Action.TOGGLE, true));
        }
        if (act >= 50 && act % 5 == 0) {
            grab(mc, name);
        }
        if (act >= 110) {
            if (ClientMeditationState.state().active()) {
                PacketDistributor.sendToServer(new io.github.verycooltimo.murim.network.MeditationInputPayload(
                        io.github.verycooltimo.murim.network.MeditationInputPayload.Action.TOGGLE, true));
            }
            next(server);
        }
    }

    private static void grab(Minecraft mc, String name) {
        BlockPos at = mc.player.blockPosition();
        Place shown = ClientPlaceState.place();
        Place raw = PlaceService.findPlace(mc.level, at).orElse(null);
        MurimMod.LOGGER.info("Placecheck frame {} {}: pos {} meditating {} shown {} rawNewRules {} oldPeakRule {}",
                name, frame, at.toShortString(), ClientMeditationState.state().active(),
                shown == null ? "-" : shown.kind(), raw == null ? "-" : raw.kind(), oldPeak(mc.level, at));
        Screenshot.grab(mc.gameDirectory, String.format("murim_place_%s_%03d.png", name, frame++),
                mc.getMainRenderTarget(), m -> {
                });
    }

    private static void next(IntegratedServer server) {
        stage++;
        wait = 0;
        stable = 0;
        lastSections = -1;
        act = 0;
        frame = 0;
        targetReady = false;
        if (stage >= STAGES.length) {
            MurimMod.LOGGER.info("Placecheck: done");
            return;
        }
        String name = STAGES[stage];
        server.execute(() -> {
            MountHuaSite site = MountHuaSites.get(server);
            ServerLevel level = server.overworld();
            if (site == null) {
                MurimMod.LOGGER.warn("Placecheck: no Mount Hua site");
                return;
            }
            MountHuaShape shape = site.shape();
            double[] t = switch (name) {
                case "walk" -> trail(site, 0.62);
                case "slope" -> slope(level, site);
                case "summit" -> summit(level, site);
                default -> forest(level, site);
            };
            if (t == null) {
                MurimMod.LOGGER.warn("Placecheck: no target for {}", name);
                t = trail(site, 0.5);
            }
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.teleportTo(level, t[0], t[1], t[2], (float) t[3], "forest".equals(name) ? 40.0F : 20.0F);
            }
            BlockPos feet = BlockPos.containing(t[0], t[1], t[2]);
            MurimMod.LOGGER.info("Placecheck stage {} at {}: server findPlace {} oldPeakRule {} localSummit-trail {}",
                    name, feet.toShortString(), PlaceService.findPlace(level, feet).map(Place::kind).orElse(null),
                    oldPeak(level, feet), shape.trailLength());
            targetReady = true;
        });
    }

    /** Standing on the trail at a fraction of its length, looking up the stair. */
    private static double[] trail(MountHuaSite site, double fraction) {
        MountHuaShape shape = site.shape();
        int i = (int) (shape.trailLength() * fraction);
        double[] a = shape.trailPoint(i);
        double[] b = shape.trailPoint(i + 12);
        int[] w = site.toWorld(a[0], a[1]);
        int[] wb = site.toWorld(b[0], b[1]);
        double y = site.worldY(shape.trailY(i)) + 1;
        return new double[] {w[0] + 0.5, y, w[1] + 0.5, yaw(wb[0] - w[0], wb[1] - w[1])};
    }

    private static float yaw(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static int top(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /** The peak rule before the fix: high, open sky, rock below — any slope of the mountain. */
    private static boolean oldPeak(Level level, BlockPos at) {
        if (at.getY() < PlaceRules.PEAK_HEIGHT || !level.canSeeSky(at.above())) {
            return false;
        }
        int solid = 0;
        for (int dy = 1; dy <= 5; dy++) {
            if (level.getBlockState(at.below(dy)).isSolid()) {
                solid++;
            }
        }
        return solid >= 4;
    }

    private static int logs(Level level, BlockPos at) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-5, -3, -5), at.offset(5, 8, 5))) {
            if (level.getBlockState(p).is(BlockTags.LOGS)) {
                n++;
            }
        }
        return n;
    }

    /** A steep spot near the middle of the stair where the old rule fired and the new one does not. */
    private static double[] slope(ServerLevel level, MountHuaSite site) {
        double[] c = trail(site, 0.45);
        scan(level, c, 48, "slope");
        double best = Double.MAX_VALUE;
        double[] out = null;
        for (int dx = -40; dx <= 40; dx += 2) {
            for (int dz = -40; dz <= 40; dz += 2) {
                int x = (int) c[0] + dx;
                int z = (int) c[2] + dz;
                int y = top(level, x, z);
                BlockPos at = new BlockPos(x, y, z);
                int drop = Math.max(Math.abs(top(level, x + 3, z) - top(level, x - 3, z)),
                        Math.abs(top(level, x, z + 3) - top(level, x, z - 3)));
                if (drop < 4 || !oldPeak(level, at) || PlaceService.findPlace(level, at).isPresent()) {
                    continue;
                }
                double d = dx * dx + dz * dz;
                if (d < best) {
                    best = d;
                    // Look along the slope: towards the higher side.
                    int ux = top(level, x + 3, z) - top(level, x - 3, z);
                    int uz = top(level, x, z + 3) - top(level, x, z - 3);
                    out = new double[] {x + 0.5, y, z + 0.5, yaw(-ux, -uz)};
                }
            }
        }
        return out;
    }

    /** The highest spot near the main summit that the rules call a peak (falls back to the highest ground). */
    private static double[] summit(ServerLevel level, MountHuaSite site) {
        int[] c = site.toWorld(5, 125);
        int best = Integer.MIN_VALUE;
        int bestPeak = Integer.MIN_VALUE;
        double[] out = null;
        double[] peak = null;
        for (int dx = -64; dx <= 64; dx += 2) {
            for (int dz = -64; dz <= 64; dz += 2) {
                int y = top(level, c[0] + dx, c[1] + dz);
                double[] t = {c[0] + dx + 0.5, y, c[1] + dz + 0.5, yaw(-dx, -dz)};
                if (y > best) {
                    best = y;
                    out = t;
                }
                if (y > bestPeak && PlaceService.findPlace(level, new BlockPos(c[0] + dx, y, c[1] + dz))
                        .filter(p -> p.kind() == PlaceKind.PEAK).isPresent()) {
                    bestPeak = y;
                    peak = t;
                }
            }
        }
        MurimMod.LOGGER.info("Placecheck summit: highest ground {}, highest PEAK spot {}", best, bestPeak);
        if (peak != null) {
            out = peak;
        }
        if (out != null) {
            scan(level, out, 48, "summit");
        }
        return out;
    }

    /**
     * An ordinary spot of the pine forest: the median spot where the old rule (≥ 14 logs around)
     * fired. Logs how many spots the new rule (big 2×2 trunk) still calls FOREST.
     */
    private static double[] forest(ServerLevel level, MountHuaSite site) {
        List<double[]> old = new ArrayList<>();
        int hits = 0;
        for (double f : new double[] {0.02, 0.1, 0.2, 0.35}) {
            double[] c = trail(site, f);
            scan(level, c, 48, "trail" + f);
            for (int dx = -48; dx <= 48; dx += 4) {
                for (int dz = -48; dz <= 48; dz += 4) {
                    int x = (int) c[0] + dx;
                    int z = (int) c[2] + dz;
                    BlockPos at = new BlockPos(x, top(level, x, z), z);
                    int n = logs(level, at);
                    if (n >= 14) {
                        old.add(new double[] {x + 0.5, at.getY(), z + 0.5, 0, n});
                    }
                    Place p = PlaceService.findPlace(level, at).orElse(null);
                    if (p != null && p.kind() == PlaceKind.FOREST) {
                        hits++;
                    }
                }
            }
        }
        if (old.isEmpty()) {
            return null;
        }
        old.sort((a, b) -> Double.compare(a[4], b[4]));
        double[] m = old.get(old.size() / 2);
        MurimMod.LOGGER.info("Placecheck forest: old rule (>=14 logs) {} spots, new rule FOREST {} spots, median old spot logs {}",
                old.size(), hits, (int) m[4]);
        return m;
    }

    /** Logs how often each place kind fires on the surface around c (step 3). */
    private static void scan(ServerLevel level, double[] c, int r, String label) {
        Map<PlaceKind, Integer> kinds = new EnumMap<>(PlaceKind.class);
        int total = 0;
        int old = 0;
        int treed = 0;
        int[] hist = new int[6];
        for (int dx = -r; dx <= r; dx += 3) {
            for (int dz = -r; dz <= r; dz += 3) {
                int x = (int) c[0] + dx;
                int z = (int) c[2] + dz;
                BlockPos at = new BlockPos(x, top(level, x, z), z);
                total++;
                if (oldPeak(level, at)) {
                    old++;
                }
                int n = logs(level, at);
                if (n > 0) {
                    treed++;
                }
                hist[Math.min(5, n / 7)]++;
                PlaceService.findPlace(level, at).ifPresent(p -> kinds.merge(p.kind(), 1, Integer::sum));
            }
        }
        MurimMod.LOGGER.info("Placecheck scan {} around {} {} {}: {} points, new rules {}, old peak rule {}, "
                        + "points with logs {} — logs histogram 0-6/7-13/14-20/21-27/28-34/35+ {}",
                label, (int) c[0], (int) c[1], (int) c[2], total, kinds, old, treed, java.util.Arrays.toString(hist));
    }

    private static void seed(ServerPlayer p) {
        var method = io.github.verycooltimo.murim.cultivation.MethodLoader.get(
                ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "six_harmonies"));
        if (method == null) {
            return;
        }
        p.setData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE,
                io.github.verycooltimo.murim.cultivation.SeedLogic.seedProfile(
                        io.github.verycooltimo.murim.profile.DantianProfile.INITIAL, method, 1.0D)
                        .withPool(method.capacity() * 0.5D).withCirculating(0.0D));
        p.setData(io.github.verycooltimo.murim.registry.ModAttachments.CULTIVATION,
                io.github.verycooltimo.murim.cultivation.CultivationState.NONE
                        .withMethod(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "six_harmonies"))
                        .withBeats(io.github.verycooltimo.murim.cultivation.CultivationState.SEEDED));
        io.github.verycooltimo.murim.profile.ProfileNetwork.sync(p);
    }
}
