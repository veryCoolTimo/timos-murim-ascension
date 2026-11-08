package io.github.verycooltimo.murim.dev;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Locale;

/**
 * Smoke-pass telemetry (tools/smoke, never in a release jar, only with env {@code MURIM_SMOKE=1}): one log line per second for
 * each player and one per 5 seconds for each sect person near a player, plus lines for fights, deaths and joins of sect people.
 * tools/smoke/analyze.py reads the lines (prefix {@code SMOKE-}) and decides what is a stuck state.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SmokeWatch {
    private static final boolean ON = !FMLEnvironment.production && "1".equals(System.getenv("MURIM_SMOKE"));
    private static final double RADIUS = 96.0D;

    private SmokeWatch() {
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String pos(Entity e) {
        return f(e.getX()) + "," + f(e.getY()) + "," + f(e.getZ());
    }

    @SubscribeEvent
    static void onTick(ServerTickEvent.Post event) {
        if (!ON) {
            return;
        }
        MinecraftServer server = event.getServer();
        long t = server.overworld().getGameTime();
        if (t % 20 != 0) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            BlockPos b = p.blockPosition();
            int air = 0;
            while (air < 12 && p.level().getBlockState(b.below(air + 1)).isAir()) {
                air++;
            }
            MurimMod.LOGGER.info("SMOKE-P t={} pos={} ground={} airBelow={} fall={} hp={} gm={}", t, pos(p), p.onGround(), air,
                    f(p.fallDistance), f(p.getHealth()), p.gameMode.getGameModeForPlayer().getName());
            if (t % 100 == 0) {
                MurimMod.LOGGER.info("SMOKE-S t={} member={} rank={}", t,
                        io.github.verycooltimo.murim.sect.SectService.state(p).member(),
                        p.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE).rank());
            }
            if (t % 100 == 0 && p.level() instanceof ServerLevel level) {
                AABB box = p.getBoundingBox().inflate(RADIUS);
                for (SectDisciple d : level.getEntitiesOfClass(SectDisciple.class, box)) {
                    Vec3 goal = d.walking().goal();
                    MurimMod.LOGGER.info("SMOKE-N t={} id={} name={} role={} pos={} doing={} spar={} goal={} stuck={} hp={}", t,
                            d.getId(), d.getName().getString().replace(' ', '_'), d.role().id(), pos(d), d.doing(), d.spar().name(),
                            goal == null ? "-" : f(goal.x) + "," + f(goal.y) + "," + f(goal.z), d.walking().stuckStage(),
                            f(d.getHealth()));
                }
            }
        }
    }

    @SubscribeEvent
    static void onDamage(LivingDamageEvent.Post event) {
        if (!ON) {
            return;
        }
        Entity by = event.getSource().getEntity();
        Entity victim = event.getEntity();
        if (by == null || by instanceof Player) {
            return;
        }
        if (by instanceof SectDisciple || victim instanceof SectDisciple) {
            String sparBy = by instanceof SectDisciple a ? a.spar().name() : "-";
            String sparVic = victim instanceof SectDisciple v ? v.spar().name() : "-";
            MurimMod.LOGGER.info("SMOKE-HIT t={} by={}({}) spar={} victim={}({}) spar={} dmg={} at={}",
                    victim.level().getGameTime(), by.getType().getDescriptionId(), by.getName().getString().replace(' ', '_'),
                    sparBy, victim.getType().getDescriptionId(), victim.getName().getString().replace(' ', '_'), sparVic,
                    f(event.getNewDamage()), pos(victim));
        }
    }

    @SubscribeEvent
    static void onDeath(LivingDeathEvent event) {
        if (ON && event.getEntity() instanceof SectDisciple d) {
            MurimMod.LOGGER.info("SMOKE-DEATH t={} name={} role={} at={} source={} by={}", d.level().getGameTime(),
                    d.getName().getString().replace(' ', '_'), d.role().id(), pos(d), event.getSource().getMsgId(),
                    event.getSource().getEntity() == null ? "-" : event.getSource().getEntity().getName().getString().replace(' ', '_'));
        }
    }

    @SubscribeEvent
    static void onJoin(EntityJoinLevelEvent event) {
        if (ON && !event.getLevel().isClientSide && event.getEntity() instanceof SectDisciple d) {
            MurimMod.LOGGER.info("SMOKE-JOIN t={} id={} uuid={} name={} role={} at={}", event.getLevel().getGameTime(), d.getId(),
                    d.getUUID(), d.getName().getString().replace(' ', '_'), d.role().id(), pos(d));
        }
    }
}
