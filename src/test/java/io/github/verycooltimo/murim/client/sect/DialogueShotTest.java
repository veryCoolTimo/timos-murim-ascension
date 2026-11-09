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
            assertEquals(64.0D + 1.8D * DialogueShot.EYE, s.camera().y, 1.0E-6D);
        }
        assertTrue(want >= 2.5D && want <= 3.5D, "план ≈3,3 блока: " + want);
    }

    @Test
    @DisplayName("Через плечо: игрок в кадре сбоку от NPC; вплотную — скрыт, а не заслоняет")
    void overTheShoulder() {
        Vec3 player = new Vec3(0.0D, 64.0D, 2.4D);
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

    /** A low lintel: every ray from the aim point is cut to 0.9 blocks above/level with the aim, only a raised or rear ray is free. */
    private static DialogueShot.Clear lintel(double ceilingY) {
        return (from, to) -> {
            if (to.y <= ceilingY) {
                return from.distanceTo(to);
            }
            // Ray rises through the ceiling: free length up to the plane.
            double k = Math.max(0.0D, (ceilingY - from.y) / (to.y - from.y));
            return from.distanceTo(to) * k;
        };
    }

    @Test
    @DisplayName("Проём: луч режет камеру ближе 1,2 блока к цели - берётся другой кадр, не голова")
    void blockedRayFallsBack() {
        Vec3 player = new Vec3(0.0D, 64.0D, 2.4D);
        Vec3 aim = NPC.add(0.0D, 1.8D * DialogueShot.AIM, 0.0D);
        // Everything but a ray that stays level or dips is blocked at 0.5 blocks from the aim.
        DialogueShot.Clear blocked = (from, to) -> to.y <= from.y - 0.04D ? from.distanceTo(to) : 0.5D;
        DialogueShot.Shot s = DialogueShot.plan(NPC, 1.8D, player, Vec3.ZERO, blocked);
        assertTrue(s.camera().distanceTo(aim) >= DialogueShot.MIN_FOCUS, "камера не в голове: " + s.camera().distanceTo(aim));
        assertTrue(s.camera().y < aim.y - 0.04D + 1.0E-6D, "выбран кадр сверху вниз по свободному лучу: " + s.camera());
    }

    @Test
    @DisplayName("Проём закрыт со всех сторон кроме спины игрока - двойной кадр сзади")
    void behindPlayerTwoShot() {
        Vec3 player = new Vec3(0.0D, 64.0D, 2.4D);
        Vec3 aim = NPC.add(0.0D, 1.8D * DialogueShot.AIM, 0.0D);
        // Open only behind the player (z beyond the player) and above the aim.
        DialogueShot.Clear onlyBehind = (from, to) -> to.z >= 3.0D && to.y > aim.y ? from.distanceTo(to) : 0.4D;
        DialogueShot.Shot s = DialogueShot.plan(NPC, 1.8D, player, Vec3.ZERO, onlyBehind);
        assertTrue(s.camera().z > player.z, "камера за спиной игрока: " + s.camera());
        assertTrue(s.camera().distanceTo(aim) >= DialogueShot.MIN_FOCUS);
        assertTrue(s.camera().y > aim.y, "чуть выше глаз");
    }

    @Test
    @DisplayName("Чистый путь не меняется при запасных кадрах; сторона держится (гистерезис)")
    void hysteresis() {
        Vec3 player = new Vec3(0.0D, 64.0D, 2.4D);
        DialogueShot.Shot plain = DialogueShot.plan(NPC, 1.8D, player, Vec3.ZERO, OPEN);
        assertEquals(DialogueShot.distance(1.8D), npcDistance(plain), 1.0E-6D);
        // Both shoulders are equally free: the held side is kept, in both directions.
        DialogueShot.Shot left = DialogueShot.plan(NPC, 1.8D, player, Vec3.ZERO, OPEN, -1);
        DialogueShot.Shot right = DialogueShot.plan(NPC, 1.8D, player, Vec3.ZERO, OPEN, 1);
        assertEquals(-1, left.side());
        assertEquals(1, right.side());
    }
}
