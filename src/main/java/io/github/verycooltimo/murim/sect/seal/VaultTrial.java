package io.github.verycooltimo.murim.sect.seal;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniqueState;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;

/**
 * The vault door trial (docs/design/23-mount-hua-sect.md §0 p.9): the player touches the engraved seal and performs
 * the ladder ({@link VaultLadder}) in front of it. A right rung rings the engraving one note higher; a wrong move
 * breaks the ladder and it starts over; walking away or a minute without a move ends the trial. The whole ladder —
 * flag {@link SealAccess#VAULT_FLAG} for good, and the cold iron doors by the seal open.
 *
 * <p>Moves are read without touching the combat code: a Six Harmonies form comes from the foundation swing hook
 * ({@code SectLife.onPlayerForm} → {@link #onFoundation}); a technique — from the start time in
 * {@code TECHNIQUE_STATE}, which changes on every start ({@link #onPlayerTick}). The trial lives in the player's
 * persistent data ({@code murim_vault_trial}); it is short and needs no own attachment.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class VaultTrial {

    private static final String TAG = "murim_vault_trial";
    /** The trial is performed within this many blocks of the seal. */
    public static final double RADIUS = 8.0D;
    /** A minute without a move ends the trial. */
    public static final int IDLE_TICKS = 1200;

    private VaultTrial() {
    }

    /** Touching the seal: begin (or show again) the trial. */
    public static void begin(ServerPlayer p, BlockPos seal) {
        if (p.getData(ModAttachments.SECT).has(SealAccess.VAULT_FLAG)) {
            int n = SealAccess.openDoors(p.level(), seal, SealAccess.RANGE, ColdIronRules.DOOR_OPEN_TICKS);
            p.displayClientMessage(Component.translatable("murim.vault.known").withStyle(ChatFormatting.GOLD), true);
            if (n == 0) {
                p.level().playSound(null, seal, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.6F, 1.2F);
            }
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putLong("seal", seal.asLong());
        tag.putInt("step", 0);
        tag.putLong("last", p.level().getGameTime());
        tag.putLong("seen", p.getData(ModAttachments.TECHNIQUE_STATE).lastStartGameTime());
        p.getPersistentData().put(TAG, tag);
        p.sendSystemMessage(Component.translatable("murim.vault.engraving", ladderText()).withStyle(ChatFormatting.GOLD));
        p.level().playSound(null, seal, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.7F, 0.7F);
        progress(p, 0);
    }

    public static boolean active(ServerPlayer p) {
        return p.getPersistentData().contains(TAG);
    }

    public static int step(ServerPlayer p) {
        return active(p) ? p.getPersistentData().getCompound(TAG).getInt("step") : -1;
    }

    /** A Six Harmonies (or another foundation) form swung: {@code foundation} — its technique id. */
    public static void onFoundation(ServerPlayer p, ResourceLocation foundation, int form) {
        if (active(p)) {
            feed(p, new VaultLadder.Step(foundation, form));
        }
    }

    /** A technique started (also the GameTest entry). */
    public static void onTechnique(ServerPlayer p, ResourceLocation technique) {
        if (active(p)) {
            feed(p, VaultLadder.Step.technique(technique));
        }
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !active(p)) {
            return;
        }
        CompoundTag tag = p.getPersistentData().getCompound(TAG);
        BlockPos seal = BlockPos.of(tag.getLong("seal"));
        long now = p.level().getGameTime();
        if (!p.isAlive() || !p.level().getBlockState(seal).is(SealRegistry.VAULT_SEAL.get())
                || p.position().distanceTo(seal.getCenter()) > RADIUS) {
            end(p, "murim.vault.left");
            return;
        }
        if (now - tag.getLong("last") > IDLE_TICKS) {
            end(p, "murim.vault.idle");
            return;
        }
        TechniqueState t = p.getData(ModAttachments.TECHNIQUE_STATE);
        if (t.lastStartGameTime() != tag.getLong("seen")) {
            tag.putLong("seen", t.lastStartGameTime());
            p.getPersistentData().put(TAG, tag);
            if (t.techniqueId() != null) {
                feed(p, VaultLadder.Step.technique(t.techniqueId()));
            }
        }
    }

    private static void feed(ServerPlayer p, VaultLadder.Step move) {
        List<VaultLadder.Step> ladder = VaultLadder.LADDER;
        if (!VaultLadder.relevant(ladder, move)) {
            return;
        }
        CompoundTag tag = p.getPersistentData().getCompound(TAG);
        int before = tag.getInt("step");
        int after = VaultLadder.advance(ladder, before, move);
        tag.putInt("step", after);
        tag.putLong("last", p.level().getGameTime());
        p.getPersistentData().put(TAG, tag);
        BlockPos seal = BlockPos.of(tag.getLong("seal"));
        if (after >= ladder.size()) {
            pass(p, seal);
            return;
        }
        if (after != before + 1) {
            // The ladder broke: the engraving goes dark, start over (a fresh first rung already counts).
            p.level().playSound(null, seal, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 0.8F, 0.5F);
            p.displayClientMessage(Component.translatable("murim.vault.broken", name(ladder.get(before))).withStyle(ChatFormatting.RED), false);
            progress(p, after);
            return;
        }
        // Each right rung — the next note up the scale.
        float pitch = (float) Math.pow(2.0D, (after - 6) / 12.0D);
        p.level().playSound(null, seal, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.8F, pitch);
        progress(p, after);
    }

    private static void pass(ServerPlayer p, BlockPos seal) {
        p.getPersistentData().remove(TAG);
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with(SealAccess.VAULT_FLAG));
        int doors = SealAccess.openDoors(p.level(), seal, SealAccess.RANGE, ColdIronRules.DOOR_OPEN_TICKS * 2);
        p.level().playSound(null, seal, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 1.0F, 0.8F);
        p.sendSystemMessage(Component.translatable("murim.vault.passed").withStyle(ChatFormatting.GOLD));
        MurimMod.LOGGER.info("Тайник: {} прошёл испытание двери у {} — дверей открыто {}", p.getName().getString(),
                seal.toShortString(), doors);
    }

    private static void end(ServerPlayer p, String key) {
        p.getPersistentData().remove(TAG);
        p.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), true);
    }

    /** «Door trial: 7/13 — next: Falling Petal» over the hotbar. */
    private static void progress(ServerPlayer p, int step) {
        List<VaultLadder.Step> ladder = VaultLadder.LADDER;
        p.displayClientMessage(Component.translatable("murim.vault.progress", step, ladder.size(), name(ladder.get(step)))
                .withStyle(ChatFormatting.YELLOW), true);
    }

    /** Name of a rung: «Six Harmonies, form 3» or the technique. */
    static Component name(VaultLadder.Step s) {
        if (s.foundation()) {
            return Component.translatable("murim.vault.form", MasteryService.name(s.technique()), s.form() + 1);
        }
        return MasteryService.name(s.technique());
    }

    /** The whole engraving for the chat: rung → rung → … */
    static Component ladderText() {
        MutableComponent out = Component.empty();
        List<VaultLadder.Step> ladder = VaultLadder.LADDER;
        out.append(Component.translatable("murim.vault.form_all", MasteryService.name(VaultLadder.SIX)));
        for (VaultLadder.Step s : ladder) {
            if (!s.foundation()) {
                out.append(" → ").append(MasteryService.name(s.technique()));
            }
        }
        return out;
    }
}
