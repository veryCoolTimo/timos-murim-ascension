package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.CultivationState;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Optional;

/** Author 21.10: exercise the server packet handlers, not just the UI predicate. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class ArtSlotsGameTests {
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("murim", path);
    }

    private static ServerPlayer player(GameTestHelper h) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "art-slots"), false);
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        // Same recording-free mock connection as CombatHudGameTests; busy feedback sends vanilla chat.
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie) {
            @Override public boolean hasChannel(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
                return false;
            }
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
        };
        return p;
    }

    @GameTest(template = "small_floor", batch = "art_slots", timeoutTicks = 20)
    public static void quickswapSelectFormRestoresPreviousSlotAndForm(GameTestHelper h) {
        var p = player(h);
        var a = id("six_harmonies_strike");
        var b = id("six_harmonies_chain");
        var palm = id("demon_palm");
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY
                .with(a, TechniqueProgress.learned(2, 12))
                .with(b, TechniqueProgress.learned(2, 12))
                .with(palm, TechniqueProgress.learned(2, 12)));
        p.setData(ModAttachments.LOADOUT, Loadout.EMPTY.with(0, Optional.of(a)).with(1, Optional.of(palm)));
        h.assertTrue(LoadoutService.selectForm(p, 0, b, true), "same-art switch refused");
        h.assertTrue(LoadoutService.selectForm(p, 0, a, true), "previous form refused");
        h.assertTrue(p.getData(ModAttachments.LOADOUT).at(0).equals(Optional.of(a)), "wrong restored form");
        h.assertTrue(LoadoutService.selectForm(p, 1, palm, true), "other art refused");
        h.assertTrue(LoadoutService.selectForm(p, 0, a, true), "previous art refused");
        h.assertTrue(p.getData(ModAttachments.LOADOUT).active() == 0
                && p.getData(ModAttachments.LOADOUT).at(0).equals(Optional.of(a)), "wrong restored pair");
        h.assertTrue(LoadoutService.selectForm(p, 1, palm, true), "toggle back refused");
        h.assertTrue(p.getData(ModAttachments.LOADOUT).active() == 1, "toggle lost slot");
        p.setData(ModAttachments.LOADOUT, p.getData(ModAttachments.LOADOUT).with(0, Optional.empty()));
        h.assertTrue(!LoadoutService.selectForm(p, 0, a, true), "empty previous slot accepted");
        h.assertTrue(p.getData(ModAttachments.LOADOUT).active() == 1, "refusal changed selection");
        p.setData(ModAttachments.LOADOUT, p.getData(ModAttachments.LOADOUT).with(0, Optional.of(b)));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(palm, TechniqueProgress.learned(2, 12)));
        h.assertTrue(!LoadoutService.selectForm(p, 0, a, true), "unlearned previous form accepted");
        h.assertTrue(p.getData(ModAttachments.LOADOUT).active() == 1, "unlearned refusal changed slot");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "art_slots", timeoutTicks = 20)
    public static void quickswapBambooFormsRestoresFormAndRejectsLockedForm(GameTestHelper h) {
        var p = player(h);
        var turn = id("bamboo_leaf_hand");
        var wall = id("bamboo_iron_wall");
        var fingers = id("bamboo_sword_fingers");
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(turn, TechniqueProgress.learned(2, 12)));
        MasteryService.unlockStarForms(p);
        p.setData(ModAttachments.LOADOUT, Loadout.EMPTY.with(0, Optional.of(turn)));
        h.assertTrue(LoadoutService.selectForm(p, 0, wall, true), "Bamboo wall switch refused");
        h.assertTrue(LoadoutService.selectForm(p, 0, turn, true), "Bamboo previous form refused");
        h.assertTrue(LoadoutService.selectForm(p, 0, wall, true), "Bamboo toggle back refused");
        h.assertTrue(!LoadoutService.selectForm(p, 0, fingers, true), "locked Bamboo form selected");
        h.assertTrue(p.getData(ModAttachments.LOADOUT).at(0).equals(Optional.of(wall)), "refusal lost previous form");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "art_slots", timeoutTicks = 20)
    public static void breathingChangesWithoutQiLossButNotDuringCombat(GameTestHelper h) {
        var p = player(h);
        var method = id("six_harmonies");
        var art = Arts.methodArt(method);
        var sword = id("falling_petal_sword");
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(art, TechniqueProgress.learned(0, 12))
                .with(sword, TechniqueProgress.learned(1, 12)));
        p.setData(ModAttachments.PROFILE, io.github.verycooltimo.murim.profile.DantianProfile.INITIAL
                .withPool(17).withCirculating(3));
        p.setData(ModAttachments.CULTIVATION, new CultivationState(Optional.of(id("street_strength")), CultivationState.SEEDED));
        var qi = p.getData(ModAttachments.PROFILE);
        LoadoutService.setSlot(p, 0, Optional.of(art));
        h.assertTrue(p.getData(ModAttachments.LOADOUT).at(0).isEmpty(), "breathing entered a technique slot");
        LoadoutService.setSlot(p, ArtSlots.BREATHING, Optional.of(sword));
        h.assertTrue(p.getData(ModAttachments.CULTIVATION).method().equals(Optional.of(id("street_strength"))),
                "sword entered breathing slot");
        LoadoutService.setSlot(p, ArtSlots.BREATHING, Optional.of(art));
        h.assertTrue(p.getData(ModAttachments.CULTIVATION).method().equals(Optional.of(method)), "method not selected");
        h.assertTrue(p.getData(ModAttachments.PROFILE).equals(qi), "switching changed qi/profile");
        h.assertTrue(p.getData(ModAttachments.CULTIVATION).beats() == CultivationState.SEEDED, "lost cultivation progress");
        p.setData(ModAttachments.TECHNIQUE_STATE,
                io.github.verycooltimo.murim.combat.TechniqueState.started(sword, h.getLevel().getGameTime()));
        LoadoutService.setSlot(p, ArtSlots.BREATHING, Optional.empty());
        h.assertTrue(p.getData(ModAttachments.CULTIVATION).method().equals(Optional.of(method)), "cleared breathing in combat");
        h.assertTrue(p.getData(ModAttachments.PROFILE).equals(qi), "combat refusal changed qi");
        var other = io.github.verycooltimo.murim.cultivation.MethodLoader.get(id("street_strength"));
        io.github.verycooltimo.murim.item.MethodScrollItem.learn(p, other);
        h.assertTrue(p.getData(ModAttachments.CULTIVATION).method().equals(Optional.of(method)), "scroll bypassed combat gate");
        p.setData(ModAttachments.TECHNIQUE_STATE, io.github.verycooltimo.murim.combat.TechniqueState.IDLE);
        io.github.verycooltimo.murim.item.MethodScrollItem.learn(p, other);
        h.assertTrue(p.getData(ModAttachments.CULTIVATION).method().equals(Optional.of(other.id())), "scroll did not switch outside combat");
        var switched = p.getData(ModAttachments.PROFILE);
        h.assertTrue(switched.pool() == qi.pool() && switched.circulating() == qi.circulating(),
                "scroll switching spent qi");
        h.assertTrue(switched.purity() == Math.max(qi.purity(), other.purity()),
                "scroll switching did not preserve or improve purity");
        h.assertTrue(switched.capacity() == qi.capacity() && switched.rank() == qi.rank(),
                "scroll switching changed capacity or rank");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "art_slots", timeoutTicks = 20)
    public static void protectionOnlyUsesItsOwnSlotIncludingAutomaticPlacement(GameTestHelper h) {
        // There is no shipped body-protection art yet. Supply a datapack-shaped definition without changing game content.
        var original = TechniqueLoader.all();
        var base = TechniqueLoader.get(id("falling_petal_sword"));
        var guard = id("test_body_protection");
        var definition = new TechniqueDefinition(guard, base.phaseTicks(), "body_protection", base.intent(), base.breath(),
                base.behavior(), base.vfx(), base.animation(), base.interruption(), base.weaknessKey(), base.hitStopTicks(),
                base.cooldownTicks(), base.starCap(), base.tier(), base.requires(), false);
        var definitions = new HashMap<>(original);
        definitions.put(guard, definition);
        TechniqueLoader.replaceAll(definitions);
        try {
            var p = player(h);
            p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(guard, TechniqueProgress.learned(1, 12))
                    .with(base.id(), TechniqueProgress.learned(1, 12)));
            LoadoutService.setSlot(p, 0, Optional.of(guard));
            LoadoutService.setSlot(p, ArtSlots.FOUNDATION, Optional.of(guard));
            LoadoutService.setSlot(p, ArtSlots.BREATHING, Optional.of(guard));
            h.assertTrue(p.getData(ModAttachments.LOADOUT).equals(Loadout.EMPTY), "protection entered another slot");
            LoadoutService.placeLearned(p, guard);
            h.assertTrue(p.getData(ModAttachments.LOADOUT).bodyProtection().equals(Optional.of(guard)), "auto placement failed");
            h.assertTrue(p.getData(ModAttachments.LOADOUT).slots().isEmpty(), "auto placement consumed a technique slot");
            LoadoutService.setSlot(p, ArtSlots.BODY_PROTECTION, Optional.of(base.id()));
            h.assertTrue(p.getData(ModAttachments.LOADOUT).bodyProtection().equals(Optional.of(guard)), "sword replaced protection");
            LoadoutService.setSlot(p, ArtSlots.BODY_PROTECTION, Optional.empty());
            LoadoutService.setSlot(p, ArtSlots.BODY_PROTECTION, Optional.of(guard));
            h.assertTrue(p.getData(ModAttachments.LOADOUT).bodyProtection().equals(Optional.of(guard)), "direct assignment failed");
            p.setData(ModAttachments.LOADOUT, Loadout.EMPTY.with(0, Optional.of(base.id())));
            h.assertTrue(!LoadoutService.selectForm(p, 0, guard, true), "wheel bypassed protection rule");
            p.setData(ModAttachments.LOADOUT, Loadout.EMPTY.with(0, Optional.of(guard)));
            LoadoutService.sync(p);
            h.assertTrue(p.getData(ModAttachments.LOADOUT).at(0).isEmpty()
                    && p.getData(ModAttachments.LOADOUT).bodyProtection().equals(Optional.of(guard)), "old placement not migrated");
            h.succeed();
        } finally {
            TechniqueLoader.replaceAll(original);
        }
    }

    private ArtSlotsGameTests() {}
}
