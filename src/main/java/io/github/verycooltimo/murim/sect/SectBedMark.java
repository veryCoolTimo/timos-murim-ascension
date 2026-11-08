package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BedBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Your own bed (docs/design/39-player-path-v2.md, author 18.10): after admission the bed the sect assigned to the player
 * ({@link SectRooms}) shows soft particles to him alone while he is within {@link #RANGE} blocks, until he has slept in it
 * once ({@link #SLEPT}). Admission sets a safe dormitory fallback; vanilla {@code ServerPlayer#startSleepInBed} refines it when a bed is used.
 *
 * <p>The particles are sent to that one player ({@link ServerLevel#sendParticles(ServerPlayer, net.minecraft.core.particles.ParticleOptions,
 * boolean, double, double, double, int, double, double, double, double)}), so other players and NPCs see nothing; a few
 * motes every half second, not a column of light.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectBedMark {

    /** Flag of {@link SectState}: the player has slept in his assigned bed. */
    public static final String SLEPT = "room.slept";
    /** Blocks around the bed (centre to the player's eyes) inside which the marker shows. */
    public static final double RANGE = 12.0D;
    static final int PERIOD = 10;

    static final String NIGHT_UNTIL = "murim_bed_night_until";
    public static final int NIGHT_TICKS = 2400;

    @SubscribeEvent
    static void login(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) nightLogin(p);
    }

    static void nightLogin(ServerPlayer p) {
        p.getPersistentData().remove(NIGHT_UNTIL);
        if (SectService.state(p).member() && !SectService.state(p).has(SLEPT)
                && SectSchedule.at(p.level().getDayTime()) == SectSchedule.Period.NIGHT)
            p.getPersistentData().putLong(NIGHT_UNTIL, p.level().getGameTime() + NIGHT_TICKS);
    }

    private SectBedMark() {
    }

    /** Admission fallback, only once; sleeping anywhere later retains vanilla control of respawn.
     * API: NeoForge 1.21.1 patches/net/minecraft/server/level/ServerPlayer.java.patch#setRespawnPosition.
     */
    public static boolean admissionRespawn(ServerPlayer p, SectLayout layout) {
        if (!SectService.state(p).member() || p.getRespawnPosition() != null || layout == null || p.getPersistentData().getBoolean("murim_admission_spawn")) return false;
        var at = layout.at("dorm_3rd", 0, 0);
        if (at == null) return false;
        ServerLevel level = p.getServer().overworld();
        BlockPos base = BlockPos.containing(at);
        // One-time admission needs the real dorm floor even when it is outside the player's view distance.
        // API: existing sect/vault/VaultGameTests.java#getChunkAt.
        level.getChunkAt(base);
        for (int r = 0; r <= 12; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                for (int height = 0; height <= 8; height++) {
                    int dy = height % 2 == 0 ? height / 2 : -(height + 1) / 2;
                    BlockPos pos = base.offset(dx, dy, dz);
                    if (!level.isLoaded(pos) || !SectLife.standable(level, pos)
                            || !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                            || !level.getFluidState(pos.above()).isEmpty()
                            || level.getBlockState(pos.below()).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                            || level.getBlockState(pos.below()).is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
                            || level.getBlockState(pos.below()).is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)
                            || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)) continue;
                    p.setRespawnPosition(level.dimension(), pos, 0.0F, true, false);
                    p.getPersistentData().putBoolean("murim_admission_spawn", true);
                    return true;
                }
            }
        }
        return false;
    }

    /** The bed that should glow for this player now, or null: not a member, no real bed, already slept in it, too far. */
    public static BlockPos glowing(ServerPlayer p) {
        SectState state = SectService.state(p);
        if (!state.member() || state.has(SLEPT)
                || p.getPersistentData().contains(NIGHT_UNTIL)
                && p.level().getGameTime() >= p.getPersistentData().getLong(NIGHT_UNTIL)) {
            return null;
        }
        BlockPos head = SectRooms.bed(p);
        if (head == null || !p.level().isLoaded(head) || !(p.level().getBlockState(head).getBlock() instanceof BedBlock)) {
            return null;
        }
        return p.getEyePosition().distanceToSqr(head.getX() + 0.5D, head.getY() + 0.5D, head.getZ() + 0.5D) <= RANGE * RANGE ? head : null;
    }

    /** True when the player lies in his assigned bed (the head cell is the sleeping position). */
    public static boolean sleepingInOwnBed(ServerPlayer p) {
        BlockPos head = SectRooms.bed(p);
        return head != null && p.isSleeping() && p.getSleepingPos().filter(pos -> pos.equals(head)).isPresent();
    }

    /** Marks the first sleep in the own bed; returns true when it was marked now. */
    public static boolean markSlept(ServerPlayer p) {
        SectState state = SectService.state(p);
        if (!state.member() || state.has(SLEPT) || !sleepingInOwnBed(p)) {
            return false;
        }
        p.setData(ModAttachments.SECT, state.with(SLEPT));
        return true;
    }

    @SubscribeEvent
    static void playerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % PERIOD != 3 || p.isSpectator()) {
            return;
        }
        if (markSlept(p)) {
            return;
        }
        BlockPos head = glowing(p);
        if (head != null) {
            glow((ServerLevel) p.level(), p, head);
        }
    }

    /** Soft motes drifting up over the sheets; only {@code p} receives them. */
    static void glow(ServerLevel level, ServerPlayer p, BlockPos head) {
        // A bed is two cells: the motes spread over the head end and the cell next to it.
        double x = head.getX() + 0.5D;
        double z = head.getZ() + 0.5D;
        level.sendParticles(p, ParticleTypes.END_ROD, false, x, head.getY() + 0.75D, z, 2, 0.45D, 0.15D, 0.45D, 0.01D);
        level.sendParticles(p, ParticleTypes.GLOW, false, x, head.getY() + 0.9D, z, 1, 0.5D, 0.2D, 0.5D, 0.0D);
    }
}
