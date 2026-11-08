package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Gallery only: every Murim achievement on one sheet (icon, title in the frame colour, description), in tree order from the
 * root. Read from the integrated server, so nothing has to be granted and the hidden ones show too.
 * API: decompiled 1.21.1 sources (neoformruntime sourcesAndCompiledWithNeoForge jar) - ServerAdvancementManager#getAllAdvancements,
 * Advancement#parent/#display, DisplayInfo#getIcon/#getTitle/#getDescription/#getType.
 */
final class FilmAdvancements extends Screen {
    FilmAdvancements() { super(Component.empty()); }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xF0181818);
        var server = minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }
        List<AdvancementHolder> all = server.getAdvancements().getAllAdvancements().stream()
                .filter(h -> MurimMod.MODID.equals(h.id().getNamespace()) && h.value().display().isPresent())
                .sorted(Comparator.comparing(h -> h.id().getPath())).toList();
        Map<ResourceLocation, List<AdvancementHolder>> children = all.stream().filter(h -> h.value().parent().isPresent())
                .collect(Collectors.groupingBy(h -> h.value().parent().get()));
        List<AdvancementHolder> ordered = new ArrayList<>();
        all.stream().filter(h -> h.value().parent().isEmpty()).forEach(h -> walk(h, children, ordered));
        int cols = 3;
        int colW = width / cols;
        int rowH = 30;
        for (int i = 0; i < ordered.size(); i++) {
            var display = ordered.get(i).value().display().get();
            int x = 8 + (i % cols) * colW;
            int y = 8 + (i / cols) * rowH;
            graphics.renderItem(display.getIcon(), x, y + 2);
            int colour = switch (display.getType()) {
                case CHALLENGE -> 0xFFD58CFF;
                case GOAL -> 0xFF8FD8FF;
                default -> 0xFFFFE070;
            };
            graphics.drawString(font, display.getTitle(), x + 20, y, colour);
            var lines = font.split(display.getDescription(), colW - 30);
            for (int l = 0; l < Math.min(2, lines.size()); l++) {
                graphics.drawString(font, lines.get(l), x + 20, y + 10 + l * 9, 0xFFBBBBBB);
            }
        }
    }

    private static void walk(AdvancementHolder at, Map<ResourceLocation, List<AdvancementHolder>> children, List<AdvancementHolder> out) {
        out.add(at);
        for (AdvancementHolder child : children.getOrDefault(at.id(), List.of())) {
            walk(child, children, out);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
}
