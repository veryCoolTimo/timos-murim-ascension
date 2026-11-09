package io.github.verycooltimo.murim.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.verycooltimo.murim.npc.NpcProfile;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Every skin the bandit renderer and the guest profiles name must exist as a 128x64 PNG with painted pixels. */
class BanditSkinTexturesTest {
    private static final String[] RENDERER = {"bandit", "bandit_elite", "bandit_chief", "bandit_archer", "bandit_archer_elite"};

    private static BufferedImage load(String name) throws Exception {
        try (InputStream in = BanditSkinTexturesTest.class.getResourceAsStream("/assets/murim/textures/entity/" + name + ".png")) {
            assertNotNull(in, "missing texture " + name);
            return ImageIO.read(in);
        }
    }

    @Test
    void everyReferencedSkinExistsAtBanditUvSize() throws Exception {
        Set<String> names = new LinkedHashSet<>(Set.of(RENDERER));
        for (NpcProfile p : NpcProfile.values()) names.add(p.texture);
        for (String n : names) {
            BufferedImage im = load(n);
            assertEquals(128, im.getWidth(), n);
            assertEquals(64, im.getHeight(), n);
            int opaque = 0;
            for (int y = 0; y < 64; y++) for (int x = 0; x < 128; x++) if ((im.getRGB(x, y) >>> 24) != 0) opaque++;
            assertTrue(opaque > 1500, n + " looks empty: " + opaque);
        }
    }

    @Test
    void bandits_and_guests_do_not_share_a_face() throws Exception {
        String[] heads = {"bandit", "bandit_elite", "bandit_chief", "guest_ha_yun", "guest_tae_hyeon", "guest_cho_gan", "guest_han_dok", "guest_kwon_yeon"};
        Set<String> seen = new LinkedHashSet<>();
        for (String n : heads) {
            BufferedImage im = load(n);
            StringBuilder sb = new StringBuilder();
            for (int y = 8; y < 16; y++) for (int x = 8; x < 16; x++) sb.append(Integer.toHexString(im.getRGB(x, y)));
            assertTrue(seen.add(sb.toString()), n + " repeats another face");
        }
    }
}
