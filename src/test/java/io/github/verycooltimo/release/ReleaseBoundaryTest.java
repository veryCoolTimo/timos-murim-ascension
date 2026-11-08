package io.github.verycooltimo.release;

import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.spi.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseBoundaryTest {
    private static final Path PROJECT_ROOT = ReleaseTestPaths.projectRoot();

    @Test void excludedDirectoryEntriesAndFilesAreRejected() throws Exception {
        var patterns = ReleasePatterns.read(PROJECT_ROOT.resolve("tools/ci/release-excludes.txt"));
        // Do not filter out ZipEntry.isDirectory(): an empty fixture directory is still a leak.
        assertTrue(ReleasePatterns.matches(patterns, "data/murim/gametest/"));
        assertTrue(ReleasePatterns.matches(patterns, "data/murimbench/"));
        String build = Files.readString(PROJECT_ROOT.resolve("build.gradle"));
        int jar = build.indexOf("tasks.named('jar', Jar).configure {");
        assertTrue(jar >= 0, "release jar task missing");
        assertTrue(build.substring(jar, build.indexOf("\n}", jar))
                .contains("includeEmptyDirs = false"), "release jar must omit empty directories");
    }

    @Test void releaseArchiveContainsNoExcludedEntries() throws Exception {
        String archive = System.getenv("MURIM_RELEASE_JAR");
        org.junit.jupiter.api.Assumptions.assumeTrue(archive != null && !archive.isBlank(),
                "Build the release jar, then set MURIM_RELEASE_JAR to its absolute path");
        var patterns = ReleasePatterns.read(PROJECT_ROOT.resolve("tools/ci/release-excludes.txt"));
        try (var zip = new java.util.zip.ZipFile(archive)) {
            var excluded = zip.stream().map(java.util.zip.ZipEntry::getName)
                    .filter(name -> ReleasePatterns.matches(patterns, name)).toList();
            assertTrue(excluded.isEmpty(), "Excluded release entries (including directories): " + excluded);
        }
    }

    @Test void modListLogoIsAnExistingResource() throws Exception {
        String metadata = Files.readString(PROJECT_ROOT.resolve("src/main/templates/META-INF/neoforge.mods.toml"));
        var logo = java.util.regex.Pattern.compile("(?m)^logoFile\\s*=\\s*\"([^\"]+)\"").matcher(metadata);
        assertTrue(logo.find(), "logoFile must be enabled in the mod metadata");
        Path resources = PROJECT_ROOT.resolve("src/main/resources");
        Path image = resources.resolve(logo.group(1)).normalize();
        assertTrue(image.startsWith(resources) && Files.isRegularFile(image), "logo resource missing: " + image);
        assertTrue(java.util.regex.Pattern.compile("(?m)^logoBlur\\s*=\\s*true\\s*$").matcher(metadata).find());
    }

    @Test void jarAndTestUseIdenticalParser() throws Exception {
        String relative = "src/main/java/io/github/verycooltimo/release/ReleasePatterns.java";
        assertEquals(Files.readString(PROJECT_ROOT.resolve("buildSrc").resolve(relative)),
                Files.readString(PROJECT_ROOT.resolve("src/test/java/io/github/verycooltimo/release/ReleasePatterns.java")));
        assertTrue(ReleasePatterns.matches(List.of("**/Thing$*.class"), "Thing$1.class"));
        assertTrue(ReleasePatterns.matches(List.of("data/test/**"), "data/test/a/b.nbt"));
        assertFalse(ReleasePatterns.matches(List.of("pkg/Test*.class"), "pkg/sub/Test.class"));
        assertTrue(ReleasePatterns.matches(List.of("pkg/T?st.class"), "pkg/Test.class"));
        Path list = Files.createTempFile("release-patterns", ".txt");
        try {
            Files.writeString(list, "  # comment\n\n pkg/Test.class  \n");
            assertEquals(List.of("pkg/Test.class"), ReleasePatterns.read(list));
        } finally { Files.delete(list); }
    }

    @Test void footworkCapturePolicyIsExcluded() throws Exception {
        var patterns = ReleasePatterns.read(PROJECT_ROOT.resolve("tools/ci/release-excludes.txt"));
        String stem = "io/github/verycooltimo/murim/dev/FootworkCapturePlan";
        assertTrue(ReleasePatterns.matches(patterns, stem + ".class"));
        assertTrue(ReleasePatterns.matches(patterns, stem + "$Schedule.class"));
        String duel = "io/github/verycooltimo/murim/dev/FootworkDuelCapture";
        assertTrue(ReleasePatterns.matches(patterns, duel + ".class"));
        assertTrue(ReleasePatterns.matches(patterns, duel + "$Fixture.class"));
    }

    @Test void everyGameTestHolderAndItsCompanionsAreExcluded() throws Exception {
        var patterns = ReleasePatterns.read(PROJECT_ROOT.resolve("tools/ci/release-excludes.txt"));
        Path root = PROJECT_ROOT.resolve("src/main/java");
        try (var sources = Files.walk(root)) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source);
                if (!text.contains("@GameTestHolder") && !text.contains("@GameTest(")) continue;
                String stem = root.relativize(source).toString().replace('\\', '/').replaceFirst("\\.java$", "");
                assertTrue(ReleasePatterns.matches(patterns, stem + ".class"), source.toString());
                assertTrue(ReleasePatterns.matches(patterns, stem + "$Fixture.class"), source.toString());
            }
        }
    }

    @Test void retainedClassesNeverReferenceExcludedClasses() throws Exception {
        var patterns = ReleasePatterns.read(PROJECT_ROOT.resolve("tools/ci/release-excludes.txt"));
        Path classes = PROJECT_ROOT.resolve("build/classes/java/main");
        assertTrue(Files.isDirectory(classes), "compileJava must produce the main classes first");
        var jdeps = ToolProvider.findFirst("jdeps").orElseThrow(() -> new AssertionError("Run tests with a full JDK (jdeps required)"));
        StringWriter output = new StringWriter();
        int result = jdeps.run(new PrintWriter(output), new PrintWriter(output),
                "--ignore-missing-deps", "-verbose:class", "-filter:none", classes.toString());
        assertEquals(0, result, output.toString());
        List<String> offenders = new ArrayList<>();
        int edges = 0;
        for (String line : output.toString().lines().toList()) {
            String[] pair = line.trim().split("\\s+");
            if (pair.length < 3 || !pair[1].equals("->") || !pair[0].startsWith("io.github.verycooltimo.murim.")) continue;
            edges++;
            String from = pair[0].replace('.', '/') + ".class";
            String to = pair[2].replace('.', '/') + ".class";
            if (!ReleasePatterns.matches(patterns, from) && ReleasePatterns.matches(patterns, to)) {
                offenders.add(pair[0] + " -> " + pair[2]);
            }
        }
        assertTrue(edges > 0, "jdeps produced no mod class dependencies: " + output);
        assertTrue(offenders.isEmpty(), "Release references excluded classes:\n" + String.join("\n", offenders));
    }
}
