package io.github.verycooltimo.murim.cultivation.flow;

import java.util.List;

/**
 * The stream of one sitting: one pill, or up to three eaten together in one stream (Return of Mount Hua and Absolute Regression).
 * The characters add up as in the approved mockup ({@code merged()}): the quickest pulse wins, narrow spots and wildness grow with
 * every extra pill, cold and hot together «спорят» (more spots, wilder), the dissolve time grows by four seconds a pill.
 *
 * @param colours colour of every pill (the first is the clot, the others are filaments around it)
 * @param cores   core colour of every pill
 * @param clash   cold and hot in one stream
 */
public record FlowCharacter(int[] colours, int[] cores, double pulse, int narrows, double wild, int forks, boolean cold,
                            boolean hot, boolean poison, boolean clash, double len, int time, int count) {

    public static FlowCharacter of(List<FlowSpec> parts) {
        int n = parts.size();
        boolean cold = false;
        boolean hot = false;
        boolean poison = false;
        double pulse = Double.MAX_VALUE;
        int narrows = 0;
        double wild = 0.0D;
        int forks = 0;
        double len = 0.0D;
        int time = 0;
        int[] colours = new int[n];
        int[] cores = new int[n];
        for (int i = 0; i < n; i++) {
            FlowSpec p = parts.get(i);
            colours[i] = p.colour();
            cores[i] = p.core();
            cold |= p.special() == FlowSpec.Special.COLD;
            hot |= p.special() == FlowSpec.Special.HOT;
            poison |= p.special() == FlowSpec.Special.POISON;
            pulse = Math.min(pulse, p.pulse());
            narrows = Math.max(narrows, p.narrows());
            wild = Math.max(wild, p.wild());
            forks = Math.max(forks, p.forks());
            len = Math.max(len, p.len());
            time = Math.max(time, p.time());
        }
        boolean clash = cold && hot;
        return new FlowCharacter(colours, cores, pulse * (clash ? 0.8D : 1.0D), narrows + (n - 1) + (clash ? 1 : 0),
                Math.min(1.2D, wild + 0.15D * (n - 1) + (clash ? 0.2D : 0.0D)), Math.min(3, forks + (n > 2 ? 1 : 0)),
                cold, hot, poison, clash, len, time + 4 * (n - 1), n);
    }

    public int colour() {
        return colours[0];
    }

    public int core() {
        return cores[0];
    }
}
