package io.github.verycooltimo.murim.client.vfx.fx;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.verycooltimo.murim.MurimMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Live tuning of effect numbers (dev only). An effect reads its numbers as {@code FxTune.f("flow.channel.width", 0.06F)}: the default lives
 * in the code. In normal play {@link #ON} is a static final false (no env, no property) and every call returns its default at once:
 * no file, no map, no allocation. With {@code MURIM_TUNE=<path to json>} (or {@code -Dmurim.tune=<path>}) the file is polled about five
 * times a second from the render thread; on a change the overrides are replaced, and {@code <path>.ack} is written with the file's
 * {@code revision}, the keys no code asked for, and every key code asked for with its default (what can be tuned). A malformed file keeps
 * the old values and logs one line. Scene controls ({@code _time}, {@code _loop}, {@code _speed}, {@code _shot}, ...) are in {@link FxTuneScene}.
 * Naming rule: {@code <scene>.<layer>.<what>}, e.g. {@code stroke.gold.arc.half}. docs/vfx-live-tuning.md.
 */
public final class FxTune {
    private static final String PATH = resolvePath();
    /** Static final: the JIT removes the whole body of every accessor when tuning is off. */
    public static final boolean ON = PATH != null;
    private static final long POLL_NANOS = 200_000_000L;

    private static final FxTuneStore STORE = new FxTuneStore();
    private static long lastPoll, lastModified = Long.MIN_VALUE, lastSize = -1;
    private static boolean lastWasBad;
    private static String lastError;
    private static String ackShot = "";
    private static int ackShotCounter = -1;
    private static final java.util.List<String> ackBurst = new java.util.ArrayList<>();

    private FxTune() {}

    private static String resolvePath() {
        String p = System.getProperty("murim.tune");
        if (p == null || p.isBlank()) p = System.getenv("MURIM_TUNE");
        return p == null || p.isBlank() ? null : p.trim();
    }

    public static boolean on() { return ON; }
    public static String path() { return PATH; }
    static FxTuneStore store() { return STORE; }

    public static float f(String key, float def) { return ON ? STORE.f(key, def) : def; }
    /** Double numbers: the default is returned exactly as written in code. */
    public static double d(String key, double def) { return ON ? STORE.d(key, def) : def; }
    public static int i(String key, int def) { return ON ? STORE.i(key, def) : def; }
    public static boolean b(String key, boolean def) { return ON ? STORE.b(key, def) : def; }
    /** A colour as 0xRRGGBB; in the file "#rrggbb". */
    public static int rgb(String key, int def) { return ON ? STORE.rgb(key, def) : def; }
    public static float[] fa(String key, float... def) { return ON ? STORE.fa(key, def) : def; }

    /**
     * Polls the file; called once per frame by {@link FxTuneScene} before the frame is drawn. Returns true when new values were applied in this call.
     * Throttled to five stats a second.
     */
    static boolean poll() {
        if (!ON) return false;
        long now = System.nanoTime();
        if (now - lastPoll < POLL_NANOS) {
            if (STORE.takeKnownChanged()) writeAck();
            return false;
        }
        lastPoll = now;
        Path file = Path.of(PATH);
        try {
            if (!Files.exists(file)) return false;
            long modified = Files.getLastModifiedTime(file).toMillis(), size = Files.size(file);
            if (modified == lastModified && size == lastSize) {
                if (STORE.takeKnownChanged()) writeAck();
                return false;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            lastModified = modified;
            lastSize = size;
            String error = STORE.load(text);
            if (error != null) {
                // keep the old values; one log line per distinct failure
                if (!error.equals(lastError)) MurimMod.LOGGER.warn("FxTune: {} is not valid, old values kept: {}", PATH, error);
                lastError = error;
                lastWasBad = true;
                writeAck();
                return false;
            }
            lastError = null;
            lastWasBad = false;
            MurimMod.LOGGER.info("FxTune: loaded revision {} from {}", STORE.revision(), PATH);
            return true;
        } catch (IOException | RuntimeException e) {
            // a half-written file or a race with the pusher: try again at the next change
            lastModified = Long.MIN_VALUE;
            if (!String.valueOf(e).equals(lastError)) MurimMod.LOGGER.warn("FxTune: cannot read {}: {}", PATH, e.toString());
            lastError = String.valueOf(e);
            return false;
        }
    }

    /** Writes the ack for the values now in force, with the result of the shot just taken (if any). */
    static synchronized void writeAck() {
        if (!ON) return;
        JsonObject ack = new JsonObject();
        ack.addProperty("revision", STORE.revision());
        ack.addProperty("error", lastWasBad ? lastError : null);
        ack.addProperty("applied_ms", System.currentTimeMillis());
        JsonArray unknown = new JsonArray();
        for (String k : STORE.unknownKeys()) unknown.add(k);
        ack.add("unknown", unknown);
        JsonObject known = new JsonObject();
        STORE.knownKeys().forEach(known::addProperty);
        ack.add("known", known);
        JsonObject bad = new JsonObject();
        STORE.badValues().forEach(bad::addProperty);
        ack.add("bad_values", bad);
        JsonObject shot = new JsonObject();
        shot.addProperty("counter", ackShotCounter);
        shot.addProperty("file", ackShot);
        JsonArray burst = new JsonArray();
        for (String name : ackBurst) burst.add(name);
        shot.add("burst", burst);
        ack.add("shot", shot);
        ack.add("scene", FxTuneScene.status());
        Path target = Path.of(PATH + ".ack"), tmp = Path.of(PATH + ".ack.tmp");
        try {
            Files.writeString(tmp, ack.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            MurimMod.LOGGER.warn("FxTune: cannot write the ack: {}", e.toString());
        }
    }

    /** A shot (or the last frame of a burst) is on disk: tell the driver. Called from the io thread. */
    static synchronized void shotSaved(int counter, String file, java.util.List<String> burst) {
        ackShotCounter = counter;
        ackShot = file;
        ackBurst.clear();
        ackBurst.addAll(burst);
        writeAck();
    }

    static String shotsDir() { return PATH + ".shots"; }
}
