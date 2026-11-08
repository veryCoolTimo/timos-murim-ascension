package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.technique.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen 2d3522ec8 ports: any coefficient/count/timing drift requires deliberate review.
 * See docs/adr/restored-star-looks-2026-11-06.md for the exhaustive rename/adaptation list. */
class RestoredLooksTest {
    private static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("src/main/java"))) p = p.getParent();
        assertNotNull(p);
        return p;
    }
    private static String source(String name) throws Exception {
        return Files.readString(root().resolve("src/main/java/io/github/verycooltimo/murim/client/vfx/" + name + ".java"));
    }
    @Test void portsRetainEveryHistoricalCoefficientAndPayloadRead() throws Exception {
        var expected = Map.ofEntries(
            Map.entry("BambooClassicVfx", "6c53ac9c76a57f2aa213f962c625c63ad945c6b5eabde2aba61e671e5328ccdd"),
            Map.entry("ClassicBambooScale", "0a87f96ba8cd514936af34188f8d82a312f9b09e00ab846224873e6fe867f63f"),
            Map.entry("ClassicBambooEnding", "160f16644f56c4ee71ba96aca17ad1ab7fdea6ad6089e4c4501d8ff69a1e9575"),
            Map.entry("ClassicBambooGroveEnding", "3321f222bbcbca2091d8197a86db464ee69bcdbd51966d70c46c5aabf81d7226"));
        for (var e : expected.entrySet()) {
            byte[] bytes = source(e.getKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(e.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), e.getKey());
        }
    }
    @Test void everyFormKeepsTheExistingLookBoundary() throws Exception {
        String bridge = Files.readString(root().resolve("src/main/java/io/github/verycooltimo/murim/network/ClientPayloadBridge.java"));
        for (int stars : new int[]{0, 1, 6, 11, 12}) {
            assertEquals(stars == 12, BambooLook.mastered(stars));
        }
        assertTrue(source("BambooVfx").contains("if (!BambooLook.mastered(p.stars())) BambooClassicVfx.accept(p);"));
    }
    @Test void bambooRetainsItsContinuousStarScale() throws Exception {
        String scale = source("ClassicBambooScale");
        assertTrue(scale.contains("double tier = Math.clamp((cue.stars() - 1) / 11.0, 0, 1);"));
        assertTrue(scale.contains("double size = .24 + .76 * tier;"));
        double[] sizes = {.24, .5854545454545454, .9309090909090909};
        int[] stars = {1, 6, 11};
        for (int i = 0; i < stars.length; i++) {
            double tier = Math.clamp((stars[i] - 1) / 11.0, 0, 1);
            assertEquals((stars[i] - 1) / 11.0, tier, 1e-12);
            assertEquals(sizes[i], .24 + .76 * tier, 1e-12);
        }
        assertTrue(source("BambooClassicVfx").contains("new Hand(p.masteryStars(),"));
    }
}
