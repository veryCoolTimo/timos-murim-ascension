package io.github.verycooltimo.murim.client.sect;

import net.minecraft.world.phys.Vec3;

/**
 * Кадр разговора — чистая геометрия без мира (юнит-тест {@code DialogueShotTest}). Автор 06.10: «нужна фиксированная
 * камера с NPC, потому что если ты близко подходишь, то NPC на весь экран».
 *
 * <p>Камера ставится не от игрока, а от собеседника: на дуге радиуса {@link #distance} вокруг NPC (≈3,3 блока при росте
 * 1,8), на высоте глаз, лицом к NPC. Поэтому NPC в кадре одного размера — подошёл игрок вплотную или стоит в четырёх
 * блоках. Классический план «через плечо»: на дуге выбирается угол, при котором игрок виден краем кадра с другой
 * стороны ({@link #PLAYER_ANGLE}). Стена за спиной — камера подтягивается по лучу, но не ближе {@link #MIN_SHARE}
 * от плана; не вышло — пробуются другие углы, другая сторона и боковой план. Игрок, который в кадре заслонил бы NPC
 * (стоит вплотную) или оказался у самой камеры, скрывается.
 */
public final class DialogueShot {

    /** Угол между NPC и игроком в кадре, градусов: плечо игрока у края, NPC правее центра. */
    static final double PLAYER_ANGLE = 36.0D;
    /** NPC правее (левее) центра кадра на столько градусов. */
    static final double NPC_OFFSET = 10.0D;
    /** Камера подтягивается стеной не ближе этой доли плана. */
    static final double MIN_SHARE = 0.6D;
    /** Ближе этого угла игрок закрывал бы NPC — его не рисуем. */
    static final double HIDE_ANGLE = 14.0D;
    /** Вертикальный угол объектива на полном плане, градусов. */
    static final double FOV = 50.0D;
    /** Центр кадра по высоте NPC (доля роста) и высота камеры: над свитком видно от макушки до пояса. */
    public static final double AIM = 0.55D;
    static final double EYE = 0.8D;

    /** Сколько блоков свободно по лучу от точки прицела к камере (стены). */
    @FunctionalInterface
    public interface Clear {
        double free(Vec3 from, Vec3 to);
    }

    /**
     * @param camera     точка глаз камеры
     * @param aim        куда смотрит центр кадра
     * @param fov        вертикальный объектив (шире, если стена подтянула камеру)
     * @param side       +1 — камера за правым плечом игрока, −1 — за левым
     * @param hidePlayer игрок заслонил бы NPC или стоит у камеры
     */
    public record Shot(Vec3 camera, Vec3 aim, double fov, int side, boolean hidePlayer) {
    }

    private DialogueShot() {
    }

    /**
     * План по росту NPC: ~3,4 блока при 1,8 и объектив 50° — от макушки до пояса над свитком (кадры 06.10: при 3 блоках
     * и 36–40° видно только по грудь — модель NPC крупнее хитбокса, а свиток закрывает нижние 45 % кадра).
     */
    public static double distance(double height) {
        return Math.max(2.8D, Math.min(10.0D, 1.89D * height));
    }

    /**
     * Кадр разговора.
     *
     * @param npcFeet    ноги NPC
     * @param npcHeight  рост NPC (высота хитбокса)
     * @param playerFeet ноги игрока
     * @param fallback   направление взгляда игрока (если стоят в одной точке)
     */
    public static Shot plan(Vec3 npcFeet, double npcHeight, Vec3 playerFeet, Vec3 fallback, Clear clear) {
        double d = distance(npcHeight);
        // f — от игрока к NPC по горизонтали, r — правая рука игрока, смотрящего на NPC.
        Vec3 f = new Vec3(npcFeet.x - playerFeet.x, 0.0D, npcFeet.z - playerFeet.z);
        double gap = f.length();
        if (gap < 1.0E-3D) {
            f = new Vec3(fallback.x, 0.0D, fallback.z);
            if (f.lengthSqr() < 1.0E-6D) {
                f = new Vec3(0.0D, 0.0D, 1.0D);
            }
        }
        f = f.normalize();
        Vec3 r = new Vec3(-f.z, 0.0D, f.x);
        Vec3 aim = npcFeet.add(0.0D, npcHeight * AIM, 0.0D);
        double camY = npcFeet.y + npcHeight * EYE;

        Shot best = null;
        double bestFree = -1.0D;
        for (int side : new int[] {1, -1}) {
            double phi = bestPhi(npcFeet, playerFeet, f, r, side, d, gap);
            // Предпочтительный угол, потом соседние, потом боковой план.
            for (double p : new double[] {phi, phi + 15.0D, phi - 15.0D, phi + 30.0D, 90.0D}) {
                if (p < 3.0D || p > 95.0D) {
                    continue;
                }
                Vec3 dir = dirAt(f, r, side, p);
                Vec3 full = new Vec3(npcFeet.x + dir.x * d, camY, npcFeet.z + dir.z * d);
                double free = Math.min(d, clear.free(aim, full));
                if (free >= d - 1.0E-3D) {
                    return shot(full, aim, d, d, side, npcFeet, playerFeet);
                }
                if (free > bestFree) {
                    bestFree = free;
                    // Подтянуть по лучу, отступив от стены.
                    double keep = Math.max(0.5D, free - 0.3D);
                    Vec3 ray = full.subtract(aim).normalize();
                    best = shot(aim.add(ray.scale(keep)), aim, keep, d, side, npcFeet, playerFeet);
                }
                if (free - 0.3D >= d * MIN_SHARE && best != null && best.side() == side) {
                    return best;
                }
            }
        }
        return best;
    }

