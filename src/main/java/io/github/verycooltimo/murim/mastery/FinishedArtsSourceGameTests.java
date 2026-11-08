package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;

/** Actual seeded loot rolls, not a JSON substring check. API verified under /tmp/mr-ref-1121:
 * minecraft-src/net/minecraft/world/level/storage/loot/{LootTable,LootParams}.java,
 * server/ReloadableServerRegistries.java#Holder.getLootTable, resources/ResourceKey.java#create,
 * gametest/framework/GameTestHelper.java#makeMockPlayer/assertTrue/succeed;
 * neoforge-src/net/neoforged/neoforge/gametest/{GameTestHolder,PrefixGameTestTemplate}.java.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class FinishedArtsSourceGameTests {
    private FinishedArtsSourceGameTests() {}

    @GameTest(template = "small_floor", timeoutTicks = 100)
    public static void allFiveFinishedArtsHaveRollableWorldSources(GameTestHelper h) {
        Set<String> formerlyCreativeOnly = new HashSet<>(Set.of("crescent_sweep", "wedge_fan", "ceremonial_draw", "shadow_step", "demon_palm"));
        for (String chest : new String[] {"crate", "cart", "chief"}) {
            roll(h, "chests/bandit_camp/" + chest, false,
                    Set.of("crescent_sweep", "wedge_fan", "ceremonial_draw"), formerlyCreativeOnly);
        }
        roll(h, "chests/fortress_vault", false, Set.of("shadow_step"), formerlyCreativeOnly);
        roll(h, "entities/bandit_chief", true, Set.of("demon_palm"), formerlyCreativeOnly);
        h.assertTrue(formerlyCreativeOnly.isEmpty(), "finished arts still lack sources: " + formerlyCreativeOnly);
        h.assertTrue(Arts.school(id("crescent_sweep")).equals("street") && Arts.school(id("wedge_fan")).equals("street")
                && Arts.school(id("ceremonial_draw")).equals("shaman"), "school filters changed");
        h.assertTrue(Arts.kin(id("crescent_sweep"), id("demon_palm")) && Arts.kin(id("demon_palm"), id("crescent_sweep")),
                "authored art relationship changed");
        h.succeed();
    }

    private static void roll(GameTestHelper h, String path, boolean entity, Set<String> expected, Set<String> unsourced) {
        var level = h.getLevel();
        var table = level.getServer().reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id(path)));
        var builder = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.ZERO);
        if (entity) builder.withParameter(LootContextParams.THIS_ENTITY, h.makeMockPlayer(GameType.SURVIVAL))
                .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().generic());
        var params = builder.create(entity ? LootContextParamSets.ENTITY : LootContextParamSets.CHEST);
        Set<String> missing = new HashSet<>(expected);
        var random = net.minecraft.util.RandomSource.create(1102L);
        for (int roll = 0; roll < 2048 && !missing.isEmpty(); roll++) {
            for (var stack : table.getRandomItems(params, random)) {
                var book = stack.get(ModDataComponents.TECHNIQUE.get());
                if (book == null || !expected.contains(book.getPath())) continue;
                h.assertTrue(stack.is(entity || path.equals("chests/fortress_vault")
                        ? ModItems.TECHNIQUE_MANUAL.get() : ModItems.MANUAL_PAGE.get()), "wrong book/page item for " + book);
                missing.remove(book.getPath());
                unsourced.remove(book.getPath());
            }
        }
        h.assertTrue(missing.isEmpty(), path + " could not roll " + missing);
    }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path); }
}
