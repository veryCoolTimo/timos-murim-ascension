package io.github.verycooltimo.murim.client.sect;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Кадр разговора (автор 06.10): одинаковый план NPC, как бы близко ни стоял игрок. */
class DialogueShotTest {

    private static final Vec3 NPC = new Vec3(0.0D, 64.0D, 0.0D);
    private static final DialogueShot.Clear OPEN = (from, to) -> from.distanceTo(to);

    private static double npcDistance(DialogueShot.Shot s) {
        return Math.hypot(s.camera().x - NPC.x, s.camera().z - NPC.z);
    }

    @Test
    @DisplayName("Вплотную и в четырёх блоках — камера на одном расстоянии от NPC и с тем же объективом")
    void sameFramingAtAnyDistance() {
        double want = DialogueShot.distance(1.8D);
        for (double gap : new double[] {0.6D, 1.0D, 1.5D, 2.0D, 3.0D, 4.0D}) {
            DialogueShot.Shot s = DialogueShot.plan(NPC, 1.8D, new Vec3(0.0D, 64.0D, gap), new Vec3(0.0D, 0.0D, -1.0D), OPEN);
            assertEquals(want, npcDistance(s), 1.0E-6D, "игрок в " + gap);
            assertEquals(DialogueShot.FOV, s.fov(), 1.0E-6D);
            assertEquals(64.0D + 1.8D * 0.85D, s.camera().y, 1.0E-6D);
        }
        assertTrue(want >= 2.5D && want <= 3.5D, "план ≈3 блока: " + want);
    }

    @Test
    @DisplayName("Через плечо: игрок в кадре сбоку от NPC; вплотную — скрыт, а не заслоняет")
    void overTheShoulder() {
        Vec3 player = new Vec3(0.0D, 64.0D, 1.8D);
        DialogueShot.Shot s = DialogueShot.plan(NPC, 1.8D, player, Vec3.ZERO, OPEN);
        Vec3 flat = new Vec3(s.camera().x, 0.0D, s.camera().z);
        double a = DialogueShot.angle(flat, NPC, player);
        assertEquals(DialogueShot.PLAYER_ANGLE, a, 3.0D, "игрок у края кадра");
        assertFalse(s.hidePlayer());
        DialogueShot.Shot close = DialogueShot.plan(NPC, 1.8D, new Vec3(0.0D, 64.0D, 0.5D), Vec3.ZERO, OPEN);
        assertTrue(close.hidePlayer(), "вплотную игрок закрыл бы NPC");
    }

    @Test
    @DisplayName("Стена за спиной: камера уходит на другой угол или подтягивается, объектив шире")
    void wallBehind() {
        // Стена по z = 2.2 за игроком: всё, что дальше, закрыто.
        DialogueShot.Clear wall = (from, to) -> {
            if (to.z <= 2.2D) {
                return from.distanceTo(to);
            }
            double k = (2.2D - from.z) / (to.z - from.z);
            return from.distanceTo(to) * k;
        };
        DialogueShot.Shot s = DialogueShot.plan(NPC, 1.8D, new Vec3(0.0D, 64.0D, 1.5D), Vec3.ZERO, wall);
        assertTrue(s.camera().z <= 2.2D, "камера не в стене: " + s.camera());
        assertTrue(npcDistance(s) >= DialogueShot.distance(1.8D) * DialogueShot.MIN_SHARE - 0.31D, "не ближе минимального плана");
    }

    @Test
    @DisplayName("Большой NPC — камера дальше, кадр тот же по доле")
    void bigNpc() {
        DialogueShot.Shot s = DialogueShot.plan(NPC, 3.6D, new Vec3(0.0D, 64.0D, 1.0D), Vec3.ZERO, OPEN);
        assertEquals(DialogueShot.distance(3.6D), npcDistance(s), 1.0E-6D);
        assertTrue(DialogueShot.distance(3.6D) > DialogueShot.distance(1.8D));
    }
}
