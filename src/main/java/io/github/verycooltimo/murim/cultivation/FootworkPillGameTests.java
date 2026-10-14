package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.FootworkService;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.FootworkFamily;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Accesses the real transient pill discount in its owning package. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class FootworkPillGameTests {
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void runDiscountIsAppliedOnceAndExpires(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "footpill-test"), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                cookie.gameProfile(), cookie.clientInformation());
        double baseline = FootworkService.runQiPerTick(player, FootworkFamily.WIND_GOD, 8);
        PillService.Slot slot = player.getData(ModAttachments.ABSORB);
        slot.discountUntil = player.level().getGameTime() + 20;
        helper.assertTrue(Math.abs(FootworkService.runQiPerTick(player, FootworkFamily.WIND_GOD, 8)
                - baseline * PillService.DISCOUNT) < 1.0E-9D, "pill discount omitted or applied twice");
        helper.assertTrue(PillService.costDiscountUntil(player) == slot.discountUntil, "HUD expiry differs from server");
        slot.discountUntil = player.level().getGameTime();
        helper.assertTrue(FootworkService.runQiPerTick(player, FootworkFamily.WIND_GOD, 8) == baseline, "expired discount stayed active");
        helper.succeed();
    }

    private FootworkPillGameTests() {
    }
}
