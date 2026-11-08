package io.github.verycooltimo.murim.client.sect;

import java.util.Objects;

/** Client-session-only reception progress; no saved or synchronized game state. */
final class SmokeGateReception {
    enum Stage { AIM, GREETER, TALK, DONE }

    private Stage stage = Stage.AIM;

    Stage stage() { return stage; }

    boolean knock(boolean aimedAtDoor) {
        if (stage != Stage.AIM || !aimedAtDoor) return false;
        stage = Stage.GREETER;
        return true;
    }

    boolean greet(boolean greeterNearby) {
        if (stage != Stage.GREETER || !greeterNearby) return false;
        stage = Stage.TALK;
        return true;
    }

    boolean finish(boolean guideReady) {
        if (stage != Stage.TALK || !guideReady) return false;
        stage = Stage.DONE;
        return true;
    }

    static String requestPhase(String walkingPhase, boolean reception) {
        return reception ? "gate_reception" : walkingPhase;
    }

    static boolean accepts(String requestedTarget, String requestedPhase, String target,
                           String walkingPhase, boolean reception) {
        return requestedPhase != null && Objects.equals(requestedTarget, target)
                && Objects.equals(requestedPhase, requestPhase(walkingPhase, reception));
    }
}
