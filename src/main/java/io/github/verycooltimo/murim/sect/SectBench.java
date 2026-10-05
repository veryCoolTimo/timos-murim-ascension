package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Dev-only bench of the sect's server cost (docs/design/30-sect-performance.md). Runs only on the capture stand:
 * {@code -Dmurim.capture=true}, technique {@code sectlife}, {@code MURIM_SECT_BENCH=1}, {@code MURIM_SECT_SCENES=none}.
 * Puts the player at fixed spots around Mount Hua with frozen time, warms up, then measures full server tick
 * time (Pre to the last Post listener, so mod post-tick handlers are included) and runs the vanilla time profiler.
 * Results go to the log ({@code SectBench}) and to {@code run/debug/sectbench-<scenario>.txt}.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectBench {

    private static final boolean ENABLED = Boolean.getBoolean("murim.capture")
            && "sectlife".equals(System.getProperty("murim.capture.technique"))
            && "1".equals(System.getenv("MURIM_SECT_BENCH"));

    private static int env(String name, int fallback) {
        String raw = System.getenv(name);
        try {
            return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final int WARM = env("MURIM_BENCH_WARM", 1200);
    private static final int MEASURE = env("MURIM_BENCH_MEASURE", 2400);
    private static final int PROFILE = env("MURIM_BENCH_PROFILE", 220);

    /** Spot: training courtyard ("center"), the gate ray at a distance from the courtyard ("ray:50"), far ("ray:300"). */
    private record Phase(String name, int time, String spot, int warm, int measure, int profile) {
    }

    private static final List<Phase> PHASES = List.of(
            // Chunks and the mountain generate once before any measurement.
            new Phase("pre_center", 4200, "center", 400, 0, 0),
            new Phase("pre_border", 4200, "ray:50", 300, 0, 0),
            new Phase("pre_far", 4200, "ray:300", 400, 0, 0),
            new Phase("D_dawn", 23300, "center", WARM, MEASURE, PROFILE),
            new Phase("C_day", 4200, "center", WARM, MEASURE, PROFILE),
            new Phase("B_border", 4200, "ray:50", WARM, MEASURE, PROFILE),
            new Phase("A_far", 4200, "ray:300", WARM, MEASURE, PROFILE));

    private static int phase = -1;
    private static int tick;
    private static int startDelay = 200;
    private static long t0;
    private static long tPost;
    private static final long[] total = new long[8000];
    private static final long[] post = new long[8000];
    private static int n;
    private static long loaded, awake, nav, spar, tech, form, samples;

    private SectBench() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onPre(ServerTickEvent.Pre event) {
        if (ENABLED) {
            t0 = System.nanoTime();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onPostFirst(ServerTickEvent.Post event) {
        if (ENABLED) {
            tPost = System.nanoTime();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onPostLast(ServerTickEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        long end = System.nanoTime();
        MinecraftServer server = event.getServer();
        if (server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        if (phase < 0) {
            if (--startDelay > 0) {
                return;
            }
            next(server);
            return;
        }
        if (phase >= PHASES.size()) {
            return;
        }
        Phase ph = PHASES.get(phase);
        tick++;
        if (tick == 40 || tick == 140) {
            MurimMod.LOGGER.info("SectBench [{}]: settled {}", ph.name(), SectLife.settleAll(server.overworld()));
        }
        int m = tick - ph.warm();
        if (m > 0 && m <= ph.measure()) {
            total[n] = end - t0;
            post[n] = end - tPost;
            n++;
            if (m % 20 == 0) {
                sample(server.overworld());
            }
            if (m == ph.measure()) {
                report(ph);
            }
        }
        int p = m - ph.measure();
        // The /debug time profiler of 1.21.1 only counts ticks; the call tree comes from the /perf recorder
        // (10 s at most). API: reference/minecraft-src/net/minecraft/server/MinecraftServer.java#startRecordingMetrics
        if (ph.profile() > 0 && p == 1) {
            Path out = Path.of("debug", "sectbench-" + ph.name() + ".txt");
            server.startRecordingMetrics(res -> {
                try {
                    Files.createDirectories(out.getParent());
                } catch (java.io.IOException ignored) {
                    // saveResults reports its own failure
                }
                MurimMod.LOGGER.info("SectBench [{}]: profile {} saved={}", ph.name(), out.toAbsolutePath(), res.saveResults(out));
            }, path -> {
            });
        }
        if (ph.profile() > 0 && p == ph.profile() && server.isRecordingMetrics()) {
            server.finishRecordingMetrics();
        }
        if (p >= ph.profile() + 5) {
            next(server);
        }
    }

    private static void sample(ServerLevel level) {
        samples++;
        for (Entity e : level.getAllEntities()) {
            if (e instanceof SectDisciple d && !d.memberKey().isEmpty()) {
                loaded++;
                if (!d.dormant()) {
                    awake++;
                }
                if (d.getNavigation().isInProgress()) {
                    nav++;
                }
                if (d.spar() != SectDisciple.Spar.NONE) {
                    spar++;
                }
                String doing = d.doing();
                if (doing.contains("@")) {
                    tech++;
                } else if (doing.startsWith("form")) {
                    form++;
                }
            }
        }
    }

    private static void report(Phase ph) {
        long[] t = Arrays.copyOf(total, n);
        long[] q = Arrays.copyOf(post, n);
        Arrays.sort(t);
        double mean = Arrays.stream(t).average().orElse(0) / 1e6;
        double pm = Arrays.stream(q).average().orElse(0) / 1e6;
        double s = Math.max(1, samples);
        MurimMod.LOGGER.info(String.format(java.util.Locale.ROOT,
                "SectBench [%s]: ticks=%d mspt mean=%.3f p50=%.3f p95=%.3f p99=%.3f max=%.3f | post-listeners mean=%.3f"
                        + " | npc loaded=%.1f awake=%.1f navigating=%.1f spar=%.1f technique=%.1f form=%.1f",
                ph.name(), n, mean, t[n / 2] / 1e6, t[(int) (n * 0.95)] / 1e6, t[(int) (n * 0.99)] / 1e6, t[n - 1] / 1e6,
                pm, loaded / s, awake / s, nav / s, spar / s, tech / s, form / s));
    }

    private static void next(MinecraftServer server) {
        phase++;
        tick = 0;
        n = 0;
        loaded = awake = nav = spar = tech = form = samples = 0;
        if (phase >= PHASES.size()) {
            MurimMod.LOGGER.info("SectBench: done");
            return;
        }
        Phase ph = PHASES.get(phase);
        ServerLevel level = server.overworld();
        SectLayout layout = SectLife.layout(level);
        if (layout == null) {
            MurimMod.LOGGER.warn("SectBench: no Mount Hua");
            phase = PHASES.size();
            return;
        }
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        long day = Math.floorDiv(level.getDayTime(), 24000L) * 24000L + 24000L;
        for (ServerLevel l : server.getAllLevels()) {
            l.setDayTime(day + ph.time());
        }
        Vec3 center = layout.at("training", 0.0D, 0.0D);
        Vec3 at;
        if ("center".equals(ph.spot())) {
            at = SectLife.stand(level, center).add(0.0D, 3.0D, 0.0D);
        } else {
            double dist = Double.parseDouble(ph.spot().substring(4));
            Vec3 gate = layout.at("sect_gate", 0.0D, 0.0D);
            Vec3 dir = gate.subtract(center).multiply(1.0D, 0.0D, 1.0D).normalize();
            Vec3 flat = center.add(dir.scale(dist));
            at = dist > 100.0D ? new Vec3(flat.x, Math.max(center.y + 20.0D, 200.0D), flat.z)
                    : SectLife.stand(level, flat).add(0.0D, 2.0D, 0.0D);
        }
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        if (!p.getData(ModAttachments.SECT).member()) {
            p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).joined());
        }
        p.setGameMode(GameType.CREATIVE);
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 20 * 3600, 0, false, false, false));
        p.getAbilities().flying = true;
        p.onUpdateAbilities();
        p.teleportTo(level, at.x, at.y, at.z, 0.0F, 0.0F);
        MurimMod.LOGGER.info(String.format(java.util.Locale.ROOT, "SectBench [%s]: player at %.0f %.0f %.0f, %.0f blocks from courtyard, gate at %s",
                ph.name(), at.x, at.y, at.z, Math.sqrt(at.distanceToSqr(center.x, at.y, center.z)), layout.at("sect_gate", 0.0D, 0.0D)));
    }
}
