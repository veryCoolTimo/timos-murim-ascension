package io.github.verycooltimo.murim.client.vfx.fx;

/**
 * How a brush stroke is painted (fx_stroke.fsh): the colour bands from the core out to the contour, and the brush itself.
 * Colours are 0xRRGGBB. The stops are the outer edges of the core, body and rim bands in stroke radii; the contour takes the rest.
 * {@code stepFps} above zero moves the stroke's noise in steps, as a hand-drawn line is redrawn (the author, 14.11: ink must move
 * «как анимация», not sway like hair); {@code boil} is how much the thickness pattern changes at each step.
 * {@code clots} are thick drops that travel along the stroke at {@code clotSpeed}, {@code clotSpacing} half-widths apart, and
 * {@code shimmer} keeps the centre line moving: energy that flows out of a body (the author, 14.11: smooth, «как будто сгустки»).
 * {@code halo} turns the stroke into a soft light that fades from its centre line to its edge.
 * The same numbers drive tools/vfx-lab scenes, so a look tuned there next to a reference crop is copied here as it is.
 */
public record FxLook(int core, int body, int rim, int ink, float inkAlpha, float coreStop, float bodyStop, float rimStop,
                     float ragged, float dry, float strands, float wobble, float flow, float tail, float head, float mainShare,
                     float glow, float contourBreak, float belly, float softBands,
                     float boil, float stepFps, float clots, float clotSpeed, float clotSpacing, float shimmer, float halo) {
    /** Reference atlas A1/A2/A5: white-yellow core, yellow body, orange rim, a thin dark red contour. */
    public static final FxLook GOLD = new FxLook(0xFFF6DC, 0xFFE27A, 0xF7B24C, 0x7A1206, .35F, .34F, .74F, .95F,
            .22F, .8F, .4F, .6F, .6F, .3F, .08F, .6F, .3F, .68F, 0F, .7F, 0F, 0F, 0F, 0F, 4F, 0F, 0F);
    /**
     * Reference atlas A4: one white slash with a bright knot, translucent towards both tips, and a wide WHITE glow (the author,
     * 14.11: «на рефе не синее свечение, а белое» — the blue-grey steel was my invention). Tuned on the
     * reference plate with the slash removed (tools/vfx-lab/scenes/a4-slash-on-plate.json).
     */
    public static final FxLook STEEL = new FxLook(0xFFFFFF, 0xF6F6F6, 0xD8D8D8, 0x1A1A1A, .25F, .3F, .7F, .92F,
            .07F, .5F, 0F, .3F, .8F, .5F, .5F, .5F, .14F, .5F, .55F, .6F, 0F, 0F, 0F, 0F, 4F, 0F, 0F);
    /** Reference atlas A4: the wide pale light round a slash, drawn as its own soft stroke under it (BtGlow's blur is only a few pixels). */
    public static final FxLook SLASH_HALO = new FxLook(0xFFFFFF, 0xF4F4F4, 0xE2E2E2, 0xE2E2E2, 1F, .3F, .6F, .9F,
            0F, 0F, 0F, .25F, .5F, .5F, .5F, .95F, 0F, 0F, .6F, 1F, 0F, 0F, 0F, 0F, 4F, 0F, 1F);
    /** Reference atlas A4: the thin black streaks that run out of a slash along it. Plain ink, no lit contour. */
    public static final FxLook SLASH_INK = new FxLook(0x07080C, 0x07080C, 0x0B0C12, 0x000000, 1F, .3F, .6F, .9F,
            .03F, .15F, 0F, .6F, .8F, .2F, .6F, .5F, 0F, 0F, 0F, 0F, 0F, 0F, 0F, 0F, 4F, 0F, 0F);
    /**
     * The small dark slivers that sit along both edges of a slash near the figure: clean and tapered. A dry ragged brush on a
     * stroke this thin breaks into dashes, which reads as crumbs.
     */
    public static final FxLook SLASH_SLIVER = new FxLook(0x07080C, 0x07080C, 0x0B0C12, 0x000000, 1F, .3F, .6F, .9F,
            0F, 0F, 0F, .3F, .8F, .45F, .55F, .6F, 0F, 0F, 0F, 0F, 0F, 0F, 0F, 0F, 4F, 0F, 0F);
    /** The fast lines that leave a steel cut: the same metal, no knot and no companions, so a short dash stays a dash. */
    public static final FxLook STEEL_LINE = new FxLook(0xFFFFFF, 0xD6E2F8, 0x8FA4C8, 0x161A24, .7F, .34F, .74F, .93F,
            .12F, .9F, 0F, .25F, .8F, .5F, .12F, .5F, .1F, .5F, 0F, .25F, 0F, 0F, 0F, 0F, 4F, 0F, 0F);
    /** Reference 13: ink that starts thin at its root, swells and tapers again («тонкое перерастает в толстое»). */
    public static final FxLook INK_SWELL = new FxLook(0x050308, 0x1A0B3A, 0x5B2FD0, 0x9A6CFF, .9F, .35F, .7F, .9F,
            .2F, .9F, .2F, .8F, .5F, .5F, .35F, .5F, 0F, .45F, 0F, .6F, 0F, 0F, .8F, 1.3F, 6F, .7F, 0F);
    /** Reference 13: the black clot at the root that the ink comes out of: short, fat, uneven. */
    public static final FxLook INK_MASS = new FxLook(0x040207, 0x0B0518, 0x2A1470, 0x7A4AE8, .8F, .5F, .8F, .93F,
            .3F, .5F, 0F, 1F, .4F, .2F, .45F, .8F, 0F, .6F, 0F, .4F, 0F, 0F, .5F, .7F, 4F, .5F, 0F);
    /**
     * Reference 13: the violet-blue essence, «огненное немного»: a lick that goes from very thin to thick to a point, pink in the
     * middle, violet round it, blue and dark violet in the shadow edge.
     */
    public static final FxLook FLAME = new FxLook(0xD86AD0, 0x4A22B8, 0x2A2A9C, 0x150A44, 1F, .05F, .55F, .9F,
            .06F, .1F, 0F, .9F, .5F, .5F, .4F, .8F, .05F, .3F, 0F, .15F, 0F, 0F, .4F, .9F, 5F, .5F, 0F);
    /** Reference 13: the same shapes far behind, only blue. */
    public static final FxLook FLAME_BLUE = new FxLook(0x2E3EB8, 0x1F2A8C, 0x141B5C, 0x0A0E36, 1F, .1F, .6F, .9F,
            .06F, .1F, 0F, 1F, .4F, .45F, .4F, .82F, 0F, .3F, 0F, .15F, 0F, 0F, .3F, .6F, 6F, .4F, 0F);
    /** Reference 13: the violet light that comes from all of it, as a soft stroke on the ground and behind the body. */
    public static final FxLook ESSENCE_HALO = new FxLook(0x9A70FF, 0x7A4CFF, 0x5A30E0, 0x5A30E0, 1F, .3F, .6F, .9F,
            0F, 0F, 0F, .3F, .3F, .5F, .5F, .95F, 0F, 0F, .3F, 1F, 0F, 0F, 0F, 0F, 4F, 0F, 1F);
    /** Reference atlas A6/A8: ink in thin angular lines, black in the middle through deep violet to a lit edge; clots run out along it. */
    public static final FxLook INK = new FxLook(0x050308, 0x1A0B3A, 0x5B2FD0, 0x9A6CFF, .9F, .35F, .7F, .9F,
            .2F, .9F, .35F, .7F, .5F, .08F, .5F, .46F, 0F, .45F, 0F, .6F, 0F, 0F, .95F, 1.5F, 7F, .7F, 0F);
    /**
     * Reference atlas A6: the violet behind the ink is not lines but flat angular shapes, cut out like a mask, with a gradient from
     * a lighter middle to a dark edge (the author, 14.11: «угловатый, маскированный вид»).
     */
    public static final FxLook VIOLET = new FxLook(0x5A2AD6, 0x3F1CAE, 0x2B1486, 0x160A48, 1F, .45F, .78F, .94F,
            .05F, .15F, 0F, 1F, .5F, .1F, .35F, .85F, .05F, .3F, 0F, .35F, 0F, 0F, .5F, .8F, 5F, .3F, 0F);
}
