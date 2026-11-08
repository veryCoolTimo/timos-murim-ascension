package io.github.verycooltimo.murim.sect.vault;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import io.github.verycooltimo.murim.sect.SectChatter;
import io.github.verycooltimo.murim.sect.seal.PenanceService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;


/**
 * The two hidden mechanisms of the main vault (design doc 42, author 21.10: «a hidden block or mechanism in the Ancestors' Hall;
 * the order of the lock simple and explained by an inscription; found by chance»):
 *
 * <ol>
 *   <li><b>The founders' rite</b> in the Ancestors' Hall: four of the twenty tablets answer a touch ({@link LockTabletBlock#ORDER}).
 *       Touched in order — the nearest tablet on the right of the shrine, the nearest on the left, the next on the right, the next
 *       on the left (seen from the door) — the floor tiles beside the shrine slide away and show the way down. (The memorial stone
 *       beside the shrine, {@code ancestors_verse}, which used to say so, was a clue block and is gone, author 26.10.) A wrong tablet only goes dull, the order starts again. If a member of
 *       the sect sees it done, the sect changes the lock: the tablets stay silent for a day, and the sight counts as a trespass
 *       (the penance cave rules, nothing new).</li>
 *   <li>(The study has no mechanism: dig the middle shelf and the hole to the pit is under it.)</li>
 * </ol>
 */
public final class VaultMechanism {

    private static final String TAG = "murim_vault_rite";
    /** Seconds between two touches of the rite before it starts again (ticks). */
    public static final int RITE_GAP = 400;
    /** The sect changes the lock for this long after somebody was caught (one game day). */
    public static final long LOCK_TICKS = 24000L;
    /** A member of the sect sees the rite from this far with a line of sight. */
    public static final double WITNESS_RANGE = 16.0D;
    private VaultMechanism() {
    }

    // ------------------------------------------------------------------ the founders' rite

    /** A hand on one of the four tablets: {@code order} is its place in the rite. */
    public static void tablet(ServerLevel level, ServerPlayer p, BlockPos pos, int order) {
        long now = level.getGameTime();
        VaultData data = VaultData.get(level);
        if (data.locked(now)) {
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BASEDRUM.value(), SoundSource.BLOCKS, 0.6F, 0.5F);
            PlayerJournal.record(p, Component.translatable("murim.vault.rite.silent").withStyle(ChatFormatting.GRAY));
            return;
        }
        CompoundTag tag = p.getPersistentData().getCompound(TAG);
        int step = tag.getInt("step");
        if (now - tag.getLong("last") > RITE_GAP) {
            step = 0;
        }
        if (order == step + 1) {
            step++;
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.9F, (float) Math.pow(2.0D, (step * 3 - 6) / 12.0D));
            level.sendParticles(ParticleTypes.ENCHANT, pos.getX() + 0.5D, pos.getY() + 0.8D, pos.getZ() + 0.5D, 8, 0.2D, 0.2D, 0.2D, 0.4D);
            PlayerJournal.record(p, Component.translatable("murim.vault.rite.step", step).withStyle(ChatFormatting.GOLD));
        } else {
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 0.7F, 0.6F);
            PlayerJournal.record(p, Component.translatable("murim.vault.rite.dull").withStyle(ChatFormatting.GRAY));
            step = order == 1 ? 1 : 0;
        }
        tag.putInt("step", step);
        tag.putLong("last", now);
        p.getPersistentData().put(TAG, tag);
        if (step >= 4) {
            p.getPersistentData().remove(TAG);
            completed(level, p, pos);
        }
    }

    /** The rite is done: the floor slides away, unless somebody of the sect sees it. */
    static void completed(ServerLevel level, ServerPlayer p, BlockPos near) {
        SectDisciple witness = witness(level, p);
        if (witness != null) {
            VaultData.get(level).lock(level.getGameTime() + LOCK_TICKS);
            level.playSound(null, near, SoundEvents.NOTE_BLOCK_BASEDRUM.value(), SoundSource.BLOCKS, 1.0F, 0.5F);
            SectChatter.send(witness, "murim.vault.witness.shout", Component.translatable("murim.vault.witness.shout"));
            io.github.verycooltimo.murim.sect.SectNotice.chat(p, Component.translatable("murim.vault.witness.caught", witness.getName()).withStyle(ChatFormatting.RED));
            PenanceService.offence(p, "trespass", witness);
            MurimMod.LOGGER.info("Хранилище: {} пойман у табличек ({})", p.getName().getString(), witness.getName().getString());
            return;
        }
        openHatch(level, near);
    }

    /** A sect member awake and looking at the player, near. */
    public static SectDisciple witness(ServerLevel level, ServerPlayer p) {
        SectDisciple best = null;
        double bd = Double.MAX_VALUE;
        for (SectDisciple d : level.getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(WITNESS_RANGE),
                d -> d.isAlive() && !d.dormant() && !d.isSleeping() && d.member().isPresent())) {
            double dist = d.distanceToSqr(p);
            if (dist < bd && d.getSensing().hasLineOfSight(p)) {
                bd = dist;
                best = d;
            }
        }
        return best;
    }

    /** Slides the hatch cells near {@code near} away; they come back by themselves. @return the cells opened. */
    public static int openHatch(ServerLevel level, BlockPos near) {
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(near.offset(-10, -4, -10), near.offset(10, 2, 10))) {
            BlockState s = level.getBlockState(q);
            if (s.getBlock() instanceof VaultFloorBlock && s.getValue(VaultFloorBlock.HATCH) && !s.getValue(VaultFloorBlock.OPEN)) {
                VaultFloorBlock.open(level, q.immutable());
                level.sendParticles(ParticleTypes.POOF, q.getX() + 0.5D, q.getY() + 1.0D, q.getZ() + 0.5D, 3, 0.3D, 0.1D, 0.3D, 0.02D);
                n++;
            }
        }
        if (n > 0) {
            level.playSound(null, near, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 1.2F, 0.5F);
            level.playSound(null, near, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.0F, 0.5F);
        }
        return n;
    }
}
