package io.github.verycooltimo.murim.client.flow;

import io.github.verycooltimo.murim.cultivation.PillKind;
import io.github.verycooltimo.murim.cultivation.flow.FlowCharacter;
import io.github.verycooltimo.murim.cultivation.flow.FlowGame;
import io.github.verycooltimo.murim.cultivation.flow.FlowSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The picture of the game draws without a window (the canvas is pure) and shows what the state says. */
class FlowSceneTest {

    private static FlowGame game(boolean torn, PillKind... kinds) {
        List<FlowSpec> parts = new ArrayList<>();
        for (PillKind k : kinds) {
            parts.add(k.flow());
        }
        return new FlowGame(FlowCharacter.of(parts), 11L, false, torn, false);
    }

    private static int lit(FlowRaster r) {
        int n = 0;
        for (int p : r.px) {
            int rr = (p >> 16) & 255;
            int g = (p >> 8) & 255;
            int b = p & 255;
            if (rr + g + b > 300) {
                n++;
            }
        }
        return n;
    }

    /** Pixels that are nearly white: the stars and the clot's core, not the coloured route. */
    private static int white(FlowRaster r) {
        int n = 0;
        for (int p : r.px) {
            if (((p >> 16) & 255) > 215 && ((p >> 8) & 255) > 215 && (p & 255) > 200) {
                n++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("Картина рисуется на любом размере холста и тёмная до первого движения")
    void drawsAtAnySize() {
        for (int[] size : new int[][] {{320, 180}, {240, 180}, {420, 180}, {346, 195}}) {
            FlowRaster r = new FlowRaster(size[0], size[1]);
            FlowGame g = game(false, PillKind.SNOW_PLUM);
            List<FlowScene.Label> labels = FlowScene.draw(r, g, 0, 0, true);
            assertTrue(labels.stream().anyMatch(l -> l.key().equals("pressure")), "no pressure caption");
            assertTrue(labels.stream().anyMatch(l -> l.key().equals("dissolve")), "no dissolve caption");
            assertTrue(lit(r) < r.px.length / 20, "the field is not dark: " + lit(r));
            assertEquals(0xF2, r.px[0] >>> 24, "the corner is not the darkened world");
        }
    }

    @Test
    @DisplayName("Сгусток и след светятся, давление краснит края, кольцо времени убывает")
    void showsTheStream() {
        FlowGame g = game(false, PillKind.ORIGIN_ENERGY);
        g.setAuto(true);
        for (int i = 0; i < 360; i++) {
            g.update(1 / 60.0D);
        }
        FlowRaster r = new FlowRaster(320, 180);
        FlowScene.draw(r, g, 0, 0, true);
        assertTrue(lit(r) > 150, "the clot and its trail do not show: " + lit(r));
        FlowGame pressed = game(false, PillKind.ORIGIN_ENERGY);
        pressed.pressure = 90.0D;
        FlowRaster p = new FlowRaster(320, 180);
        FlowScene.draw(p, pressed, 0, 0, true);
        FlowRaster calm = new FlowRaster(320, 180);
        FlowScene.draw(calm, game(false, PillKind.ORIGIN_ENERGY), 0, 0, true);
        int edge = p.px[10 * 320 + 160];
        int was = calm.px[10 * 320 + 160];
        assertTrue(((edge >> 16) & 255) > ((was >> 16) & 255) + 15, "the pressure did not redden the edge");
    }

    @Test
    @DisplayName("Финал чистого прогона: звёздочки, потом один большой «бам», который быстро растёт и медленно гаснет")
    void theFinaleRisesFastAndFallsSlowly() {
        FlowGame g = game(false, PillKind.ORIGIN_ENERGY);
        g.setAuto(true);
        for (int i = 0; i < 60 * 60 && g.running(); i++) {
            g.update(1 / 60.0D);
        }
        assertEquals(FlowGame.State.DONE, g.state);
        int[] lit = new int[6];
        double[] at = {0.05D, 0.2D, 0.55D, 0.62D, 1.0D, 1.8D};
        int k = 0;
        while (k < at.length) {
            if (g.end >= at[k]) {
                FlowRaster r = new FlowRaster(320, 180);
                FlowScene.draw(r, g, 0, 0, false);
                lit[k++] = white(r);
            } else {
                g.update(1 / 60.0D);
            }
        }
        assertTrue(lit[3] > lit[1] * 3 + 100, "no bam after the small stars: " + java.util.Arrays.toString(lit));
        assertTrue(lit[4] < lit[3] && lit[4] > lit[5], "the bam does not fall slowly: " + java.util.Arrays.toString(lit));
    }

    @Test
    @DisplayName("Разрыв и обрыв красят экран; без разрешения вспышек тон слабее")
    void tornAndBurstTintTheScreen() {
        FlowGame torn = game(true, PillKind.ORIGIN_ENERGY);
        torn.update(0.1D);
        FlowRaster bright = new FlowRaster(320, 180);
        FlowRaster calm = new FlowRaster(320, 180);
        FlowScene.draw(bright, torn, 0, 0, true);
        FlowScene.draw(calm, torn, 0, 0, false);
        int a = bright.px[5];
        int b = calm.px[5];
        assertTrue(((a >> 16) & 255) > ((b >> 16) & 255), "the flash switch changes nothing");
    }

    @Test
    @DisplayName("Растровые примитивы: диск и кольцо с жёсткими краями, звезда четырёхконечная")
    void primitives() {
        FlowRaster r = new FlowRaster(40, 40);
        r.clear(0xFF000000);
        r.disc(20, 20, 5, 0xFFFFFF, 1.0D, false);
        assertEquals(0xFFFFFF, r.px[20 * 40 + 20] & 0xFFFFFF);
        assertEquals(0, r.px[20 * 40 + 27] & 0xFFFFFF, "an edge pixel is half-lit: no anti-aliasing is allowed");
        r.clear(0xFF000000);
        r.star(20, 20, 15, 0xFFFFFF, 1.0D);
        assertTrue((r.px[20 * 40 + 20] & 255) > 200, "the star has no centre");
        assertTrue((r.px[12 * 40 + 20] & 255) > 100 || (r.px[12 * 40 + 19] & 255) > 100, "the star has no top arm");
        assertEquals(0, r.px[10 * 40 + 10] & 255, "the star filled its corners");
        r.clear(0xFF000000);
        r.arc(20, 20, 10, 2, 0, Math.PI, 0xFFFFFF, 1.0D, false);
        // from the top clockwise to the bottom: the right half is lit, the left one is dark
        assertTrue((r.px[20 * 40 + 30] & 255) > 0 && (r.px[20 * 40 + 10] & 255) == 0, "the half ring is not a half");
    }
}
