package io.github.verycooltimo.murim.item;

import io.github.verycooltimo.murim.cultivation.PillKind;
import io.github.verycooltimo.murim.cultivation.PillRules;
import io.github.verycooltimo.murim.cultivation.PillService;
import io.github.verycooltimo.murim.cultivation.PillState;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
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
 * Пилюля (docs/design/19b §1). Съел — открылось окно «сразу»; сел медитировать в окне —
 * мини-игра поглощения ×10; не сел — десятая часть.
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

    public PillItem(PillKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public PillKind kind() {
        return kind;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
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
                player.displayClientMessage(PillService.refusalText(refusal), true);
            }
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }
        return ItemUtils.startUsingInstantly(level, player, hand);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (entity instanceof ServerPlayer player) {
            PillRules.Refusal refusal = PillService.check(player, kind, player.getData(ModAttachments.PROFILE),
                    player.getData(ModAttachments.PILLS));
            if (refusal != PillRules.Refusal.NONE) {
                player.displayClientMessage(PillService.refusalText(refusal), true);
                return stack;
            }
            PillService.eat(player, kind);
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
        tooltip.add(Component.translatable("item.murim." + kind.itemId() + ".desc")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        tooltip.add(Component.translatable("murim.pill.hint").withStyle(net.minecraft.ChatFormatting.DARK_AQUA));
    }
}
