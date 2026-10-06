package io.github.verycooltimo.murim.world.hua;

/**
 * Which climbable block covers each block of a riser on the South Peak climb (author 06.10: «свои блоки… чтобы
 * выглядело натурально»). Pure function of the riser and a column hash, so the generator and the tests agree.
 *
 * <p>Rules: a route riser (2-6 blocks) is covered from its top block down to the foot, every block climbable —
 * mostly moss mats, granite handholds where the rock behind is cracked (holds sit in cracks) and in runs on
 * dry rock; a riser of 5+ gets handholds in its lower half (the hard steps read as worked rock). Taller walls
 * (7+, not on the route) only get a short moss strand hanging from the lip, never reaching the ground.
 */
public final class ClimbAids {

    public enum Aid { MOSS, HOLD }

    /** Highest riser on the route (MountHuaShapeTest#climbRouteIsClimbable: each step rises 2-6). */
    public static final int ROUTE_RISE = 6;

    private ClimbAids() {
    }

    /**
     * Aids from the top block of the riser ({@code [0]} = the block level with the upper rim) downwards.
     *
     * @param rise    blocks between the foot column's top and the upper rim
     * @param h       column hash (MountHuaChunkWriter#mix)
     * @param cracked per block from the top: is the wall behind cracked granite (may be shorter than the riser)
     * @return one aid per covered block; empty — nothing hangs here
     */
    public static Aid[] riser(int rise, long h, boolean[] cracked) {
        if (rise < 2) {
            return new Aid[0];
        }
        if (rise > ROUTE_RISE) {
            // Off the route: one in sixteen walls gets a 1-3 block strand from the lip.
            if ((h & 15) != 0) {
                return new Aid[0];
            }
            Aid[] strand = new Aid[1 + (int) Math.floorMod(h >>> 20, 3L)];
            java.util.Arrays.fill(strand, Aid.MOSS);
            return strand;
        }
        Aid[] out = new Aid[rise];
        // Dry columns (about one in three) carry handholds; the rest are moss with the odd hold.
        boolean dry = Math.floorMod(h >>> 12, 3L) == 0;
        for (int k = 0; k < rise; k++) {
            boolean crack = k < cracked.length && cracked[k];
            boolean flip = ((h >>> (24 + 2 * k)) & 3) == 0; // ~25%: the other kind, so a run is never uniform
            boolean hard = rise >= 5 && k >= rise / 2;
            boolean hold = crack || hard || (dry != flip);
            // The top block stays moss on wet columns: the mat hangs over the lip.
            out[k] = hold && !(k == 0 && !dry && !crack) ? Aid.HOLD : Aid.MOSS;
        }
        return out;
    }
}
