package io.github.verycooltimo.murim.client.vfx.fx;

/**
 * How the flat flame field is painted (fx_essence.fsh). Every number has the same meaning in the shader's uniform comments and in
 * tools/vfx-lab/scenes/essence-*.json, so a look tuned in the lab next to the reference layer is copied here as it is.
 * Colours are 0xRRGGBB with an alpha each: shadow, blue, violet, light violet, pink accent.
 * {@code cell} is the size of one drifting disc cell in blocks, {@code radius} the disc radius in cells (bigger = narrower webs),
 * {@code angular} 0 round .. 1 octagonal discs, {@code stretch} below 1 makes the cells taller, {@code elong} how long the discs are.
 * {@code top} how much the discs grow towards the top (webs thin to wisps), {@code edge} width of the blue edge band (cells), {@code spread} spread of disc sizes,
 * {@code jag} jaggedness of the edges (cells) at {@code jagFreq} per cell, {@code shadow} share of the dark violet tone.
 * {@code tongueX1/Reach1/X2/Reach2} the flame silhouette: place (-1..1 across the quad) and reach (0..1 of its height) of a tall and a lower tongue; {@code bend} crescent bend of the discs,
 * {@code lean} how much they lean with the flow, {@code dropLength} length of a pink drop in blocks, {@code blueSide} how much the blue is a one-sided shadow edge.
 * {@code thorn} share of grid cells with a thorn (0 = none), {@code thornLength}/{@code thornWidth} in blocks (length, half width of the base), {@code thornCurl} how far a thorn bends.
 * {@code glow} is the strength of the soft light round the field (BtGlow), 0 for none.
 */
