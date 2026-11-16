package io.github.verycooltimo.murim.client.vfx.fx;

/**
 * How the cheap flat flame is painted (fx_flame.fsh). {@code tiles} is how many texture tiles cover one block, {@code rise} blocks per second,
 * {@code warp} the warp in tiles, {@code squash} stretches the cells (below 1 = taller); {@code low} and {@code high} are the web thresholds at the
 * bottom and the top (higher = thinner webs); {@code soft} softens the bites, {@code bite} (0..1) is how much the fine layer bites;
 * {@code dark}, {@code light} the shares of the dark and light tones, {@code pink} the pink accents, {@code blue} the blue areas;
 * {@code fan} the silhouette's half widths (blocks) at 0, 1/3, 2/3 and the top; colours 0xRRGGBB with an alpha each: shadow, blue, violet,
 * light violet, pink. Tuned in tools/vfx-lab next to the expensive fx_essence look it replaces.
 */
public record FxFlameLook(float tiles, float rise, float warp, float squash, float low, float high, float soft, float bite,
                          float dark, float light, float pink, float blue, int[] colors, float[] alphas, float[] fan) {
    /** The violet flame low behind the tree: the colours of FxEssenceLook.CULT_VIOLET. */
    public static final FxFlameLook VIOLET = new FxFlameLook(.3F, .75F, .45F, .55F, -.07F, .02F, 1F, 1F,
            .24F, .22F, .15F, .12F,
            new int[]{0x2A0E50, 0x23208A, 0x4B0F8C, 0x6A2AA8, 0xA8408E}, new float[]{.85F, .8F, .85F, .85F, .85F}, new float[]{1F, .9F, .7F, .4F});
    /** The same far behind, only blue, dimmer and slower. */
    public static final FxFlameLook BLUE = new FxFlameLook(.25F, .3F, .3F, .55F, -.04F, .05F, 1F, .8F,
            .24F, .22F, 0F, .1F,
            new int[]{0x12103A, 0x1B1C72, 0x241C78, 0x3A38A0, 0x3A38A0}, new float[]{.6F, .55F, .55F, .55F, .5F}, new float[]{1F, .9F, .7F, .4F});

    /** The look with a silhouette fitted to a sheet that is {@code halfWidth} blocks wide on each side. */
    public FxFlameLook fitted(double halfWidth) {
        return new FxFlameLook(tiles, rise, warp, squash, low, high, soft, bite, dark, light, pink, blue, colors, alphas,
                new float[]{(float) halfWidth * fan[0], (float) halfWidth * fan[1], (float) halfWidth * fan[2], (float) halfWidth * fan[3]});
    }
}
