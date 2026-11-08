package io.github.verycooltimo.murim.client.decor;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.PillBoxMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * The pill box menu: six compartments of red silk in a row, a black lacquer frame with a gold edge, the player's inventory below. The
 * background is one texture (textures/gui/pill_box.png, tools/art/pill_art/box_menu_texture.py); the slots sit where
 * {@link PillBoxMenu} puts them.
 */
public final class PillBoxScreen extends AbstractContainerScreen<PillBoxMenu> {

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/gui/pill_box.png");
    private static final int GOLD = 0xE6C45E;
    private static final int PALE = 0xB9A67A;

    public PillBoxScreen(PillBoxMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = PillBoxMenu.WIDTH;
        imageHeight = PillBoxMenu.HEIGHT;
        titleLabelX = 10;
        titleLabelY = 7;
        inventoryLabelX = 8;
        inventoryLabelY = 58;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);
        if (menu.isCrate()) {
            graphics.fill(leftPos + 5, topPos + 18, leftPos + 171, topPos + 55, 0xFF382B23);
            for (int i = 0; i < 18; i++) {
                int x = leftPos + 7 + i % 9 * 18, y = topPos + 19 + i / 9 * 18;
                graphics.fill(x, y, x + 18, y + 18, 0xFF9D8060);
                graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF5B4437);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        drawFittedLabel(graphics, title, titleLabelX, titleLabelY, GOLD);
        drawFittedLabel(graphics, playerInventoryTitle, inventoryLabelX, inventoryLabelY, PALE);
    }

    /** Measure in GUI coordinates, so the same inner margin holds at every GUI scale and for custom names. */
    private void drawFittedLabel(GuiGraphics graphics, Component text, int x, int y, int color) {
        int available = imageWidth - 10 - x;
        float scale = Math.min(1.0F, (float) available / Math.max(1, font.width(text)));
        // API: client/sect/SectTabletScreen.java uses the same measured text and pose scaling.
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.pose().popPose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        var hovered = getSlotUnderMouse();
        if (hovered != null && hovered.container == menu.box() && hovered.hasItem()) {
            int count = 0;
            for (int i = 0; i < menu.box().getContainerSize(); i++) {
                var stack = menu.box().getItem(i);
                if (io.github.verycooltimo.murim.decor.PillPouch.sameKind(stack, hovered.getItem())) count += stack.getCount();
            }
            graphics.renderComponentTooltip(font, java.util.List.of(hovered.getItem().getHoverName(),
                    Component.translatable("menu.murim.pills.kind_count", count)), mouseX, mouseY);
        } else {
            renderTooltip(graphics, mouseX, mouseY);
        }
    }
}
