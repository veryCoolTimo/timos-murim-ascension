package io.github.verycooltimo.murim.client.camera;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CinematicDofTest {
    @Test void lifecycleWiring() throws Exception {
        var root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.isDirectory(root.resolve("src/main/java"))) root = root.getParent();
        assertNotNull(root);
        var base = root.resolve("src/main/java/io/github/verycooltimo/murim/client");
        String source = java.nio.file.Files.readString(base.resolve("camera/CinematicDof.java"));
        assertTrue(source.contains("if (Minecraft.getInstance().level == null) off();"));
        assertTrue(source.contains("if (chain != null) chain.close();"));
        assertTrue(source.contains("owned = false; blur = 0; close();"));
        assertTrue(java.nio.file.Files.readString(base.resolve("decor/TipsyResources.java"))
                .contains("CinematicDof.close()"));
    }
    @Test void sharpRangeAndMonotonicFalloff() {
        for (float depth = 9; depth <= 11; depth += .1F)
            assertEquals(0, CinematicDof.circleOfConfusion(depth, 10, 1, 6), .00001F);
        float previous = 0;
        for (float depth = 11; depth < 20; depth += .1F) {
            float radius = CinematicDof.circleOfConfusion(depth, 10, 1, 6);
            assertTrue(radius >= previous && radius <= 6);
            previous = radius;
        }
        assertEquals(6, previous);
        assertEquals(8, CinematicDof.circleOfConfusion(100, 10, 1, 100));
    }
}
