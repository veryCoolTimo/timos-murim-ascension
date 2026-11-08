package io.github.verycooltimo.murim.cultivation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.trade.PeddlerStock;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class MorePillLootGameTests {
    private MorePillLootGameTests() {}

    private static JsonObject table(String path) throws Exception {
        try (var in = MorePillLootGameTests.class.getResourceAsStream("/data/murim/loot_table/chests/" + path + ".json")) {
            if (in == null) throw new IllegalStateException("Missing loot table " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static void rare(GameTestHelper h, String path, String pill, double chance) throws Exception {
        boolean found = false;
        for (var element : table(path).getAsJsonArray("pools")) {
            JsonObject pool = element.getAsJsonObject();
            for (var entry : pool.getAsJsonArray("entries")) {
                JsonObject item = entry.getAsJsonObject();
                if (!item.has("name") || !item.get("name").getAsString().equals("murim:pill_" + pill)) continue;
                found = true;
                h.assertTrue(pool.get("rolls").getAsInt() == 1 && item.get("weight").getAsInt() == 1,
                        "rare pill weight/count in " + path);
                h.assertTrue(pool.getAsJsonArray("conditions").get(0).getAsJsonObject().get("chance").getAsDouble() == chance,
                        "rare pill chance in " + path);
            }
        }
        h.assertTrue(found, pill + " missing from " + path);
    }

    @GameTest(template = "small_floor", timeoutTicks = 60)
    public static void soulVitalityHasNoRepeatableLootOrTraderSource(GameTestHelper h) throws Exception {
        for (String path : new String[] {"fortress_vault", "demonic_cache", "bandit_camp/chief"}) {
            h.assertTrue(!table(path).toString().contains("murim:pill_origin_energy"), "repeatable Soul Vitality in " + path);
        }
        h.assertTrue(PeddlerStock.SELLS.stream().noneMatch(l -> l.item().equals("murim:pill_origin_energy")), "repeatable trader supply");
        h.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 40)
    public static void rarePillsBelongToOutsidersOnly(GameTestHelper h) throws Exception {
        rare(h, "bandit_camp/crate", "small_restoration", 0.025D);
        rare(h, "bandit_camp/cart", "small_restoration", 0.025D);
        for (String pill : new String[] {"heavenly_earth", "moonlight"}) {
            rare(h, "bandit_camp/chief", pill, 0.04D);
            rare(h, "fortress_vault", pill, 0.06D);
        }
        var stock = PeddlerStock.SELLS.stream().filter(l -> l.item().equals("murim:pill_small_restoration")).findFirst().orElseThrow();
        h.assertTrue(stock.chance() == 0.08D && stock.count() == 1 && stock.uses() == 1
                && stock.wen() > PeddlerStock.sellPrice("murim:pill_snow_plum"), "rare expensive peddler stock");
        for (String path : new String[] {"sect/vault", "sect/head", "sect/supplies", "sect/steward", "sect/practice", "sect/food",
                "sect/elder", "sect/medicine", "sect/counting", "sect/room", "sect/hall", "sect/scrolls",
                "main_vault", "main_vault_study"}) {
            String json = table(path).toString();
            for (String pill : new String[] {"small_restoration", "heavenly_earth", "moonlight", "ascending_dragon", "great_restoration"}) {
                h.assertTrue(!json.contains("murim:pill_" + pill), "sect storage contains " + pill);
            }
        }
        // Unique treasures are placed by guarded code, never a repeatable loot-table roll.
        String fortress = table("fortress_vault").toString();
        h.assertTrue(!fortress.contains("murim:pill_great_restoration") && !fortress.contains("murim:pill_ascending_dragon"),
                "unique treasures in repeatable boss loot");
        h.succeed();
    }
}
