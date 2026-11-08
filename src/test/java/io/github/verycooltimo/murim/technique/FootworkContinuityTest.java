package io.github.verycooltimo.murim.technique;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Author-approved 5 November continuity budget. No contact exceptions are currently needed. */
class FootworkContinuityTest {
    @Test void everyFootworkBoneStaysWithinSixtyDegreesPerTick() throws Exception {
        for (String name : List.of("seven_stars_shift", "seven_stars_shift_right", "seven_stars_follow",
                "seven_stars_dipper", "dark_fragrance_step", "dark_fragrance_rising",
                "dark_fragrance_floating", "dark_fragrance_rain", "dark_fragrance_behind",
                "dark_fragrance_trail", "wind_god_steps")) {
            try (var in = getClass().getResourceAsStream("/assets/murim/player_animations/" + name + ".json")) {
                assertNotNull(in, name);
                var clip = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("animations").getAsJsonObject(name);
                double length = clip.get("animation_length").getAsDouble();
                for (var bone : clip.getAsJsonObject("bones").entrySet()) {
                    var rotation = bone.getValue().getAsJsonObject().get("rotation");
                    if (rotation == null || !rotation.isJsonObject()) continue;
                    var keys = rotation.getAsJsonObject().entrySet().stream()
                            .sorted(Comparator.comparingDouble(e -> Double.parseDouble(e.getKey()))).toList();
                    double[] times = keys.stream().mapToDouble(e -> Double.parseDouble(e.getKey())).toArray();
                    JsonObject[] values = keys.stream().map(e -> e.getValue().getAsJsonObject()).toArray(JsonObject[]::new);
                    // Sliding windows also catch a fast interpolation inside a sparse key interval.
                    for (double t = 0; t + .05 <= length + 1e-8; t += .001) for (int axis = 0; axis < 3; axis++) {
                        double change = Math.abs(sample(times, values, t + .05, axis) - sample(times, values, t, axis));
                        assertTrue(change <= 60 + 1e-4, name + "/" + bone.getKey() + " axis=" + axis + " time=" + t + " change=" + change);
                    }
                }
            }
        }
    }

    private static double component(JsonObject value, int axis) {
        return value.has("vector") ? value.getAsJsonArray("vector").get(axis).getAsDouble() : value.get("value").getAsDouble();
    }
    private static double sample(double[] times, JsonObject[] keys, double t, int axis) {
        int i = 0;
        while (i + 1 < times.length && times[i + 1] < t - 1e-9) i++;
        if (i + 1 == times.length) return component(keys[i], axis);
        double u = Math.clamp((t - times[i]) / (times[i + 1] - times[i]), 0, 1);
        double a = component(keys[i], axis), b = component(keys[i + 1], axis);
        String easing = keys[i + 1].has("easing") ? keys[i + 1].get("easing").getAsString() : "linear";
        if (easing.equals("catmullrom")) {
            double before = component(keys[Math.max(0, i - 1)], axis);
            double after = component(keys[Math.min(keys.length - 1, i + 2)], axis);
            return .5 * (2*a + (-before+b)*u + (2*before-5*a+4*b-after)*u*u + (-before+3*a-3*b+after)*u*u*u);
        }
        if (easing.equals("easeInOutSine")) u = .5 - .5 * Math.cos(Math.PI * u);
        return a + (b - a) * u;
    }

    @Test void routesKeepAuthoritativeAgeAndExplicitDurationBudgets() {
        var path = List.of(net.minecraft.world.phys.Vec3.ZERO, new net.minecraft.world.phys.Vec3(0, 8, 0));
        for (int ticks : new int[]{1, 3, 4, 5, 6, 20}) {
            var route = new io.github.verycooltimo.murim.network.TraversePayloads.SevenRoute(path, ticks, -1, 100);
            assertEquals(-2, route.ageAt(98));
            assertEquals(2, route.ageAt(102), "late packet must not restart at age zero");
        }
        assertThrows(IllegalArgumentException.class, () -> new io.github.verycooltimo.murim.network.TraversePayloads.SevenRoute(path, 7, -1, 100));
    }

    @Test void behindRibbonUsesEveryApprovedBodySample() {
        var route = List.of(new net.minecraft.world.phys.Vec3(1, 2, 3),
                new net.minecraft.world.phys.Vec3(2, 2, 4), new net.minecraft.world.phys.Vec3(3, 2, 3));
        var ribbon = FootworkPaths.behindRibbon(route);
        assertEquals(route.size(), ribbon.length);
        for (int i = 0; i < ribbon.length; i++) {
            assertEquals(route.get(i).x, ribbon[i].x);
            assertEquals(route.get(i).z, ribbon[i].z);
        }
        assertEquals(.3, ribbon[0].y - route.getFirst().y, 1e-9);
        assertEquals(1.4, ribbon[ribbon.length - 1].y - route.getLast().y, 1e-9);
    }

