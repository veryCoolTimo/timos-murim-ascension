package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Optional;

/**
 * Обмен заслуг (docs/design/23-mount-hua-sect.md §6.2, «Что осталось от секты» п. 1): заслуги тратятся у старейшины
 * Хён Ёна (казна: меч, страница, пропуск в Зал писаний, серебро) и у лекаря Ун Гака (пилюли). Тратится остаток
 * ({@link SectState#merit()}), сумма за всё время и положение не падают. Магазин — варианты разговора (действие
 * {@code merit_buy}, цена подставляется в текст варианта), отдельного экрана нет.
 *
 * <p>Цены — от курса пожертвований управляющему (docs/design/29-economy.md: 5 лян серебра = 2 заслуги, то есть
 * заслуга ≈ 2,5 ляна) и от заработка прилежного ученика (строй 2 + занятие 1 + подъём 1 + полный день 1 ≈ 5 в день,
 * пожертвования — до 6 в день). Пилюля Снежной Сливы у торговца — 7 лян ≈ 3 заслуги: в секте — 4 (своим не дешевле
 * рынка, зато без похода). Меч Хуашань у торговца не продаётся: 20 заслуг — четыре прилежных дня. Серебро обратно —
 * 2 заслуги за 3 ляна, вдвое хуже пожертвования: арбитража нет. Таблица — здесь и только здесь; тест {@code MeritShopTest}.
 *
 * <p>Роба с цветом швов по положению — не сделана: своего предмета-робы и её модели в моде нет (нужна модель автора).
 */
public final class MeritShop {

    /**
     * Строка обмена.
     *
     * @param id     id для диалога ({@code merit_buy}) и lang-ключ варианта {@code dialogue.murim.merit.<id>}
     * @param item   предмет ({@code murim:huashan_sword}) или пусто — покупка флага
     * @param count  сколько
     * @param price  цена в заслугах
     * @param need   положение не ниже
     * @param seller кто меняет (ключ человека секты)
     * @param extra  книга страницы ({@code murim:seven_plum_blossoms}) или флаг-пропуск ({@code pass.scriptures})
     */
    public record Offer(String id, String item, int count, int price, SectStanding need, String seller, String extra) {
    }

    public static final List<Offer> OFFERS = List.of(
            new Offer("sword", "murim:huashan_sword", 1, 20, SectStanding.DISCIPLE, "tae_gyun", ""),
            new Offer("page", "murim:manual_page", 1, 8, SectStanding.DISCIPLE, "tae_gyun", "murim:seven_plum_blossoms"),
            new Offer("scriptures", "", 0, 12, SectStanding.DISCIPLE, "tae_gyun", "pass.scriptures"),
            new Offer("silver", "murim:silver_tael", 3, 2, SectStanding.NOVICE, "tae_gyun", ""),
            new Offer("snow_plum", "murim:pill_snow_plum", 1, 4, SectStanding.NOVICE, "gyeong_cho", ""),
            new Offer("origin_energy", "murim:pill_origin_energy", 1, 15, SectStanding.GRADUATE, "gyeong_cho", ""));

    /** Исход обмена. */
    public enum Result {
        OK, UNKNOWN, OUTSIDER, STANDING, MERIT, OWNED
    }

    private MeritShop() {
    }

    public static Optional<Offer> offer(String id) {
        return OFFERS.stream().filter(o -> o.id().equals(id)).findFirst();
    }

    /** Можно ли купить: чистая проверка (юнит-тест). */
    public static Result check(SectState s, SectStanding standing, Offer o) {
        if (!s.member()) {
            return Result.OUTSIDER;
        }
        if (!standing.atLeast(o.need())) {
            return Result.STANDING;
        }
        if (o.item().isEmpty() && s.has(o.extra())) {
            return Result.OWNED;
        }
        return s.merit() < o.price() ? Result.MERIT : Result.OK;
    }

    /** Купить: снять заслуги, выдать предмет (или флаг), сказать игроку. */
    public static Result buy(ServerPlayer p, String id) {
        Optional<Offer> found = offer(id);
        if (found.isEmpty()) {
            MurimMod.LOGGER.warn("Обмен заслуг: нет строки {}", id);
            return Result.UNKNOWN;
        }
        Offer o = found.get();
        SectState s = p.getData(ModAttachments.SECT);
        Result r = check(s, SectService.standing(p), o);
        if (r != Result.OK) {
            p.displayClientMessage(Component.translatable("murim.sect.merit." + r.name().toLowerCase(java.util.Locale.ROOT),
                    o.price(), s.merit(), Component.translatable(o.need().nameKey())).withStyle(ChatFormatting.GRAY), false);
            return r;
        }
        SectState next = s.spend(o.price());
        if (o.item().isEmpty()) {
            next = next.with(o.extra());
        }
        p.setData(ModAttachments.SECT, next);
        Component what;
        if (o.item().isEmpty()) {
            what = Component.translatable("dialogue.murim.merit.got." + o.id());
        } else {
            ItemStack stack = stack(o);
            what = stack.getHoverName();
            if (!p.getInventory().add(stack)) {
                p.drop(stack, false);
            }
        }
        p.displayClientMessage(Component.translatable("murim.sect.merit.bought", what, o.price(), next.merit())
                .withStyle(ChatFormatting.GOLD), false);
        p.level().playSound(null, p.blockPosition(), io.github.verycooltimo.murim.registry.ModSounds.COIN_CLINK.get(), SoundSource.NEUTRAL, 0.6F, 1.0F);
        MurimMod.LOGGER.info("Обмен заслуг: {} — {} за {} (осталось {})", p.getName().getString(), o.id(), o.price(), next.merit());
        return Result.OK;
    }

    /** Предмет строки: страница — с книгой (компонент техники, как у торговца). */
    static ItemStack stack(Offer o) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(o.item()));
        if (item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item, o.count());
        if (!o.extra().isEmpty() && "murim:manual_page".equals(o.item())) {
            stack.set(ModDataComponents.TECHNIQUE.get(), ResourceLocation.parse(o.extra()));
        }
        return stack;
    }
}
