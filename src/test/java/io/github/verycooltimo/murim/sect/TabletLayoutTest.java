package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.client.sect.TabletLayout;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** No Minecraft client, world, font atlas or game bootstrap: geometry and bounded wrapping only. */
class TabletLayoutTest {
    private static Path root() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent())
            if (Files.exists(p.resolve("src/main/resources/assets/murim/lang/en_us.json"))) return p;
        throw new IllegalStateException("Cannot locate the source tree");
    }
    private static JsonObject lang(String code) throws Exception {
        return JsonParser.parseString(Files.readString(root().resolve("src/main/resources/assets/murim/lang/" + code + ".json"))).getAsJsonObject();
    }
    @Test void panelSharesAndControlsAtEveryRequestedResolutionAndScale() {
        for (int[] size : List.of(new int[] {1280, 720}, new int[] {1920, 1080})) for (int scale = 2; scale <= 4; scale++) {
            int width = (size[0] + scale - 1) / scale, height = (size[1] + scale - 1) / scale;
            var tablet = TabletLayout.panel(width, height, false);
            var board = TabletLayout.panel(width, height, true);
            var tracker = TabletLayout.tracker(width, height);
            assertEquals(Math.min(width - 8, Math.max(290, Math.round(width * .46F))), tablet.width());
            share(tablet.height(), height, .60, .65);
            share(board.width(), width, .56, .60); share(board.height(), height, .55, .60);
            share(tracker.width(), width, .28, .32); share(tracker.height(), height, .18, .24);
            assertEquals(width - 4, tablet.right());
            assertTrue(Math.abs(board.x() - (width - board.width()) / 2) <= 1);
            assertTrue(TabletLayout.button(scale) * scale >= 36 && TabletLayout.button(scale) * scale <= 40);
            assertTrue(TabletLayout.standingRow(scale) * scale >= 32 && TabletLayout.standingRow(scale) * scale <= 36);
            assertTrue(TabletLayout.tabs(scale) * scale >= 40 && TabletLayout.tabs(scale) * scale <= 44);
            int row = TabletLayout.contractRow(scale, 1, 1);
            assertTrue(row * scale >= 72);
            assertTrue(row * scale <= 88 || scale > 2, "High GUI scales grow rows instead of shrinking text");
            assertEquals(0, TabletLayout.clampScroll(-5, 200, 100));
            assertEquals(100, TabletLayout.clampScroll(500, 200, 100));
        }
    }
    @Test void completeTabletContractAndStandingRowsFitPagesAtScalesTwoThroughFour() {
        for (int[] size : List.of(new int[] {1280, 720}, new int[] {1920, 1080})) {
            for (int scale = 2; scale <= 4; scale++) {
                boolean board = false;
                var panel = TabletLayout.panel((size[0] + scale - 1) / scale, (size[1] + scale - 1) / scale, board);
                var viewport = TabletLayout.viewport(panel, scale, board);
                int contract = TabletLayout.compactContractRow(scale, 2, 1);
                int standing = Math.max(TabletLayout.standingRow(scale), 30);
                for (int row : List.of(contract, standing)) {
                    assertTrue(row <= viewport.height(), size[0] + " scale " + scale + " board " + board);
                    int offset = TabletLayout.button(scale) + TabletLayout.gap(scale); // Help / preceding controls.
                    for (int n = 0; n < 12; n++) {
                        offset = TabletLayout.wholeRowTop(offset, row, viewport.height());
                        assertTrue(offset % viewport.height() + row <= viewport.height());
                        offset += row + TabletLayout.gap(scale);
                    }
                }
            }
        }
    }
    @Test void longestEnglishAndRussianButtonLabelsNeverWrapOrExceedTheirButton() throws Exception {
        // Conservative glyph envelope; runtime uses Font.width for both fitting and drawing.
        java.util.function.ToIntFunction<String> measure = value -> value.codePointCount(0, value.length()) * 9;
        List<String> keys = List.of("board.take", "board.hand_in", "board.help", "board.accept", "board.decline",
                "contracts.invite", "contracts.leave", "today.schedule", "journal.title", "standing.signup",
                "standing.beaten_toggle", "standing.household");
        for (String locale : List.of("ru_ru", "en_us")) for (String key : keys) {
            String label = lang(locale).get("murim.tablet." + key).getAsString().replace("%s", "999");
            for (boolean locked : List.of(false, true)) {
                int width = TabletLayout.buttonWidth(label, locked, measure);
                assertEquals(label, TabletLayout.fitLabel(label, width - (locked ? 18 : 10), measure));
                for (int available : List.of(80, 120, 180, 260)) {
                    String fitted = TabletLayout.fitLabel(label, available - (locked ? 18 : 10), measure);
                    assertTrue(TabletLayout.buttonWidth(fitted, locked, measure) <= available);
                    assertFalse(fitted.contains("\n"));
                }
            }
        }
    }
    @Test void contentClearsMeasuredFrameOrnamentsAtEveryScale() {
        for (int scale = 1; scale <= 4; scale++) for (boolean board : List.of(false, true)) {
            var panel = TabletLayout.panel((1280 + scale - 1) / scale, (720 + scale - 1) / scale, board);
            var viewport = TabletLayout.viewport(panel, scale, board);
            int ornament = (int) Math.ceil(panel.width() * 32.0 / 392);
            assertTrue(viewport.x() >= panel.x() + (board ? 12 : ornament + 12));
        }
    }
    @Test void boardKeepsNarrowMarginsAndScrollsLargeRowsAtEveryScale() {
        for (int scale = 2; scale <= 4; scale++) {
            var panel = TabletLayout.panel((1280 + scale - 1) / scale, (720 + scale - 1) / scale, true);
            var viewport = TabletLayout.viewport(panel, scale, true);
            assertEquals(12, viewport.x() - panel.x());
            assertEquals(16, panel.right() - viewport.right());
            assertTrue(viewport.height() > 0);
            assertTrue(viewport.bottom() <= panel.bottom() - 12);
            int content = 3 * (54 + 28 + 4);
            assertEquals(content - viewport.height(), TabletLayout.clampScroll(content, content, viewport.height()));
        }
    }
    @Test void everyTabLabelFitsOneLineAtBothResolutionsAndAllRequestedScales() throws Exception {
        // Six pixels bounds the normal vanilla bitmap glyph advances in these RU/EN labels.
        // Production centers the same labels using Font.width, at scale 1.0.
        for (String locale : List.of("ru_ru", "en_us")) {
            JsonObject text = lang(locale);
            for (int[] size : List.of(new int[] {1280, 720}, new int[] {1920, 1080})) {
                for (int scale = 2; scale <= 4; scale++) {
                    var panel = TabletLayout.panel((size[0] + scale - 1) / scale, (size[1] + scale - 1) / scale, false);
                    int total = 0;
                    for (int i = 0; i < TabletLayout.TAB_LABELS.size(); i++) {
                        var cell = TabletLayout.tab(panel, scale, i);
                        String key = "murim.tablet.tab." + TabletLayout.TAB_LABELS.get(i);
                        String label = text.get(key).getAsString();
                        assertFalse(label.isBlank());
                        assertFalse(label.contains("\n"), key);
                        int measured = label.codePointCount(0, label.length()) * 6;
                        assertTrue(measured <= cell.width() - 2 * TabletLayout.TAB_PADDING,
                                locale + " " + key + " " + size[0] + " scale " + scale);
                        assertTrue(Math.abs(cell.width() - TabletLayout.tab(panel, scale, 0).width()) <= 1);
                        if (i > 0) assertEquals(TabletLayout.tab(panel, scale, i - 1).right(), cell.x());
                        total += cell.width();
                    }
                    assertEquals(panel.width() - 2 * TabletLayout.frameEdge(panel, scale), total);
                }
            }
        }
    }
    private static void share(int value, int total, double min, double max) {
        assertTrue(value / (double) total >= min && value / (double) total <= max);
    }
    @Test void clockLabelsAndTimerShareOneBoundaryIncludingTheMorningBreak() {
        assertEquals("breakfast", TabletLayout.clock(1999).now());
        assertEquals("break", TabletLayout.clock(2000).now());
        assertEquals("training", TabletLayout.clock(2000).next());
        assertEquals(90, TabletLayout.clock(2000).seconds());
        assertEquals("training", TabletLayout.clock(3800).now());
        assertEquals("dinner", TabletLayout.clock(3800).next());
        assertEquals(260, TabletLayout.clock(3800).seconds());
        assertEquals("formation", TabletLayout.clock(23999).now());
        assertEquals("breakfast", TabletLayout.clock(23999).next());
        for (var slot : TabletLayout.schedule()) assertEquals(slot.id(), TabletLayout.clock(slot.start()).now());
    }
    @Test void everyDisabledActionReasonHasNonemptyTextInBothLocales() throws Exception {
        for (String locale : List.of("ru_ru", "en_us")) {
            JsonObject text = lang(locale);
            for (TabletLayout.Reason reason : TabletLayout.Reason.values()) {
                assertTrue(text.has(reason.key), locale + ": " + reason.key);
                assertFalse(text.get(reason.key).getAsString().isBlank());
            }
        }
        assertEquals(TabletLayout.Reason.BOARD, TabletLayout.takeReason(false, true, false, false));
        assertEquals(TabletLayout.Reason.STANDING, TabletLayout.takeReason(true, false, false, false));
        assertEquals(TabletLayout.Reason.ACTIVE, TabletLayout.takeReason(true, true, true, false));
        assertEquals(TabletLayout.Reason.DONE, TabletLayout.takeReason(true, true, false, true));
        assertNull(TabletLayout.takeReason(true, true, false, false));
    }
    @Test void longestLocalizedRowsAndUnbrokenWordsWrapWithoutOverflow() throws Exception {
        // Conservative 9-pixel glyph envelope; production supplies vanilla Font.width to this same function.
        // Test the longest member of each row type, with large dynamic values and the longest shipped NPC name.
        for (String locale : List.of("ru_ru", "en_us")) {
            JsonObject text = lang(locale);
            String npc = text.entrySet().stream().filter(e -> e.getKey().startsWith("npc.murim."))
                    .map(e -> e.getValue().getAsString()).max(java.util.Comparator.comparingInt(String::length)).orElse("Timo");
            for (String prefix : List.of("murim.tablet.contract.", "murim.tablet.rank.", "murim.tablet.reason.",
                    "murim.tablet.standing.", "murim.tablet.today.", "murim.tablet.board.", "murim.tablet.tab.")) {
                String longest = text.entrySet().stream().filter(e -> e.getKey().startsWith(prefix))
                        .map(e -> e.getValue().getAsString().replaceAll("%[0-9$]*[sd]", npc + " 999/999"))
                        .max(java.util.Comparator.comparingInt(String::length)).orElseThrow();
                for (int width : List.of(32, 64, 128, 256)) {
                    var wrapped = TabletLayout.wrap(longest, width, s -> s.codePointCount(0, s.length()) * 9);
                    assertFalse(wrapped.isEmpty());
                    for (String line : wrapped) assertTrue(line.codePointCount(0, line.length()) * 9 <= width, locale + ": " + line);
                }
            }
            var word = TabletLayout.wrap(npc.replace(" ", "") + npc.replace(" ", ""), 32, s -> s.length() * 9);
            assertTrue(word.size() > 1);
            assertTrue(word.stream().allMatch(s -> s.length() * 9 <= 32));
        }
    }
}
