package io.github.verycooltimo.release;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every element of a Java block or item model must stay inside -16..32 on all three axes: one coordinate outside makes the
 * whole model fail to load ("'from' specifier exceeds the allowed boundaries") and the item shows black and purple.
 * API: reference/minecraft-src/net/minecraft/client/renderer/block/model/BlockElement.java#Deserializer.getFrom/getTo (bounds -16..32)
 * Regression 15.11: a 0.02 recess from the model audit moved the bed items' feet to z = -16.02 (fixed once before in be1525755).
 */
class ModelBoundsTest {
    private static final Pattern VEC = Pattern.compile(
            "\"(from|to)\"\\s*:\\s*\\[\\s*(-?[0-9.eE+-]+)\\s*,\\s*(-?[0-9.eE+-]+)\\s*,\\s*(-?[0-9.eE+-]+)\\s*]");

    @Test
    void everyModelElementStaysInsideTheAllowedBounds() throws Exception {
        Path projectRoot = ReleaseTestPaths.projectRoot();
        assertTrue(Files.isDirectory(projectRoot.resolve("src/main/resources/assets/murim/models")),
                "Main models must exist before scanning element bounds");
        List<String> offenders = new ArrayList<>();
        for (String root : List.of("src/main/resources", "src/generated/resources")) {
            Path directory = projectRoot.resolve(root).resolve("assets/murim/models");
            if (!Files.isDirectory(directory)) continue;
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                    Matcher m = VEC.matcher(Files.readString(path));
                    while (m.find()) {
                        for (int axis = 2; axis <= 4; axis++) {
                            double v = Double.parseDouble(m.group(axis));
                            if (v < -16.0D || v > 32.0D) {
                                offenders.add(projectRoot.relativize(path) + " " + m.group(0).replaceAll("\\s+", ""));
                                break;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Model elements outside -16..32 (the model would not load):\n" + String.join("\n", offenders));
    }
}