public record FxEssenceLook(float cell, float radius, float angular, float stretch, float elong,
                            float rise, float flare, float drift, float warp,
                            float maskScale, float open, float bottom, float border,
                            float toneScale, float shadow, float light, float pink, float bubbles,
                            float top, float edge, float spread, float jag, float jagFreq,
                            float tongueX1, float tongueReach1, float tongueX2, float tongueReach2,
                            float bend, float lean, float dropLength, float blueSide,
                            float thorn, float thornLength, float thornWidth, float thornCurl,
                            int[] colors, float[] alphas, float glow, float[] fan, float lod) {
    /** The looks without a fan silhouette. */
    public FxEssenceLook(float cell, float radius, float angular, float stretch, float elong, float rise, float flare, float drift, float warp,
                         float maskScale, float open, float bottom, float border, float toneScale, float shadow, float light, float pink, float bubbles,
                         float top, float edge, float spread, float jag, float jagFreq, float tongueX1, float tongueReach1, float tongueX2, float tongueReach2,
                         float bend, float lean, float dropLength, float blueSide, float thorn, float thornLength, float thornWidth, float thornCurl,
                         int[] colors, float[] alphas, float glow) {
        this(cell, radius, angular, stretch, elong, rise, flare, drift, warp, maskScale, open, bottom, border, toneScale, shadow, light, pink, bubbles,
                top, edge, spread, jag, jagFreq, tongueX1, tongueReach1, tongueX2, tongueReach2, bend, lean, dropLength, blueSide,
                thorn, thornLength, thornWidth, thornCurl, colors, alphas, glow, new float[4], 0F);
    }
    /** The looks with a fan silhouette and the full-quality field. */
    public FxEssenceLook(float cell, float radius, float angular, float stretch, float elong, float rise, float flare, float drift, float warp,
                         float maskScale, float open, float bottom, float border, float toneScale, float shadow, float light, float pink, float bubbles,
                         float top, float edge, float spread, float jag, float jagFreq, float tongueX1, float tongueReach1, float tongueX2, float tongueReach2,
                         float bend, float lean, float dropLength, float blueSide, float thorn, float thornLength, float thornWidth, float thornCurl,
                         int[] colors, float[] alphas, float glow, float[] fan) {
        this(cell, radius, angular, stretch, elong, rise, flare, drift, warp, maskScale, open, bottom, border, toneScale, shadow, light, pink, bubbles,
                top, edge, spread, jag, jagFreq, tongueX1, tongueReach1, tongueX2, tongueReach2, bend, lean, dropLength, blueSide,
                thorn, thornLength, thornWidth, thornCurl, colors, alphas, glow, fan, 0F);
    }

    /**
     * Reference 13, plates/13-violet-layer.png: flat violet patches in the webs between packed dark discs, scalloped bites and thin
     * pointed wisps, blue as a band on some edges, light violet and small pink speckles. Numbers from tools/vfx-lab/scenes/essence-13-r4.json.
     */
    public static final FxEssenceLook FLAME_13 = new FxEssenceLook(.25F, .6F, .1F, .55F, 1.5F,
            .35F, .2F, .3F, .28F, .9F, 2F, 2.6F, .4F,
            .9F, .06F, .5F, 1F, 2.5F,
            .2F, .5F, .25F, .1F, 2.2F,
            -.37F, 1.2F, -.055F, .9F,
            .4F, .4F, .17F, .9F,
            .9F, .32F, .035F, .14F,
            new int[]{0x3A1368, 0x3002AF, 0x6404B3, 0x8511B9, 0xC356C3}, new float[]{.9F, .85F, .85F, .85F, .9F}, .35F);
    /** Reference 14, panel colours sampled from the indigo membrane (#271764, #20175f): angular, torn at the edge, no pink. */
    public static final FxEssenceLook DUSK_14 = new FxEssenceLook(.24F, .58F, .9F, .5F, 3.6F,
            .22F, .06F, .25F, .15F, .9F, 1.3F, .4F, .3F,
            .8F, 0F, .3F, 0F, 2.5F,
            .05F, 0F, .3F, .1F, 6F,
            0F, 0F, 0F, 0F,
            0F, 0F, 0F, 0F,
            0F, 0F, 0F, 0F,
            new int[]{0x1A0F4A, 0x20175F, 0x271764, 0x2D1B70, 0x442580}, new float[]{.92F, .9F, .9F, .88F, .85F}, .2F);
    /** Reference 14: the torn dark smears on the wall and ground, near black with a violet tint, dense. */
    public static final FxEssenceLook SMEAR_14 = new FxEssenceLook(.24F, .54F, .9F, .5F, 3.6F,
            .22F, .06F, .25F, .15F, .9F, 1.3F, .4F, .3F,
            .8F, 0F, .3F, 0F, 2.5F,
            0F, 0F, .3F, .1F, 6F,
            0F, 0F, 0F, 0F,
            0F, 0F, 0F, 0F,
            0F, 0F, 0F, 0F,
            new int[]{0x0F0818, 0x140B20, 0x180D28, 0x1D112F, 0x1D112F}, new float[]{.9F, .88F, .88F, .85F, .85F}, 0F);

    /**
     * Cultivation, panel 13 as the night scene wants it. Colours sampled from the panel's violet patches (#460a82, #320a6e, #5a1e82, deep
     * blue #1e1e46): clearly darker and less saturated than FLAME_13's plate colours (the plate is the generated layer, the game film showed
     * it hot), pink only as a small accent. Semi-transparent so the night shows through the thin parts. The webs are held open by a fan that
     * follows the traced ink tree's silhouette (FxCultivationScene#FAN); {@code open} is low so the field closes outside it.
     */
    public static final FxEssenceLook CULT_VIOLET = new FxEssenceLook(.25F, .5F, .1F, .55F, 1.5F,
            .5F, .2F, .3F, .28F, .9F, .45F, 3.5F, .4F,
            .9F, .1F, .45F, .5F, 2.5F,
            .2F, .5F, .25F, .1F, 2.2F,
            -.3F, 1F, .35F, .8F,
            .4F, 1.2F, -.04F, .9F,
            .4F, .4F, .17F, .9F,
            new int[]{0x2A0E50, 0x23208A, 0x4B0F8C, 0x6A2AA8, 0xA8408E}, new float[]{.8F, .72F, .72F, .72F, .8F}, 0F,
            new float[]{1.75F, 1.7F, 1.5F, 1.2F}, 0F);
    /** Cultivation: the same shapes far behind, larger and only blue (the author: «на фоне точно такое же, только синим»), dimmer, slower. */
    public static final FxEssenceLook CULT_BLUE = new FxEssenceLook(.3F, .6F, .1F, .55F, 1.5F,
            .3F, .2F, .2F, .3F, .7F, .3F, 2.6F, .4F,
            .8F, .12F, .4F, 0F, 2.5F,
            .2F, .5F, .25F, .1F, 2.2F,
            0F, 0F, 0F, 0F,
            .4F, 1.2F, 0F, .9F,
            0F, .45F, .2F, .9F,
            new int[]{0x12103A, 0x1B1C72, 0x241C78, 0x3A38A0, 0x3A38A0}, new float[]{.6F, .55F, .55F, .55F, .5F}, 0F,
            new float[]{3F, 3F, 2.7F, 2.1F}, 1F);
    /** Cultivation: the few violet patches in front of the tree's lower part: the same tone, sparser, no fan. */
    public static final FxEssenceLook CULT_FRONT = new FxEssenceLook(.22F, .5F, .1F, .55F, 1.5F,
            .6F, .2F, .3F, .28F, .9F, 1F, 2.6F, .4F,
            .9F, .1F, .45F, .8F, 2.5F,
            .2F, .5F, .25F, .1F, 2.2F,
            0F, 0F, 0F, 0F,
            .4F, 1.2F, -.04F, .9F,
            .3F, .4F, .17F, .9F,
            new int[]{0x2A0E50, 0x23208A, 0x5A1E9A, 0x7A34B4, 0xB04A98}, new float[]{.85F, .78F, .78F, .78F, .85F}, 0F,
            new float[]{0F, 0F, 0F, 0F}, 0F);

    /** The same look with another fan silhouette (half widths in blocks at 0, 1/3, 2/3 and the top of the quad). */
    public FxEssenceLook withFan(float[] f) {
        return new FxEssenceLook(cell, radius, angular, stretch, elong, rise, flare, drift, warp, maskScale, open, bottom, border, toneScale, shadow, light, pink, bubbles,
                top, edge, spread, jag, jagFreq, tongueX1, tongueReach1, tongueX2, tongueReach2, bend, lean, dropLength, blueSide,
                thorn, thornLength, thornWidth, thornCurl, colors, alphas, glow, f, lod);
    }
}