    @Test void dipperEasingKeepsEveryLegDistanceAndContact() {
        assertEquals(0, SevenStarsRules.smoothstep(0));
        assertEquals(1, SevenStarsRules.smoothstep(1));
        double sum = 0;
        for (int tick = 0; tick < 3; tick++) sum += SevenStarsRules.smoothstep((tick + 1) / 3.0) - SevenStarsRules.smoothstep(tick / 3.0);
        assertEquals(1, sum, 1e-12);
        assertTrue(SevenStarsRules.smoothstep(1 / 3.0) < 1 / 3.0);
        assertEquals(1, SevenStarsRules.smoothstep(1 / 3.0) + SevenStarsRules.smoothstep(2 / 3.0), 1e-12);
    }

    @Test void clientDipperHasNoIsolatedJumpAtFiveFramesPerTick() {
        // Include late legs and server/client endpoint disagreement; sample the same linear
        // old/current tick interpolation as Entity#getPosition, not just the easing function.
        for (int late : new int[]{0, 1, 2}) for (double radius : new double[]{1.8, 2.8, 4.5}) {
            var rendered = new java.util.ArrayList<net.minecraft.world.phys.Vec3>();
            var client = new net.minecraft.world.phys.Vec3(radius, 0, 0);
            rendered.add(client);
            for (int leg = 0; leg < 7; leg++) {
                double angle = (leg + 1) * Math.PI / 3;
                var end = new net.minecraft.world.phys.Vec3(radius * Math.cos(angle), 0, radius * Math.sin(angle));
                var route = List.of(client.add(.9, 0, -.4), end);
                var accepted = client;
                assertEquals(0, client.distanceTo(FootworkPaths.dipperPoint(route, accepted, late, late)), 1e-9);
                for (int tick = late; tick < 4; tick++) {
                    var next = FootworkPaths.dipperPoint(route, accepted, late, tick + 1);
                    for (int frame = 1; frame <= 5; frame++) rendered.add(client.lerp(next, frame / 5.0));
                    client = next;
                }
                assertEquals(0, client.distanceTo(end), 1e-9, "endpoint before planted cut");
            }
            double[] steps = new double[rendered.size() - 1];
            for (int frame = 0; frame < steps.length; frame++)
                steps[frame] = rendered.get(frame).distanceTo(rendered.get(frame + 1));
            for (int frame = 2; frame + 2 < steps.length; frame++) {
                double neighbours = (steps[frame - 2] + steps[frame - 1] + steps[frame + 1] + steps[frame + 2]) / 4;
                assertTrue(steps[frame] <= 2.5 * neighbours + 1e-9,
                        "isolated jump: late=" + late + " radius=" + radius + " frame=" + frame);
            }
        }
    }

    @Test void correctionThresholdDisagreementBlendsAcrossThreeFrames() {
        for (double error : new double[]{.751, .966, 2}) {
            var client = net.minecraft.world.phys.Vec3.ZERO;
            var end = new net.minecraft.world.phys.Vec3(error, 0, 0);
            for (int remaining = 3; remaining > 0; remaining--) {
                var step = FootworkPaths.correctionStep(client, end, remaining);
                assertEquals(error / 3, step.length(), 1e-9);
                client = client.add(step);
            }
            assertEquals(0, client.distanceTo(end), 1e-9);
        }
    }

    @Test void behindFacingBoundsBodyAndHeadAcrossAngleWrap() {
        for (float current : new float[]{-179, 179, -90, 0, 90}) {
            float yaw = current;
            for (int tick = 0; tick < 20; tick++) {
                float next = FootworkPaths.facing(yaw, -current + 90);
                assertTrue(Math.abs(next - yaw) <= 18);
                yaw = next;
            }
            assertEquals(0, net.minecraft.util.Mth.wrapDegrees(-current + 90 - yaw), 1e-4);
        }
    }

    @Test void slippingPetalKeepsFiveTickDistanceWithSmoothFractions() {
        double[] expected = {.104, .248, .296, .248, .104};
        for (int i = 0; i < 5; i++) assertEquals(expected[i],
                SevenStarsRules.smoothstep((i + 1) / 5.0) - SevenStarsRules.smoothstep(i / 5.0), 1e-12);
    }
}
