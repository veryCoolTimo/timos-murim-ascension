package io.github.verycooltimo.murim.cultivation.flow;

/**
 * The character of one pill's qi stream in the «Провести поток» game (author 25.10, mockup plan-site/extra/mockups/pill-flow).
 * Pure data: the game never asks which pill it is, only how its stream behaves.
 *
 * @param colour  body colour of the clot and the trail, 0xRRGGBB
 * @param core    bright core colour, 0xRRGGBB
 * @param pulse   seconds per pulse of a narrow spot: smaller is faster
 * @param narrows narrow spots on the way (they pulse open and closed)
 * @param wild    chance rate of a surge (a flicker the player must hold through)
 * @param forks   forks (light branch or blockage)
 * @param special cold slows the flow, hot surges harder, poison burns when the flow runs long
 * @param len     share of the body loop the stream travels (a short pill takes a shorter way)
 * @param time    seconds until the pill dissolves
 */
public record FlowSpec(int colour, int core, double pulse, int narrows, double wild, int forks, Special special, double len,
                       int time) {

    /** What makes the stream special. */
    public enum Special { NONE, COLD, POISON, HOT }
}
