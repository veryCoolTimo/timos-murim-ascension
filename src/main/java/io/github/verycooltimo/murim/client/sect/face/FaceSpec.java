package io.github.verycooltimo.murim.client.sect.face;

/**
 * Which parts one person wears: an index into each of the four rows of the face atlas (eyes, brows, nose, mouth).
 * The pick is a pure function of a seed string (the sect member key) and a salt, so a person has the same face in every
 * session and on every machine; {@code tools/art/face_parts.py pick KEY} prints the same numbers (docs/design/58-face-constructor.md).
 *
 * <p>Hash: FNV-1a 64 over the UTF-8 bytes of the seed, xor salt * golden ratio, then splitmix64 of (hash + slot) modulo the
 * number of variants in that slot. Changing it changes every face in the game: do not touch without a note in the design doc.
 */
public record FaceSpec(int eyes, int brows, int nose, int mouth) {

    /** Slot order of the pick; the same order as the python tool. */
    public static final int SLOTS = 4;

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    /** The face of the person with this seed; {@code counts} = number of variants of eyes, brows, nose, mouth. */
    public static FaceSpec of(String seed, int salt, int eyes, int brows, int nose, int mouth) {
        long base = fnv1a64(seed) ^ (salt * GOLDEN);
        return new FaceSpec(pick(base, 0, eyes), pick(base, 1, brows), pick(base, 2, nose), pick(base, 3, mouth));
    }

    private static int pick(long base, int slot, int count) {
        if (count <= 0) {
            return 0;
        }
        return (int) Long.remainderUnsigned(mix64(base + slot), count);
    }

    static long fnv1a64(String s) {
        long h = 0xCBF29CE484222325L;
        for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            h = (h ^ (b & 0xFFL)) * 0x100000001B3L;
        }
        return h;
    }

    static long mix64(long z) {
        z += GOLDEN;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Short stable code for texture names and logs: {@code e1b2n0m3} (zero-based). */
    public String code() {
        return "e" + eyes + "b" + brows + "n" + nose + "m" + mouth;
    }
}
