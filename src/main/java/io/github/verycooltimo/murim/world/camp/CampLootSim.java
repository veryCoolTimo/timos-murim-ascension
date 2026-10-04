package io.github.verycooltimo.murim.world.camp;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.BanditSwordsman;
import io.github.verycooltimo.murim.mastery.PageRules;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Симуляция добычи с разгромленного лагеря на НАСТОЯЩИХ таблицах добычи (docs/design/24-bandit-camp.md §4):
 * два ящика, телега, сундук главаря и добыча с состава лагеря по {@link CampRoster}. Меряет, даёт ли
 * поход продвижение, а не только мусор: новую технику (книга или три страницы незнакомой книги),
 * продолжение рваной книги или озарение не меньше 0,8 слоя.
 *
 * <p>Общая для GameTest ({@code bandit_camp_loot}) и команды {@code /murim camp lootsim}.
 */
public final class CampLootSim {

    /** Что игрок знает: книга → {слой, предел}. */
    public record Profile(String name, Map<ResourceLocation, int[]> known) {
    }

    /** Итог по профилю: доли походов и средние на поход. */
    public record Result(String profile, double newTechnique, double useful, double deepenLayers, double insightLayers,
                         double pages, double silver, double pills) {
        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "%s: new technique %.0f%%, useful %.0f%%, deepen %.2f layers, insight %.2f layers; per run %.1f pages, %.1f silver, %.2f pills",
                    profile, newTechnique * 100, useful * 100, deepenLayers, insightLayers, pages, silver, pills);
        }
    }

    public static List<Profile> profiles() {
        ResourceLocation six = id("six_harmonies");
        ResourceLocation plum = id("seven_plum_blossoms");
        return List.of(
                new Profile("fresh", Map.of()),
                new Profile("after M2", Map.of(six, new int[] {2, 5})),
                new Profile("veteran", Map.of(six, new int[] {4, 5}, plum, new int[] {2, 2})));
    }

    public static List<Result> run(ServerLevel level, int runs, long seed) {
        RandomSource random = RandomSource.create(seed);
        List<List<ItemStack>> drops = new ArrayList<>();
        Bandit sword = ModEntities.BANDIT_SWORDSMAN.get().create(level);
        Bandit archer = ModEntities.BANDIT_ARCHER.get().create(level);
        Bandit qi = ModEntities.BANDIT_SWORDSMAN.get().create(level);
        Bandit chief = ModEntities.BANDIT_SWORDSMAN.get().create(level);
        ((BanditSwordsman) qi).makeElite();
        ((BanditSwordsman) chief).makeChief(net.minecraft.network.chat.Component.literal("sim"));
        for (int i = 0; i < runs; i++) {
            List<ItemStack> run = new ArrayList<>();
            CampLayout plan = CampLayout.plan(random.nextLong());
            run.addAll(chest(level, CampBuilder.LOOT_CRATE, random));
            run.addAll(chest(level, CampBuilder.LOOT_CRATE, random));
            run.addAll(chest(level, CampBuilder.LOOT_CART, random));
            run.addAll(chest(level, CampBuilder.LOOT_CHIEF, random));
            for (CampRoster.Member m : CampRoster.plan(plan)) {
                Bandit who = m.chief() ? chief : m.qi() ? qi : m.archer() ? archer : sword;
                run.addAll(entity(level, who, random));
            }
            drops.add(run);
        }
        List<Result> out = new ArrayList<>();
        for (Profile p : profiles()) {
            out.add(measure(p, drops));
        }
        return out;
    }

    private static List<ItemStack> chest(ServerLevel level, ResourceKey<LootTable> key, RandomSource random) {
        LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
        LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                .create(LootContextParamSets.CHEST);
        return table.getRandomItems(params, random);
    }

    private static List<ItemStack> entity(ServerLevel level, Bandit who, RandomSource random) {
        LootTable table = level.getServer().reloadableRegistries().getLootTable(who.getLootTable());
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.THIS_ENTITY, who)
                .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().generic())
                .create(LootContextParamSets.ENTITY);
        return table.getRandomItems(params, random);
    }

    private static Result measure(Profile profile, List<List<ItemStack>> drops) {
        double fresh = 0, useful = 0, deepen = 0, insight = 0, pages = 0, silver = 0, pills = 0;
        for (List<ItemStack> run : drops) {
            Map<ResourceLocation, Integer> pageCount = new HashMap<>();
            boolean newTech = false;
            double runInsight = 0;
            int runDeepen = 0;
            for (ItemStack s : run) {
                ResourceLocation book = s.get(ModDataComponents.TECHNIQUE.get());
                if (s.is(ModItems.MANUAL_PAGE.get()) && book != null) {
                    pageCount.merge(book, s.getCount(), Integer::sum);
                    pages += s.getCount();
                } else if (s.is(ModItems.TECHNIQUE_MANUAL.get()) && book != null) {
                    if (profile.known().containsKey(book)) {
                        runInsight += PageRules.BOOK_INSIGHT;
                    } else {
                        newTech = true;
                    }
                } else if (s.is(ModItems.SILVER_TAEL.get())) {
                    silver += s.getCount();
                } else if (s.getItem() instanceof io.github.verycooltimo.murim.item.PillItem) {
                    pills += s.getCount();
                }
            }
            for (Map.Entry<ResourceLocation, Integer> e : pageCount.entrySet()) {
                int[] k = profile.known().get(e.getKey());
                if (k == null) {
                    newTech |= e.getValue() >= PageRules.PAGES_TO_BIND;
                    continue;
                }
                TechniqueDefinition d = TechniqueLoader.get(e.getKey());
                int layers = d == null ? 5 : d.layers();
                int dp = Math.min(e.getValue(), layers - k[1]);
                runDeepen += dp;
                runInsight += PageRules.PAGE_INSIGHT * (e.getValue() - dp);
            }
            fresh += newTech ? 1 : 0;
            useful += newTech || runDeepen > 0 || runInsight >= 0.8D ? 1 : 0;
            deepen += runDeepen;
            insight += runInsight;
        }
        double n = drops.size();
        return new Result(profile.name(), fresh / n, useful / n, deepen / n, insight / n, pages / n, silver / n, pills / n);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    private CampLootSim() {
    }
}
