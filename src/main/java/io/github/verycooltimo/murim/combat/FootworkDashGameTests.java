package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.TraversePayloads;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class FootworkDashGameTests {
    private FootworkDashGameTests() { }

    // Same recording listener as PlaytestRegressionGameTests; record even forbidden sends to catch bypassed negotiation.
    private static ServerPlayer player(GameTestHelper h, boolean acceptsDash, List<TraversePayloads.Dash> packets) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "dash-test"), false);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie) {
            @Override public boolean hasChannel(CustomPacketPayload payload) {
                return acceptsDash && payload instanceof TraversePayloads.Dash;
            }
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
                if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                        && custom.payload() instanceof TraversePayloads.Dash dash) packets.add(dash);
            }
        };
        return p;
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void dashSkipsPlayersWithoutTheChannel(GameTestHelper h) {
        List<TraversePayloads.Dash> packets = new ArrayList<>();
        FootworkService.sendDash(player(h, false, packets), new Vec3(1, 0, 0), 4, 5);
        h.assertTrue(packets.isEmpty(), "dash sent without a negotiated channel");
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void dashReachesNegotiatedClientsWithItsMotionIntact(GameTestHelper h) {
        List<TraversePayloads.Dash> packets = new ArrayList<>();
        ServerPlayer p = player(h, true, packets);
        FootworkService.sendDash(p, new Vec3(0, 0.6D, 0.8D), 4, 5);
        h.assertTrue(packets.equals(List.of(new TraversePayloads.Dash(0, 0.6F, 0.8F, 4, 5))),
                "dash direction, distance or duration changed: " + packets);
        FootworkService.sendDash(p, new Vec3(1, 0, 0), 0.01D, 5);
        h.assertTrue(packets.size() == 1, "negligible dash was sent");
        h.succeed();
    }
}
