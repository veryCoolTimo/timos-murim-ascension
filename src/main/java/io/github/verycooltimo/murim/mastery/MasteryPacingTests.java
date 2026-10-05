package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Освоение на настоящем пути {@link MasteryService#onHit} (docs/design/27-balance.md): зомби быстро
 * надоедают, бандит учит больше. API: reference/neoforge-src/net/neoforged/neoforge/gametest/GameTestHolder.java
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class MasteryPacingTests {

    /** Игрок без входа на сервер — как в SectGameTests: события входа шлют пакеты, которые тест не принимает. */
    private static ServerPlayer fakePlayer(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "pacing-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        return p;
    }

    /** 40 попаданий по зомби дают меньше, чем 40 по бандиту, и меньше, чем 40 «свежих» по зомби. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void weakMobsTireFast(GameTestHelper helper) {
        LivingEntity zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 2, 1);
        LivingEntity bandit = helper.spawnWithNoFreeWill(
                io.github.verycooltimo.murim.registry.ModEntities.BANDIT_SWORDSMAN.get(), 3, 2, 3);
        // Сумма цен — то, что пошло бы в освоение; сам прирост и синхронизацию проверяет MasteryRulesTest
        // (пакет освоения тестовому соединению не отправить).
        ServerPlayer onZombie = fakePlayer(helper);
        ServerPlayer onBandit = fakePlayer(helper);
        double z = 0.0D;
        double b = 0.0D;
        for (int i = 0; i < 40; i++) {
            z += MasteryService.hitWorth(onZombie, zombie);
            b += MasteryService.hitWorth(onBandit, bandit);
        }
        helper.assertTrue(z < b * 0.5D, "зомби " + z + " против бандита " + b + ": слабый моб должен учить меньше");
        // Свежие 40 попаданий по зомби без усталости — 40 × цена слабого моба.
        double fresh = 40 * MasteryPacing.worth(MasteryPacing.Kind.WEAK, 0, 0, 0);
        helper.assertTrue(z < fresh * 0.8D, "усталость от зомби не сработала: " + z + " из " + fresh);
        helper.succeed();
    }
}
