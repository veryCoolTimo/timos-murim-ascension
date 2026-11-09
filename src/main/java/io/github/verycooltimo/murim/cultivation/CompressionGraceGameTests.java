package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;

/** Persona row 6: the first compression failure is forgiven once; later ones keep the full backlash. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class CompressionGraceGameTests {
    private static ServerPlayer player(GameTestHelper h) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "grace"), false);
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie) {
            @Override public boolean hasChannel(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
                return false;
            }
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
        };
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5.0F);
        return p;
    }

    private static void sit(ServerPlayer p, int beats) {
        p.setData(ModAttachments.CULTIVATION, new CultivationState(Optional.empty(), beats));
        p.setData(ModAttachments.MEDITATION, MeditationState.started(true, beats));
    }

    @GameTest(template = "small_floor", batch = "compression_grace", timeoutTicks = 20)
    public static void firstCompressionFailureIsForgiven(GameTestHelper h) {
        var p = player(h);
        sit(p, 2);
        float hp = p.getHealth();
        MeditationService.backlash(p);
        h.assertTrue(p.getHealth() == hp, "health changed");
        h.assertTrue(p.getFoodData().getFoodLevel() == 20 && p.getFoodData().getSaturationLevel() == 5.0F, "food changed");
        h.assertFalse(p.hasEffect(MobEffects.WEAKNESS) || p.hasEffect(MobEffects.CONFUSION), "debuff applied");
        var med = p.getData(ModAttachments.MEDITATION);
        h.assertTrue(med.active() && med.ring() != null, "sitting did not continue with a fresh ring");
        var cult = p.getData(ModAttachments.CULTIVATION);
        h.assertTrue(cult.beats() == 2 && cult.compressionGraceUsed(), "beats/flag wrong");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "compression_grace", timeoutTicks = 20)
    public static void secondCompressionFailureKeepsFullBacklash(GameTestHelper h) {
        var p = player(h);
        sit(p, 2);
        MeditationService.backlash(p);
        MeditationService.backlash(p);
        h.assertTrue(p.getHealth() == 1.0F, "health not cut to half a heart: " + p.getHealth());
        h.assertTrue(p.getFoodData().getFoodLevel() == 17 && p.getFoodData().getSaturationLevel() == 0.0F, "food rule");
        h.assertTrue(p.hasEffect(MobEffects.WEAKNESS) && p.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)
                && p.hasEffect(MobEffects.DIG_SLOWDOWN) && p.hasEffect(MobEffects.CONFUSION), "debuffs missing");
        h.assertFalse(p.getData(ModAttachments.MEDITATION).active(), "sitting not ended");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "compression_grace", timeoutTicks = 20)
    public static void firstRingStillPracticeAndKeepsGraceUnspent(GameTestHelper h) {
        var p = player(h);
        sit(p, 1);
        float hp = p.getHealth();
        MeditationService.backlash(p);
        h.assertTrue(p.getHealth() == hp && !p.hasEffect(MobEffects.WEAKNESS), "practice cost something");
        h.assertTrue(p.getData(ModAttachments.MEDITATION).active(), "sitting ended");
        h.assertFalse(p.getData(ModAttachments.CULTIVATION).compressionGraceUsed(), "grace spent by first ring");
        h.succeed();
    }
}
