package io.github.verycooltimo.murim.training;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registration and sending of training packets. Called from {@code ModNetwork#register} (one line there, like the
 * library). Client handlers go through {@link TrainingClientBridge}: no client type is named here (rule 03).
 */
public final class TrainingNetwork {

    private TrainingNetwork() {
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(TrainingPayloads.State.TYPE, TrainingPayloads.State.STREAM_CODEC,
                (payload, context) -> TrainingClientBridge.state(payload));
        registrar.playToClient(TrainingPayloads.Body.TYPE, TrainingPayloads.Body.STREAM_CODEC,
                (payload, context) -> TrainingClientBridge.body(payload));
    }

    /** To the player and everyone tracking them. */
    static void state(ServerPlayer player, TrainingPayloads.State state) {
        // GameTest players sit on an embedded connection without our channels (agent-log 04.10): skip them.
        // API: reference/neoforge-src/net/neoforged/neoforge/common/extensions/ICommonPacketListener.java#hasChannel
        if (player.connection == null || !player.connection.hasChannel(TrainingPayloads.State.TYPE)) {
            return;
        }
        // API: reference/neoforge-src/net/neoforged/neoforge/network/PacketDistributor.java#sendToPlayersTrackingEntityAndSelf
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, state);
    }

    static void body(ServerPlayer player) {
        if (player.connection == null || !player.connection.hasChannel(TrainingPayloads.Body.TYPE)) {
            return;
        }
        BodyState s = player.getData(TrainingRegistry.BODY);
        PacketDistributor.sendToPlayer(player, new TrainingPayloads.Body(s.level(), (float) BodyRules.progress(s.points()),
                (float) s.fatigue(), (float) BodyRules.dailyFactor(s.today()), s.bestClimb(), s.bestTrail()));
    }
}
