package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.npc.NpcProfile;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source and resource checks, no client bootstrap: every skin the renderer names exists at the size of its layout, every lay
 * role has a skin of its own (not the student's), and the quest folk render their own 128x64 swordsman-layout skins.
 */
class SectSkinsTest {
    private static Path root() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent())
            if (Files.exists(p.resolve("src/main/resources/assets/murim/lang/en_us.json"))) return p;
        throw new IllegalStateException("Cannot locate source tree");
    }

    private static Path assets() {
        return root().resolve("src/main/resources/assets/murim");
    }

    @Test void everySectTextureNamedByTheRendererExistsAt256() throws Exception {
        String src = Files.readString(root().resolve("src/main/java/io/github/verycooltimo/murim/client/sect/DiscipleRenderer.java"));
        Matcher m = Pattern.compile("\"(textures/entity/sect/[a-z0-9_]+\\.png)\"").matcher(src);
        int n = 0;
        while (m.find()) {
            Path f = assets().resolve(m.group(1));
            assertTrue(Files.isRegularFile(f), "missing " + m.group(1));
            var img = ImageIO.read(f.toFile());
            assertEquals(256, img.getWidth(), m.group(1));
            assertEquals(256, img.getHeight(), m.group(1));
            n++;
        }
        assertTrue(n >= 13, "renderer names " + n + " sect textures");
    }

    @Test void layRolesHaveTheirOwnSkinNotTheStudents() throws Exception {
        String src = Files.readString(root().resolve("src/main/java/io/github/verycooltimo/murim/client/sect/DiscipleRenderer.java"));
        for (String role : List.of("STEWARD", "COOK", "PORTER", "GARDENER", "SWEEPER", "WATER_CARRIER")) {
            assertTrue(SectRole.valueOf(role).lay(), role + " is a lay role");
            Matcher m = Pattern.compile("Map\\.entry\\(SectRole\\." + role + ", (\\w+)\\)").matcher(src);
            assertTrue(m.find(), role + " has a body entry");
            assertTrue(m.group(1).startsWith("LAY_"), role + " wears " + m.group(1));
        }
        for (String skin : List.of("servant", "steward", "cook", "servant_scarf")) {
            assertTrue(Files.isRegularFile(assets().resolve("textures/entity/sect/" + skin + ".png")), skin);
        }
    }

    @Test void faceConstructorKnowsTheSkinsItComposes() throws Exception {
        String json = Files.readString(assets().resolve("face/parts.json"));
        for (String skin : List.of("servant", "steward")) {
            assertTrue(json.contains("textures/entity/sect/" + skin + ".png"), skin);
        }
    }

    @Test void everyGuestProfileResolvesToItsTexture() throws Exception {
        for (NpcProfile p : NpcProfile.values()) {
            Path f = assets().resolve("textures/entity/" + p.texture + ".png");
            assertTrue(Files.isRegularFile(f), p.id + " -> " + p.texture);
            var img = ImageIO.read(f.toFile());
            // Guest skins (11.14, tools/art/guests_1114.py): 256x512 = the 128x256 UV atlas at 2 px per unit; the peddler skin stays 128x64.
            boolean guest = p.texture.startsWith("guest_");
            assertEquals(guest ? 256 : 128, img.getWidth(), p.id);
            assertEquals(guest ? 512 : 64, img.getHeight(), p.id);
        }
        for (NpcProfile p : List.of(NpcProfile.SUPPLIER, NpcProfile.IMPOSTOR, NpcProfile.TRADER)) {
            assertEquals("guest_" + p.id, p.texture, "quest folk have their own skin");
        }
    }
}
