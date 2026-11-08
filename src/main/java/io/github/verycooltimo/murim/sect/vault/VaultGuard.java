package io.github.verycooltimo.murim.sect.vault;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;

/**
 * The cold iron wraps the vault on all sides (author 23.10: «mining, TNT and ender pearls must not get you in»): the wall is
 * unbreakable and blast-proof ({@code SealRegistry#sealed}); this class closes the last gap, teleports. A pearl, a chorus fruit or
 * an enderman may not take anything from outside the shaft into it, or from the shaft into the vault proper; moving inside the same
 * volume (or out) is free. Commands (/tp) are the operator's own business and stay.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class VaultGuard {

    /** How deep inside the vault a position is: 0 outside, 1 in the shaft or the tunnel, 2 in the vault proper. */
    public static int depth(MountHuaSite site, double x, double y, double z) {
        BlockPos at = BlockPos.containing(x, y, z);
        BlockPos l = HuaVault.local(site, at);
        int best = 0;
        // the position and the cell above it (the body of a player stands in two cells)
        for (int dy = 0; dy <= 1; dy++) {
            if (VaultPlan.inSuite(l.getX(), l.getY() + dy, l.getZ())) {
                return 2;
            }
            if (VaultPlan.inApproach(l.getX(), l.getY() + dy, l.getZ())) {
                best = 1;
            }
        }
        return best;
    }

    /** Would a teleport from {@code from} to {@code to} cross into the vault. */
    public static boolean blocks(MountHuaSite site, Vec3 from, Vec3 to) {
        return depth(site, to.x, to.y, to.z) > depth(site, from.x, from.y, from.z);
    }

    /** Tests stamp the vault at a site of their own; when set, it stands in for the world's site. */
    static volatile MountHuaSite testSite;

    private static boolean forbidden(EntityTeleportEvent e) {
        MinecraftServer server = e.getEntity().getServer();
        if (server == null || e.getEntity().level().dimension() != Level.OVERWORLD || !(e.getEntity().level() instanceof ServerLevel)) {
            return false;
        }
        MountHuaSite site = testSite != null ? testSite : MountHuaSites.get(server);
        return site != null && HuaVault.applies(site) && blocks(site, e.getPrev(), e.getTarget());
    }

    @SubscribeEvent
    static void onPearl(EntityTeleportEvent.EnderPearl event) {
        if (forbidden(event)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onChorus(EntityTeleportEvent.ChorusFruit event) {
        if (forbidden(event)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onEnderman(EntityTeleportEvent.EnderEntity event) {
        if (forbidden(event)) {
            event.setCanceled(true);
        }
    }
}
