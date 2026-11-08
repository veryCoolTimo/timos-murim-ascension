package io.github.verycooltimo.murim.technique;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Автор 04.10: книга стиля идёт «картинка, текст» на титул и на каждую форму; ни одной «шахматки». */
class BookLayoutTest {

    @Test
    void bambooHasSixHuaReferencePagesAndArtTitle() {
        var pages = BookLayout.pages(Styles.BAMBOO_HAND, BookLayoutTest::exists);
        assertEquals(6, pages.size());
        assertEquals("art.murim.bamboo_leaf_hand", Styles.BAMBOO_HAND.nameKey());
        assertEquals("huashan", BookLayout.sect(Styles.BAMBOO_HAND));
        for (int i = 0; i < pages.size(); i++) {
            assertTrue(!pages.get(i).picture());
            assertEquals(Styles.BAMBOO_HAND.forms().get(i), pages.get(i).form());
            assertTrue(exists(pages.get(i).texture()));
        }
    }

    private static boolean exists(String path) {
        // Ресурсы мода лежат на classpath теста (рабочая папка теста — не корень проекта).
        return BookLayoutTest.class.getResource("/assets/murim/" + path) != null;
    }

    @Test
    void everyBookAlternatesPictureAndText() {
        for (Styles.Style style : Styles.ALL) {
            if (!Styles.sequential(style)) {
                continue;
            }
            List<BookLayout.Page> pages = BookLayout.pages(style, BookLayoutTest::exists);
            assertEquals(2 + 2 * style.forms().size(), pages.size(), style.id().toString());
            for (int i = 0; i < pages.size(); i++) {
                assertEquals(i % 2 == 0, pages.get(i).picture(), style.id() + " page " + i + " should be a " + (i % 2 == 0 ? "picture" : "text"));
            }
            for (int i = 2; i < pages.size(); i += 2) {
                // Картинка и следующий за ней текст — одной и той же формы, по порядку стиля.
                assertEquals(style.forms().get(i / 2 - 1), pages.get(i).form());
                assertEquals(pages.get(i).form(), pages.get(i + 1).form());
            }
        }
    }

