package io.github.verycooltimo.murim.client.sect;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MapLabelPlacerTest {
    @Test
    void placesInsideBoundsAndAvoidsTaken() {
        List<int[]> taken = new ArrayList<>();
        List<int[]> feet = new ArrayList<>();
        feet.add(new int[] {100, 80, 30, 20});
        int[] r = MapLabelPlacer.place(115, 90, 40, 11, 0, 0, 300, 200, taken, feet);
        assertNotNull(r);
        assertArrayEquals(r, MapLabelPlacer.place(115, 90, 40, 11, 0, 0, 300, 200, taken, feet));
    }

    @Test
    void measureFrameCostOfTwentyLabels() {
        List<int[]> feet = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            feet.add(new int[] {10 + i * 13, 20 + (i % 5) * 25, 12, 14});
        }
        long t0 = System.nanoTime();
        MapLabelPlacer.candidates = 0;
        List<int[]> keep = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int[] b = MapLabelPlacer.place(16 + i * 13, 27 + (i % 5) * 25, 40, 11, 0, 0, 300, 171, keep, feet);
            if (b != null) {
                keep.add(b);
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("MAPLABEL one full layout of 20 labels: " + MapLabelPlacer.candidates + " candidates, " + ms + " ms"
                + " (per frame before the cache; now once per change)");
    }
}
