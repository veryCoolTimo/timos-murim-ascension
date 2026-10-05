package io.github.verycooltimo.murim.client.seal;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.seal.ColdIron;
import io.github.verycooltimo.murim.sect.seal.ColdIronRegrowth;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Sparks off cold iron under the local player's qi strike: pale blue sparks while the strike bites (above Peak, qi
 * left), a few dull chips on the first blow when it does not. Block feedback in the vanilla way, client only; the
 * server decides whether the iron gives ({@link ColdIron}).
 * API: reference/minecraft-src/net/minecraft/client/multiplayer/MultiPlayerGameMode.java#isDestroying.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ColdIronSparks {

    private ColdIronSparks() {
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.isPaused()
                || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !mc.options.keyAttack.isDown() || mc.player.isCreative()) {
            return;
        }
        if (!ColdIronRegrowth.isColdIron(mc.level.getBlockState(hit.getBlockPos()))) {
            return;
        }
        Vec3 at = hit.getLocation();
        var r = mc.level.random;
        if (mc.gameMode.isDestroying() && ColdIron.biting(mc.player)) {
            for (int i = 0; i < 3; i++) {
                mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z,
                        (r.nextDouble() - 0.5D) * 0.6D, r.nextDouble() * 0.4D, (r.nextDouble() - 0.5D) * 0.6D);
            }
            if (mc.level.getGameTime() % 4 == 0) {
                mc.level.addParticle(ParticleTypes.CRIT, at.x, at.y, at.z, 0.0D, 0.1D, 0.0D);
            }
        } else if (mc.level.getGameTime() % 6 == 0) {
            for (int i = 0; i < 3; i++) {
                mc.level.addParticle(ParticleTypes.CRIT, at.x, at.y, at.z,
                        (r.nextDouble() - 0.5D) * 0.3D, r.nextDouble() * 0.2D, (r.nextDouble() - 0.5D) * 0.3D);
            }
        }
    }
}
