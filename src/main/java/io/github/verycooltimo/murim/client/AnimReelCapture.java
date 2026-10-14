package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * Dev capture stand: plays a list of player animations one after another on the local player
 * (subject {@code animreel}), so one client run films many clips for an old | new comparison.
 *
 * <p>Env {@code MURIM_CAPTURE_ANIMS=name:ticks,name:ticks,...}: each clip plays for {@code ticks}
 * client ticks, then the layer stops and the player stands idle for {@code MURIM_CAPTURE_ANIM_GAP}
 * ticks (default 8); {@code name:ticks:chain} starts the next clip right away without a stop.
 * Six-form clips go through the foundation layer at speed 1, like the game.
 * The frame index where every clip starts is written to {@code screenshots/animreel.txt}.
 * Slow the world with {@code MURIM_CAPTURE_CMDS="tick rate 5"} so software GL gets one frame per tick.
 */
final class AnimReelCapture {

    static final String SUBJECT = "animreel";

    private static final java.util.ArrayDeque<String[]> QUEUE = new java.util.ArrayDeque<>();
    private static int left;
    private static int gap;
    /** The running clip is chained: the next one starts on its last tick without a stop (lotus -> breath). */
    private static boolean chained;
    private static String current = "";
    private static boolean active;
    private static java.io.PrintWriter manifest;

    static void begin(Minecraft minecraft) {
        QUEUE.clear();
        for (String entry : System.getenv().getOrDefault("MURIM_CAPTURE_ANIMS", "").split(",")) {
            if (!entry.isBlank()) {
                QUEUE.add(entry.trim().split(":"));
            }
        }
        try {
            java.io.File dir = new java.io.File(minecraft.gameDirectory, "screenshots");
            dir.mkdirs();
            manifest = new java.io.PrintWriter(new java.io.FileWriter(new java.io.File(dir, "animreel.txt")), true);
        } catch (java.io.IOException e) {
            MurimMod.LOGGER.warn("animreel manifest", e);
        }
        gap = 4;
        active = true;
    }

    static void tick(Minecraft minecraft, int frameIndex) {
        if (!active || minecraft.player == null) {
            return;
        }
        if (left > 0) {
            if (--left == 0 && chained) {
                gap = 0;
            } else if (left == 0) {
                // Current clips end like the game does (blended stop); old copies (zz_*) as before 10.10.
                if (current.startsWith("zz_")) {
                    MurimPlayerAnimations.stop(minecraft.player);
                } else {
                    MurimPlayerAnimations.stopBlended(minecraft.player, 6);
                }
                gap = Integer.parseInt(System.getenv().getOrDefault("MURIM_CAPTURE_ANIM_GAP", "8"));
            }
            if (!chained || left > 0) {
                return;
            }
        }
        if (gap > 0) {
            gap--;
            return;
        }
        String[] next = QUEUE.poll();
        if (next == null) {
            active = false;
            if (manifest != null) {
                manifest.println(frameIndex + " END");
                manifest.close();
            }
            return;
        }
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, next[0]);
        current = next[0];
        boolean blendIn = !current.startsWith("zz_") && (current.equals("training_horse") || current.equals("training_carry"));
        boolean ok = current.contains("six_form_")
                ? MurimPlayerAnimations.playForm(minecraft.player, id, 1.0F)
                : blendIn ? MurimPlayerAnimations.playBlended(minecraft.player, id, 5)
                : MurimPlayerAnimations.play(minecraft.player, id);
        left = Integer.parseInt(next[1]);
        chained = next.length > 2 && "chain".equals(next[2]);
        if (manifest != null) {
            manifest.println(frameIndex + " " + next[0] + " " + left + (ok ? "" : " MISSING"));
        }
    }

    private AnimReelCapture() {
    }
}