    /** Угол на дуге (от оси «NPC → игрок»), при котором игрок в кадре на {@link #PLAYER_ANGLE} от NPC. */
    static double bestPhi(Vec3 npc, Vec3 player, Vec3 f, Vec3 r, int side, double d, double gap) {
        if (gap >= d - 0.4D) {
            // Игрок за камерой: в кадре только NPC, вполоборота.
            return 25.0D;
        }
        double best = 25.0D;
        double err = Double.MAX_VALUE;
        double widest = 0.0D;
        for (double p = 3.0D; p <= 80.0D; p += 1.0D) {
            Vec3 dir = dirAt(f, r, side, p);
            Vec3 cam = new Vec3(npc.x + dir.x * d, 0.0D, npc.z + dir.z * d);
            double a = angle(cam, npc, player);
            widest = Math.max(widest, a);
            double e = Math.abs(a - PLAYER_ANGLE);
            if (e < err) {
                err = e;
                best = p;
            }
        }
        // Игрок вплотную к NPC: развести их в кадре нельзя — он скрыт, NPC вполоборота.
        return widest < HIDE_ANGLE ? 25.0D : best;
    }

    /** Направление от NPC к камере: за спиной игрока ({@code -f}), повёрнутое к стороне на {@code phi}. */
    static Vec3 dirAt(Vec3 f, Vec3 r, int side, double phi) {
        double a = Math.toRadians(phi);
        return f.scale(-Math.cos(a)).add(r.scale(side * Math.sin(a)));
    }

    /** Угол между направлениями от камеры на NPC и на игрока по горизонтали, градусов. */
    static double angle(Vec3 cam, Vec3 npc, Vec3 player) {
        Vec3 a = new Vec3(npc.x - cam.x, 0.0D, npc.z - cam.z);
        Vec3 b = new Vec3(player.x - cam.x, 0.0D, player.z - cam.z);
        double la = a.length();
        double lb = b.length();
        if (la < 1.0E-6D || lb < 1.0E-6D) {
            return 0.0D;
        }
        double cos = Math.max(-1.0D, Math.min(1.0D, a.dot(b) / (la * lb)));
        return Math.toDegrees(Math.acos(cos));
    }

    private static Shot shot(Vec3 cam, Vec3 aim, double dist, double full, int side, Vec3 npc, Vec3 player) {
        // Подтянутая камера — шире объектив: NPC того же размера в кадре.
        double fov = Math.toDegrees(2.0D * Math.atan(Math.tan(Math.toRadians(FOV / 2.0D)) * full / Math.max(0.5D, dist)));
        fov = Math.min(80.0D, fov);
        Vec3 camFlat = new Vec3(cam.x, 0.0D, cam.z);
        double toPlayer = Math.hypot(player.x - cam.x, player.z - cam.z);
        // Игрок перед камерой: за ним NPC (угол мал) или камера у самой его головы.
        Vec3 n = new Vec3(npc.x, 0.0D, npc.z);
        Vec3 p = new Vec3(player.x, 0.0D, player.z);
        boolean front = p.subtract(camFlat).dot(n.subtract(camFlat)) > 0.0D && toPlayer < camFlat.distanceTo(n);
        boolean hide = toPlayer < 1.0D || front && angle(camFlat, n, p) < HIDE_ANGLE;
        return new Shot(cam, aim, fov, side, hide);
    }

    /** Поворот камеры (yaw, pitch по правилам Minecraft): NPC в стороне от центра, плечо игрока — у другого края. */
    public static float[] look(Vec3 cam, Vec3 aim, int side) {
        Vec3 l = aim.subtract(cam);
        float yaw = (float) (Math.toDegrees(Math.atan2(l.z, l.x))) - 90.0F;
        // Взгляд чуть в сторону игрока: NPC уходит на NPC_OFFSET к противоположному краю.
        yaw -= (float) (side * NPC_OFFSET);
        float pitch = (float) -Math.toDegrees(Math.atan2(l.y, Math.sqrt(l.x * l.x + l.z * l.z)));
        return new float[] {yaw, pitch};
    }
}
