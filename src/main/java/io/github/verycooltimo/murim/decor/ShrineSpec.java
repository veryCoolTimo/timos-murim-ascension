package io.github.verycooltimo.murim.decor;

/**
 * Shape and behaviour data of one shrine-set block (tools/art/shrine_set.py). Boxes are in px of the WHOLE model
 * (width x height blocks, x to the viewer's right, y up, front +z, facing SOUTH); {@link ShrineBlock#buildShapes} cuts
 * them per block and {@link HallFurniture#turn} rotates them for the other facings.
 *
 * @param fxY height in px (whole model, tabletop items include their 1 px drop) where particles start
 */
public record ShrineSpec(int width, int height, boolean wall, double[][] boxes, Fx fx, double fxY) {

    /** Client-side ambient particles. */
    public enum Fx { NONE, CANDLE, INCENSE }

    public static final ShrineSpec DUMMY = new ShrineSpec(1, 1, false, new double[][] {{0, 0, 0, 16, 16, 16}}, Fx.NONE, 0);

    public static ShrineSpec of(int width, int height, boolean wall, Fx fx, double fxY, double[]... boxes) {
        return new ShrineSpec(width, height, wall, boxes, fx, fxY);
    }
}
