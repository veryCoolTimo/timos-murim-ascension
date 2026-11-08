package io.github.verycooltimo.murim.client.vfx;

/** Per-slot motion; stateless so partial frames, dissolve and capture sample the same wave.
 * Author 6 Nov: independent +/-30–50% speeds, 0–6 tick delays, 8–16 tick side sway.
 */
final class SeaPetalMotion {
    private static double random(int slot, int salt) {
        double n = Math.sin(slot * 127.1 + salt * 311.7) * 43758.5453;
        return n - Math.floor(n);
    }
    static double speed(int slot) {
        return 1 + (slot % 2 == 0 ? -1 : 1) * (.30 + .20 * random(slot, 51));
    }
    static int delay(int slot) { return (int) (7 * random(slot, 52)); }
    static double phase(int slot) { return random(slot, 53) * Math.PI * 2; }
    static double period(int slot) { return 8 + 8 * random(slot, 54); }
    static double sway(int slot, double age) {
        return (.1 + .2 * random(slot, 55)) * Math.sin(age * Math.PI * 2 / period(slot) + phase(slot));
    }
    static double spin(int slot, double age) {
        return phase(slot) + age * (.04 + .14 * random(slot, 56)) * (slot % 3 == 0 ? -1 : 1);
    }
    /** A front crosses the original longitudinal field in six ticks; no cast clock changes. */
    static double clock(int slot, double age, double initialAlong) {
        double arrival = (initialAlong + 7.2) / 14.4 * 6 + delay(slot);
        return Math.max(0, age - arrival) * speed(slot);
    }
    // Round 2: breaker-only clocks; keep the round-1 circulating field unchanged.
    static double sweepDelay(double initialAlong) {
        return Math.max(0, Math.min(1, (initialAlong + 7.2) / 14.4)) * 8;
    }
    static double sweepSpeed(int slot) {
        return 1 + (slot % 2 == 0 ? -1 : 1) * (.36 + .04 * random(slot, 61));
    }
    static boolean peels(int slot) { return slot % 4 == 0; }
    static double sweepTravel(int slot, double age, double initialAlong) {
        double elapsed = Math.max(0, age - 52 - sweepDelay(initialAlong));
        // A quarter of the existing cohort peels away progressively, with no new slots.
        double lag = peels(slot) ? .65 * (elapsed - 4 * (1 - Math.exp(-elapsed / 4))) : 0;
        return (elapsed - lag) * sweepSpeed(slot);
    }
    static double driftRadius(int slot) { return .3 + .5 * random(slot, 62); }
    record Drift(double side, double up) {}
    static Drift sweepDrift(int slot, double age, double initialAlong) {
        double elapsed = Math.max(0, age - 52 - sweepDelay(initialAlong));
        double x = initialAlong * .7 + phase(slot), y = random(slot, 63) * 6;
        double time = elapsed * (.12 + .12 * random(slot, 64));
        // Curl of a seeded two-mode scalar potential: (dPsi/dy, -dPsi/dx).
        double side = Math.sin(x + time) * Math.cos(y - time)
                + .5 * Math.cos(2 * y + time);
        double up = -Math.cos(x + time) * Math.sin(y - time)
                - .5 * Math.cos(2 * x - time);
        double amplitude = driftRadius(slot) * (1 - Math.exp(-elapsed / 4)) / 2.122;
        return new Drift(side * amplitude, up * amplitude);
    }
    private SeaPetalMotion() {}
}
