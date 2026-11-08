package io.github.verycooltimo.murim.client.library;

import org.junit.jupiter.api.Test;
import com.google.gson.JsonParser;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Style;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class MethodScrollLayoutTest {
    @Test
    void frameUsesTextureAspectsAndCentersHorizontally() {
        for (int[] size : new int[][] {{240, 120}, {320, 240}, {640, 360}, {854, 480}, {1920, 1080}}) {
            var l = MethodScrollLayout.of(size[0], size[1], 128, 192);
            assertTrue(l.bounds().width() <= Math.max(220, Math.min(300, Math.round(size[0] * 0.38F))));
            assertEquals(l.top().width() * 28.0 / 186, l.top().height(), 1.0);
            assertEquals(l.bottom().width() * 40.0 / 200, l.bottom().height(), 1.0);
            assertBand(l.top(), 8, 178, 186, l.paper());
            assertBand(l.bottom(), 15, 185, 200, l.paper());
            assertTrue(l.bottom().width() > l.top().width());
            if (size[1] >= 240) {
                assertEquals(186, l.top().width());
                assertEquals(28, l.top().height());
                assertEquals(200, l.bottom().width());
                assertEquals(40, l.bottom().height());
            }
            assertEquals(Math.min(Math.round(size[1] * 0.92F), size[1] - 60), l.bounds().height());
            assertTrue(Math.abs(l.bounds().x() * 2 + l.bounds().width() - size[0]) <= 1);
            assertEquals(6, l.bounds().y());
            assertEquals(l.top().bottom(), l.paper().y());
            assertEquals(l.paper().bottom(), l.bottom().y());
            assertEquals(l.bounds().bottom(), l.bottom().bottom());
            assertTrue(l.bottom().width() > l.paper().width());
        }
    }

    @Test
    void fullBottomTextureClearsHudAtRequestedResolutionsAndScales() {
        for (int[] size : new int[][] {{1280, 720}, {1920, 1080}}) {
            for (int scale = 2; scale <= 4; scale++) {
                int guiWidth = (int) Math.ceil(size[0] / (double) scale);
                int guiHeight = (int) Math.ceil(size[1] / (double) scale);
                var l = MethodScrollLayout.of(guiWidth, guiHeight, 128, 192);
                // bottom() spans all 40 texture pixels, including the tassel below the roller.
                assertTrue(l.bottom().bottom() <= guiHeight - 50,
                        size[0] + "x" + size[1] + " at GUI scale " + scale);
                assertEquals(6, l.top().y());
                assertTrue(l.seal().x() >= l.paper().x());
                assertTrue(l.seal().right() <= l.paper().right());
                assertTrue(l.seal().y() >= l.paper().y());
                assertTrue(l.seal().bottom() < l.bottom().y());
                assertEquals(l.paper().x() + l.paper().width() / 2, l.seal().x() + l.seal().width() / 2, 1);
                assertTrue(l.text().bottom() <= l.seal().y() - 13);
            }
        }
    }

    @Test
    void shortestRequestedGuiKeepsOverflowViewportAboveSeal() {
        // 1280x720 at GUI scale 4 leaves only 12 paper pixels above the seal reserve.
        for (int textHeight : new int[] {0, 40, 200}) {
            var l = MethodScrollLayout.of(320, 180, 128, 192, textHeight);
            assertTrue(l.illustration().height() > 0);
            assertTrue(l.illustration().bottom() <= l.text().y());
            assertTrue(l.text().y() >= l.paper().y());
            assertTrue(l.text().height() >= 0);
            assertTrue(l.text().bottom() <= l.seal().y() - 13);
            if (textHeight > 0) assertTrue(l.text().height() > 0);
        }
    }

    @Test
    void shortPaperShrinksArtBeforeTextAndReservesSeal() {
        var l = MethodScrollLayout.of(320, 240, 128, 192, 40);
        int artAreaHeight = l.illustration().height();
        assertTrue(artAreaHeight < Math.round(l.paper().height() * 0.52F));
        assertTrue(artAreaHeight > 0);
        assertTrue(l.illustration().bottom() <= l.paper().y() + artAreaHeight);
        assertTrue(l.text().height() > 0);
    }

    @Test
    void shippedDescriptionsFitAboveSealInBothLanguagesAndRequestedGuiSizes() throws Exception {
        // Deliberately generous advances for the default font, including Cyrillic.
        // Use vanilla's word wrapping rather than a character-count approximation.
        // API: supplied minecraft-src/net/minecraft/client/StringSplitter.java constructor/splitLines.
        var splitter = new StringSplitter((codePoint, style) -> codePoint == ' ' ? 4F : 7F);
        for (String language : new String[] {"en_us", "ru_ru"}) {
            try (var input = getClass().getResourceAsStream("/assets/murim/lang/" + language + ".json")) {
                assertNotNull(input);
                var strings = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                for (String method : new String[] {"six_harmonies", "street_strength", "mountain_root",
                        "tang_poison_breath", "holy_fire_breath", "reverse_breathing"}) {
                    for (int[] size : new int[][] {{1280, 720, 2}, {1920, 1080, 3}}) {
                        int w = size[0] / size[2];
                        int h = size[1] / size[2];
                        var initial = MethodScrollLayout.of(w, h, 128, 192);
                        String key = "method.murim." + method;
                        int titleLines = splitter.splitLines(strings.get(key).getAsString(),
                                (int) (initial.text().width() / 1.25F), Style.EMPTY).size();
                        int bodyLines = splitter.splitLines(strings.get(key + ".cost").getAsString(),
                                initial.text().width(), Style.EMPTY).size();
                        int textHeight = titleLines * 12 + 4 + bodyLines * 9;
                        var fitted = MethodScrollLayout.of(w, h, 128, 192, textHeight);
                        assertTrue(textHeight <= fitted.text().height(), language + ": " + method);
                        assertTrue(fitted.text().bottom() <= fitted.seal().y() - 13);
                    }
                }
            }
        }
    }

    @Test
    void illustrationShrinksBeforeTitleGapAndOverflowRemainsScrollable() {
        var preferred = MethodScrollLayout.of(640, 360, 128, 192);
        var fitted = MethodScrollLayout.of(640, 360, 128, 192, 100);
        int minimumArt = Math.round(fitted.paper().height() * 0.36F);
        assertEquals(minimumArt, fitted.illustration().height(), 1);
        assertEquals(100, fitted.text().height());
        assertTrue(fitted.illustration().height() < preferred.illustration().height());
        var overflow = MethodScrollLayout.of(640, 360, 128, 192, 200);
        assertEquals(fitted.text(), overflow.text());
        assertTrue(200 > overflow.text().height());
    }

    private static void assertBand(MethodScrollLayout.Rect frame, int from, int to, int textureWidth,
                                   MethodScrollLayout.Rect paper) {
        int left = frame.x() + (int) Math.round(from * frame.width() / (double) textureWidth);
        int right = frame.x() + (int) Math.round(to * frame.width() / (double) textureWidth);
        assertEquals(paper.x(), left, 1);
        assertEquals(paper.right(), right, 1);
        assertEquals(paper.width(), right - left, 1);
    }

    @Test
    void artAndTextStayInsidePaperWithoutOverlap() {
        for (int[] image : new int[][] {{128, 192}, {1024, 256}, {32, 32}, {256, 2048}}) {
            var l = MethodScrollLayout.of(640, 360, image[0], image[1]);
            var a = l.illustration();
            assertTrue(a.x() >= l.paper().x() + 8 && a.right() <= l.paper().right() - 8);
            int artAreaHeight = Math.round(l.paper().height() * 0.52F);
            assertEquals(l.paper().y() + artAreaHeight + 6, l.text().y());
            assertTrue(a.bottom() <= l.paper().y() + artAreaHeight);
            assertTrue(a.y() >= l.paper().y() && a.bottom() <= l.text().y());
            assertTrue(l.text().x() > l.paper().x() && l.text().right() < l.paper().right());
            assertTrue(l.text().bottom() < l.paper().bottom());
            assertTrue(l.text().height() > 0);
            // Each dimension rounds by at most half a pixel. Cross multiplication
            // propagates both errors without amplifying them through a short height.
            assertEquals(a.width() * (double) image[1], a.height() * (double) image[0],
                    (image[0] + image[1]) * 0.5,
                    "Aspect must be preserved within half-pixel rounding per dimension");
        }
    }

    @Test
    void wideArtCanFillPaperWithEightPixelMargins() {
        var l = MethodScrollLayout.of(640, 360, 1024, 256);
        assertEquals(l.paper().x() + 8, l.illustration().x());
        assertEquals(l.paper().width() - 16, l.illustration().width());
        // 1024x256 fits as 154x38.5, so the height rounds up to 39 GUI pixels.
        assertEquals(154, l.illustration().width());
        assertEquals(39, l.illustration().height());
    }

    @Test
    void fittingUsesWholePixelEnlargementAndPreservesAspectWhenShrinking() {
        var box = new MethodScrollLayout.Rect(10, 20, 100, 80);
        assertEquals(new MethodScrollLayout.Rect(28, 28, 64, 64), MethodScrollLayout.fit(box, 32, 32));
        assertEquals(new MethodScrollLayout.Rect(10, 35, 100, 50), MethodScrollLayout.fit(box, 400, 200));
    }
}
