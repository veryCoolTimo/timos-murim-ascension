package io.github.verycooltimo.murim.library;

import io.github.verycooltimo.murim.Config;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The short book every newcomer finds at first join (author, 9.11): what murim is, that nobody forces the path, that
 * a core is needed and where one is taught. Once per player; the flag sits in the player's persistent data.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SpawnBook {
    public static final String FLAG = "murim_spawn_book";
    public static final String TITLE = "murim.spawn_book.title";
    private static final String[] DIRS = {"e", "se", "s", "sw", "w", "nw", "n", "ne"};

    private SpawnBook() { }

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) grantOnce(p);
    }

    /** Gives the book when the toggle is on and the player never had it. Returns whether a book was given. */
    public static boolean grantOnce(ServerPlayer p) {
        if (!Config.SPAWN_BOOK.get() || p.getPersistentData().getBoolean(FLAG)) return false;
        p.getPersistentData().putBoolean(FLAG, true);
        give(p);
        return true;
    }

    /** Dev re-grant: ignores the flag and the toggle. */
    public static void give(ServerPlayer p) {
        ItemStack book = new ItemStack(ModLibrary.SPAWN_BOOK.get());
        if (!p.getInventory().add(book)) p.drop(book, false);
    }

    /** Compass key of the massif as seen from the player; null when the mountain is not placed yet. */
    static String direction(ServerPlayer p) {
        // The mountain's centre is in Overworld coordinates: in the Nether or the End the compass would lie.
        if (p.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) return null;
        MountHuaSite site = MountHuaSites.get(p.getServer());
        if (site == null) return null;
        double dx = site.centerX() - p.getX(), dz = site.centerZ() - p.getZ();
        double deg = Math.toDegrees(Math.atan2(dz, dx)); // +x east, +z south
        int sector = (int) Math.floorMod(Math.round(deg / 45.0), 8L);
        return DIRS[sector];
    }

    public static List<Component> pages(ServerPlayer p) {
        List<Component> pages = new ArrayList<>();
        for (int i = 0; i <= 4; i++) pages.add(Component.translatable("murim.spawn_book.page_" + i));
        String dir = direction(p);
        pages.add(dir == null ? Component.translatable("murim.spawn_book.page_5_unknown")
                : Component.translatable("murim.spawn_book.page_5", Component.translatable("murim.spawn_book.dir." + dir)));
        pages.add(Component.translatable("murim.spawn_book.page_6"));
        return pages;
    }

    public static void open(ServerPlayer p) {
        TextBooks.open(p, Component.translatable(TITLE), pages(p));
    }
}
