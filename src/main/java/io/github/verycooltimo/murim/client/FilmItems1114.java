package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.item.TechniqueCovers;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Gallery only (art review 11.11 proof): every technique manual cover, the new item textures and the Tang coin sheet in one grid. */
final class FilmItems1114 extends Screen {
    private static final List<String> OTHER = List.of("cracked_bowl", "empty_wine_jar", "worn_practice_sword", "red_vein_fern_100",
            "silver_vein_mugwort_100", "hua_litter");

    private final List<ItemStack> stacks = new ArrayList<>();
    private final boolean herbs;

    /** Herbs scene: the approved pills in the first row, then every herb and ingredient item (author 11.14: they looked strange). */
    private static final List<String> HERBS = List.of("pill_vitality", "pill_blood_replenishing", "pill_blue", "pill_snow_plum",
            "pill_thousand_poison", "pill_quick_recovery", "pill_antidote", "pill_fasting",
            "hundred_ginseng", "purple_ginseng", "thousand_ginseng", "purple_wood_herb", "ice_crystal", "black_lingzhi",
            "thousand_lingzhi", "beauty_tear", "red_vein_fern_100", "silver_vein_mugwort_100");

    FilmItems1114() {
        this(false);
    }

    FilmItems1114(boolean herbs) {
        super(Component.empty());
        this.herbs = herbs;
        if (herbs) {
            for (String id : HERBS) {
                stacks.add(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, id))));
            }
            return;
        }
        for (String art : TechniqueCovers.IDS) {
            ItemStack stack = new ItemStack(ModItems.TECHNIQUE_MANUAL.get());
            stack.set(ModDataComponents.TECHNIQUE.get(), ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, art));
            stacks.add(stack);
        }
        for (String id : OTHER) {
            stacks.add(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, id))));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xFF2A2724);
        int cell = 44;
        int cols = herbs ? 8 : Math.max(1, (width - 40) / cell);
        int x0 = 20, y0 = 20;
        for (int i = 0; i < stacks.size(); i++) {
            int x = x0 + (i % cols) * cell, y = y0 + (i / cols) * cell;
            g.fill(x, y, x + cell - 4, y + cell - 4, 0xFF3C3833);
            g.pose().pushPose();
            g.pose().translate(x + 2, y + 2, 0);
            g.pose().scale(2.0F, 2.0F, 1.0F);
            g.renderItem(stacks.get(i), 0, 0);
            g.pose().popPose();
        }
        if (herbs) {
            return;
        }
        // The Tang coin is an entity texture sheet (64x64): show it beside the grid, face region 48x48.
        int rows = (stacks.size() + cols - 1) / cols;
        ResourceLocation coin = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/item/tang_coin.png");
        g.blit(coin, x0, y0 + rows * cell + 8, 96, 96, 0.0F, 0.0F, 48, 48, 64, 64);
        g.blit(coin, x0 + 110, y0 + rows * cell + 8, 48, 48, 0.0F, 0.0F, 48, 48, 64, 64);
        g.blit(coin, x0 + 170, y0 + rows * cell + 8, 24, 24, 0.0F, 0.0F, 48, 48, 64, 64);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
