package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.ArtSlots;
import io.github.verycooltimo.murim.mastery.Arts;
import io.github.verycooltimo.murim.mastery.LoadoutService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Pass of 15.11 over cultivation feedback: loadout breathing, refusals without a tablet, stand-only packet. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class CultivationFeedbackGameTests {
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("murim", path);
    }

    /** A real server player whose outgoing packets are collected in {@code sink}. */
    public static ServerPlayer player(GameTestHelper h, List<Packet<?>> sink) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "feedback"), false);
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie) {
            @Override public boolean hasChannel(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
                return false;
            }
            @Override public void send(Packet<?> packet) { sink.add(packet); }
        };
        return p;
    }

    /** Action-bar lines with this key that went out to the player (read from the connection's channel, where send(packet, listener) lands). */
    public static long actionBars(ServerPlayer p, String key) {
        var channel = (io.netty.channel.embedded.EmbeddedChannel) p.connection.getConnection().channel();
        long n = 0;
        for (Object o : channel.outboundMessages()) {
            if (o instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket a && a.overlay()
                    && a.content().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                    && t.getKey().equals(key)) n++;
        }
        return n;
    }

    public static long chatLines(ServerPlayer p) {
        var channel = (io.netty.channel.embedded.EmbeddedChannel) p.connection.getConnection().channel();
        long n = 0;
        for (Object o : channel.outboundMessages()) {
            if (o instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket a && !a.overlay()) n++;
        }
        return n;
    }

    private static void twoMethods(ServerPlayer p, int beats, boolean grace) {
        p.setData(ModAttachments.CULTIVATION, new CultivationState(Optional.of(id("six_harmonies")), beats, grace));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY
                .with(Arts.methodArt(id("six_harmonies")), TechniqueProgress.learned(6, 12))
                .with(Arts.methodArt(id("mountain_root")), TechniqueProgress.learned(1, 12)));
    }

    @GameTest(template = "small_floor", batch = "cultivation_feedback", timeoutTicks = 20)
    public static void switchingBreathingKeepsSpentCompressionGrace(GameTestHelper h) {
        var p = player(h, new ArrayList<>());
        twoMethods(p, 2, true);
        LoadoutService.setSlot(p, ArtSlots.BREATHING, Optional.of(Arts.methodArt(id("mountain_root"))));
        var c = p.getData(ModAttachments.CULTIVATION);
        h.assertTrue(c.method().equals(Optional.of(id("mountain_root"))), "method not switched: " + c.method());
        h.assertTrue(c.compressionGraceUsed(), "switching methods handed the compression grace out again");
        h.assertTrue(c.beats() == 2, "beats changed");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "cultivation_feedback", timeoutTicks = 20)
    public static void clearingBreathingSlotDoesNotBreakMeditation(GameTestHelper h) {
        var p = player(h, new ArrayList<>());
        twoMethods(p, CultivationState.SEEDED, true);
        LoadoutService.setSlot(p, ArtSlots.BREATHING, Optional.empty());
        var c = p.getData(ModAttachments.CULTIVATION);
        h.assertTrue(c.method().isPresent(), "right-click on the breathing slot removed the method");
        h.assertTrue(c.compressionGraceUsed() && c.seeded(), "state lost");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "cultivation_feedback", timeoutTicks = 20)
    public static void refusalWithoutTabletShowsOneActionBarLine(GameTestHelper h) {
        var sink = new ArrayList<Packet<?>>();
        var p = player(h, sink);
        p.setData(ModAttachments.CULTIVATION, CultivationState.NONE);
        p.setOnGround(true);
        h.assertFalse(MeditationService.start(p, true), "sat without a method");
        h.assertTrue(actionBars(p, "murim.meditation.no_method") == 1, "no action-bar line for the refusal");
        h.assertTrue(chatLines(p) == 0, "refusal went to chat");
        h.assertTrue(!PlayerJournal.entries(p).isEmpty(), "journal lost the refusal");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "cultivation_feedback", timeoutTicks = 20)
    public static void refusalWithTabletStaysInJournalOnly(GameTestHelper h) {
        var sink = new ArrayList<Packet<?>>();
        var p = player(h, sink);
        p.getInventory().add(new ItemStack(ModItems.JADE_SECT_TABLET.get()));
        p.setData(ModAttachments.CULTIVATION, CultivationState.NONE);
        p.setOnGround(true);
        MeditationService.start(p, true);
        h.assertTrue(actionBars(p, "murim.meditation.no_method") == 0, "member with a tablet got an action-bar line");
        h.assertTrue(!PlayerJournal.entries(p).isEmpty(), "journal lost the refusal");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "cultivation_feedback", timeoutTicks = 20)
    public static void standPacketNeverSitsAndStandsWhenSitting(GameTestHelper h) {
        var p = player(h, new ArrayList<>());
        twoMethods(p, CultivationState.SEEDED, false);
        p.setOnGround(true);
        MeditationService.stand(p);
        h.assertFalse(p.getData(ModAttachments.MEDITATION).active(), "a late stand packet sat the player down");
        h.assertTrue(MeditationService.start(p, true), "setup: could not sit");
        MeditationService.stand(p);
        h.assertFalse(p.getData(ModAttachments.MEDITATION).active(), "stand did not stand up");
        h.succeed();
    }
}
