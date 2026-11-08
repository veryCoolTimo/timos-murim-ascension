package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.FootworkFamily;
import io.github.verycooltimo.murim.technique.Styles;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Footwork half of QiFreeGameTests: creative runs on an empty pool, survival still pays (author 18.10). */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class QiFreeFootworkGameTests {
    private static ServerPlayer fakePlayer(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "qifree-test"), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, player, cookie);
        return player;
    }

    private static ServerPlayer runner(GameTestHelper h, GameType mode, double qi) {
        ServerPlayer p = fakePlayer(h);
        p.setGameMode(mode);
        MasteryState family = MasteryState.EMPTY;
        for (ResourceLocation form : Styles.WIND_GOD.forms()) {
            family = family.with(form, TechniqueProgress.learned(8, 12));
        }
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("clear", "plum").withCirculating(qi));
        p.setData(ModAttachments.MASTERY, family);
        Vec3 at = h.absoluteVec(new Vec3(1.5D, 2.0D, 1.5D));
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        p.setOnGround(true);
        p.setSprinting(true);
        p.setData(ModAttachments.LOADOUT, io.github.verycooltimo.murim.mastery.Loadout.EMPTY.with(0,
                java.util.Optional.of(Styles.footworkRun(Styles.WIND_GOD).orElseThrow())));
        return p;
    }

    @GameTest(template = "small_floor", timeoutTicks = 40)
    public static void creativeRunsFootworkOnEmptyQiAndSurvivalDoesNot(GameTestHelper h) {
        ResourceLocation run = Styles.footworkRun(Styles.WIND_GOD).orElseThrow();
        ServerPlayer creative = runner(h, GameType.CREATIVE, 0.0D);
        FootworkService.startRun(creative, run, FootworkFamily.WIND_GOD, 8);
        h.assertTrue(FootworkService.isRunning(creative), "creative run refused for lack of qi");
        for (int tick = 0; tick < 20; tick++) {
            FootworkService.tick(creative);
        }
        h.assertTrue(FootworkService.isRunning(creative), "creative run stopped when the qi was empty");
        h.assertTrue(creative.getData(ModAttachments.PROFILE).circulating() == 0.0D, "creative run changed qi");
        FootworkService.stop(creative);
        creative.discard();

        ServerPlayer survival = runner(h, GameType.SURVIVAL, 0.0D);
        FootworkService.startRun(survival, run, FootworkFamily.WIND_GOD, 8);
        h.assertFalse(FootworkService.isRunning(survival), "survival run started on empty qi");
        survival.discard();

        ServerPlayer paying = runner(h, GameType.SURVIVAL, 5.0D);
        FootworkService.startRun(paying, run, FootworkFamily.WIND_GOD, 8);
        h.assertTrue(FootworkService.isRunning(paying), "survival run with qi did not start");
        for (int tick = 0; tick < 20; tick++) {
            FootworkService.tick(paying);
        }
        h.assertTrue(paying.getData(ModAttachments.PROFILE).circulating() < 5.0D, "survival run did not pay qi");
        FootworkService.stop(paying);
        paying.discard();
        h.succeed();
    }

    /** A headless player has a connection but no negotiated mod channels. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void dashSkipsPlayerWithoutChannel(GameTestHelper h) {
        ServerPlayer player = fakePlayer(h);
        h.assertFalse(player.connection.hasChannel(io.github.verycooltimo.murim.network.TraversePayloads.Dash.TYPE),
                "setup: fake player unexpectedly supports dash packets");
        // Must return without trying to encode an unsupported client payload.
        FootworkService.sendDash(player, new Vec3(1, 0, 0), 4.0D, 5);
        player.discard();
        h.succeed();
    }

    private QiFreeFootworkGameTests() {
    }
}
