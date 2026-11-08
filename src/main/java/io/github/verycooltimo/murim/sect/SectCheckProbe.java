package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.GateDoorBlock;
import io.github.verycooltimo.murim.decor.HallGrandDoorBlock;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sect-check telemetry (tools/sectcheck, never in a release run, only with env {@code MURIM_SMOKE=1}): per player in the sect
 * territory the guard watch (what is forbidden to him, what the watch did), the council intrusion, the guards near him and the
 * doors around him; per sect person near him the place he should be at and the place he is at. Prefix {@code SC-}.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectCheckProbe {
    private static final boolean ON = !FMLEnvironment.production && "1".equals(System.getenv("MURIM_SMOKE"));
    private static final double RADIUS = 48.0D;

    private SectCheckProbe() {
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    @SubscribeEvent
    static void onTick(ServerTickEvent.Post event) {
        if (!ON || event.getServer().getTickCount() % 20 != 3) {
            return;
        }
        MountHuaSite site = MountHuaSites.get(event.getServer());
        if (site == null) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        SectLayout layout = SectLayout.hua(site);
        long t = level.getGameTime();
        for (ServerPlayer p : level.players()) {
            player(level, p, layout, t);
            if (t % 40 < 20) {
                people(level, p, t);
                doors(level, p, layout, t);
            }
        }
    }

    private static void player(ServerLevel level, ServerPlayer p, SectLayout layout, long t) {
        SectState s = SectService.state(p);
        SectStanding standing = SectWatch.standing(p);
        SectSchedule.Period period = SectSchedule.at(level.getDayTime());
        List<String> in = new ArrayList<>();
        for (SectAccess.Rule r : SectAccess.forbidden(layout, p.position(), standing, s.flags(), period, 0.0D)) {
            in.add(r.id());
        }
        List<String> near = new ArrayList<>();
        for (SectAccess.Rule r : SectAccess.forbidden(layout, p.position(), standing, s.flags(), period, SectAccess.WARN)) {
            near.add(r.id());
        }
        SectWatch.Trespass tr = SectWatch.of(p);
        List<String> guards = new ArrayList<>();
        for (SectDisciple d : level.getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(40.0D), d -> d.isAlive() && SectWatch.guards(d))) {
            guards.add(d.memberKey() + ":" + f(d.distanceTo(p)) + (d.dormant() ? ":dormant" : "") + (d.escorting() ? ":escort" : "")
                    + (d.blocking() ? ":block" : "") + (d.talkingTo() >= 0 ? ":talk" : "") + (d.isSleeping() ? ":asleep" : ""));
        }
        MurimMod.LOGGER.info("SC-W t={} day={} time={} pos={},{},{} gm={} member={} standing={} forbidden={} near={} last={} inside={} strikes={} escort={} "
                        + "council={} intr={} guards={}", t, SectSchedule.day(level.getDayTime()), Math.floorMod(level.getDayTime(), 24000L),
                f(p.getX()), f(p.getY()), f(p.getZ()), p.gameMode.getGameModeForPlayer().getName(), s.member(), standing, in, near,
                tr.last(), tr.inside, tr.strikes(), tr.escort != null && tr.escort.escortGoal().forPlayer(p),
                SectCouncil.phase(level.getDayTime()), SectCouncilIntrusion.count(p), guards);
    }

    private static void people(ServerLevel level, ServerPlayer p, long t) {
        for (SectDisciple d : level.getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(RADIUS), d -> d.isAlive())) {
            SectLife.Resolved r = SectLife.resolve(d);
            Vec3 spot = r == null ? null : r.spot();
            double away = spot == null ? -1.0D : Math.sqrt(d.position().distanceToSqr(spot.x, d.getY(), spot.z));
            Vec3 goal = d.walking().goal();
            MurimMod.LOGGER.info("SC-N t={} key={} role={} pos={},{},{} task={} zone={} place={} away={} dormant={} sit={} asleep={} escort={} block={} "
                            + "doing={} goal={} stuck={} nav={} walk=[{}] esc=[{}]", t, d.memberKey(), d.role().id(), f(d.getX()), f(d.getY()), f(d.getZ()),
                    r == null ? "-" : r.task().kind(), r == null ? "-" : r.task().zone(), r == null || r.spotId() == null ? "-" : r.spotId(),
                    f(away), d.dormant(), d.sitting(), d.isSleeping(), d.escorting(), d.blocking(), d.doing(),
                    goal == null ? "-" : f(goal.x) + "," + f(goal.y) + "," + f(goal.z), d.walking().stuckStage(), !d.getNavigation().isDone(),
                    d.walking().debug(), d.escortGoal().debug());
        }
    }

    private static void doors(ServerLevel level, ServerPlayer p, SectLayout layout, long t) {
        for (SectLayoutFile.Door d : layout.file().doors()) {
            BlockPos at = d.min();
            if (p.distanceToSqr(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D) > 30.0D * 30.0D || !level.isLoaded(at)) {
                continue;
            }
            boolean open = false;
            for (BlockPos q : BlockPos.betweenClosed(at, d.max())) {
                BlockState s = level.getBlockState(q);
                if (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.OPEN) || s.getBlock() instanceof HallGrandDoorBlock && s.getValue(HallGrandDoorBlock.OPEN)
                        || s.getBlock() instanceof GateDoorBlock && s.getValue(GateDoorBlock.OPEN)) {
                    open = true;
                }
            }
            if (!"opening".equals(d.kind())) {
                MurimMod.LOGGER.info("SC-D t={} door={} kind={} at={},{},{} open={}", t, d.id(), d.kind(), at.getX(), at.getY(), at.getZ(), open);
            }
        }
    }
}
