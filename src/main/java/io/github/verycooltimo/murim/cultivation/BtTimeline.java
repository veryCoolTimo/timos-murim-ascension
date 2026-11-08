package io.github.verycooltimo.murim.cultivation;

/** Pure lab timing contract. Source: docs/vfx-stage/bt-port/README.md and lab constants.
 * freeze denotes the start of pre-blow gather for scenes without a literal item freeze. */
public record BtTimeline(double blow, double freeze, double end) {
    public static BtTimeline of(int target) {
        return switch (target) {
            case 1 -> new BtTimeline(10.8, 10.1, 16);
            case 2 -> new BtTimeline(15.2, 14.6, 20);
            case 3 -> new BtTimeline(15.2, 14.4, 20);
            case 4 -> new BtTimeline(25.5, 24.1, 34);
            default -> throw new IllegalArgumentException("Unknown breakthrough target " + target);
        };
    }
    public int sceneTicks() { return (int) Math.round(blow * 20); }
    public int releaseTicks() { return (int) Math.round((end - blow) * 20); }
}