    /**
     * Автор 18.10: «почему в книге 4 шагов Бога Ветра теперь две страницы всего и страничка со сливой?» и «первая
     * страница — просто красивые картинки, что это Wind God Steps». Шаги — полная книга стиля, как у Семи Цветков:
     * титульная картинка, страница-описание, затем картинка и текст на каждую форму, без слив Хуашань.
     */
    @Test
    void footworkBooksAreFullStyleBooks() {
        for (Styles.Style style : Styles.FOOTWORK) {
            List<BookLayout.Page> pages = BookLayout.pages(style, BookLayoutTest::exists);
            // Seven Stars ships without paintings (seven-stars-implementation-1102.md).
            // Keep every overview/form text and verify the documented missing-art path;
            // the existing illustrated books still require all their pictures below.
            if (style == Styles.SEVEN_STARS) {
                assertEquals(1 + style.forms().size(), pages.size(), "Seven Stars: overview and both form texts");
                assertTrue(!pages.get(0).picture() && pages.get(0).form() == null);
                for (int i = 0; i < style.forms().size(); i++) {
                    assertTrue(!pages.get(i + 1).picture());
                    assertEquals(style.forms().get(i), pages.get(i + 1).form());
                }
                for (var page : pages) {
                    assertEquals("textures/gui/book/basic_huashan.png", page.texture());
                    assertTrue(exists(page.texture()), "Seven Stars text template is missing");
                }
                assertEquals("textures/gui/book/cover_huashan.png", BookLayout.cover(style));
                assertTrue(exists(BookLayout.cover(style)));
                // Supplying art must immediately give the same complete layout as the older books.
                List<BookLayout.Page> illustrated = BookLayout.pages(style, path -> true);
                assertEquals(2 + 2 * style.forms().size(), illustrated.size());
                assertEquals(BookLayout.title(style), illustrated.get(0).texture());
                assertTrue(illustrated.get(0).picture() && illustrated.get(0).form() == null);
                assertTrue(!illustrated.get(1).picture() && illustrated.get(1).form() == null);
                for (int i = 0; i < style.forms().size(); i++) {
                    var picture = illustrated.get(2 + 2 * i);
                    var text = illustrated.get(3 + 2 * i);
                    assertTrue(picture.picture() && !text.picture());
                    assertEquals(style.forms().get(i), picture.form());
                    assertEquals(picture.form(), text.form());
                    assertEquals(BookLayout.spread(picture.form().getPath()), picture.texture());
                }
                continue;
            }
            // Air forms currently ship with text only (fragrance-air-1103.md).
            // Preserve the title and all existing paintings; never substitute another form's art.
            Set<String> pendingArt = style == Styles.DARK_FRAGRANCE
                    ? Set.of("dark_fragrance_rising", "dark_fragrance_floating", "dark_fragrance_rain")
                    : Set.of();
            int pictures = 0;
            for (var form : style.forms()) {
                if (!pendingArt.contains(form.getPath()) || exists(BookLayout.spread(form.getPath()))) pictures++;
            }
            assertEquals(2 + style.forms().size() + pictures, pages.size(), style.id().toString());
            // Первый разворот — титульные картинки искусства, второй — описание (текст, без картинки формы).
            assertTrue(pages.get(0).picture() && pages.get(0).form() == null, style.id() + ": the book opens on the title picture");
            assertEquals(BookLayout.title(style), pages.get(0).texture());
            assertTrue(exists(BookLayout.title(style)), "no title spread " + BookLayout.title(style));
            assertTrue(!pages.get(1).picture() && pages.get(1).form() == null, style.id() + ": the second spread is the overview text");
            int page = 2;
            for (var form : style.forms()) {
                String spread = BookLayout.spread(form.getPath());
                if (!pendingArt.contains(form.getPath()) || exists(spread)) {
                    BookLayout.Page picture = pages.get(page++);
                    assertTrue(picture.picture(), style.id() + ": picture for " + form);
                    assertEquals(form, picture.form());
                    assertEquals(spread, picture.texture(), "the form's own picture");
                    assertTrue(exists(spread), "missing painting for " + form);
                }
                BookLayout.Page text = pages.get(page++);
                assertTrue(!text.picture(), style.id() + ": text for " + form);
                assertEquals(form, text.form());
                assertEquals(BookLayout.textTemplate(style), text.texture());
            }
            assertEquals(pages.size(), page);
            // Supplying the pending paintings must add a picture before every form's text.
            if (!pendingArt.isEmpty()) {
                List<BookLayout.Page> illustrated = BookLayout.pages(style, path -> true);
                assertEquals(2 + 2 * style.forms().size(), illustrated.size());
                for (int i = 0; i < style.forms().size(); i++) {
                    var form = style.forms().get(i);
                    var picture = illustrated.get(2 + 2 * i);
                    var text = illustrated.get(3 + 2 * i);
                    assertTrue(picture.picture() && !text.picture());
                    assertEquals(form, picture.form());
                    assertEquals(form, text.form());
                    assertEquals(BookLayout.spread(form.getPath()), picture.texture());
                }
            }
            assertTrue(exists(BookLayout.cover(style)), "cover " + BookLayout.cover(style));
            // Без титульной картинки книга открывается описанием.
            List<BookLayout.Page> noTitle = BookLayout.pages(style, path -> exists(path) && !path.equals(BookLayout.title(style)));
            assertEquals(pages.size() - 1, noTitle.size(), style.id() + " without a title spread");
            assertTrue(!noTitle.get(0).picture() && noTitle.get(0).form() == null);
        }
        assertEquals(2 + 2 * 4, BookLayout.pages(Styles.WIND_GOD, BookLayoutTest::exists).size(), "Wind God Steps: title + overview + 4 x (picture, text)");
    }

    private static final List<String> MAJOR_SINGLE = List.of(
            "six_harmonies", "falling_petal_sword", "shadow_step", "demon_palm");

    private static ResourceLocation art(String path) {
        return ResourceLocation.fromNamespaceAndPath("murim", path);
    }

    /**
     * Автор 19.10: «сделай так для каждой большой техники титульную». Большое одиночное искусство открывается
     * титульным разворотом, затем картинка техники (если нарисована), затем текст; шаблон — своей секты.
     */
    @Test
    void everyMajorSingleBookOpensOnATitle() {
        for (String path : MAJOR_SINGLE) {
            ResourceLocation id = art(path);
            List<BookLayout.Page> pages = BookLayout.single(id, BookLayoutTest::exists);
            boolean ownPicture = exists(BookLayout.spread(path));
            assertEquals(ownPicture ? 3 : 2, pages.size(), path);
            assertTrue(pages.get(0).picture() && BookLayout.title(id).equals(pages.get(0).texture()), path + ": the first spread is the title");
            assertTrue(exists(BookLayout.title(id)), "no title spread for " + path);
            if (ownPicture) {
                assertTrue(pages.get(1).picture() && BookLayout.spread(path).equals(pages.get(1).texture()), path + ": its own picture follows the title");
            }
            BookLayout.Page text = pages.get(pages.size() - 1);
            assertTrue(!text.picture(), path + ": the book ends on the text");
            // Страница формы, не страница стиля: подпись и печать работают по технике.
            for (BookLayout.Page p : pages) {
                assertEquals(id, p.form());
                assertTrue(exists(p.texture()), path + ": missing " + p.texture());
            }
            boolean hua = path.equals("six_harmonies") || path.equals("falling_petal_sword");
            assertEquals(hua ? "textures/gui/book/basic_huashan.png" : "textures/gui/book/basic_plain.png", text.texture(), path + " text template");
            assertEquals(hua ? "textures/gui/book/cover_huashan.png" : "textures/gui/book/cover_plain.png", BookLayout.cover(id), path + " cover");
            assertTrue(exists(BookLayout.cover(id)));
        }
    }

