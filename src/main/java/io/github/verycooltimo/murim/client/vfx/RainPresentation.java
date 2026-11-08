package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.technique.RainRules;
import net.minecraft.world.phys.Vec3;

/** Pure client presentation policy; no combat clocks or art-star conversion live here.
 * Source: 6 Nov pilot 2 author brief. API: read-only minecraft-src/net/minecraft/world/phys/Vec3.java.
 */
final class RainPresentation {
    private RainPresentation() {}
    static double smooth(double value) {
        double u = Math.max(0, Math.min(1, value));
        return u * u * (3 - 2 * u);
    }
    static Vec3 centre(Vec3 feet, Vec3 caster) {
        Vec3 away = new Vec3(feet.x - caster.x, 0, feet.z - caster.z).normalize();
        return feet.add(away.scale(.3)).add(0, PlumLayers.rainFlowerHeight(8), 0);
    }
    /** Worst-case projection of the old 40-degree cup, including depth and particle extent.
     * Cup/fold point away from the floor; the radial bound also covers partial opening.
     */
    static double lowest(int tier) {
        double radius = Math.hypot(1, .43) * 9.5 * RainRules.scale(tier) * 1.06 * 1.04;
        // smooth(k) <= 1-(1-k)^2 and sin(pi*k) <= pi*(1-k) bound open below 1.01.
        // Its tiny overshoot can fold at most .8 degrees past the plane.
        return PlumLayers.rainFlowerHeight(tier) - radius * 1.01 * (Math.sin(Math.toRadians(40))
                + Math.sin(Math.toRadians(.8)) * Math.cos(Math.toRadians(40)))
                - 1.88 * RainRules.scale(tier) * Math.cos(Math.toRadians(40)) - .3;
    }
    /** bfed8c10b full-quality population before vanilla/configured particle thinning. */
    static int flowerPetals(int tier) {
        return RainRules.petals(tier) ? (int) Math.ceil(1200 * RainRules.density(tier) / 1.25) : 0;
    }
    static int beamCount(int tier, int wave) {
        return RainRules.petals(tier) ? Math.max(3,
                (int) Math.ceil((wave == 0 ? 60 : wave == 1 ? 72 : 84) * RainRules.density(tier) / 1.25)) : 0;
    }
    static int columnCount(int tier) {
        return RainRules.column(tier) ? (int) Math.ceil(26 * RainRules.density(tier)) : 0;
    }
    static int streamCount(int tier) { return tier < 5 ? 0 : tier < 7 ? 3 : 5; }
    static int streamPetals(int tier) { return streamCount(tier) * 24; }
    // API: minecraft-src/net/minecraft/world/phys/Vec3.java#distanceToSqr,add.
    static boolean eyeSafe(Vec3 at, Vec3 eye) { return at.distanceToSqr(eye) >= .36; }
    /** Side of the upper torso, with room below the eye exclusion sphere. */
    static Vec3 bodyEnd(Vec3 feet, Vec3 eye, double width, double height, double angle) {
        double x = Math.cos(angle), z = Math.sin(angle);
        double radius = width * .5 / Math.max(Math.abs(x), Math.abs(z));
        Vec3 head = feet.add(x * radius, height, z * radius);
        // Only broad heads can satisfy the same eye clearance; humanoids fall back to torso.
        if (radius >= .65 && Math.sin(angle * 7) > .92 && eyeSafe(head, eye)) return head;
        return feet.add(x * radius, Math.max(0, Math.min(height * .72, eye.y - feet.y - .7)), z * radius);
    }
    /** Approach outside the eye sphere, then a short inward stroke onto the torso surface. */
    static Vec3 lancePoint(Vec3 from, Vec3 approach, Vec3 to, double u) {
        return u < .9 ? from.lerp(approach, u / .9) : approach.lerp(to, (u - .9) / .1);
    }
    static int lanceCount(int tier) {
        return tier < 3 ? 0 : (int) Math.round(80 * RainRules.density(tier) / RainRules.density(8));
    }
    static double rainEnd() { return RainRules.contact(2) + 10; }
    static double poseWeight(double tick) {
        return smooth((tick - RainRules.BLOOM) / 8) * (1 - smooth((tick - rainEnd()) / 10));
    }
    static float headPitch(double tick, double desired) {
        double weight = poseWeight(tick);
        // An inactive pose has canonical positive zero, even for a negative desired pitch.
        return weight == 0 ? 0.0f : (float) (Math.max(-90, Math.min(90, desired)) * weight);
    }
    static double skyStrength(int stars, double tick) {
        return stars < 9 ? 0 : (.12 + .13 * Math.min(1, (stars - 9) / 3.0))
                * smooth((tick - RainRules.BLOOM) / 10)
                * (1 - smooth((tick - rainEnd()) / 20));
    }
}
