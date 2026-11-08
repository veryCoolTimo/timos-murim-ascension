package io.github.verycooltimo.murim.dev;

import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.sect.SectArrival;
import io.github.verycooltimo.murim.sect.SectService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;
import java.util.stream.Collectors;

/** Dev evidence beside level.dat, never an attachment or a change to the gameplay save format. */
public final class SmokeReloadProbe {
    private SmokeReloadProbe() { }

    private static Properties snapshot(ServerPlayer p, double radius) {
        var result = new Properties();
        var state = SectService.state(p);
        result.setProperty("player", p.getUUID().toString());
        result.setProperty("member", Boolean.toString(state.member()));
        result.setProperty("generation", Integer.toString(state.generation()));
        result.setProperty("flags", state.flags().stream().sorted().collect(Collectors.joining(";")));
        result.setProperty("guide_off", Long.toString(p.getPersistentData().getLong(SectArrival.GUIDE_OFF)));
        for (var entry : state.journal().entrySet()) {
            if (entry.getKey().startsWith("arrival.")) result.setProperty("journal." + entry.getKey(), entry.getValue().toString());
        }
        for (var d : p.serverLevel().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(radius))) {
            result.setProperty("npc." + d.getUUID(), d.getName().getString());
        }
        return result;
    }

    public static void beforeQuit(ServerPlayer p) throws IOException {
        if (!SmokeWalkRoute.enabled()) throw new IllegalStateException("Smoke save outside dev capture");
        if (!SectService.state(p).member()) throw new IOException("membership_missing_before_save");
        for (var d : p.serverLevel().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(160))) {
            if (d.escortGoal().forPlayer(p)) throw new IOException("escort_unfinished_before_save");
        }
        try (var out = Files.newOutputStream(p.server.getWorldPath(LevelResource.ROOT).resolve("smoke-reload.properties"))) {
            snapshot(p, 64).store(out, "Expected state before client Save and Quit; compare after a new process loads this world");
        }
    }

    /** Called after chunks load, and again after ten game seconds to expose a stalled resumed escort. */
    public static String check(ServerPlayer p) {
        if (!SmokeWalkRoute.enabled()) return "not_dev_capture";
        Properties expected = new Properties();
        try (var in = Files.newInputStream(p.server.getWorldPath(LevelResource.ROOT).resolve("smoke-reload.properties"))) {
            expected.load(in);
        } catch (IOException e) { return "missing_save_baseline"; }
        var actual = snapshot(p, 160);
        if (!"true".equals(expected.getProperty("member"))) return "baseline_not_member";
        for (String key : expected.stringPropertyNames()) {
            if ("flags".equals(key)) {
                for (String flag : expected.getProperty(key).split(";")) {
                    if (!flag.isEmpty() && !SectService.state(p).has(flag)) return "guide_progress_reset_" + flag;
                }
            } else if (!expected.getProperty(key).equals(actual.getProperty(key))) return "state_reset_" + key;
        }
        for (var d : p.serverLevel().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(160))) {
            if (!d.isAlive() || d.walking().stuckStage() >= 3 || d.escortGoal().stalled(p.level().getGameTime())
                    || d.escortGoal().forPlayer(p)) return "escort_stuck_" + d.getUUID();
        }
        return "";
    }
}
