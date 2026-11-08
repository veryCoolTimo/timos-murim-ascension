package io.github.verycooltimo.murim.dev;

import io.github.verycooltimo.murim.combat.FootworkService;

/** Pure stand policy; see docs/adr/footwork-capture-1102.md. */
public final class FootworkCapturePlan {
    public static String subject(String requested, String mode) {
        // Lightning is the run form; W+R Flash belongs to Steps in the shipped definitions.
        return "wind_god_lightning".equals(requested) && ("pass".equals(mode) || "forward".equals(mode)) ? "wind_god_steps" : requested;
    }

    public static int input(String mode) {
        return switch (mode) {
            case "run" -> FootworkService.SPRINT | FootworkService.FORWARD;
            case "pass", "forward" -> FootworkService.FORWARD;
            case "left" -> FootworkService.LEFT;
            case "right" -> FootworkService.RIGHT;
            case "back" -> FootworkService.BACK;
            default -> 0;
        };
    }

    /** Age zero is the end of setup/warmup. Toggle forms get one R, not repeated on/off. */
    public static boolean due(String mode, int age, int repeat) {
        if (age < 0 || "autorun".equals(mode) || "autoshadow".equals(mode) || mode.isEmpty()) return false;
        if (age == 0) return true;
        return !"run".equals(mode) && !"shadow".equals(mode) && !"shadowrun".equals(mode)
                && age % Math.max(1, repeat) == 0;
    }

    public static final String CHASING_DUEL = "enemy_retreat@20,seven_stars_follow@25,falling_petal@31";
    public static final String DIPPER_DUEL = "seven_stars_dipper@20";
    public static final int ENEMY_WINDUP = 8;
    public static final String DEFAULT_DUEL = "enemy_swing@20,seven_stars_shift@25,six_harmonies_strike@36,seven_stars_follow@60,falling_petal@63";

    public static String enemy(String requested) {
        if (!java.util.Set.of("bandit", "vindicator", "zombie").contains(requested))
            throw new IllegalArgumentException("Unknown duel enemy: " + requested);
        return requested;
    }

    public record Cue(String action, int tick) { }
    /** Strict parsing makes a mistyped duel fail visibly before filming. */
    public static java.util.List<Cue> sequence(String script) {
        if (script.isBlank()) return java.util.List.of();
        var cues = new java.util.ArrayList<Cue>();
        for (String part : script.split(",", -1)) {
            String[] pair = part.trim().split("@", -1);
            if (pair.length != 2) throw new IllegalArgumentException("Expected action@tick: " + part);
            int tick = Integer.parseInt(pair[1]);
            if (tick < 0) throw new IllegalArgumentException("Negative cue: " + part);
            String action = pair[0];
            if (!java.util.Set.of("enemy_swing", "enemy_retreat", "seven_stars_dipper", "seven_stars_shift", "seven_stars_follow", "falling_petal",
                    "six_harmonies_counter", "six_harmonies_strike").contains(action))
                throw new IllegalArgumentException("Unknown duel cue: " + action);
            cues.add(new Cue(action, tick));
            if (action.equals("enemy_swing")) cues.add(new Cue("enemy_hit", Math.addExact(tick, ENEMY_WINDUP)));
        }
        return java.util.List.copyOf(cues);
    }

    private FootworkCapturePlan() { }
}
