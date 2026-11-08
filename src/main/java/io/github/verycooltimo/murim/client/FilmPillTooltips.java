package io.github.verycooltimo.murim.client;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import io.github.verycooltimo.murim.network.SyncProfilePayload;
import io.github.verycooltimo.murim.profile.DantianProfile;

/** Gallery only: vanilla tooltip collection/rendering, including the real pill rank rules. */
final class FilmPillTooltips extends Screen {
    /** {@code MURIM_GALLERY_PILLS=a,b,…} films other items (up to six cells). */
    private static final List<String> ITEMS = List.of(System.getenv().getOrDefault("MURIM_GALLERY_PILLS",
            "pill_vitality,pill_purple_heaven,black_lingzhi,thousand_lingzhi,pill_fasting,int_pill_box").split(","));
    /**
     * {@code MURIM_GALLERY_RANK}: unset - the old rank-1 sample profile; a number - a player of that rank; {@code own} - each
     * pill shown to a player of its own rank. Both grow the Six Harmonies seed (30) through real breakthroughs, so the walls
     * and the tear threshold are the ones of a played save.
     */
    private static final String RANK = System.getenv().getOrDefault("MURIM_GALLERY_RANK", "").trim();
    private static final double SEED = 30.0D;
    FilmPillTooltips() { super(Component.empty()); }
    private static DantianProfile profileFor(ItemStack stack) {
        var base = DantianProfile.INITIAL.withTags("calm", "none");
        if (RANK.isEmpty()) {
            return base.withRank(1);
        }
        int rank = !"own".equals(RANK) ? Integer.parseInt(RANK)
                : stack.getItem() instanceof io.github.verycooltimo.murim.item.PillItem pill ? pill.kind().rank() : 1;
        return base.withAxes(SEED, 0.5D, 0.5D).withRank(rank);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xF0181818);
        var before = ClientProfileState.profile();
        // Default rank 1: vitality is at rank, black lingzhi (rank 3) is two ranks above.
        try {
            for (int i = 0; i < ITEMS.size(); i++) {
                var stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("murim", ITEMS.get(i))));
                ClientProfileState.setProfile(new SyncProfilePayload(profileFor(stack)).profile());
                int x = 12 + (i % 3) * (width / 3);
                int y = 32 + (i / 3) * (height / 2);
                graphics.renderItem(stack, x, y - 24);
                // Wrap the real components within their cell; retain the name and all hover lines/styles.
                var lines = Screen.getTooltipFromItem(minecraft, stack).stream()
                        .flatMap(line -> font.split(line, width / 3 - 32).stream()).toList();
                graphics.renderTooltip(font, lines, x, y);
            }
        } finally {
            ClientProfileState.setProfile(before);
        }
    }
    @Override public boolean isPauseScreen() { return false; }
}
