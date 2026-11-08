package io.github.verycooltimo.murim.decor;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Locate checked-in assets even when the test runner starts in a nested directory. */
final class CaveTestFiles {
    private CaveTestFiles() {}

    static Path root() {
        // Same source-tree lookup as DialogueCaptureGateTest; never silently skip missing assets.
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path root = workingDirectory;
        while (root != null && !Files.isRegularFile(root.resolve("src/test/resources/cave-props-before-1102.json"))) {
            root = root.getParent();
        }
        assertNotNull(root, "Cave test source tree not found above " + workingDirectory);
        return root;
    }
}
