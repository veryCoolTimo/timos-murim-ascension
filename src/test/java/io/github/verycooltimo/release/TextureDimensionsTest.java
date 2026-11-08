package io.github.verycooltimo.release;

import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TextureDimensionsTest {
    @Test void blockAndItemTexturesSupportFourMipLevels() throws Exception {
        Path projectRoot = ReleaseTestPaths.projectRoot();
        assertTrue(Files.isDirectory(projectRoot.resolve("src/main/resources/assets/murim/textures/block")),
                "Main block textures must exist before scanning texture dimensions");
        List<String> offenders = new ArrayList<>();
        for (String root : List.of("src/main/resources", "src/generated/resources")) {
            for (String folder : List.of("block", "item")) {
                Path directory = projectRoot.resolve(root).resolve("assets/murim/textures").resolve(folder);
                if (!Files.isDirectory(directory)) continue;
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.filter(p -> p.toString().endsWith(".png")).toList()) {
                        try (var in = new DataInputStream(Files.newInputStream(path))) {
                            assertEquals(0x89504e470d0a1a0aL, in.readLong(), path.toString());
                            assertEquals(13, in.readInt(), path.toString());
                            assertEquals(0x49484452, in.readInt(), path.toString());
                            int width = in.readInt(), height = in.readInt();
                            if (width <= 0 || height <= 0 || width % 16 != 0 || height % 16 != 0)
                                offenders.add(path + " (" + width + "x" + height + ")");
                        }
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Texture dimensions must be divisible by 16:\n" + String.join("\n", offenders));
    }
}
