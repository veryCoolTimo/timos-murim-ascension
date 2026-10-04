package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.technique.Styles;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Обрывки манускриптов и дубликаты книг на сервере (docs/design/24-bandit-camp.md §3). Решение —
 * {@link PageRules}; здесь стопка в руке, освоение игрока, сообщения и звук.
 *
 * <p>Страница принадлежит книге: книге стиля (Семь Цветков, шаги) или отдельной техники. Всё, что
 * книга умеет учить, — её «техники»; знакома книга, если игрок знает хоть одну из них.
 */
public final class PageService {

    /** Все техники, которым учит книга {@code book}: формы стиля и его основа или сама техника. */
    public static List<ResourceLocation> teaches(ResourceLocation book) {
        Optional<Styles.Style> style = Styles.of(book);
        if (style.isEmpty()) {
            return List.of(book);
        }
        List<ResourceLocation> out = new ArrayList<>(style.get().forms());
        style.get().basic().ifPresent(out::add);
        return out;
    }

    /**
     * Фронтир книги: последняя выученная форма по порядку стиля (её сейчас и осваивают), иначе
     * основа стиля, иначе ничего.
     */
    public static Optional<ResourceLocation> frontier(MasteryState state, ResourceLocation book) {
        Optional<Styles.Style> style = Styles.of(book);
        if (style.isEmpty()) {
            return state.knows(book) ? Optional.of(book) : Optional.empty();
        }
        List<ResourceLocation> forms = style.get().forms();
        for (int i = forms.size() - 1; i >= 0; i--) {
            if (state.knows(forms.get(i))) {
                return Optional.of(forms.get(i));
            }
        }
        return style.get().basic().filter(state::knows);
    }

    /** Название книги: стиль — по стилю, иначе по технике (так же называется манускрипт). */
    public static Component bookName(ResourceLocation book) {
        Optional<Styles.Style> style = Styles.of(book);
        return style.isPresent() ? Component.translatable(style.get().nameKey()) : MasteryService.name(book);
    }

    /** Прочитать страницу из стопки в руке. */
    public static void readPage(ServerPlayer player, ItemStack stack) {
        ResourceLocation book = stack.get(ModDataComponents.TECHNIQUE.get());
        if (book == null || TechniqueLoader.get(book) == null) {
            return;
        }
        MasteryState state = player.getData(ModAttachments.MASTERY);
        Optional<ResourceLocation> front = frontier(state, book);
        boolean anyShallow = false;
        for (ResourceLocation t : teaches(book)) {
            TechniqueProgress p = state.techniques().get(t);
            TechniqueDefinition d = TechniqueLoader.get(t);
            if (p != null && d != null && p.cap() < d.layers()) {
                anyShallow = true;
            }
        }
        TechniqueProgress fp = front.map(f -> state.techniques().get(f)).orElse(null);
        TechniqueDefinition fd = front.map(TechniqueLoader::get).orElse(null);
        PageRules.Outcome outcome = PageRules.page(front.isPresent(), stack.getCount(),
                fp == null ? 0 : fp.cap(), fd == null ? 0 : fd.layers(), fp == null ? 0 : fp.layer(), anyShallow);
        Component name = bookName(book);
        switch (outcome) {
            case NEED_MORE -> {
                message(player, "murim.page.need_more", ChatFormatting.GRAY, name, stack.getCount(), PageRules.PAGES_TO_BIND);
                return;
            }
            case BIND -> {
                stack.shrink(PageRules.PAGES_TO_BIND);
                ItemStack manual = new ItemStack(ModItems.TECHNIQUE_MANUAL.get());
                manual.set(ModDataComponents.TECHNIQUE.get(), book);
                manual.set(ModDataComponents.MANUAL_DEPTH.get(), PageRules.BOUND_DEPTH);
                if (!player.getInventory().add(manual)) {
                    player.drop(manual, false);
                }
                message(player, "murim.page.bound", ChatFormatting.GOLD, name, PageRules.BOUND_DEPTH);
                sound(player, 0.7F);
            }
            case DEEPEN -> {
                for (ResourceLocation t : teaches(book)) {
                    TechniqueProgress p = player.getData(ModAttachments.MASTERY).techniques().get(t);
                    TechniqueDefinition d = TechniqueLoader.get(t);
                    if (p != null && d != null && p.cap() < d.layers()) {
                        MasteryService.deepen(player, t, PageRules.deepen(p.cap(), d.layers()));
                    }
                }
                stack.shrink(1);
                TechniqueProgress after = player.getData(ModAttachments.MASTERY).techniques().get(front.get());
                message(player, "murim.page.deepened", ChatFormatting.AQUA, name, after == null ? 0 : after.cap());
                sound(player, 1.0F);
            }
            case INSIGHT -> {
                stack.shrink(1);
                message(player, "murim.page.insight", ChatFormatting.AQUA, name);
                MasteryService.study(player, front.get(), PageRules.insight(fp.layer(), PageRules.PAGE_INSIGHT));
                sound(player, 1.2F);
            }
            case WISDOM -> {
                stack.shrink(1);
                MasteryService.addWisdom(player, PageRules.PAGE_WISDOM);
                message(player, "murim.page.wisdom", ChatFormatting.GRAY, name);
                sound(player, 1.2F);
            }
        }
    }

    /**
     * Знакомая книга, которой нечему научить: всё, чему она учит, выучено и предел не ниже
     * её глубины ({@code depth <= 0} — полная).
     */
    public static boolean nothingToTeach(MasteryState state, ResourceLocation book, int depth) {
        for (ResourceLocation t : teaches(book)) {
            TechniqueDefinition d = TechniqueLoader.get(t);
            if (d == null) {
                continue;
            }
            TechniqueProgress p = state.techniques().get(t);
            int bookCap = depth <= 0 ? d.layers() : Math.min(depth, d.layers());
            if (p == null || p.cap() < bookCap) {
                return false;
            }
        }
        return true;
    }

    /** Перечитать знакомую книгу: книга истрёпывается, игрок получает озарение или мудрость. */
    public static void reread(ServerPlayer player, ItemStack stack, ResourceLocation book) {
        MasteryState state = player.getData(ModAttachments.MASTERY);
        Optional<ResourceLocation> front = frontier(state, book);
        if (front.isEmpty()) {
            return;
        }
        TechniqueProgress p = state.techniques().get(front.get());
        Component name = bookName(book);
        stack.shrink(1);
        if (PageRules.reread(p.cap(), p.layer()) == PageRules.Outcome.INSIGHT) {
            message(player, "murim.page.reread", ChatFormatting.AQUA, name);
            MasteryService.study(player, front.get(), PageRules.insight(p.layer(), PageRules.BOOK_INSIGHT));
        } else {
            MasteryService.addWisdom(player, PageRules.BOOK_WISDOM);
            message(player, "murim.page.reread_wisdom", ChatFormatting.GRAY, name);
        }
        sound(player, 0.9F);
    }

    private static void sound(ServerPlayer player, float pitch) {
        player.level().playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0F, pitch);
    }

    private static void message(ServerPlayer player, String key, ChatFormatting style, Object... args) {
        player.displayClientMessage(Component.translatable(key, args).withStyle(style), true);
    }

    private PageService() {
    }
}
