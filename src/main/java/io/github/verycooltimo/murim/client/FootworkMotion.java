package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.TraversePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Рывок шага собственной физикой игрока (автор 02.10: «от первого лица это телепорт — должно
 * быть, что ты быстро промчался, плавно до следующего расстояния доходишь»). Перед тиком игрока
 * ставим скорость, равную отрезку пути этого тика: профиль — резкий старт и торможение к концу.
 * Камера и коллизии — честные: стена останавливает, а не пропускает.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class FootworkMotion {

    private static Vec3 dir = Vec3.ZERO;
    private static double distance;
    private static int ticks;
    private static int elapsed = -1;

    public static void start(TraversePayloads.Dash dash) {
        Vec3 d = new Vec3(dash.dx(), dash.dy(), dash.dz());
        if (d.lengthSqr() < 1.0E-6D || dash.ticks() <= 0) {
            return;
        }
        dir = d.normalize();
        distance = dash.distance();
        ticks = dash.ticks();
        elapsed = 0;
    }

    public static boolean active() {
        return elapsed >= 0;
    }

    /** Доля пути к доле времени: быстро срывается, плавно тормозит. */
    static double progress(double x) {
        double k = 1.0D - Math.max(0.0D, Math.min(1.0D, x));
        return 1.0D - k * k * k;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (elapsed < 0 || player == null || minecraft.isPaused()) {
            return;
        }
        if (elapsed >= ticks || player.isPassenger()) {
            // Последний тик: гасим остаток скорости, чтобы не скользить дальше точки.
            Vec3 v = player.getDeltaMovement();
            player.setDeltaMovement(v.x * 0.2D, Math.abs(dir.y) > 0.02D ? 0.0D : v.y, v.z * 0.2D);
            elapsed = -1;
            return;
        }
        double step = distance * (progress((elapsed + 1) / (double) ticks) - progress(elapsed / (double) ticks));
        // Рывок в воздух ведёт и высоту; горизонтальный оставляет падение как есть.
        double vy = Math.abs(dir.y) > 0.02D ? dir.y * step + 0.08D : player.onGround() ? 0.0D : player.getDeltaMovement().y;
        player.setDeltaMovement(dir.x * step, vy, dir.z * step);
        elapsed++;
    }

    private FootworkMotion() {
    }
}
