package io.github.verycooltimo.murim.client.sect;

/** Capture-only clock: one initial click plus three retries, each given 20 client ticks. */
final class DialogueCaptureGate {
    enum Result { WAIT, USE, OPEN, FAIL }

    private int attempts;
    private int lastUse;
    private int started = -1;

    // Direct capture interaction needs a tracked target and controller, not a vanilla picked hit.
    static boolean ready(boolean targetPresent, boolean controllerPresent, boolean screenOpen) {
        return targetPresent && controllerPresent && !screenOpen;
    }

    Result poll(int tick, boolean ready, boolean open) {
        if (started < 0) started = tick;
        if (attempts > 0 && open) return Result.OPEN;
        if (attempts > 0 && tick - lastUse < 20) return Result.WAIT;
        if (attempts == 4 || (attempts == 0 ? tick - started >= 200 : tick - lastUse >= 220)) {
            return Result.FAIL;
        }
        if (!ready) return Result.WAIT;
        attempts++;
        lastUse = tick;
        return Result.USE;
    }
}
