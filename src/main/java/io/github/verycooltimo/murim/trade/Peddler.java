package io.github.verycooltimo.murim.trade;

import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.InteractGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.LookAtTradingPlayerGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.TradeWithPlayerGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Бродячий торговец (보부상) — первое, на что тратится серебро (docs/design/24-bandit-camp.md §7).
 * Торговля — ванильный экран торговца: продаёт пилюли, учебное оружие, еду и изредка страницу
 * манускрипта; скупает хлам из архива и страницы. Цены — {@link PeddlerStock}.
 *
 * <p>Два вида: ходит по деревням и через двое суток уходит; стоит лавкой у подножия Хуашань
 * (не уходит, сам код секты не трогает — {@link PeddlerSpawns}). Товар пополняется раз в игровые сутки.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/entity/npc/WanderingTrader.java (образец),
 * reference/minecraft-src/net/minecraft/world/entity/npc/AbstractVillager.java,
 * reference/minecraft-src/net/minecraft/world/item/trading/MerchantOffer.java, ItemCost.java.
 */
public class Peddler extends AbstractVillager {

    /** Сколько живёт в деревне: двое суток. */
    public static final int VILLAGE_STAY = 48000;

    /** Пополнение товара — раз в игровые сутки. */
    static final long RESTOCK_TICKS = 24000L;

    /** Сколько реплик приветствия ({@code murim.peddler.greet.N}). */
    private static final int GREETINGS = 5;

    @Nullable
    private BlockPos home;
    /** Тиков до ухода; ≤ 0 — не уходит (лавка у Хуашань). */
    private int stayTicks;
    private long lastRestock = Long.MIN_VALUE / 2;

    public Peddler(EntityType<? extends Peddler> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.5D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new TradeWithPlayerGoal(this));
        // Разбойники лагеря — его главный страх (не ученики секты, хоть они тоже Bandit по классу).
        goalSelector.addGoal(1, new AvoidEntityGoal<>(this, Bandit.class, 10.0F, 0.5D, 0.6D,
                e -> e instanceof Bandit b && !(b instanceof io.github.verycooltimo.murim.entity.SectDisciple)));
        goalSelector.addGoal(1, new PanicGoal(this, 0.5D));
        goalSelector.addGoal(1, new LookAtTradingPlayerGoal(this));
        goalSelector.addGoal(4, new MoveTowardsRestrictionGoal(this, 0.35D));
        goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 0.35D));
        goalSelector.addGoal(9, new InteractGoal(this, Player.class, 3.0F, 1.0F));
        goalSelector.addGoal(10, new LookAtPlayerGoal(this, Mob.class, 8.0F));
        goalSelector.addGoal(11, new RandomLookAroundGoal(this));
    }

    /** Поставить на место: дом, радиус прогулки и срок (0 — навсегда). */
    public void settle(BlockPos home, int stay) {
        this.home = home.immutable();
        this.stayTicks = stay;
        restrictTo(this.home, stay > 0 ? 10 : 5);
        setPersistenceRequired();
    }

    public boolean isStall() {
        return stayTicks <= 0;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!isAlive() || isTrading() || isBaby()) {
            return super.mobInteract(player, hand);
        }
        if (hand == InteractionHand.MAIN_HAND) {
            player.awardStat(Stats.TALKED_TO_VILLAGER);
        }
        if (!level().isClientSide) {
            if (getOffers().isEmpty()) {
                return InteractionResult.CONSUME;
            }
            // Реплика в чат от его имени: «Серебро вперёд, товар потом».
            player.sendSystemMessage(Component.translatable("murim.peddler.says", getDisplayName(),
                    Component.translatable("murim.peddler.greet." + random.nextInt(GREETINGS)).withStyle(ChatFormatting.GRAY)));
            setTradingPlayer(player);
            openTradingScreen(player, getDisplayName(), 1);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    protected void updateTrades() {
        stock(getOffers());
    }

    /** Завоз: собрать предложения из {@link PeddlerStock} (редкие строки — по своей вероятности). */
    void stock(MerchantOffers offers) {
        offers.clear();
        for (PeddlerStock.Line l : PeddlerStock.SELLS) {
            if (l.chance() < 1.0D && random.nextDouble() >= l.chance()) {
                continue;
            }
            ItemStack result = new ItemStack(item(l.item()), l.count());
            if (l.page()) {
                result.set(ModDataComponents.TECHNIQUE.get(), ResourceLocation.parse(PeddlerStock.pageBook(random.nextDouble())));
            }
            offers.add(new MerchantOffer(new ItemCost(io.github.verycooltimo.murim.registry.ModItems.SILVER_TAEL.get(), l.silver()),
                    result, l.uses(), 0, 0.0F));
        }
        for (PeddlerStock.Line l : PeddlerStock.BUYS) {
            // Скупка: любой экземпляр предмета (у хлама и страниц свои компоненты — ItemCost без них их принимает).
            offers.add(new MerchantOffer(new ItemCost(item(l.item()), l.count()),
                    new ItemStack(io.github.verycooltimo.murim.registry.ModItems.SILVER_TAEL.get(), l.silver()), l.uses(), 0, 0.0F));
        }
        lastRestock = level().getGameTime();
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide) {
            return;
        }
        if (!isTrading() && level().getGameTime() - lastRestock >= RESTOCK_TICKS && offers != null) {
            stock(offers);
        }
        if (stayTicks > 0 && !isTrading() && --stayTicks <= 0) {
            // Ушёл дальше по дороге.
            discard();
        }
    }

    @Override
    protected void rewardTradeXp(MerchantOffer offer) {
    }

    @Override
    public boolean showProgressBar() {
        return false;
    }

    @Nullable
    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob other) {
        return null;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    // Звук сделки — звон монет (цепь), без «хм» жителя.
    @Override
    public SoundEvent getNotifyTradeSound() {
        return SoundEvents.ARMOR_EQUIP_CHAIN.value();
    }

    @Override
    protected SoundEvent getTradeUpdatedSound(boolean yes) {
        return yes ? SoundEvents.CHAIN_STEP : SoundEvents.WOOL_STEP;
    }

    @Nullable
    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Stay", stayTicks);
        tag.putLong("Restock", lastRestock);
        if (home != null) {
            tag.put("Home", NbtUtils.writeBlockPos(home));
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        stayTicks = tag.getInt("Stay");
        lastRestock = tag.getLong("Restock");
        NbtUtils.readBlockPos(tag, "Home").ifPresent(p -> {
            home = p;
            restrictTo(p, stayTicks > 0 ? 10 : 5);
        });
    }
}
