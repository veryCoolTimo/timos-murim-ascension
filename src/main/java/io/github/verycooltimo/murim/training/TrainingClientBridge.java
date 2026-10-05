package io.github.verycooltimo.murim.training;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Bridge from common packet registration to the client handler (same reason as {@code network/ClientPayloadBridge}):
 * the client class is named only inside method bodies, behind the side check, so a dedicated server never loads it.
 */
final class TrainingClientBridge {

    private TrainingClientBridge() {
    }

    static void state(TrainingPayloads.State payload) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            io.github.verycooltimo.murim.client.training.ClientTraining.onState(payload);
        }
    }

    static void body(TrainingPayloads.Body payload) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            io.github.verycooltimo.murim.client.training.ClientTraining.onBody(payload);
        }
    }
}
