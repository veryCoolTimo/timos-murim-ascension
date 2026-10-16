package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.combat.AuraService;
import io.github.verycooltimo.murim.combat.AuraState;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.SectLife;
import io.github.verycooltimo.murim.sect.seal.PenanceEnforcement;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** The elder approaches visibly. A hidden arrival must be hidden from every nearby player at both ends. */
public final class SectSuppressionGoal extends Goal {
    private final SectDisciple npc;
    private ServerPlayer target;
    private static final String AURA_BACKUP = "murim_suppression_aura";
    private long nextLeap;

    public SectSuppressionGoal(SectDisciple npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public boolean active() { return target != null; }
    public boolean forPlayer(ServerPlayer p) { return target == p; }

    public boolean begin(ServerPlayer p) {
        if (target == p) return true;
        if (active() || !npc.free() || npc.wounded() || npc.escorting() || p.level() != npc.level()) return false;
        npc.clearBlock();
        npc.wake();
        npc.sit(false);
        npc.workPose(null);
        target = p;
        nextLeap = npc.level().getGameTime() + 20;
        return true;
    }

    public void fullAura() {
        if (!npc.getPersistentData().contains(AURA_BACKUP)) {
            AuraState before = npc.getData(ModAttachments.AURA);
            var backup = new net.minecraft.nbt.CompoundTag();
            backup.putInt("rank", before.rank());
            backup.putBoolean("demonic", before.demonic());
            npc.getPersistentData().put(AURA_BACKUP, backup);
            AuraService.set(npc, new AuraState(AuraState.MAX_RANK, false));
        }
    }

    public void cancel() {
        target = null;
        npc.setSprinting(false);
        npc.walking().stop();
        restoreAura();
    }

    /** Also runs after loading an NPC whose target disconnected while the world was saved. */
    public void restoreAura() {
        if (!active() && npc.getPersistentData().contains(AURA_BACKUP)) {
            var backup = npc.getPersistentData().getCompound(AURA_BACKUP);
            AuraService.set(npc, new AuraState(backup.getInt("rank"), backup.getBoolean("demonic")));
            npc.getPersistentData().remove(AURA_BACKUP);
        }
    }

    @Override public boolean canUse() {
        if (target == null) return false;
        if (!npc.isAlive() || !target.isAlive() || target.isRemoved() || target.level() != npc.level()
                || !PenanceEnforcement.active(target)) { cancel(); return false; }
        return true;
    }
    @Override public boolean canContinueToUse() { return canUse(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void stop() { npc.walking().stop(); }

    @Override public void tick() {
        npc.getLookControl().setLookAt(target, 30, 30);
        npc.faceEntity(target, 25);
        if (PenanceEnforcement.pinned(target)) {
            fullAura();
            npc.walking().stop();
            return;
        }
        if (npc.distanceToSqr(target) <= 16 && npc.getSensing().hasLineOfSight(target)) {
            npc.walking().stop();
            PenanceEnforcement.pin(target, npc);
            return;
        }
        long now = npc.level().getGameTime();
        if (now >= nextLeap) {
            nextLeap = now + 20;
            Vec3 near = SectLife.stand(npc.level(), target.position().add(4, 0, 0));
            if (npc.distanceToSqr(target) > 16 * 16 && hidden(npc.position()) && hidden(near)
                    && SectLife.standable(npc.level(), net.minecraft.core.BlockPos.containing(near))
                    && npc.level().noCollision(npc, npc.getBoundingBox().move(near.subtract(npc.position())))) {
                npc.teleportTo(near.x, near.y, near.z);
            } else if (npc.onGround()) {
                Vec3 to = target.position().subtract(npc.position()).multiply(1, 0, 1).normalize().scale(0.55);
                npc.setDeltaMovement(to.x, 0.42, to.z);
                npc.hasImpulse = true;
            }
        }
        npc.setSprinting(true);
        npc.walking().walk(target.position(), 1.45D);
    }

    private boolean hidden(Vec3 feet) {
        Vec3 eyes = feet.add(0, npc.getEyeHeight(), 0);
        for (var player : npc.level().players()) {
            if (player.distanceToSqr(feet) > 128 * 128) continue;
            if (npc.level().clip(new ClipContext(player.getEyePosition(), eyes, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS) return false;
        }
        return true;
    }
}
