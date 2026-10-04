package io.github.verycooltimo.murim.technique;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Автор 04.10: книга стиля идёт «картинка, текст» на титул и на каждую форму; ни одной «шахматки». */
class BookLayoutTest {

    /** Формы, у которых своей картинки пока нет (вместо неё — титул стиля). Двенадцать — отложена автором 03.10. */
    private static final Set<String> NO_OWN_SPREAD = Set.of("tang_twelve_daggers");

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
                if (!NO_OWN_SPREAD.contains(f.getPath())) {
                    assertTrue(exists(BookLayout.spread(f.getPath())), "no picture spread for " + f);
                }
                assertTrue(exists("textures/gui/technique/" + f.getPath() + ".png") || NO_OWN_SPREAD.contains(f.getPath()),
                        "no technique icon for " + f);
            }
        }
    }
}
