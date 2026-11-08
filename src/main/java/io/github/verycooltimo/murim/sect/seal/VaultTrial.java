package io.github.verycooltimo.murim.sect.seal;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.vault.VaultDoorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Any one Twenty-Four Plum Blossom form performed before the cold iron. The door's glow is the only trial feedback. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class VaultTrial {
    private static final String TAG = "murim_vault_trial";
    public static final double RADIUS = 8.0D;
    public static final int IDLE_TICKS = 1200;

    private VaultTrial() { }

    static BlockPos doorNear(net.minecraft.world.level.Level level, BlockPos at) {
        BlockPos best = null;
        double distance = RADIUS * RADIUS;
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-8, -4, -8), at.offset(8, 4, 8))) {
            if (!level.isLoaded(q)) continue;
            var s = level.getBlockState(q);
            if (!(s.getBlock() instanceof VaultDoorBlock) || s.getValue(VaultDoorBlock.STATE) != VaultDoorBlock.State.CLOSED) continue;
            double d = q.distSqr(at);
            if (d <= distance) {
                distance = d;
                best = q.immutable();
            }
        }
        return best;
    }

    public static void begin(ServerPlayer p, BlockPos at) {
        BlockPos door = doorNear(p.level(), at);
        if (door == null) return;
        CompoundTag tag = new CompoundTag();
        tag.putLong("door", door.asLong());
        tag.putLong("last", p.level().getGameTime());
        tag.putLong("seen", p.getData(ModAttachments.TECHNIQUE_STATE).lastStartGameTime());
        p.getPersistentData().put(TAG, tag);
        glow(p, door);
    }

    public static boolean active(ServerPlayer p) { return p.getPersistentData().contains(TAG); }
    public static int step(ServerPlayer p) { return active(p) ? p.getPersistentData().getCompound(TAG).getInt("step") : -1; }

    public static void onFoundation(ServerPlayer p, ResourceLocation foundation, int form) {
        accept(p, new VaultLadder.Step(foundation, form));
    }

    public static void onTechnique(ServerPlayer p, ResourceLocation technique) {
        accept(p, VaultLadder.Step.technique(technique));
    }

    private static boolean beforeDoor(ServerPlayer p, BlockPos door) {
        var s = p.level().getBlockState(door);
        if (!(s.getBlock() instanceof VaultDoorBlock) || s.getValue(VaultDoorBlock.STATE) != VaultDoorBlock.State.CLOSED
                || p.position().distanceTo(door.getCenter()) > RADIUS) return false;
        var facing = s.getValue(VaultDoorBlock.FACING);
        return (p.getX() - door.getX() - 0.5) * facing.getStepX()
                + (p.getZ() - door.getZ() - 0.5) * facing.getStepZ() > 0;
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p
                && p.level().dimension() == net.minecraft.world.level.Level.OVERWORLD
                && (active(p) || io.github.verycooltimo.murim.sect.vault.HuaVault.near(p, 8))) tick(p);
    }

    @SubscribeEvent
    static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && active(p)) {
            BlockPos door = BlockPos.of(p.getPersistentData().getCompound(TAG).getLong("door"));
            p.getPersistentData().remove(TAG);
            glow(p, door);
        }
    }

    static void tick(ServerPlayer p) {
        if (!active(p)) {
            var current = p.getData(ModAttachments.TECHNIQUE_STATE);
            if (p.level().getGameTime() % 20 != 0 && current.lastStartGameTime() < p.level().getGameTime() - 1) return;
            BlockPos near = doorNear(p.level(), p.blockPosition());
            if (near != null && beforeDoor(p, near)) {
                begin(p, near);
                var started = p.getData(ModAttachments.TECHNIQUE_STATE);
                if (started.techniqueId() != null && started.lastStartGameTime() >= p.level().getGameTime() - 1)
                    accept(p, VaultLadder.Step.technique(started.techniqueId()));
            }
            return;
        }
        var tag = p.getPersistentData().getCompound(TAG);
        BlockPos door = BlockPos.of(tag.getLong("door"));
        if (!p.isAlive() || !beforeDoor(p, door) || p.level().getGameTime() - tag.getLong("last") > IDLE_TICKS) {
            p.getPersistentData().remove(TAG);
            glow(p, door);
            return;
        }
        var technique = p.getData(ModAttachments.TECHNIQUE_STATE);
        if (tag.contains("finished")) {
            if (p.level().getGameTime() >= tag.getLong("finished") + 8) {
                p.getPersistentData().remove(TAG);
                p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with(SealAccess.VAULT_FLAG));
                VaultDoorBlock.openCompleted(p.level(), door);
            }
            return;
        }
        if (tag.contains("cast") && (!technique.isActive() || technique.lastStartGameTime() != tag.getLong("cast"))) {
            p.getPersistentData().remove(TAG);
            glow(p, door);
            return;
        }
        glow(p, door);
        if (technique.lastStartGameTime() != tag.getLong("seen")) {
            tag.putLong("seen", technique.lastStartGameTime());
            if (technique.techniqueId() != null) accept(p, VaultLadder.Step.technique(technique.techniqueId()));
        }
    }

    private static void accept(ServerPlayer p, VaultLadder.Step move) {
        if (!active(p)) begin(p, p.blockPosition());
        if (!active(p)) return;
        var tag = p.getPersistentData().getCompound(TAG);
        BlockPos door = BlockPos.of(tag.getLong("door"));
        if (!beforeDoor(p, door)) {
            p.getPersistentData().remove(TAG);
            glow(p, door);
            return;
        }
        if (!VaultLadder.relevant(VaultLadder.LADDER, move)) return;
        var cast = p.getData(ModAttachments.TECHNIQUE_STATE);
        if (!VaultLadder.LADDER.contains(move) || !move.technique().equals(cast.techniqueId())) return;
        if (tag.contains("cast") && tag.getLong("cast") == cast.lastStartGameTime()) return;
        var definition = io.github.verycooltimo.murim.technique.TechniqueLoader.get(move.technique());
        tag.putLong("cast", cast.lastStartGameTime());
        tag.putString("technique", move.technique().toString());
        tag.putInt("duration", definition != null && definition.totalTicks() > 0 ? definition.totalTicks() : 50);
        tag.remove("finished");
        tag.putLong("last", p.level().getGameTime());
        tag.putLong("seen", cast.lastStartGameTime());
        p.getPersistentData().put(TAG, tag);
    }

    /** Called by TechniqueService before clearing the cast: idle alone cannot distinguish cancellation. */
    public static void ended(ServerPlayer p, boolean completed) {
        if (!active(p)) return;
        var tag = p.getPersistentData().getCompound(TAG);
        var cast = p.getData(ModAttachments.TECHNIQUE_STATE);
        if (!tag.contains("cast") || tag.getLong("cast") != cast.lastStartGameTime()
                || !tag.getString("technique").equals(String.valueOf(cast.techniqueId()))) return;
        if (completed) {
            tag.putLong("finished", p.level().getGameTime());
            glow(p, BlockPos.of(tag.getLong("door")));
        }
        else {
            p.getPersistentData().remove(TAG);
            glow(p, BlockPos.of(tag.getLong("door")));
        }
    }

    /** Shared door shows the furthest performer; another player's reset cannot erase their progress. */
    private static void glow(ServerPlayer p, BlockPos door) {
        var cells = VaultDoorBlock.cells(p.level(), door);
        float count = progress(p);
        for (var other : p.level().players()) {
            if (other instanceof ServerPlayer sp && sp != p && active(sp)
                    && cells.contains(BlockPos.of(sp.getPersistentData().getCompound(TAG).getLong("door"))))
                count = Math.max(count, progress(sp));
        }
        for (BlockPos cell : cells) {
            if (p.level().getBlockEntity(cell) instanceof io.github.verycooltimo.murim.sect.vault.VaultDoorBlockEntity be)
                be.setProgress(count);
        }
    }

    private static float progress(ServerPlayer p) {
        if (!active(p)) return 0;
        var tag = p.getPersistentData().getCompound(TAG);
        if (tag.contains("finished")) return 1;
        if (!tag.contains("cast")) return 0;
        return Math.min(1.0F, p.getData(ModAttachments.TECHNIQUE_STATE).tick() / (float) Math.max(1, tag.getInt("duration")));
    }
}
