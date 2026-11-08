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
                for (var uniform : ModUniforms.ALL) {
                    for (var piece : uniform.pieces()) out.accept(piece.get());
                }
                // Враги этапа M1 (автор 03.10: «яйцо призыва во вкладке Мурим»).
                out.accept(new ItemStack(ModItems.BANDIT_SWORDSMAN_SPAWN_EGG.get()));
                out.accept(new ItemStack(ModItems.BANDIT_ARCHER_SPAWN_EGG.get()));
                // Кинжал клана Тан (модель автора, 03.10).
                out.accept(new ItemStack(ModItems.TANG_DAGGER.get()));
                out.accept(new ItemStack(ModItems.HUASHAN_SWORD.get()));
                out.accept(new ItemStack(ModItems.PLUM_GUARD_SWORD.get()));
                out.accept(new ItemStack(ModItems.WOODEN_SWORD.get()));
                out.accept(new ItemStack(ModItems.JADE_SECT_TABLET.get()));
                out.accept(new ItemStack(ModItems.MEAL_BOWL.get()));
                out.accept(new ItemStack(ModItems.COLD_MEAL_BOWL.get()));
                // The Mount Hua kitchen (author 18.10).
                for (io.github.verycooltimo.murim.sect.SectMenu.Dish dish : io.github.verycooltimo.murim.sect.SectMenu.Dish.values()) {
                    out.accept(new ItemStack(ModItems.dishItem(dish)));
                }
                out.accept(new ItemStack(ModItems.HUNDRED_GINSENG.get()));
                out.accept(new ItemStack(ModItems.PURPLE_GINSENG.get()));
                out.accept(new ItemStack(ModItems.PURPLE_WOOD_HERB.get()));
                out.accept(new ItemStack(ModItems.ICE_CRYSTAL.get()));
                // Пилюли (M5, docs/design/19b).
                // by tier: common, middle, high
                out.accept(new ItemStack(ModItems.PILL_VITALITY.get()));
                out.accept(new ItemStack(ModItems.PILL_FASTING.get()));
                out.accept(new ItemStack(ModItems.PILL_ANTIDOTE.get()));
                out.accept(new ItemStack(ModItems.PILL_SNOW_PLUM.get()));
                out.accept(new ItemStack(ModItems.PILL_PLUM_BLOSSOM.get()));
                out.accept(new ItemStack(ModItems.PILL_BLOOD_REPLENISHING.get()));
                out.accept(new ItemStack(ModItems.PILL_QUICK_RECOVERY.get()));
                out.accept(new ItemStack(ModItems.BLACK_LINGZHI.get()));
                out.accept(new ItemStack(ModItems.PILL_PURPLE_HEAVEN.get()));
                out.accept(new ItemStack(ModItems.PILL_SMALL_RESTORATION.get()));
                out.accept(new ItemStack(ModItems.PILL_HEAVENLY_EARTH.get()));
                out.accept(new ItemStack(ModItems.PILL_MOONLIGHT.get()));
                out.accept(new ItemStack(ModItems.PILL_ASCENDING_DRAGON.get()));
                out.accept(new ItemStack(ModItems.PILL_GREAT_RESTORATION.get()));
                out.accept(new ItemStack(ModItems.PILL_ORIGIN_ENERGY.get()));
                out.accept(new ItemStack(ModItems.BEAUTY_TEAR.get()));
                if (io.github.verycooltimo.murim.world.PlaceService.enabled) {
                    out.accept(new ItemStack(io.github.verycooltimo.murim.world.ModWorld.SPIRIT_VEIN_ITEM.get()));
                }
                out.accept(new ItemStack(ModItems.SECT_DISCIPLE_SPAWN_EGG.get()));
                // Red timber building kit (docs/design/33-building-kit.md).
                out.accept(new ItemStack(ModBlocks.RED_PILLAR.get()));
                out.accept(new ItemStack(ModBlocks.WHITE_PILLAR.get()));
                out.accept(new ItemStack(ModBlocks.RED_BEAM.get()));
                out.accept(new ItemStack(ModBlocks.RED_LINTEL.get()));
                out.accept(new ItemStack(ModBlocks.SIMPLE_BRACKET.get()));
                out.accept(new ItemStack(ModBlocks.BEAM_BRACKET.get()));
                out.accept(new ItemStack(ModBlocks.HANGING_POST.get()));
                out.accept(new ItemStack(ModBlocks.BEAM_STUB.get()));
                out.accept(new ItemStack(ModBlocks.PLUM_PLANKS.get()));
                out.accept(new ItemStack(ModBlocks.PLUM_STAIRS.get()));
                out.accept(new ItemStack(ModBlocks.PLUM_SLAB.get()));
                out.accept(new ItemStack(ModBlocks.TIMBER_DIVIDER.get()));
                out.accept(new ItemStack(ModBlocks.PLUM_LOG.get()));
                out.accept(new ItemStack(ModBlocks.PLUM_WOOD.get()));
                out.accept(new ItemStack(ModBlocks.STRIPPED_PLUM_LOG.get()));
                // Mount Hua blocks: balustrade, granite set, climbing blocks, plum blossoms (author 06.10: «не могу найти»).
                for (var b : java.util.List.of(ModBlocks.HUA_BALUSTRADE, ModBlocks.HUA_GRANITE, ModBlocks.HUA_GRANITE_PALE,
                        ModBlocks.HUA_GRANITE_STAINED, ModBlocks.HUA_GRANITE_CRACKED, ModBlocks.HUA_GRANITE_DARK,
                        ModBlocks.HUA_GRANITE_MOSSY, ModBlocks.POLISHED_HUA_GRANITE, ModBlocks.POLISHED_HUA_GRANITE_STAIRS,
                        ModBlocks.POLISHED_HUA_GRANITE_SLAB, io.github.verycooltimo.murim.world.hua.HuaGround.POLISHED_HUA_GRANITE_LAYER, ModBlocks.POLISHED_HUA_GRANITE_WALL, ModBlocks.HUA_LITTER,
                        ModBlocks.CLIMBING_MOSS, ModBlocks.ROCK_HANDHOLD, ModBlocks.OLD_ROPE, ModBlocks.PLUM_BLOSSOM,
                        ModBlocks.PLUM_BLOSSOM_PALE, ModBlocks.PLUM_BLOSSOM_RED, ModBlocks.PLUM_SAPLING,
                        ModBlocks.CLIFF_PLUM_SAPLING, ModBlocks.YOUNG_PLUM_SAPLING)) {
                    out.accept(new ItemStack(b.get()));
                }
            })
            .build());

    private static ItemStack manual(ResourceLocation technique) {
        ItemStack s = new ItemStack(technique.equals(io.github.verycooltimo.murim.technique.JunkArts.HEAVENLY_DRAGON)
                ? ModItems.JUNK_HEAVENLY_DRAGON_MANUAL.get()
                : technique.equals(io.github.verycooltimo.murim.technique.JunkArts.THOUSAND_CRANES)
                ? ModItems.JUNK_THOUSAND_CRANES_MANUAL.get()
                : technique.equals(io.github.verycooltimo.murim.technique.JunkArts.BEGGARS_TAP)
                ? ModItems.JUNK_BEGGARS_TAP_MANUAL.get() : ModItems.TECHNIQUE_MANUAL.get());
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
            if (!Styles.starGated(style) && TechniqueLoader.get(first) != null) {
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
