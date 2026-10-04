package io.github.verycooltimo.murim.item;

import io.github.verycooltimo.murim.technique.TangExecutor;
import io.github.verycooltimo.murim.technique.TangRules;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Метательный кинжал клана Тан как обычное оружие (автор 04.10: «кинжалы должен кидать сам игрок, обычными
 * кнопками мыши, без техники»). ПКМ — бросок одного кинжала: летит с тяжестью, бьёт как метательный нож,
 * промах втыкается в землю и подбирается касанием или «Возвратом Лезвий». ЛКМ — удар кинжалом в руке
 * (атрибуты предмета, см. ModItems). API: reference/minecraft-src/net/minecraft/world/item/SnowballItem.java#use.
 */
public class TangDaggerItem extends Item {

    public TangDaggerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            TangExecutor.throwByHand(sp, player.hasInfiniteMaterials());
        }
        player.getCooldowns().addCooldown(this, TangRules.HAND_COOLDOWN);
        player.awardStat(Stats.ITEM_USED.get(this));
        stack.consume(1, player);
        player.swing(hand);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
