package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.*;
import io.github.verycooltimo.murim.mastery.*;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.FullBloomExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Uses the same accepted-duel start as accept(), then waits through its real countdown. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class CombatContactReviewGameTests {
    @GameTest(template = "sect_yard", timeoutTicks = 140)
    public static void noContactWithoutPvpButAcceptedDuelAllowsIt(GameTestHelper h) {
        for (int x = 0; x <= 10; x++) for (int z = 0; z <= 10; z++) {
            h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            for (int y = 2; y <= 6; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        var a = SectGameTests.fakePlayer(h);
        var b = SectGameTests.fakePlayer(h);
        a.moveTo(h.absoluteVec(new Vec3(5, 2, 3))); a.setYRot(0); a.setOnGround(true);
        b.moveTo(h.absoluteVec(new Vec3(5, 2, 5))); b.setOnGround(true);
        h.getLevel().addNewPlayer(a); h.getLevel().addNewPlayer(b);
        var six = ResourceLocation.fromNamespaceAndPath("murim", "six_harmonies");
        a.setData(ModAttachments.PROFILE, a.getData(ModAttachments.PROFILE).withTags("clear", "plum")
                .withAxes(100, .9, .5).withCirculating(50));
        a.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(six, TechniqueProgress.learned(0, 12))
                .with(DipperService.FORM, TechniqueProgress.learned(8, 12)));
        a.setData(ModAttachments.LOADOUT, Loadout.EMPTY.with(0, java.util.Optional.of(DipperService.FORM))
                .withFoundation(java.util.Optional.of(six)));
        a.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        TargetLock.set(a, b.getId());
        var server = h.getLevel().getServer();
        boolean pvp = server.isPvpAllowed();
        try {
            server.setPvpAllowed(false);
            h.assertTrue(DipperService.start(a) != null && b.getData(ModAttachments.WIND_GOD).rootOwner == null,
                    "PvP-off Dipper rooted a player");
            FullBloomExecutor.begin(a, FullBloomExecutor.ID);
            var c = a.getData(ModAttachments.FULL_BLOOM);
            h.assertTrue(c.target != b.getId(), "PvP-off Bloom selected player");
            c.target = b.getId(); c.reached = 4;
            c.direction = TargetLock.centre(b).subtract(c.origin).normalize();
            var at = c.origin.add(c.direction);
            var arrow = new net.minecraft.world.entity.projectile.Arrow(h.getLevel(), at.x, at.y, at.z,
                    new ItemStack(Items.ARROW), null);
            arrow.setOwner(b);
            h.getLevel().addFreshEntity(arrow);
            double reserve = c.reserve;
            FullBloomExecutor.tick(a, FullBloomExecutor.ID, 10);
            h.assertTrue(b.getDeltaMovement().lengthSqr() == 0, "PvP-off Bloom pushed player");
            h.assertTrue(arrow.isAlive() && c.reserve == reserve, "PvP-off Bloom removed player's projectile");
            arrow.discard();
            FullBloomExecutor.end(a);
        } finally { server.setPvpAllowed(pvp); }
        var duel = SectDuel.start(a, b, a.position(), null);
        h.runAfterDelay(81, () -> {
            boolean previous = server.isPvpAllowed();
            try {
                server.setPvpAllowed(true);
                h.assertTrue(SectDuel.permitsContact(a, b), "duel countdown has not ended");
                String denied = DipperService.start(a);
                h.assertTrue(denied == null, "accepted duel Dipper rejected: " + denied);
                h.assertTrue(a.getUUID().equals(b.getData(ModAttachments.WIND_GOD).rootOwner), "duel Dipper failed to root");
                // Revocation must be honoured by the entity root tick, not just by the next cast.
                server.setPvpAllowed(false);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.EntityTickEvent.Post(b));
                h.assertTrue(b.getData(ModAttachments.WIND_GOD).rootOwner == null, "revoked consent kept root");
                FootworkService.cancel(a);
                server.setPvpAllowed(true);
                b.setDeltaMovement(Vec3.ZERO);
                FullBloomExecutor.begin(a, FullBloomExecutor.ID);
                var c = a.getData(ModAttachments.FULL_BLOOM);
                c.target = b.getId(); c.reached = 4;
                c.direction = TargetLock.centre(b).subtract(c.origin).normalize();
                FullBloomExecutor.tick(a, FullBloomExecutor.ID, 10);
                h.assertTrue(b.getDeltaMovement().dot(c.direction) > .1, "duel Bloom failed to push");
                h.succeed();
            } finally {
                FullBloomExecutor.end(a); FootworkService.cancel(a);
                SectDuel.end(h.getLevel(), duel, null, "gone");
                server.setPvpAllowed(previous);
                a.discard(); b.discard();
            }
        });
    }
}
