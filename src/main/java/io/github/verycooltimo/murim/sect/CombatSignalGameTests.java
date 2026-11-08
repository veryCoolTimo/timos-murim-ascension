package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.CombatSignal;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** A sect bout or duel is a fight the combat HUD must know about: the server pulse is on for it and off after. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class CombatSignalGameTests {
    private CombatSignalGameTests() {
    }

    @GameTest(template = "small_floor", batch = "combat_hud_sect", timeoutTicks = 20)
    public static void boutAndDuelKeepTheCombatPulseOn(GameTestHelper h) {
        ServerPlayer a = SectGameTests.fakePlayer(h);
        ServerPlayer b = SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(2.5D, 2.0D, 2.5D));
        a.moveTo(at.x, at.y, at.z, 0, 0);
        b.moveTo(at.x + 2.0D, at.y, at.z, 0, 0);
        h.assertTrue(!CombatSignal.engaged(a) && !CombatSignal.engaged(b), "engaged with no fight");
        SectBoutSession.of(a).active.put(a.getUUID(),
                new SectBouts.Bout(UUID.randomUUID(), "yul_jin", SectBouts.Mode.TRAIN, h.getLevel().getGameTime(), 1.0D));
        h.assertTrue(SectBouts.inBout(a) && CombatSignal.engaged(a) && !CombatSignal.engaged(b), "a bout did not engage only its player");
        SectBoutSession.of(a).active.remove(a.getUUID());
        h.assertTrue(!CombatSignal.engaged(a), "an ended bout stayed engaged");
        SectDuel.start(a, b, at, w -> { });
        h.assertTrue(SectDuel.inDuel(a) && CombatSignal.engaged(a) && CombatSignal.engaged(b), "a duel did not engage both sides");
        SectDuel.cancel(h.getLevel(), a.getUUID());
        h.assertTrue(!CombatSignal.engaged(a) && !CombatSignal.engaged(b), "a cancelled duel stayed engaged");
        h.succeed();
    }
}
