package io.github.verycooltimo.release;

import java.nio.file.Files;
import java.nio.file.Path;

/** Source-tree tests also run from ModDevGradle's nested test working directory. */
final class ReleaseTestPaths {
    private ReleaseTestPaths() {}

    // Same ancestor lookup as DialogueAckClosesTest; do not change the worker's working directory.
    static Path projectRoot() {
        Path start = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("build.gradle"))
                    && Files.isDirectory(candidate.resolve("src/main/java"))) {
                return candidate;
            }
        }
        throw new AssertionError("Project source tree not found above test working directory: " + start);
    }
}
