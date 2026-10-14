package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.library.JunkBook;
import io.github.verycooltimo.murim.library.JunkFactory;
import io.github.verycooltimo.murim.library.JunkKind;
import io.github.verycooltimo.murim.library.ModLibrary;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Registration and real reloaded loot, plus the deliberately harmless crane effect. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class FunnyJunkGameTests {
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void funnyManualsAndDefinitionsRegistered(GameTestHelper helper) {
        var items = List.of(ModItems.JUNK_HEAVENLY_DRAGON_MANUAL.get(),
                ModItems.JUNK_THOUSAND_CRANES_MANUAL.get(), ModItems.JUNK_BEGGARS_TAP_MANUAL.get());
        for (int i = 0; i < JunkArts.FUNNY.size(); i++) {
            var art = JunkArts.FUNNY.get(i);
            var definition = TechniqueLoader.get(art);
            helper.assertTrue(definition != null, "missing loaded dance " + art);
            helper.assertTrue(JunkArts.isJunk(art) && JunkArts.STREET_BOOKS.contains(art), "missing junk catalog " + art);
            helper.assertTrue(new ItemStack(items.get(i)).is(net.minecraft.tags.TagKey.create(Registries.ITEM,
                    id("funny_junk_manuals"))), "manual missing item tag " + art);
            helper.assertTrue(art.equals(new ItemStack(items.get(i)).get(ModDataComponents.TECHNIQUE.get())),
                    "wrong default manual component " + art);
            helper.assertTrue(BuiltInRegistries.ITEM.getKey(items.get(i)).equals(id("junk_" + art.getPath() + "_manual")),
                    "wrong registered item " + art);
            helper.assertTrue(definition.animation().equals(id("junk_" + art.getPath())), "wrong PAL clip " + art);
            helper.assertTrue(definition.behavior().damage() <= 0.5F && definition.hitStopTicks() == 0,
                    "dance turned into a real attack " + art);
            var pages = BookLayout.single(art, path -> true);
            helper.assertTrue(pages.size() == 1 && !pages.get(0).picture()
                    && pages.get(0).texture().equals("textures/gui/book/junk_text.png"), "junk book has a sect picture " + art);
            helper.assertTrue(definition.cooldownTicks() >= definition.totalTicks(), "cast exceeds cooldown " + art);
        }
        helper.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 100)
    public static void banditAndCampLootContainEveryDance(GameTestHelper helper) {
        var level = helper.getLevel();
        Zombie source = helper.spawn(EntityType.ZOMBIE, new BlockPos(2, 2, 2));
        source.setNoAi(true);
        LootParams entityParams = new LootParams.Builder(level)
                .withParameter(LootContextParams.THIS_ENTITY, source)
                .withParameter(LootContextParams.ORIGIN, source.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().generic())
                .create(LootContextParamSets.ENTITY);
        LootParams chestParams = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, source.position()).create(LootContextParamSets.CHEST);
        for (String path : List.of("entities/bandit_swordsman", "entities/bandit_archer", "entities/bandit_qi",
                "entities/bandit_chief", "chests/bandit_camp/crate", "chests/bandit_camp/chief", "chests/bandit_camp/cart")) {
            var table = level.getServer().reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id(path)));
            Set<ResourceLocation> found = new HashSet<>();
            for (int roll = 0; roll < 6000 && found.size() < JunkArts.FUNNY.size(); roll++) {
                for (ItemStack stack : table.getRandomItems(path.startsWith("entities/") ? entityParams : chestParams, 91000L + roll)) {
                    var art = stack.get(ModDataComponents.TECHNIQUE.get());
                    if (art != null && JunkArts.FUNNY.contains(art)) {
                        helper.assertTrue(BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id("junk_" + art.getPath() + "_manual")),
                                "loot has wrong cover " + art);
                        found.add(art);
                    }
                }
            }
            helper.assertTrue(found.containsAll(JunkArts.FUNNY), path + " missing funny loot: " + found);
        }
        source.discard();
        helper.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void archiveFactoryOffersAllDances(GameTestHelper helper) {
        Set<ResourceLocation> found = new HashSet<>();
        var random = net.minecraft.util.RandomSource.create(20261010L);
        for (int roll = 0; roll < 6000; roll++) {
            JunkBook book = JunkFactory.roll(random);
            if (book.kind() == JunkKind.FAKE_GRAND) {
                var art = JunkFactory.fakeArt(book);
                helper.assertTrue(art.equals(JunkFactory.fakeArt(book.withRead())), "reading changes archive art");
                helper.assertTrue(TechniqueLoader.get(art) != null, "archive teaches missing art " + art);
                ItemStack stack = JunkFactory.stack(book);
                helper.assertTrue(book.equals(stack.get(ModLibrary.JUNK.get())), "archive lost its text component");
                found.add(art);
            }
        }
        helper.assertTrue(found.containsAll(JunkArts.ARCHIVE_BOOKS), "archive factory missing books: " + found);
        // Negative and extreme seeds are real random.nextLong() outcomes as well.
        for (long seed : new long[] {Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) {
            helper.assertTrue(JunkArts.ARCHIVE_BOOKS.contains(JunkFactory.fakeArt(new JunkBook(JunkKind.FAKE_GRAND, seed))),
                    "bad signed seed " + seed);
        }
        helper.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void craneFlapPushesWithoutDamageAndTapDoesNotAttack(GameTestHelper helper) {
        ServerPlayer player = JunkArtTests.fakePlayer(helper);
        player.moveTo(helper.absoluteVec(new Vec3(1.5D, 2.0D, 1.5D)));
        Zombie near = helper.spawn(EntityType.ZOMBIE, new BlockPos(2, 2, 1));
        Zombie far = helper.spawn(EntityType.ZOMBIE, new BlockPos(5, 2, 1));
        near.setNoAi(true);
        far.setNoAi(true);
        near.setDeltaMovement(Vec3.ZERO);
        far.setDeltaMovement(Vec3.ZERO);
        float health = near.getHealth();
        var cranes = TechniqueLoader.get(JunkArts.THOUSAND_CRANES);
        FunnyJunkArts.tick(player, cranes, 21);
        helper.assertTrue(near.getDeltaMovement().lengthSqr() == 0.0D, "flap before the animation beat");
        FunnyJunkArts.tick(player, cranes, 22);
        helper.assertTrue(near.getDeltaMovement().x > 0.0D, "near mob did not move away");
        helper.assertTrue(far.getDeltaMovement().lengthSqr() == 0.0D, "flap reached a distant mob");
        helper.assertTrue(near.getHealth() == health, "flap damaged mob");
        helper.assertTrue(!BehaviorExecutor.execute(player, TechniqueLoader.get(JunkArts.BEGGARS_TAP)), "tap attacked");
        helper.assertTrue(near.getHealth() == health, "tap damaged mob");
        near.discard();
        far.discard();
        helper.succeed();
    }

    private FunnyJunkGameTests() {
    }
}
