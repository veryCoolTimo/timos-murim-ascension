package io.github.verycooltimo.murim.library;

import io.github.verycooltimo.murim.Config;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.SectGameTests;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The first-join book: exactly once per player, the toggle, the title, the page list. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SpawnBookGameTests {
    private SpawnBookGameTests() { }

    @GameTest(template = "sect_yard", batch = "spawnbook1109", timeoutTicks = 40)
    public static void firstJoinGivesOneBookSecondNone(GameTestHelper h) {
        var p = SectGameTests.fakePlayer(h);
        boolean before = Config.SPAWN_BOOK.get();
        try {
            Config.SPAWN_BOOK.set(true);
            h.assertTrue(SpawnBook.grantOnce(p), "first join gave no book");
            h.assertTrue(p.getInventory().countItem(ModLibrary.SPAWN_BOOK.get()) == 1, "expected exactly one book");
            ItemStack book = p.getInventory().items.stream().filter(s -> s.is(ModLibrary.SPAWN_BOOK.get())).findFirst().orElseThrow();
            h.assertTrue(book.getHoverName().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                    && t.getKey().equals(SpawnBook.TITLE), "wrong book title");
            h.assertFalse(SpawnBook.grantOnce(p), "second join gave a book");
            h.assertTrue(p.getInventory().countItem(ModLibrary.SPAWN_BOOK.get()) == 1, "book duplicated");
            // the flag survives a save of the player's own data
            h.assertTrue(p.getPersistentData().getBoolean(SpawnBook.FLAG), "flag missing");
            h.assertTrue(SpawnBook.pages(p).size() == 7, "page count");
        } finally {
            Config.SPAWN_BOOK.set(before);
        }
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "spawnbook1109", timeoutTicks = 40)
    public static void toggleOffGivesNothing(GameTestHelper h) {
        var p = SectGameTests.fakePlayer(h);
        boolean before = Config.SPAWN_BOOK.get();
        try {
            Config.SPAWN_BOOK.set(false);
            h.assertFalse(SpawnBook.grantOnce(p), "book given with the toggle off");
            h.assertTrue(p.getInventory().countItem(ModLibrary.SPAWN_BOOK.get()) == 0, "book in inventory");
            h.assertFalse(p.getPersistentData().getBoolean(SpawnBook.FLAG), "flag set with the toggle off");
            Config.SPAWN_BOOK.set(true);
            h.assertTrue(SpawnBook.grantOnce(p), "toggled on later: no book");
        } finally {
            Config.SPAWN_BOOK.set(before);
        }
        h.succeed();
    }

    /** The mountain's centre is an Overworld coordinate: elsewhere the book must not name a direction. */
    @GameTest(template = "sect_yard", batch = "spawnbook1109", timeoutTicks = 40)
    public static void directionIsNotGivenOutsideTheOverworld(GameTestHelper h) {
        var p = SectGameTests.fakePlayer(h);
        var nether = h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        var home = p.serverLevel();
        h.assertTrue(nether != null, "no Nether level in the test server");
        h.assertTrue(io.github.verycooltimo.murim.world.hua.MountHuaSites.get(h.getLevel().getServer()) != null, "no mountain placed");
        try {
            p.setServerLevel(nether);
            h.assertTrue(SpawnBook.direction(p) == null, "the book named a direction to the mountain from the Nether");
        } finally {
            p.setServerLevel(home);
        }
        h.succeed();
    }
}
