package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniqueState;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Save migration of 19.10 through Minecraft's own save and load path: a player of the previous build still holds the six
 * removed Heavenly Demon arts in the mastery, the loadout, the cooldowns and a running technique. Loading that player
 * must not throw and must leave nothing of them anywhere (see {@link RemovedArts}).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class RemovedArtsGameTests {
    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("murim", name);
    }

    private static ServerPlayer player(GameTestHelper h) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "removed-arts"), false);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Vec3 at = h.absoluteVec(new Vec3(2.5D, 2.0D, 2.5D));
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        return p;
    }

    @GameTest(template = "small_floor", batch = "removed_arts", timeoutTicks = 40)
    public static void aPlayerOfTheOldBuildLoadsWithoutTheRemovedArts(GameTestHelper h) {
        ServerPlayer old = player(h);
        old.setData(ModAttachments.MASTERY, MasteryState.EMPTY
                .with(id("rift_palm"), TechniqueProgress.learned(9, 12))
                .with(id("high_cloud_steps"), TechniqueProgress.learned(5, 10))
                .with(id("village_brawler_fist"), TechniqueProgress.learned(3, 12))
                .with(id("wind_god_steps"), TechniqueProgress.learned(6, 10))
                .comprehend(id("rift_palm"))
                .withFired(id("quiet_heart_guard"), List.of(1))
                .withFired(id("twenty_four_plum"), List.of(1)));
        old.setData(ModAttachments.LOADOUT, new Loadout(List.of(Optional.of(id("rift_palm")), Optional.of(id("wind_god_steps")),
                Optional.of(id("high_cloud_steps"))), 0, Optional.of(id("clear_sky_sword"))));
        Map<ResourceLocation, Long> cooldowns = new HashMap<>();
        cooldowns.put(id("rift_palm"), 5L);
        cooldowns.put(id("seven_plum_blossoms"), 6L);
        old.setData(ModAttachments.COOLDOWNS, cooldowns);
        old.setData(ModAttachments.TECHNIQUE_STATE, TechniqueState.started(id("four_horizons"), 77L).advanced());

        CompoundTag saved = old.saveWithoutId(new CompoundTag());
        h.assertTrue(saved.toString().contains("rift_palm") && saved.toString().contains("four_horizons"),
                "the bench did not write the removed arts, so the test would prove nothing");

        ServerPlayer relogged = player(h);
        relogged.load(saved);

        MasteryState mastery = relogged.getData(ModAttachments.MASTERY);
        h.assertTrue(mastery.techniques().keySet().equals(Set.of(id("village_brawler_fist"), id("wind_god_steps"))),
                "mastery kept " + mastery.techniques().keySet());
        h.assertTrue(mastery.progress(id("wind_god_steps")).stars() == 6, "the Wind God Steps lost their stars");
        h.assertTrue(mastery.comprehended().isEmpty(), "comprehension of a removed art survived: " + mastery.comprehended());
        h.assertTrue(mastery.fired().equals(Set.of("murim:twenty_four_plum#1")), "milestones kept: " + mastery.fired());
        Loadout loadout = relogged.getData(ModAttachments.LOADOUT);
        h.assertTrue(loadout.slots().equals(List.of(Optional.empty(), Optional.of(id("wind_god_steps")))),
                "loadout slots: " + loadout.slots());
        h.assertTrue(loadout.active() == 1 && loadout.foundation().isEmpty(), "active " + loadout.active() + ", foundation " + loadout.foundation());
        h.assertTrue(relogged.getData(ModAttachments.COOLDOWNS).equals(Map.of(id("seven_plum_blossoms"), 6L)),
                "cooldowns: " + relogged.getData(ModAttachments.COOLDOWNS));
        TechniqueState state = relogged.getData(ModAttachments.TECHNIQUE_STATE);
        h.assertTrue(!state.isActive() && state.lastStartGameTime() == 77L, "technique state: " + state);

        // The wheel and the star list are rebuilt from this data on every join: none of it may throw.
        LoadoutService.sync(relogged);
        MasteryService.sync(relogged);

        // The next save is clean for good.
        String next = relogged.saveWithoutId(new CompoundTag()).toString();
        for (String gone : RemovedArts.ARTS) {
            h.assertTrue(!next.contains(gone), "the next save still names " + gone);
        }
        h.succeed();
    }

    private RemovedArtsGameTests() {
    }
}
