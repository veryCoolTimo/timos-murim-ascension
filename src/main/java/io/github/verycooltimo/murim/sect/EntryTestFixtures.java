package io.github.verycooltimo.murim.sect;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Test-only resources, released by AfterBatch even after an assertion or timeout. */
public final class EntryTestFixtures {
    // This is harness bookkeeping, never live-world gameplay state.
    private static final Map<String, List<Runnable>> CLEANUP = new HashMap<>();
    private final GameTestHelper helper;
    private final List<BlockPos> chunks = new ArrayList<>();
    private final List<Runnable> cleanup;
    private int entitySerial;

    public EntryTestFixtures(GameTestHelper helper, String batch) {
        this.helper = helper;
        // These batches each contain one test. A rerun must not inherit a failed attempt's actors/tickets.
        closeBatch(batch);
        cleanup = CLEANUP.computeIfAbsent(batch, ignored -> new ArrayList<>());
    }

    /** Own live tickets independently of the world's saved /forceload flags. */
    public void force(BlockPos a, BlockPos b) {
        ServerLevel level = helper.getLevel();
        int minX = Math.min(a.getX(), b.getX()) >> 4;
        int maxX = Math.max(a.getX(), b.getX()) >> 4;
        int minZ = Math.min(a.getZ(), b.getZ()) >> 4;
        int maxZ = Math.max(a.getZ(), b.getZ()) >> 4;
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/server/level/
        // ChunkMap#prepareEntityTickingChunk requires FULL neighbors within radius 2.
        // Load these before requesting promotion, including chunks untouched by fixture blocks.
        // [НЕПРОВЕРЕНО: startup ordering caused the real-machine readiness stall;
        // rerun both walking fixtures and the full suite to confirm.]
        for (int x = minX - 2; x <= maxX + 2; x++) {
            for (int z = minZ - 2; z <= maxZ + 2; z++) {
                level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, true);
            }
        }
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/server/level/
        // ServerChunkCache#{add,remove}RegionTicket, DistanceManager#addRegionTicket:
        // FULL(33) - distance(3) = 30; adjacent chunks also reach ENTITY_TICKING(31).
        // Each fixture owns its ticket type, so cleanup cannot remove another test's ticket.
        TicketType<ChunkPos> ticket = TicketType.create("murim_entry_fixture", java.util.Comparator.comparingLong(ChunkPos::toLong));
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                level.getChunkSource().addRegionTicket(ticket, chunk, 3, chunk);
                cleanup.add(() -> level.getChunkSource().removeRegionTicket(ticket, chunk, 3, chunk));
                // Readiness covers the requested area; the surrounding dependency ring is not a route.
                chunks.add(new BlockPos(x << 4, a.getY(), z << 4));
            }
        }
        // API: minecraft-src/net/minecraft/server/level/ChunkMap#prepareEntityTickingChunk
        // needs a radius-two FULL neighbourhood. Level#getChunk(int,int) requests FULL;
        // ServerChunkCache#getChunk drives its future with managedBlock. GameTestServer
        // #waitUntilNextTick has no normal tick delay, so a tick timeout is not an I/O budget.
        // Finish generation here, then let whenReady await the ticking-status callbacks.
        // Only the requested chunks above are readiness targets; this is dependency padding.
        for (int x = (Math.min(a.getX(), b.getX()) >> 4) - 2; x <= (Math.max(a.getX(), b.getX()) >> 4) + 2; x++) {
            for (int z = (Math.min(a.getZ(), b.getZ()) >> 4) - 2; z <= (Math.max(a.getZ(), b.getZ()) >> 4) + 2; z++) {
                level.getChunk(x, z);
            }
        }
    }

    public boolean ready() {
        for (BlockPos chunk : chunks) if (!helper.getLevel().isPositionEntityTicking(chunk)) return false;
        return true;
    }

    /** Distinguish promotion/visibility from ticking-ticket failures on the tester machine. */
    public String readiness() {
        ServerLevel level = helper.getLevel();
        List<String> pending = new ArrayList<>();
        for (BlockPos chunk : chunks) {
            if (!level.isPositionEntityTicking(chunk)) {
                // API: ServerLevel#isNaturalSpawningAllowed(BlockPos) reads entity-manager visibility;
                // ServerChunkCache#isPositionTicking(long) checks the block-ticking future and tickets.
                pending.add(new ChunkPos(chunk) + " entityVisibility=" + level.isNaturalSpawningAllowed(chunk)
                        + " blockTicking=" + level.getChunkSource().isPositionTicking(ChunkPos.asLong(chunk)));
            }
        }
        return pending.toString();
    }

    public void whenReady(Runnable action) {
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(ready(), "entry fixture chunks are not entity-ticking: " + readiness()))
                .thenExecute(action);
    }

    public void whenTicking(Runnable action, Entity... actors) {
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(ready(), "entry fixture lost entity-ticking chunks: " + readiness());
            for (Entity actor : actors)
                helper.assertTrue(actor.tickCount > 0 && !actor.isRemoved(), "entry actor has not ticked: " + actor.getId());
        }).thenExecute(action);
    }

    public ServerPlayer register(ServerPlayer player) {
        track(player);
        helper.getLevel().addNewPlayer(player);
        return player;
    }

    public <T extends Entity> T track(T entity) {
        if (entity instanceof net.minecraft.world.entity.LivingEntity actor) {
            actor.getRandom().setSeed(1101L + entitySerial++);
            actor.setOnGround(true);
        }
        cleanup.add(() -> {
            if (entity instanceof ServerPlayer player) {
                SectArrival.forget(player);
                SectRooms.release(player);
                helper.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            } else entity.discard();
        });
        return entity;
    }

    /** API: Mojang 1.21.1 AfterBatch hooks run after both passed and failed tests. */
    public static void closeBatch(String batch) {
        List<Runnable> actions = CLEANUP.remove(batch);
        if (actions == null) return;
        for (int i = actions.size() - 1; i >= 0; i--) actions.get(i).run();
    }
}
