package io.github.verycooltimo.murim.client.flow;

import io.github.verycooltimo.murim.cultivation.PillKind;
import io.github.verycooltimo.murim.cultivation.flow.FlowRoute;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowBodySurfaceTest {
    @Test
    void torsoPlaneAndMirrorSymmetrySurviveYawPitchRollAndTranslation() {
        for (double yaw : new double[] {0, 90, 180, 270}) {
            for (double[] tilt : new double[][] {{0, 0}, {18, -12}, {-9, 15}}) {
                Vec3 bottom = posed(0, 12, 0, yaw, tilt);
                Vec3 top = posed(0, 0, 0, yaw, tilt);
                Vec3 face = posed(0, 12, -2, yaw, tilt);
                Vec3 up = top.subtract(bottom).normalize();
                Vec3 normal = face.subtract(bottom).normalize();
                Vec3 right = up.cross(normal).normalize();
                FlowBodySurface body = new FlowBodySurface(bottom, top, face);
                assertEquals(0, body.front().distanceTo(normal), .005D);
                assertEquals(0, body.at(.5D, .8D).distanceTo(posed(0, 10 - FlowBodySurface.LIFT_PX, -2, yaw, tilt).add(normal.scale(.02D))), .005D);
                assertEquals(0, body.at(.5D, .19D).distanceTo(
                        posed(0, 1.5D - FlowBodySurface.LIFT_PX, -2, yaw, tilt).add(normal.scale(.02D))), .005D);
                // Check actual smoothed control points, not just the mapping of synthetic mirrors.
                double[][] outline = new FlowRoute(1).points();
                for (int i = 0; i < outline.length; i++) {
                    double[] a = outline[i], b = outline[outline.length - 1 - i];
                    Vec3 midpoint = body.at(a[0], a[1]).add(body.at(b[0], b[1])).scale(.5D);
                    assertEquals(0, midpoint.distanceTo(body.at(.5D, a[1])), .005D,
                            "mirrored control points must match within 0.01 blocks");
                    assertEquals(0, body.at(b[0], b[1]).distanceTo(body.at(1 - a[0], a[1])), .01D);
                }
                for (PillKind kind : PillKind.values()) {
                    if (kind.flow() == null) continue;
                    FlowRoute route = new FlowRoute(kind.flow().len());
                    for (int i = 0; i <= 1000; i++) {
                        double[] p = route.at(i / 1000D);
                        // Include quad edges, fork ends, rings and the clot glow.
                        for (double dx : new double[] {-.12D, 0, .12D}) {
                            for (double dy : new double[] {-.16D, 0, .16D}) {
                                double x = p[0] + dx, y = p[1] + dy;
                                Vec3 point = body.at(x, y);
                                Vec3 mirror = body.at(1 - x, y);
                                Vec3 centre = body.at(.5D, y);
                                assertEquals(0, point.add(mirror).scale(.5D).distanceTo(centre), .01D,
                                        "points must mirror about the torso midline at yaw " + yaw);
                                Vec3 relative = point.subtract(face);
                                assertEquals(.02D, relative.dot(normal), .005D,
                                        "all vertices must have the same torso-normal offset");
                                assertTrue(Math.abs(relative.dot(right)) <= .25D - FlowBodySurface.INSET + 1.0E-8D);
                                double torsoHeight = top.distanceTo(bottom);
                                // In the model frame the plane is lifted by LIFT_PX (render calibration), so the bounds shift with it.
                                double belowCollar = 1 - relative.dot(up) / torsoHeight + FlowBodySurface.LIFT_PX / 12;
                                assertTrue(belowCollar >= 1.5D / 12 - 1.0E-8D);
                                assertTrue(belowCollar <= 10D / 12 + 1.0E-8D);
                            }
                        }
                    }
                }
                assertTrue(body.at(.6D, .6D).subtract(body.at(.4D, .6D)).dot(right) > 0,
                        "route x must retain the screen's left/right controls");
            }
        }
    }

    // Model-space points transformed by a translated, pitched and rolled body bone.
    // Wide and slim skins share this torso geometry; no arm anchors enter the mapping.
    private static Vec3 posed(double x, double y, double z, double yaw, double[] tilt) {
        double pitch = Math.toRadians(tilt[0]), roll = Math.toRadians(tilt[1]);
        double rx = x * Math.cos(roll) - y * Math.sin(roll);
        double ry = x * Math.sin(roll) + y * Math.cos(roll);
        double py = ry * Math.cos(pitch) - z * Math.sin(pitch);
        double pz = ry * Math.sin(pitch) + z * Math.cos(pitch);
        // Entity renderer flips model X/Y; yaw then rotates the whole seated player.
        double angle = Math.toRadians(yaw);
        return new Vec3(10 + (-rx * Math.cos(angle) - pz * Math.sin(angle)) / 16,
                65 - py / 16, -12 + (-rx * Math.sin(angle) + pz * Math.cos(angle)) / 16);
    }
}
