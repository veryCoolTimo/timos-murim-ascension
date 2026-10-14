package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.technique.Styles;
import net.minecraft.resources.ResourceLocation;

/** Timing of style LMB cuts. Contact at tick two; damage/cooldown remain Player.attack's vanilla rules. */
public final class SwordBasicCombo {
    public static final int SWING_TICKS = 8;
    public static final int RESET_TICKS = 20;
    public static final int CONTACT_TICK = 2;
    /** Finisher keys: wind-up 0..1, blade sweep 1..3, held pose 3..6, return 6..8. */
    public static final int FINISHER_SWEEP_START = 1;
    public static final int FINISHER_SWEEP_END = 3;
    private static final FoundationForms.Form[] CUTS = {
            FoundationForms.Form.DOWN_SLASH, FoundationForms.Form.SIDE, FoundationForms.Form.RISING
    };

    public static boolean isBasic(ResourceLocation id) {
        return id != null && Styles.ofBasic(id).isPresent();
    }

    /** Both physical clicks and capture automation must choose exactly one attack transport. */
    public static boolean ownsAttack(ResourceLocation foundation, boolean hasBlade, boolean betterCombat) {
        return isBasic(foundation) && hasBlade && !betterCombat;
    }

    public static FoundationForms.Form at(int step) {
        return CUTS[Math.floorMod(step, CUTS.length)];
    }

    public static String animation(FoundationForms.Form form) {
        return "seven_plum_cut_" + (index(form) + 1);
    }

    public static int index(FoundationForms.Form form) {
        for (int i = 0; i < CUTS.length; i++) {
            if (CUTS[i] == form) return i;
        }
        return 0;
    }

    public static boolean isFinisher(FoundationForms.Form form) {
        return form == CUTS[CUTS.length - 1];
    }

    /** Transient server combo state; switching stance or waiting 20 ticks starts a new chain. */
    public record State(ResourceLocation basic, int nextStep, long lastTick) {
        public static final State IDLE = new State(null, 0, Long.MIN_VALUE);

        public int next(ResourceLocation id, long now) {
            return id.equals(basic) && now >= lastTick && now - lastTick < RESET_TICKS ? nextStep : 0;
        }

        public boolean recovering(ResourceLocation id, long now) {
            return id.equals(basic) && now >= lastTick && now - lastTick < SWING_TICKS;
        }
    }

    private SwordBasicCombo() {}
}
