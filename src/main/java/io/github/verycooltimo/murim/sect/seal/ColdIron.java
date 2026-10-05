package io.github.verycooltimo.murim.sect.seal;

import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;

/**
 * What all cold iron blocks share: how fast a player breaks one ({@link ColdIronRules}), the qi it drains, the clang
 * and the refusal. Called from {@code getDestroyProgress} of the blocks, which the server asks every tick of digging
 * (ServerPlayerGameMode#tick → incrementDestroyProgress) and the client asks for its own crack animation.
 *
 * <p>The strike is tracked in the player's persistent data ({@code murim_cold_iron}: block, last tick, whether the
 * strike began with full qi) on both sides, so the client predicts the same «no» as the server. Qi is drained only on
 * the server. API: reference/minecraft-src/net/minecraft/server/level/ServerPlayerGameMode.java#tick/
 * incrementDestroyProgress/handleBlockBreakAction (STOP: progress × ticks ≥ 0.7).
 */
public final class ColdIron {

    private static final String TAG = "murim_cold_iron";
    /** Refusal message not more often than this (ticks). */
    private static final int SAY_EVERY = 30;

    private ColdIron() {
    }

    /** Did the current strike of {@code player} begin with full qi (client sparks read it: client/seal/ColdIronSparks). */
    public static boolean biting(Player player) {
        CompoundTag tag = player.getPersistentData().getCompound(TAG);
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        return tag.getBoolean("ok") && ColdIronRules.progress(profile.rank(), profile.circulating(), profile.maxCirculating(), false) > 0.0F;
    }

    /** Progress per tick for {@code player} digging the cold iron at {@code pos}. */
    public static float progress(Player player, BlockGetter level, BlockPos pos) {
        if (player.isCreative()) {
            return 1.0F;
        }
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        long now = player.level().getGameTime();
        CompoundTag tag = player.getPersistentData().getCompound(TAG);
        boolean newStrike = tag.getLong("pos") != pos.asLong() || now - tag.getLong("last") > ColdIronRules.STRIKE_GAP
                || !tag.contains("last");
        if (newStrike) {
            tag.putLong("pos", pos.asLong());
            tag.putBoolean("ok", ColdIronRules.progress(profile.rank(), profile.circulating(), profile.maxCirculating(), true) > 0.0F);
        }
        tag.putLong("last", now);
        player.getPersistentData().put(TAG, tag);
        float progress = tag.getBoolean("ok")
                ? ColdIronRules.progress(profile.rank(), profile.circulating(), profile.maxCirculating(), false) : 0.0F;
        if (player instanceof ServerPlayer sp && sp.level() instanceof ServerLevel sl) {
            if (progress > 0.0F) {
                drain(sp, sl, pos, profile);
            } else if (newStrike || now - tag.getLong("said") >= SAY_EVERY) {
                refuse(sp, sl, pos, profile);
                tag.putLong("said", now);
                player.getPersistentData().put(TAG, tag);
            }
        }
        return progress;
    }

    /** One tick of a qi strike: qi out of the reserve, a ring of the iron now and then (sparks — client/seal/ColdIronSparks). */
    private static void drain(ServerPlayer p, ServerLevel level, BlockPos pos, DantianProfile profile) {
        p.setData(ModAttachments.PROFILE, profile.withCirculating(profile.circulating() - ColdIronRules.costPerTick(profile.maxCirculating())));
        long now = level.getGameTime();
        if (now % 8 == 0) {
            level.playSound(null, pos, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, 0.35F, 1.6F + level.random.nextFloat() * 0.3F);
        }
        // API: reference/neoforge-src/net/neoforged/neoforge/common/extensions/ICommonPacketListener.java#hasChannel
        // (a GameTest player has no negotiated channels).
        if (now % 10 == 0 && p.connection.hasChannel(io.github.verycooltimo.murim.network.SyncProfilePayload.TYPE)) {
            ProfileNetwork.sync(p);
        }
    }

    /** The iron does not yield: a dull clang and one line — why (rank or qi). */
    private static void refuse(ServerPlayer p, ServerLevel level, BlockPos pos, DantianProfile profile) {
        level.playSound(null, pos, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.4F, 0.6F);
        String key = ColdIronRules.rankAllows(profile.rank()) ? "murim.cold_iron.no_qi" : "murim.cold_iron.refuse";
        p.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), true);
    }
}
