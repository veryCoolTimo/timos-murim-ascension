package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.Arts;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.SyncMeditationPayload;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercises real command routing and the gates used by meditation, pills and synchronized HUD state. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class AwakenGameTests {
    @GameTest(template = "small_floor", batch = "commands", timeoutTicks = 20)
    public static void awakenAndResetCreateNormalSeed(GameTestHelper h) throws Exception {
        var sent = new java.util.ArrayList<CustomPacketPayload>();
        var p = player(h, sent);
        var source = p.createCommandSourceStack().withPermission(2);
        var commands = h.getLevel().getServer().getCommands().getDispatcher();
        for (String command : new String[] {"murim dev rank awaken", "murim awaken"}) {
            h.assertTrue(commands.execute("murim dev rank reset", source) == 1, "reset failed");
            h.assertTrue(!p.getData(ModAttachments.PROFILE).isAwakened(), "reset kept profile awake");
            h.assertTrue(MeditationService.snapshot(p, SyncMeditationPayload.Event.NONE).beats() == 0,
                    "reset kept client seed flag");
            sent.clear();
            h.assertTrue(commands.execute(command, source) == 1, "awaken failed");
            var cultivation = p.getData(ModAttachments.CULTIVATION);
            var method = cultivation.method().map(MethodLoader::get).orElseThrow();
            var profile = p.getData(ModAttachments.PROFILE);
            h.assertTrue(cultivation.seeded() && profile.isAwakened(), "dantian existence gates disagree");
            h.assertTrue(profile.equals(SeedLogic.seedProfile(DantianProfile.INITIAL, method, 1)),
                    "profile differs from a newly founded dantian (including foundation and qi)");
            h.assertTrue(MasteryService.knows(p, Arts.methodArt(method.id())), "starting method not learned");
            var payload = MeditationService.snapshot(p, SyncMeditationPayload.Event.SEED);
            h.assertTrue(payload.beats() == CultivationState.SEEDED && !payload.active()
                    && !payload.ring().active() && payload.event() == SyncMeditationPayload.Event.SEED,
                    "client HUD snapshot still describes founding practice");
            h.assertTrue(sent.stream().anyMatch(packet -> packet instanceof SyncMeditationPayload sync
                    && sync.equals(payload)), "seed flags were not sent to the HUD");
            h.assertTrue(sent.stream().anyMatch(packet -> packet instanceof
                    io.github.verycooltimo.murim.network.SyncProfilePayload sync && sync.profile().equals(profile)),
                    "awakened profile was not sent to qi and pill HUDs");
            h.assertTrue(sent.stream().anyMatch(packet -> packet instanceof
                    io.github.verycooltimo.murim.network.LoadoutPayloads.Sync sync
                    && sync.breathing().equals(java.util.Optional.of(Arts.methodArt(method.id())))),
                    "starting method was not sent to loadout screen");
            h.assertTrue(PillService.check(p, PillKind.VITALITY, profile, PillState.NONE) == PillRules.Refusal.NONE,
                    "pills refuse newly awakened dantian");
            p.setOnGround(true);
            h.assertTrue(MeditationService.start(p, false), "meditation refused starting method");
            h.assertTrue(PillService.meditating(p), "pill absorption does not recognize seeded meditation");
            MeditationService.stop(p, null);
        }
        h.succeed();
    }

    // API: same recording listener as combat/FootworkDashGameTests.java.
    private static ServerPlayer player(GameTestHelper h, java.util.List<CustomPacketPayload> sent) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "awaken-test"), false);
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie) {
            @Override public boolean hasChannel(CustomPacketPayload payload) { return true; }
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
                if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom) {
                    sent.add(custom.payload());
                }
            }
        };
        return p;
    }

    private AwakenGameTests() {}
}
