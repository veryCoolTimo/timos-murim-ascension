package io.github.verycooltimo.murim.client.vfx.fx;

import java.util.ArrayList;
import java.util.List;

/**
 * The low stirred dust ring on the floor round the cultivating body (round 4, author 14.11: «пыль тоже»): puffs of the approved cel dust
 * (FxDust, FxDustSim.Puff, unchanged) that are lifted off the ground, turn slowly round the body with the aura's swirl, drift outward, swell and
 * erode. A pure function of time (no state): puff i lives in cycles of its own length and phase, so the ring is never one repeating pattern.
 * Subtle on purpose: low (under ~0.7 block), pale violet, thin in the sector towards the camera so the lower body stays clear.
 * {@code share} (0..1, the quality governor's particle share) fades the highest-numbered puffs out smoothly, so lowering quality never pops one.
 * Client-free (FxCultivationDustTest).
 */
public final class FxCultivationDust {
    public static final int MAX = 12;
    /** The look: the aura's violet light on grey dust, dimmed for a night scene like the other night looks, ink dark so the contour still reads. */
    public static final FxDust.Look LOOK = new FxDust.Look(0xA080E8, 0x7050C0, 0x3A2670, 0x120A24, .55F, .5F, .5F, 5, -.3F, .9F, .6F, .15F, .12F);

    private FxCultivationDust() {}

    private static double h(int i, int k) { return FxShellWrap.hash(i * 31L + k * 977L + 11); }

    public static List<FxDustSim.Puff> at(double t, double glow, double share) {
        List<FxDustSim.Puff> out = new ArrayList<>(MAX);
        if (glow <= 0) return out;
        for (int i = 0; i < MAX; i++) {
            double on = Math.clamp(share * MAX - i, 0, 1);
            if (on <= 0) continue;
            double period = 5.5 + 3.5 * h(i, 1), age = (((t - FxCultivationScene.START) / period + h(i, 2)) % 1 + 1) % 1;
            double azBase = 2 * Math.PI * (i + h(i, 3) * .8) / MAX, turn = (.28 + .25 * h(i, 4)) * (t - FxCultivationScene.START);
            double az = azBase + turn * (h(i, 5) < .8 ? 1 : -1);
            double r = FxShellWrap.R0 + .3 + .7 * h(i, 6) + .5 * age;                // lifted off the floor ring, drifting outward
            double x = Math.sin(az) * r, z = Math.cos(az) * r;
            double y = .1 + .5 * age * (.6 + .6 * h(i, 7));
            double size = .5 * (.2 + .16 * h(i, 8)) * (.7 + .6 * Math.sin(Math.PI * Math.min(1, age * 1.15)));
            double life = Math.sin(Math.PI * age), front = Math.cos(az);                // front: towards the camera, where the body must stay clear
            double clear = front > .55 ? 1 - .75 * (front - .55) / .45 : 1;
            double alpha = glow * on * Math.pow(life, .7) * .55 * clear;
            double speed = (.28 + .25 * h(i, 4)) * r;
            out.add(new FxDustSim.Puff(x, y, z, Math.cos(az) * speed, .1, -Math.sin(az) * speed, size, h(i, 9), Math.min(1, age * age * 1.1), h(i, 10) * 6.28,
                    FxDustSim.CLUMP, 1.15 + .3 * h(i, 11), 0, alpha, 0, Double.NaN, .85 + .3 * h(i, 12)));
        }
        return out;
    }
}
