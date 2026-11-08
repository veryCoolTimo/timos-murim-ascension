package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.Arts;
import io.github.verycooltimo.murim.mastery.MasteryRules;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.PageRules;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Optional;

/**
 * Обмен заслуг (docs/design/23-mount-hua-sect.md §6.2, «Что осталось от секты» п. 1): заслуги тратятся у старейшины
 * Тэ Гюна (казна: меч, страница, пропуск в Зал писаний, серебро) и у лекаря Гён Чхо (пилюли). Тратится остаток
 * ({@link SectState#merit()}), сумма за всё время и положение не падают. Магазин — варианты разговора (действие
 * {@code merit_buy}, цена подставляется в текст варианта), отдельного экрана нет.
 *
 * <p>Цены (с 19.10 — в {@code merit_shop.json}) выбраны от курса пожертвований управляющему (docs/design/29-economy.md:
 * 5 лян серебра = 2 заслуги, то есть заслуга ≈ 2,5 ляна) и от заработка прилежного ученика (≈ 24 в сутки,
 * docs/design/41-economy-pacing-mvp.md). Пилюля Снежной Сливы у торговца — 7 лян ≈ 3 заслуги: в секте — 6 (своим не
 * дешевле рынка, зато без похода). Серебро обратно — 2 заслуги за 3 ляна, вдвое хуже пожертвования: арбитража нет.
 * Неравенства держит тест {@code MeritShopTest}.
 *
 * <p>Роба с цветом швов по положению — не сделана: своего предмета-робы и её модели в моде нет (нужна модель автора).
 *
 * <p>Каталог теперь в данных ({@code data/murim/sect/merit_shop.json}, {@link MeritCatalog}, docs/adr/0012): цены, личный
 * запас с пополнением по времени игры ({@link MeritStock}) и положение, с которого продаётся. Меч — раз в 20 минут игры,
 * пилюли — запас, пропуск в Зал писаний — один раз (дальше разделы открывает положение, второй платы нет). Мебели за
 * заслуги нет (автор 19.10: «это Майнкрафт»). Положение считается по сумме заработанного, покупки — из остатка.
 */
public final class MeritShop {

    /**
     * Личный запас строки: до {@code max} штук, одна штука возвращается за {@code minutes} минут времени игры игрока
     * ({@link MeritStock}); сон и смена суток запас не пополняют.
     */
    public record Refill(int max, int minutes) {
    }

    /**
     * Строка обмена.
     *
     * @param id     id для диалога ({@code merit_buy}) и lang-ключ варианта {@code dialogue.murim.merit.<id>}
     * @param item   предмет ({@code murim:huashan_sword}) или пусто — покупка флага
     * @param count  сколько
     * @param price  цена в заслугах
     * @param need   положение не ниже
     * @param seller кто меняет (ключ человека секты)
     * @param extra  книга страницы ({@code murim:seven_plum_blossoms}) или флаг ({@code pass.scriptures})
     * @param refill личный запас с пополнением по времени игры; {@code null} — без запаса
     * @param gate   имя возможности каталога ({@link MeritCatalog#feature}), без которой строка не продаётся; пусто — всегда
     */
    public record Offer(String id, String item, int count, int price, SectStanding need, String seller, String extra,
                        Refill refill, String gate) {
    }

    /** The shipped catalog (data/murim/sect/merit_shop.json). */
    public static final MeritCatalog CATALOG = MeritCatalog.shipped();

    /** Every row of the shipped catalog, listed or not (see {@link #listed}). */
    public static final List<Offer> OFFERS = CATALOG.offers();

    /** Исход обмена. */
    public enum Result {
        OK, UNKNOWN, OUTSIDER, STANDING, MERIT, OWNED, SUMMONED, LIMIT, STOCK
    }

    private MeritShop() {
    }

    public static Optional<Offer> offer(String id) {
        return CATALOG.offer(id);
    }

