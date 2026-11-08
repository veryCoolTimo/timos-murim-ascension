package io.github.verycooltimo.murim.item;

import io.github.verycooltimo.murim.cultivation.PillEffects;
import io.github.verycooltimo.murim.cultivation.PillKind;
import io.github.verycooltimo.murim.cultivation.PillRules;
import io.github.verycooltimo.murim.cultivation.PillService;
import io.github.verycooltimo.murim.cultivation.PillState;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Пилюля (docs/design/19b §1). Съел - открылось окно «сразу»; сел медитировать в окне - игра «Провести поток» решает, сколько
 * из фиксированного ци осядет (40-100 %); не сел - пятая часть.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/item/Item.java#use/finishUsingItem/getUseDuration,
 * ItemUtils#startUsingInstantly.
 */
public class PillItem extends Item {

    private final PillKind kind;

    /**
     * Профиль на клиенте: подставляет клиентский код при запуске (общему коду нельзя
     * импортировать client/). Не игровое состояние — только источник данных.
     */
    public static java.util.function.Supplier<DantianProfile> clientProfile = () -> DantianProfile.INITIAL;

    /** Read-only synchronized tolerance for the tooltip: how many times in a row this pill was eaten alone (0 - none). */
    public static java.util.function.ToIntFunction<PillKind> clientStreak = kind -> 0;

    public PillItem(PillKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public PillKind kind() {
        return kind;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (kind.utility()) {
            // Everyday pills (24.10): no dantian, no window; only a cooldown (and hunger for the Fasting pill).
            if (!PillEffects.usable(player, kind)) {
                if (!level.isClientSide()) {
                    PlayerJournal.refuse(player, Component.translatable(kind == PillKind.MENDING ? "murim.pill.mending.unavailable" : "murim.pill.not_hungry")
                            .withStyle(net.minecraft.ChatFormatting.GRAY));
                }
                return InteractionResultHolder.fail(player.getItemInHand(hand));
            }
            if (player.getCooldowns().isOnCooldown(this)) {
                return InteractionResultHolder.fail(player.getItemInHand(hand));
            }
            return ItemUtils.startUsingInstantly(level, player, hand);
        }
        // Проверка до начала поедания и на клиенте: иначе анимация проиграется зря.
        DantianProfile profile = level.isClientSide()
                ? clientProfile.get()
                : player.getData(ModAttachments.PROFILE);
        PillState pills = level.isClientSide()
                ? PillState.NONE
                : player.getData(ModAttachments.PILLS);
        PillRules.Refusal refusal = PillService.check(player, kind, profile, pills);
        if (refusal != PillRules.Refusal.NONE) {
            if (!level.isClientSide()) {
                PlayerJournal.refuse(player, PillService.refusalText(refusal));
            }
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }
        if (kind.herb() && player.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }
        return ItemUtils.startUsingInstantly(level, player, hand);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (kind.utility()) {
            if (entity instanceof ServerPlayer player && PillEffects.usable(player, kind) && !player.getCooldowns().isOnCooldown(this)) {
                PillEffects.use(player, kind);
                PillService.sound(player, "pill_eat");
                int cooldown = PillEffects.cooldown(kind);
                if (cooldown > 0) {
                    player.getCooldowns().addCooldown(this, cooldown);
                }
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
            }
            return stack;
        }
        if (entity instanceof ServerPlayer player) {
            PillRules.Refusal refusal = PillService.check(player, kind, player.getData(ModAttachments.PROFILE),
                    player.getData(ModAttachments.PILLS));
            if (refusal != PillRules.Refusal.NONE) {
                PlayerJournal.refuse(player, PillService.refusalText(refusal));
                return stack;
            }
            if (kind.herb() && player.getCooldowns().isOnCooldown(this)) {
                PlayerJournal.refuse(player, Component.translatable("murim.herb.cooldown")
                        .withStyle(net.minecraft.ChatFormatting.GRAY));
                return stack;
            }
            // A pill the body will not endure is only warned about at the first try: it stays in the hand.
            if (!PillService.eat(player, kind)) {
                return stack;
            }
            if (kind.herb()) {
                for (PillKind other : PillKind.values()) {
                    if (other.herb()) {
                        player.getCooldowns().addCooldown(io.github.verycooltimo.murim.registry.ModItems.itemOf(other),
                                PillKind.HERB_COOLDOWN);
                    }
                }
            }
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        return stack;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return kind == PillKind.BEAUTY_TEAR ? UseAnim.DRINK : UseAnim.EAT;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return kind == PillKind.BEAUTY_TEAR ? 24 : 16;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (kind.utility()) {
            if (kind == PillKind.PLUM_BLOSSOM) tooltip.add(Component.translatable("item.murim.pill_plum_blossom.origin")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            tooltip.add(Component.translatable("item.murim." + kind.itemId() + ".desc")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            return;
        }
        if (kind == PillKind.PURPLE_GINSENG) {
            tooltip.add(Component.translatable("item.murim.purple_ginseng.desc"));
        }
        DantianProfile profile = clientProfile.get();
        PillRules.Plan plan = PillRules.plan(List.of(kind), profile.rank(), Realm.wall(profile),
                PillRules.toleranceFactor(clientStreak.applyAsInt(kind)));
        tooltip.add(Component.translatable("murim.pill.qi", kind.qi(),
                Component.translatable("murim.pill.rank.name." + kind.rank())).withStyle(net.minecraft.ChatFormatting.GRAY));
        if (kind.greatElixir()) {
            tooltip.add(Component.translatable("murim.pill.great.tooltip").withStyle(net.minecraft.ChatFormatting.GOLD));
        }
        if (!profile.isAwakened()) {
            tooltip.add(Component.translatable("murim.pill.tooltip.no_dantian").withStyle(net.minecraft.ChatFormatting.GRAY));
        } else if (plan.tooHigh()) {
            tooltip.add(Component.translatable("murim.pill.rank.torn").withStyle(net.minecraft.ChatFormatting.RED));
        } else if (plan.overload()) {
            tooltip.add(Component.translatable("murim.pill.load.torn", (long) Math.floor(plan.wall() * PillRules.TORN_LOAD))
                    .withStyle(net.minecraft.ChatFormatting.RED));
        } else if (profile.pool() >= Realm.wall(profile)) {
            tooltip.add(Component.translatable("murim.pill.tooltip.full").withStyle(net.minecraft.ChatFormatting.RED));
        } else if (plan.hard()) {
            tooltip.add(Component.translatable("murim.pill.rank.hard", Math.round(plan.load())).withStyle(net.minecraft.ChatFormatting.YELLOW));
        } else if (clientStreak.applyAsInt(kind) > 0) {
            tooltip.add(Component.translatable("murim.pill.repeat", String.format(java.util.Locale.ROOT, "%.3g",
                    plan.tolerance())).withStyle(net.minecraft.ChatFormatting.YELLOW));
        }
    }
}
