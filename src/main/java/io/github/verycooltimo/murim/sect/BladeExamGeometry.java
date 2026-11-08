package io.github.verycooltimo.murim.sect;

/** Shared, client-independent dimensions from the author's first_rate_disciple.geo.json and
 * huashan_sword.geo.json, plus models/item/huashan_sword.json's normal third-person scale.
 * Coordinates here are blocks, model X and world Y up. No scene state or client model loading is needed on the server.
 */
public final class BladeExamGeometry {
    public static final double SHOULDER_X = -5.0 / 16;
    public static final double SHOULDER_Y = 22.0 / 16;
    public static final double ARM_LENGTH = 10.0 / 16;
    public static final double GRIP_Y_PIXELS = 4.575;
    // Highest rotated corner of bone2's tip cube (pivot [0.05,22.55,0], Z=-134.25).
    public static final double TIP_Y_PIXELS = 22.55
            + 0.275 * Math.sin(Math.toRadians(134.25))
            + 0.225 * -Math.cos(Math.toRadians(134.25));
    public static final double BLADE_REACH = 1.5 * (TIP_Y_PIXELS - GRIP_Y_PIXELS) / 16;
    public static final double REACH = ARM_LENGTH + BLADE_REACH;
    public static final double FOREHEAD_Y = 1.62 + 0.13;
    public static final double FACE_FORWARD = 0.25;
    public static final double TIP_GAP = 0.15;
    public static final double STOP_DISTANCE = FACE_FORWARD + TIP_GAP
            + Math.sqrt(REACH * REACH - SHOULDER_X * SHOULDER_X
                    - (FOREHEAD_Y - SHOULDER_Y) * (FOREHEAD_Y - SHOULDER_Y));
    // Shoulder -> forehead target at the existing stop distance (model Y down, forward -Z).
    private static final double TARGET_X = -SHOULDER_X;
    private static final double TARGET_Y = -(FOREHEAD_Y - SHOULDER_Y);
    private static final double TARGET_Z = -(STOP_DISTANCE - FACE_FORWARD - TIP_GAP);
    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/model/geom/ModelPart.java#translateAndRotate
    // rotationZYX applies Rx then Ry to local +Y.
    public static final float HOLD_PITCH = (float) Math.atan2(-Math.hypot(TARGET_X, TARGET_Z), TARGET_Y);
    public static final float HOLD_YAW = (float) Math.atan2(-TARGET_X, -TARGET_Z);
    public static final double START_DISTANCE = STOP_DISTANCE + 0.4;

    private BladeExamGeometry() { }
}
