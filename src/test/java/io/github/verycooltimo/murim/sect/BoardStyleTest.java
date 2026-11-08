package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.client.sect.BoardStyle;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Source/resource checks only: no client bootstrap or Minecraft font atlas. */
class BoardStyleTest {
    private static Path root() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent())
            if (Files.exists(p.resolve("src/main/resources/assets/murim/lang/en_us.json"))) return p;
        throw new IllegalStateException("Cannot locate source tree");
    }

    @Test void boardRoutesOnlyToBoardSpritesAndTabletKeepsItsNames() throws Exception {
        var names = new HashSet<>(BoardStyle.STRETCHED);
        names.addAll(BoardStyle.FIXED);
        names.addAll(List.of("button", "button_gold"));
        for (String name : names) {
            assertTrue(BoardStyle.sprite(true, name).startsWith("board/"));
            assertFalse(BoardStyle.sprite(true, name).contains("tablet"));
            assertEquals("tablet/" + name, BoardStyle.sprite(false, name));
        }
        for (String ornament : List.of("tassel", "medallion", "unknown"))
            assertThrows(IllegalArgumentException.class, () -> BoardStyle.sprite(true, ornament));
        String screen = Files.readString(root().resolve("src/main/java/io/github/verycooltimo/murim/client/sect/SectTabletScreen.java"));
        assertTrue(screen.contains("BoardStyle.sprite(board, name)"));
        assertFalse(screen.contains("\"tassel\""));
        assertFalse(screen.contains("\"medallion\""));
        String contracts = screen.substring(screen.indexOf("private void contracts("), screen.indexOf("private void standing("));
        assertFalse(contracts.contains("blitSprite("), "Contract rendering must use the checked sprite router");
        // All server-provided contract pictograms must have a board version too.
        String server = Files.readString(root().resolve("src/main/java/io/github/verycooltimo/murim/sect/SectTablet.java"));
        String icons = server.substring(server.indexOf("static String icon("), server.indexOf("static SectTabletData.Offer offer("));
        var matcher = java.util.regex.Pattern.compile("\\\"(big_[a-z_]+)\\\"").matcher(icons);
        int count = 0;
        while (matcher.find()) { assertTrue(BoardStyle.FIXED.contains(matcher.group(1))); count++; }
        assertEquals(3, count);
    }

    @Test void everyBoardSpriteExistsAndStretchingHasValidNineSliceMetadata() throws Exception {
        Path sprites = root().resolve("src/main/resources/assets/murim/textures/gui/sprites");
        var names = new HashSet<>(BoardStyle.STRETCHED);
        names.addAll(BoardStyle.FIXED);
        for (String name : names) {
            Path png = sprites.resolve(BoardStyle.sprite(true, name) + ".png");
            assertTrue(Files.isRegularFile(png), name);
            var image = ImageIO.read(png.toFile());
            assertNotNull(image, name);
            if (name.startsWith("big_")) {
                assertEquals(16, image.getWidth());
                assertEquals(16, image.getHeight());
                var colors = new HashSet<Integer>();
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                    if ((image.getRGB(x, y) >>> 24) != 0) colors.add(image.getRGB(x, y));
                assertTrue(colors.size() > 8, "Coloured illustration required: " + name);
            }
            if (name.startsWith("seal")) {
                assertTrue(image.getHeight() >= 24, name);
                assertEquals(0, image.getRGB(0, 0) >>> 24, "Stepped seal corner: " + name);
            }
            if (!BoardStyle.STRETCHED.contains(name)) continue;
            var scaling = JsonParser.parseString(Files.readString(Path.of(png + ".mcmeta")))
                    .getAsJsonObject().getAsJsonObject("gui").getAsJsonObject("scaling");
            assertEquals("nine_slice", scaling.get("type").getAsString());
            int w = scaling.get("width").getAsInt(), h = scaling.get("height").getAsInt();
            assertEquals(image.getWidth(), w); assertEquals(image.getHeight(), h);
            int border = scaling.get("border").getAsInt();
            // GuiSpriteScaling.NineSlice#validate: border sums must leave a positive center.
            assertTrue(border > 0 && border * 2 < w && border * 2 < h, name);
        }
        try (var files = Files.list(sprites.resolve("board"))) {
            assertEquals(names.size(), files.filter(p -> p.toString().endsWith(".png")).count());
        }
    }
}