    /**
     * Is the row on sale at all? A row behind a feature that is off (the refining formulas) or whose item does not exist in
     * this build (the Quick Recovery pill before it is added) is unlisted: it is not offered, not bought, not shown.
     */
    public static boolean listed(Offer o, MeritCatalog catalog) {
        if (!o.gate().isEmpty() && !catalog.feature(o.gate())) {
            return false;
        }
        return o.item().isEmpty() || BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(o.item()));
    }

    public static boolean listed(Offer o) {
        return listed(o, CATALOG);
    }

    /** The row with this id when it is on sale (dialogue condition {@code shop}). */
    public static boolean listed(String id) {
        return offer(id).filter(MeritShop::listed).isPresent();
    }

    /**
     * The player's own time in the world, in ticks: the vanilla {@code play_time} statistic. It counts this player's server
     * ticks, so sleeping through the night or changing the day does not move it, and it survives death and reloads.
     * API: reference/minecraft-src/net/minecraft/world/entity/player/Player.java#tick (awardStat(Stats.PLAY_TIME)).
     */
    public static long playTicks(ServerPlayer p) {
        return p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
    }

    /** What is in stock of a row with a stock now; {@code null} for a row without one. */
    public static MeritStock.Level stock(ServerPlayer p, Offer o) {
        MeritStock.Level level = o.refill() == null ? null : MeritStock.level(p.getData(ModAttachments.SECT), o.id(), o.refill(), playTicks(p));
        if (level != null && soulVitality(o)) return new MeritStock.Level(Math.min(level.count(), elixirs(p).remaining()), level.clock());
        return level;
    }

    private static boolean soulVitality(Offer o) { return "murim:pill_origin_energy".equals(o.item()); }

    private static io.github.verycooltimo.murim.cultivation.GreatElixirStock elixirs(ServerPlayer p) {
        return io.github.verycooltimo.murim.cultivation.GreatElixirStock.get(p.serverLevel());
    }

    /** Whole minutes of play until the next unit of a stocked row comes back; 0 when full or without a stock. */
    public static int minutesToNext(ServerPlayer p, Offer o) {
        if (soulVitality(o) && elixirs(p).remaining() == 0) return 0;
        MeritStock.Level level = stock(p, o);
        return level == null ? 0 : MeritStock.minutesToNext(level, playTicks(p), o.refill());
    }

    /**
     * The stocked rows the tablet's Standing tab tells of: those on sale that this standing may buy, with what is in stock
     * and the minutes of play until the next unit (docs/adr/0012).
     */
    public static List<io.github.verycooltimo.murim.network.SectTabletData.Shop> tabletLines(ServerPlayer p, SectStanding standing) {
        List<io.github.verycooltimo.murim.network.SectTabletData.Shop> out = new java.util.ArrayList<>();
        for (Offer o : OFFERS) {
            if (o.refill() == null || !listed(o) || !standing.atLeast(o.need()) || o.item().isEmpty()) {
                continue;
            }
            MeritStock.Level level = stock(p, o);
            // A short name for the tablet (the item's own name is long: «Snow Plum Blossom Pill»); the key is per row id.
            out.add(new io.github.verycooltimo.murim.network.SectTabletData.Shop(Component.translatable("murim.tablet.shop." + o.id()),
                    level.count(), o.refill().max(), minutesToNext(p, o)));
        }
        return out;
    }

    /**
     * The stock told after the price in a dialogue option: nothing for a row without a stock or a full single unit; the
     * count left for a larger stock; the wait in minutes when it is empty.
     */
    public static Component stockSuffix(ServerPlayer p, Offer o) {
        if (soulVitality(o) && elixirs(p).remaining() == 0) return Component.translatable("murim.pill.stock.exhausted");
        MeritStock.Level level = stock(p, o);
        if (level == null) {
            return Component.empty();
        }
        if (level.count() == 0) {
            return Component.translatable("murim.sect.merit.stock.none", minutesToNext(p, o));
        }
        return o.refill().max() > 1 ? Component.translatable("murim.sect.merit.stock.left", level.count()) : Component.empty();
    }

    /**
     * What the offer costs this player. A page costs {@code 8 + 2 s} merit, where {@code s} is the star the page leads to
     * (author 18.10: 10 / 20 / 32 at ★1 / ★6 / ★12), so a page for a late star is dear and merit cannot buy a star
     * cheaply; every other offer has its fixed price.
     */
    public static int price(Offer o, MasteryState mastery) {
        if (!"page".equals(o.id()) || o.extra().isEmpty()) {
            return o.price();
        }
        ResourceLocation book = ResourceLocation.parse(o.extra());
        TechniqueProgress progress = mastery.progress(book);
        int target = progress == null ? 1 : Math.min(Arts.cap(book), progress.stars() + 1);
        return PageRules.pagePrice(target);
    }

    /** The price of the offer for this player right now. */
    public static int price(ServerPlayer p, Offer o) {
        return price(o, p.getData(ModAttachments.MASTERY));
    }

    /** Key of the purchases of pages of one art for one transition (the star the page leads to) in the sect journal. */
    static String pageKey(Offer o, MasteryState mastery) {
        ResourceLocation book = ResourceLocation.parse(o.extra());
        TechniqueProgress progress = mastery.progress(book);
        int target = progress == null ? 1 : Math.min(Arts.cap(book), progress.stars() + 1);
        return "shop.page." + Arts.masteryId(book).getPath() + "." + target;
    }

    /**
     * May the page still be sold? At most two pages are ever SOLD for one transition of one art (counted in the sect
     * journal, so a chest or the offhand do not give a third), and the help pool of the transition, shared with lessons,
     * readings and guidance, must still have room for the pages the player carries and one more.
     */
    public static boolean pageOpen(ServerPlayer p, Offer o) {
        if (!"page".equals(o.id()) || o.extra().isEmpty()) {
            return true;
        }
        MasteryState mastery = p.getData(ModAttachments.MASTERY);
        if (p.getData(ModAttachments.SECT).entry(pageKey(o, mastery), 0L) >= PageRules.MAX_PAGES_PER_TRANSITION) {
            return false;
        }
        ResourceLocation book = ResourceLocation.parse(o.extra());
        TechniqueProgress progress = mastery.progress(book);
        if (progress == null) {
            return true;
        }
        // A mastered art has no star left to lead to: a page would cost the top price for a trifle of wisdom.
        if (progress.stars() >= Math.min(progress.cap(), Arts.cap(book))) {
            return false;
        }
        int held = 0;
        java.util.List<ItemStack> carried = new java.util.ArrayList<>(p.getInventory().items);
        carried.addAll(p.getInventory().offhand);
        for (ItemStack stack : carried) {
            if (stack.is(ModItems.MANUAL_PAGE.get()) && book.equals(stack.get(ModDataComponents.TECHNIQUE.get()))) {
                held += stack.getCount();
            }
        }
        return PageRules.saleOpen(MasteryRules.helpRoom(progress, Arts.cap(book)),
                MasteryRules.need(progress.stars(), Arts.cap(book)), held);
    }

    /** Можно ли купить: чистая проверка (юнит-тест). */
    public static Result check(SectState s, SectStanding standing, Offer o) {
        return check(s, standing, o, o.price());
    }

    /** The same at a given price (a page's price depends on the star it leads to). */
    public static Result check(SectState s, SectStanding standing, Offer o, int price) {
        if (s.has(io.github.verycooltimo.murim.sect.seal.PenanceService.SUMMONED)) {
            return Result.SUMMONED;
        }
        if (!s.member()) {
            return Result.OUTSIDER;
        }
        if (!standing.atLeast(o.need())) {
            return Result.STANDING;
        }
        if (o.item().isEmpty() && s.has(o.extra())) {
            return Result.OWNED;
        }
        return s.merit() < price ? Result.MERIT : Result.OK;
    }

    /** Купить: снять заслуги, выдать предмет (или флаг), сказать игроку. */
    public static Result buy(ServerPlayer p, String id) {
        return buy(p, id, CATALOG);
    }

    /** The same against a given catalog (tests switch features on it). */
    public static Result buy(ServerPlayer p, String id, MeritCatalog catalog) {
        Optional<Offer> found = catalog.offer(id).filter(o -> listed(o, catalog));
        if (found.isEmpty()) {
            MurimMod.LOGGER.warn("Обмен заслуг: нет строки {}", id);
            PlayerJournal.refuse(p, Component.translatable("murim.sect.merit.unknown").withStyle(ChatFormatting.GRAY));
            return Result.UNKNOWN;
        }
        Offer o = found.get();
        SectState s = p.getData(ModAttachments.SECT);
        int price = price(p, o);
        long now = playTicks(p);
        Result r = check(s, SectService.standing(p), o, price);
        // Nothing left to sell is told before the lack of merit: the wait is the thing to know.
        if ((r == Result.OK || r == Result.MERIT) && o.refill() != null && MeritStock.level(s, o.id(), o.refill(), now).count() == 0) {
            r = Result.STOCK;
        }
        if ((r == Result.OK || r == Result.MERIT) && soulVitality(o) && elixirs(p).remaining() == 0) {
            PlayerJournal.refuse(p, Component.translatable("murim.pill.stock.exhausted"));
            return Result.STOCK;
        }
        if (r == Result.OK && !pageOpen(p, o)) {
            r = Result.LIMIT;
        }
        if (r != Result.OK) {
            PlayerJournal.refuse(p, Component.translatable("murim.sect.merit." + r.name().toLowerCase(java.util.Locale.ROOT),
                    price, s.merit(), Component.translatable(o.need().nameKey()), minutesToNext(p, o)).withStyle(ChatFormatting.GRAY));
            return r;
        }
        if (soulVitality(o) && !elixirs(p).issue()) {
            PlayerJournal.refuse(p, Component.translatable("murim.pill.stock.exhausted"));
            return Result.STOCK;
        }
        SectState next = s.spend(price);
        if (o.item().isEmpty()) {
            next = next.with(o.extra());
        } else if ("page".equals(o.id()) && !o.extra().isEmpty()) {
            String key = pageKey(o, p.getData(ModAttachments.MASTERY));
            next = next.record(key, next.entry(key, 0L) + 1L);
        }
        if (o.refill() != null) {
            next = MeritStock.take(next, o.id(), o.refill(), now);
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
        SectNotice.bar(p, Component.translatable("murim.sect.merit.bought", what, price, next.merit())
                .withStyle(ChatFormatting.GOLD));
        p.level().playSound(null, p.blockPosition(), io.github.verycooltimo.murim.registry.ModSounds.COIN_CLINK.get(), SoundSource.NEUTRAL, 0.6F, 1.0F);
        MurimMod.LOGGER.info("Обмен заслуг: {} — {} за {} (осталось {})", p.getName().getString(), o.id(), price, next.merit());
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