    /** Аудит 19.10: в книгах стилей нет ни одной формы без собственного разворота-картинки (двенадцатая Тан была последней). */
    @Test
    void noFormOfAStyleBookFallsBackToTheTitlePicture() {
        for (Styles.Style style : Styles.ALL) {
            for (BookLayout.Page p : BookLayout.pages(style, BookLayoutTest::exists)) {
                if (p.picture() && p.form() != null) {
                    assertEquals(BookLayout.spread(p.form().getPath()), p.texture(), style.id() + ": " + p.form() + " shows another picture");
                }
            }
        }
    }

    /** Малые искусства (бандитские, уличные, шуточные) и основа Семи Цветков титула не получают. */
    @Test
    void minorArtsHaveNoTitle() {
        for (String path : List.of("bandit_rush", "sand_in_eyes", "village_brawler_fist", "green_forest_axe", "crescent_sweep",
                "wedge_fan", "ceremonial_draw", "burst_breath", "seven_plum_basic", "heavenly_dragon_strike",
                "thousand_cranes_dance", "beggars_crushing_tap")) {
            for (BookLayout.Page p : BookLayout.single(art(path), BookLayoutTest::exists)) {
                assertTrue(!p.texture().endsWith("_title.png"), path + " must not open on a title: " + p.texture());
            }
        }
    }

    /** Шаги не Хуашань-сливой, кроме Тёмного Аромата (его текст — искусство лёгкого тела Хуашань). */
    @Test
    void windGodUsesTheNeutralTemplates() {
        assertEquals("plain", BookLayout.sect(Styles.WIND_GOD));
        assertEquals("textures/gui/book/basic_plain.png", BookLayout.textTemplate(Styles.WIND_GOD));
        assertEquals("textures/gui/book/cover_plain.png", BookLayout.cover(Styles.WIND_GOD));
        assertTrue(exists(BookLayout.textTemplate(Styles.WIND_GOD)) && exists(BookLayout.cover(Styles.WIND_GOD)));
        for (BookLayout.Page p : BookLayout.pages(Styles.WIND_GOD, BookLayoutTest::exists)) {
            assertTrue(!p.texture().contains("huashan"), "a plum Mount Hua page in the Wind God book: " + p.texture());
        }
        // Книги, которые были на сливах и остались: у них секта своя.
        assertEquals("huashan", BookLayout.sect(Styles.SEVEN_PLUM));
        assertEquals("huashan", BookLayout.sect(Styles.TWENTY_FOUR_PLUM));
        assertEquals("tang", BookLayout.sect(Styles.TANG_DAGGERS));
    }

    /** Каждый ключ текста книги шагов есть в обоих языках: описание искусства и текст каждой формы. */
    @Test
    void footworkBookTextsExistInBothLanguages() throws Exception {
        for (String lang : List.of("en_us", "ru_ru")) {
            String json;
            try (var in = BookLayoutTest.class.getResourceAsStream("/assets/murim/lang/" + lang + ".json")) {
                json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            var lines = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            for (Styles.Style style : Styles.FOOTWORK) {
                assertTrue(lines.has("book.murim.style." + style.id().getPath()), lang + ": no overview for " + style.id());
                for (var f : style.forms()) {
                    assertTrue(lines.has("book.murim." + f.getPath()), lang + ": no book text for " + f);
                    assertTrue(!lines.get("book.murim." + f.getPath()).getAsString().equals(lines.get("book.murim.style." + style.id().getPath()).getAsString()),
                            lang + ": the text of " + f + " repeats the overview");
                }
            }
        }
    }

    @Test
    void everyBookTextureExists() {
        for (Styles.Style style : Styles.ALL) {
            if (!Styles.sequential(style)) {
                continue;
            }
            assertTrue(exists(BookLayout.cover(style)), "cover " + BookLayout.cover(style));
            for (BookLayout.Page p : BookLayout.pages(style, BookLayoutTest::exists)) {
                assertTrue(exists(p.texture()), style.id() + ": missing " + p.texture());
            }
            for (var f : style.forms()) {
                // Аудит 19.10: у каждой формы каждой книги стиля — своя картинка (двенадцатая Тан дорисована).
                assertTrue(exists(BookLayout.spread(f.getPath())), "no picture spread for " + f);
                assertTrue(exists("textures/gui/technique/" + f.getPath() + ".png"),
                        "no technique icon for " + f);
            }
        }
    }
}
