package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.technique.PlumRules;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Author 22.10: the forms of the Seven Plum Blossoms unlock by the ART's stars, one after another. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SevenPlumOrderGameTests {
    private SevenPlumOrderGameTests() { }

    private static ResourceLocation id(String p) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, p);
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void formsUnlockByTheArtsStarsInTheApprovedOrder(GameTestHelper h) {
        String[] forms = {"rush", "whirlwind", "execution", "shower", "explosion", "blossoms"};
        int[] stars = {1, 3, 5, 7, 9, 11};
        for (int i = 0; i < forms.length; i++) {
            var def = TechniqueLoader.get(id("seven_plum_" + forms[i]));
            for (int have = 0; have <= 12; have++) {
                MasteryState state = MasteryState.EMPTY.with(id("seven_plum_basic"), TechniqueProgress.learned(have, 12));
                boolean open = MasteryRules.missing(def.requires(), state.stars()).isEmpty();
                h.assertTrue(open == (have >= stars[i]), forms[i] + " at art star " + have + " open=" + open);
            }
        }
        h.assertTrue(!PlumRules.blossoms(3) && PlumRules.blossoms(7), "the blossom shows too early");
        h.assertTrue(io.github.verycooltimo.murim.technique.Styles.SEVEN_PLUM.forms().equals(List.of(
                id("seven_plum_rush"), id("seven_plum_whirlwind"), id("seven_plum_execution"), id("seven_plum_shower"),
                id("seven_plum_explosion"), id("seven_plum_blossoms"))), "wrong page order");
        h.succeed();
    }
}
