package io.github.verycooltimo.murim.registry;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.MethodLoader;
import io.github.verycooltimo.murim.technique.Styles;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Вкладка мода в творческом режиме (автор 03.10): по книге-манускрипту на каждую технику —
 * сначала стили по порядку форм, затем остальные по уровню — и свитки всех методов.
 * Список берётся из определений, которые клиент получил от сервера.
 */
public final class ModCreativeTabs {

    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MurimMod.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MURIM = TABS.register("murim", () -> CreativeModeTab.builder()
            // API: reference/minecraft-src/net/minecraft/world/item/CreativeModeTab.java#builder/title/icon/displayItems
            .title(Component.translatable("itemGroup.murim"))
            .icon(() -> manual(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_blossoms")))
            .displayItems((params, out) -> {
                for (ResourceLocation id : ordered()) {
                    out.accept(manual(id));
                }
                List<ResourceLocation> methods = new ArrayList<>(MethodLoader.all().keySet());
                methods.sort(Comparator.comparing(ResourceLocation::toString));
                for (ResourceLocation m : methods) {
                    ItemStack s = new ItemStack(ModItems.METHOD_SCROLL.get());
                    s.set(ModDataComponents.METHOD.get(), m);
                    out.accept(s);
                }
                // Враги этапа M1 (автор 03.10: «яйцо призыва во вкладке Мурим»).
                out.accept(new ItemStack(ModItems.BANDIT_SWORDSMAN_SPAWN_EGG.get()));
                out.accept(new ItemStack(ModItems.BANDIT_ARCHER_SPAWN_EGG.get()));
                // Пилюли (M5, docs/design/19b).
                out.accept(new ItemStack(ModItems.PILL_SNOW_PLUM.get()));
                out.accept(new ItemStack(ModItems.PILL_ORIGIN_ENERGY.get()));
                out.accept(new ItemStack(ModItems.PILL_THOUSAND_POISON.get()));
                out.accept(new ItemStack(ModItems.BEAUTY_TEAR.get()));
                out.accept(new ItemStack(io.github.verycooltimo.murim.world.ModWorld.SPIRIT_VEIN_ITEM.get()));
            })
            .build());

    private static ItemStack manual(ResourceLocation technique) {
        ItemStack s = new ItemStack(ModItems.TECHNIQUE_MANUAL.get());
        s.set(ModDataComponents.TECHNIQUE.get(), technique);
        return s;
    }

    /** Стили (в порядке форм), потом остальные техники: по уровню, затем по id. */
    private static List<ResourceLocation> ordered() {
        List<ResourceLocation> out = new ArrayList<>();
        // Одна книга на стиль (автор 03.10): формы и основа ЛКМ — внутри неё.
        List<ResourceLocation> inStyles = new ArrayList<>();
        for (Styles.Style style : Styles.ALL) {
            ResourceLocation first = style.forms().get(0);
            if (TechniqueLoader.get(first) != null) {
                out.add(first);
            }
            inStyles.addAll(style.forms());
            style.basic().ifPresent(inStyles::add);
        }
        List<TechniqueDefinition> rest = new ArrayList<>(TechniqueLoader.all().values());
        rest.removeIf(d -> out.contains(d.id()) || inStyles.contains(d.id()));
        rest.sort(Comparator.comparing((TechniqueDefinition d) -> d.tier().ordinal()).thenComparing(d -> d.id().toString()));
        rest.forEach(d -> out.add(d.id()));
        return out;
    }

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }

    private ModCreativeTabs() {
    }
}
