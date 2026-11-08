package io.github.verycooltimo.murim.item;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.MethodLoader;
import io.github.verycooltimo.murim.library.JunkBook;
import io.github.verycooltimo.murim.library.JunkBookItem;
import io.github.verycooltimo.murim.library.JunkFactory;
import io.github.verycooltimo.murim.library.JunkKind;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Item names, resolved against both shipped locales without installing a client Language singleton. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class ManualCopyGameTests {
    // API: minecraft-src/net/minecraft/network/chat/{Component,contents/TranslatableContents}.java.
    private static String resolve(Component component, JsonObject lang) {
        String text;
        if (component.getContents() instanceof TranslatableContents translation) {
            Object[] args = Arrays.stream(translation.getArgs())
                    .map(arg -> arg instanceof Component nested ? resolve(nested, lang) : arg).toArray();
            text = String.format(Locale.ROOT, lang.get(translation.getKey()).getAsString(), args);
        } else {
            text = component.getString();
        }
        for (Component sibling : component.getSiblings()) text += resolve(sibling, lang);
        return text;
    }

    private static void copy(GameTestHelper helper, ItemStack stack, JsonObject lang, String suffix) {
        // API: minecraft-src/net/minecraft/world/item/ItemStack.java#getHoverName (custom/item names win).
        helper.assertTrue(resolve(stack.getHoverName(), lang).endsWith(suffix), "missing copy name: " + stack);
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void allOrdinaryManualsAreCopiesAndOriginalNamesSurvive(GameTestHelper helper) throws Exception {
        for (String locale : new String[]{"en_us", "ru_ru"}) {
            String suffix = locale.equals("en_us") ? "(copy)" : "(копия)";
            String original = locale.equals("en_us") ? "Custom archive manual" : "Особый архивный манускрипт";
            try (var stream = ManualCopyGameTests.class.getResourceAsStream("/assets/murim/lang/" + locale + ".json")) {
                helper.assertTrue(stream != null, "missing locale " + locale);
                JsonObject lang = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                // Includes dedicated manuals with default technique components, generic blanks, and all scrolls.
                for (var item : BuiltInRegistries.ITEM) {
                    if (item instanceof TechniqueManualItem || item instanceof MethodScrollItem || item instanceof JunkBookItem) {
                        copy(helper, new ItemStack(item), lang, suffix);
                    }
                }
                for (var id : TechniqueLoader.all().keySet()) {
                    ItemStack stack = new ItemStack(ModItems.TECHNIQUE_MANUAL.get());
                    stack.set(ModDataComponents.TECHNIQUE.get(), id);
                    copy(helper, stack, lang, suffix);
                    stack.set(DataComponents.CUSTOM_NAME, Component.literal(original));
                    helper.assertTrue(resolve(stack.getHoverName(), lang).equals(original), "original manual renamed");
                }
                for (var id : MethodLoader.all().keySet()) {
                    ItemStack stack = new ItemStack(ModItems.METHOD_SCROLL.get());
                    stack.set(ModDataComponents.METHOD.get(), id);
                    copy(helper, stack, lang, suffix);
                    stack.set(DataComponents.ITEM_NAME, Component.literal(original));
                    helper.assertTrue(resolve(stack.getHoverName(), lang).equals(original), "original scroll renamed");
                }
                String originalSuffix = locale.equals("en_us") ? "(original)" : "(подлинник)";
                for (boolean method : new boolean[]{false, true}) {
                    ItemStack stack = new ItemStack(method ? ModItems.METHOD_SCROLL.get() : ModItems.TECHNIQUE_MANUAL.get());
                    stack.set(method ? ModDataComponents.METHOD.get() : ModDataComponents.TECHNIQUE.get(),
                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim",
                                    method ? "six_harmonies" : "seven_plum_blossoms"));
                    String copyName = resolve(stack.getHoverName(), lang);
                    helper.assertTrue(copyName.endsWith(suffix), "copy suffix missing");
                    stack.set(ModDataComponents.ORIGINAL.get(), true);
                    String originalName = resolve(stack.getHoverName(), lang);
                    helper.assertTrue(originalName.endsWith(originalSuffix) && !originalName.contains(suffix),
                            "original has wrong suffix: " + originalName);
                    helper.assertTrue(originalName.equals(copyName.substring(0, copyName.length() - suffix.length()) + originalSuffix),
                            "original and copy base names differ");
                }
                for (JunkKind kind : JunkKind.values()) {
                    if (!kind.letters()) {
                        for (long seed = 0; seed < 32; seed++) copy(helper, JunkFactory.stack(new JunkBook(kind, seed)), lang, suffix);
                    }
                }
            }
        }
        helper.succeed();
    }

    private ManualCopyGameTests() {}
}
