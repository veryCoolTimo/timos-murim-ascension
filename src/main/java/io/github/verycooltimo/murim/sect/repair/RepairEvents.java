package io.github.verycooltimo.murim.sect.repair;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The listeners of the sect repairs (docs/design/39-player-path-v2.md, «Ремонт секты и казна»).
 *
 * <p>Damage is noticed where a block of the sect becomes air, whoever did it: {@link BlockEvent.NeighborNotifyEvent} fires for
 * every block change that updates neighbours (a player's break, an explosion, a fire that burns out, a mob, one of our techniques,
 * a falling block), and the explosion's own event lists its blocks before they go. A creative player's break and whatever a command
 * changes this tick are building, not damage. What players place is noted on {@link BlockEvent.EntityPlaceEvent}: the repairs
 * keep their hands off it.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
final class RepairEvents {
    private RepairEvents() {
    }

    @SubscribeEvent
    static void onNeighbor(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        BlockState state = event.getState();
        // only a block that is gone (air), burning or washed over can be damage; everything else is a block set or changed
        if (!(state.isAir() || state.is(BlockTags.FIRE) || !state.getFluidState().isEmpty())) {
            return;
        }
        RepairData data = RepairData.get(level);
        RepairEnv env = RepairService.env(level, data);
        if (env != null) {
            RepairService.noteRemoved(data, env, event.getPos(), level.getGameTime());
        }
    }

    @SubscribeEvent
    static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        RepairData data = RepairData.get(level);
        RepairService.noteBroken(data, event.getPos());
        if (event.getPlayer().isCreative()) {
            long now = level.getGameTime();
            if (data.creativeTick != now) {
                data.creativeBreaks.clear();
                data.creativeTick = now;
            }
            data.creativeBreaks.add(event.getPos().asLong());
        }
    }

    /** Last, so a placement another listener cancels is not noted. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof net.minecraft.world.entity.player.Player)
                || !(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        RepairData data = RepairData.get(level);
        RepairEnv env = RepairService.env(level, data);
        if (env == null) {
            return;
        }
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (BlockSnapshot snap : multi.getReplacedBlockSnapshots()) {
                RepairService.notePlaced(data, env, snap.getPos(), snap.getCurrentState());
            }
        } else {
            RepairService.notePlaced(data, env, event.getPos(), event.getPlacedBlock());
        }
    }

    @SubscribeEvent
    static void onDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || event.getAffectedBlocks().isEmpty()) {
            return;
        }
        RepairData data = RepairData.get(level);
        RepairEnv env = RepairService.env(level, data);
        if (env == null) {
            return;
        }
        long now = level.getGameTime();
        for (BlockPos pos : event.getAffectedBlocks()) {
            RepairService.noteBlast(data, env, pos, now);
        }
    }

    @SubscribeEvent
    static void onCommand(CommandEvent event) {
        MinecraftServer server = event.getParseResults().getContext().getSource().getServer();
        RepairData.get(server).commandTick = server.overworld().getGameTime();
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 7) {
            return;
        }
        ServerLevel level = server.overworld();
        RepairData data = RepairData.get(level);
        RepairEnv env = RepairService.env(level, data);
        if (env != null) {
            RepairService.tick(level, data, env, () -> RepairService.peddlers(level));
        }
    }
}
