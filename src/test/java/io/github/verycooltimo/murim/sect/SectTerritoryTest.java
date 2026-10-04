package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.world.hua.MountHuaPlan;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Земля секты (без враждебного спавна) и раскладка площадок в мире при любом повороте горы. */
class SectTerritoryTest {

    private static MountHuaPlan.Zone zone(String id) {
        return MountHuaPlan.ZONES.stream().filter(z -> z.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Площадки секты — земля секты; нижние ворота, равнина и небо над ней — нет")
    void territory() {
        for (int rot = 0; rot < 4; rot++) {
            MountHuaSite site = MountHuaSite.placement(1000, -2000, 70, rot);
            for (String id : SectLayout.SECT_ZONES) {
                MountHuaPlan.Zone z = zone(id);
                int[] w = site.toWorld(z.u(), z.v());
                double y = site.worldY(z.y()) + 1;
                assertTrue(SectTerritory.contains(site, w[0] + 0.5, y, w[1] + 0.5), id + " rot " + rot);
                assertFalse(SectTerritory.contains(site, w[0] + 0.5, y + 120, w[1] + 0.5), id + " высоко в небе");
            }
            MountHuaPlan.Zone gate = zone("gate");
            int[] g = site.toWorld(gate.u(), gate.v());
            assertFalse(SectTerritory.contains(site, g[0] + 0.5, site.worldY(gate.y()) + 1, g[1] + 0.5), "нижние ворота тропы");
            assertFalse(SectTerritory.contains(site, 1000 + 3000, 70, -2000), "далеко");
        }
    }

    @Test
    @DisplayName("Раскладка: точка площадки и обратный перевод сходятся, поворот смотрит по локальной оси")
    void layoutRoundTrip() {
        for (int rot = 0; rot < 4; rot++) {
            MountHuaSite site = MountHuaSite.placement(-500, 800, 66, rot);
            SectLayout layout = SectLayout.hua(site);
            Vec3 p = layout.at("training", 4.0D, -3.0D);
            double[] back = layout.local("training", p);
            assertArrayEquals(new double[] {4.0D, -3.0D}, back, 1e-6, "rot " + rot);
            assertTrue(layout.inside("training", p, 0.0D));
            // Локальное «на юг» (+v) переводится в мир так же, как сама рамка.
            Vec3 south = layout.at("training", 0.0D, 10.0D).subtract(layout.at("training", 0.0D, 0.0D));
            float yaw = layout.yaw(0.0D, 1.0D);
            Vec3 look = Vec3.directionFromRotation(0.0F, yaw);
            assertEquals(1.0D, look.dot(south.normalize()), 1e-6, "rot " + rot);
        }
    }
}
