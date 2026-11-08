package io.github.verycooltimo.murim.world;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.CultivationState;
import io.github.verycooltimo.murim.cultivation.MeditationState;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.SectGameTests;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.Optional;

/** Places of power are off (author 22.10 and 23.10): no node in new worlds, and old vein stones give a meditating player nothing. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class PlaceOffGameTests {
    private PlaceOffGameTests() {
    }

    private static final ResourceLocation QI_NODE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "qi_node");

    @GameTest(template = "small_floor", timeoutTicks = 60, batch = "placeoff")
    public static void theQiNodeIsNotInTheWorldgen(GameTestHelper h) {
        var access = h.getLevel().registryAccess();
        h.assertFalse(access.registryOrThrow(Registries.PLACED_FEATURE).containsKey(QI_NODE), "placed feature murim:qi_node still ships");
        h.assertFalse(access.registryOrThrow(Registries.CONFIGURED_FEATURE).containsKey(QI_NODE), "configured feature murim:qi_node still ships");
        Optional<? extends net.minecraft.core.Registry<?>> mods = access.registry(NeoForgeRegistries.Keys.BIOME_MODIFIERS);
        h.assertTrue(mods.isPresent(), "no biome modifier registry");
        h.assertFalse(mods.get().containsKey(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "qi_nodes")), "biome modifier qi_nodes still ships");
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 60, batch = "placeoff")
    public static void meditatingNextToOldNodeBlocksGivesNoPlaceEffect(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(2.5D, 3.0D, 2.5D));
        p.setPos(at.x, at.y, at.z);
        p.setData(ModAttachments.CULTIVATION, new CultivationState(Optional.empty(), CultivationState.SEEDED));
        p.setData(ModAttachments.MEDITATION, MeditationState.started(true, CultivationState.SEEDED));
        PlaceKind[] kinds = PlaceKind.values();
        for (int i = 0; i < kinds.length; i++) {
            h.setBlock(new BlockPos(1 + i % 3, 2, 1 + i / 3 + 3), ModWorld.SPIRIT_VEIN.get().defaultBlockState().setValue(SpiritVeinBlock.KIND, kinds[i]));
        }
        h.setBlock(new BlockPos(3, 2, 2), ModWorld.SPIRIT_VEIN.get().defaultBlockState());
        BlockPos feet = p.blockPosition();
        boolean before = PlaceService.enabled;
        try {
            PlaceService.enabled = false;
            h.assertTrue(PlaceService.findPlace(h.getLevel(), feet).isEmpty(), "a place was found beside the old node");
            h.assertTrue(PlaceService.findNode(h.getLevel(), feet).isEmpty(), "a node was found");
            h.assertTrue(PlaceService.gainFactor(p) == 1.0D, "meditation gain changed beside the node: " + PlaceService.gainFactor(p));
            h.assertTrue(p.getData(ModAttachments.ABSORB).place == null, "a place sits in the session slot");
            h.assertTrue(ModWorld.SPIRIT_VEIN.get().defaultBlockState().getLightEmission() == 0, "the old node still glows");
            // The mechanic is only behind the flag: with it on, the same blocks are a place again.
            PlaceService.enabled = true;
            h.assertTrue(PlaceService.findNode(h.getLevel(), feet).isPresent(), "the mechanic is gone, not just switched off");
        } finally {
            PlaceService.enabled = before;
        }
        h.succeed();
    }
}
