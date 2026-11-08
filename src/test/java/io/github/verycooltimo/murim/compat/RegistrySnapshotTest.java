package io.github.verycooltimo.murim.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RegistrySnapshotTest {
    @TempDir Path directory;

    @Test void disappearanceNeedsAnExistingReplacementOrExplicitRetirement() {
        var frozen = java.util.Set.of("minecraft:block murim:old");
        var current = java.util.Set.of("minecraft:item murim:old", "minecraft:block murim:new");
        assertEquals(frozen, RegistrySnapshotGameTests.missing(frozen, current, java.util.Map.of()));
        assertTrue(RegistrySnapshotGameTests.missing(frozen, current,
                java.util.Map.of("minecraft:block murim:old", "minecraft:block murim:new")).isEmpty());
        assertEquals(frozen, RegistrySnapshotGameTests.missing(frozen, current,
                java.util.Map.of("minecraft:block murim:old", "minecraft:block murim:absent")));
        assertTrue(RegistrySnapshotGameTests.missing(frozen, current,
                java.util.Map.of("minecraft:block murim:old", "RETIRED")).isEmpty());
        assertTrue(RegistrySnapshotGameTests.missing(current, current, java.util.Map.of()).isEmpty());
    }

    @Test void aliasesRequireExplicitReplacementOrRetirement() throws Exception {
        Path file = directory.resolve("retired.txt");
        Files.writeString(file, """
                # explicit migration
                minecraft:item murim:old => minecraft:item murim:new
                minecraft:block murim:removed => RETIRED
                """);
        var aliases = RegistrySnapshotGameTests.aliases(file);
        assertEquals("minecraft:item murim:new", aliases.get("minecraft:item murim:old"));
        assertEquals("RETIRED", aliases.get("minecraft:block murim:removed"));
        Files.writeString(file, "minecraft:item murim:old\n");
        assertThrows(IllegalArgumentException.class, () -> RegistrySnapshotGameTests.aliases(file));
    }
}
