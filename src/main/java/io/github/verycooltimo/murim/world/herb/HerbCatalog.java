package io.github.verycooltimo.murim.world.herb;

import io.github.verycooltimo.murim.quest.QuestLoader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Board/book view of real harvestable herbs. K1/K2: quest habitats plus wild feature/Hua plants.
 * Habitat is a murim.contract.habitat suffix; origin/rawEffect are localized book text keys.
 * No mutable registry snapshot: datapack reloads are visible on the next board generation.
 */
public final class HerbCatalog {
    public record Entry(String item, String block, String habitat, String origin, String rawEffect,
                        boolean dangerous, int weight) { }

    /** Sources: WildHerbFeature (forest treasures), HuaHerbs (black lingzhi niche). */
    public static final List<Entry> WILD = List.of(
            new Entry("murim:thousand_ginseng", "murim:thousand_ginseng_plant", "forest",
                    "murim.herb.origin.mount_hua", "murim.herb.raw.thousand_ginseng", false, 1),
            new Entry("murim:thousand_lingzhi", "murim:thousand_lingzhi_plant", "dark",
                    "murim.herb.origin.mount_hua", "murim.herb.raw.thousand_lingzhi", false, 2),
            new Entry("murim:black_lingzhi", "murim:black_lingzhi_plant", "cliff",
                    "murim.herb.origin.absolute_regression", "murim.herb.raw.black_lingzhi", true, 1));

    /** Non-plant brewing materials stay out of board orders. */
    public static final List<Entry> MATERIALS = List.of(new Entry("murim:ice_crystal", "", "merchant",
            "murim.herb.origin.ice_crystal", "murim.herb.raw.ice_crystal", false, 0));

    public static List<Entry> all() {
        List<Entry> entries = new ArrayList<>(WILD);
        for (var located : QuestLoader.herbs()) {
            var h = located.step();
            entries.removeIf(e -> e.item().equals(h.item()));
            entries.add(new Entry(h.item(), h.block(), h.habitat(), h.origin(), h.rawEffect(), h.dangerous(),
                    h.id().equals("hundred_ginseng") ? 18 : Math.max(1, 16 / h.rarity())));
        }
        entries.sort(Comparator.comparing(Entry::item));
        return List.copyOf(entries);
    }

    public static String habitat(String item) {
        return all().stream().filter(e -> e.item().equals(item)).map(Entry::habitat).findFirst().orElse("forest");
    }

    private HerbCatalog() { }
}
