package io.github.verycooltimo.murim.client.decor;

/** Pure visual curves, in ticks/degrees; no Minecraft or server state. */
public final class TipsyMotion {
    private TipsyMotion() { }

    public static float rollAmplitude(int level) {
        return switch (level) { case 1 -> 2F; case 2 -> 3.5F; case 3 -> 5F; default -> 0F; };
    }

    public static float yawAmplitude(int level) {
        return switch (level) { case 1 -> .6F; case 2 -> 1.2F; case 3 -> 2F; default -> 0F; };
    }

    public static float fadeStep(float fade, boolean active) {
        float next = active ? fade + 1F / 40 : fade - 1F / 60;
        return next >= 1 - .000001F ? 1 : next <= .000001F ? 0 : next;
    }

    public static float smooth(float value) {
        float x = Math.max(0, Math.min(1, value));
        return x * x * (3 - 2 * x);
    }

    public static float roll(int level, float ticks, float shake, float fade) {
        // Return positive zero before a negative wave can turn it into -0.0F.
        if (rollAmplitude(level) == 0 || shake == 0 || smooth(fade) == 0) return 0F;
        return rollAmplitude(level) * shake * smooth(fade)
                * (float) (.65 * Math.sin(ticks * 2 * Math.PI / 106)
                + .35 * Math.sin(ticks * 2 * Math.PI / 178));
    }

    public static float yaw(int level, float ticks, float shake, float fade) {
        if (yawAmplitude(level) == 0 || shake == 0 || smooth(fade) == 0) return 0F;
        return yawAmplitude(level) * shake * smooth(fade) * (float) Math.sin(ticks * 2 * Math.PI / 132);
    }

    public static float pitch(int level, float ticks, float shake, float fade) {
        if (yawAmplitude(level) == 0 || shake == 0 || smooth(fade) == 0) return 0F;
        return yawAmplitude(level) * .5F * shake * smooth(fade) * (float) Math.sin(ticks * 2 * Math.PI / 142);
    }

    /** One 1.2-second pulse, exactly zero outside its lifetime. */
    public static float pulse(float age) {
        return age <= 0 || age >= 24 ? 0 : (float) Math.pow(Math.sin(Math.PI * age / 24), 2);
    }
}
