package io.github.verycooltimo.murim.item;

import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.profile.RitualService;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Мануал — находимый предмет, с которого начинается культивация.
 *
 * <p>Ритуал запускается им, а не клавишей, намеренно: культивация должна быть тем, что игрок
 * нашёл в мире, а не способностью с рождения. Пока мануала нет, даньтянь не сформирован.
 *
 * <p>Повторное нажатие прекращает ритуал и зачисляет накопленное — это и есть половина
 * решения «остановиться или рискнуть».
 */
public class ManualItem extends Item {

    public ManualItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            // Решение принимает сервер; клиент только сообщает о намерении.
            // consume, а не success: success заставляет клиент махнуть рукой, а сервер
            // этого не делает — получался рассинхрон анимации.
            return InteractionResultHolder.consume(stack);
        }

        if (serverPlayer.getData(ModAttachments.RITUAL).active()) {
            RitualService.stop(serverPlayer, true);
        } else {
            RitualService.start(serverPlayer);
        }
        ProfileNetwork.syncRitual(serverPlayer);
        // Небольшая задержка: без неё удержание кнопки перезапускает ритуал пять раз
        // в секунду со всеми звуками и пакетами.
        serverPlayer.getCooldowns().addCooldown(stack.getItem(), 10);
        return InteractionResultHolder.consume(stack);
    }

    /**
     * Тот же переключатель при нажатии по блоку.
     *
     * <p>{@code use} не вызывается, когда игрок смотрит на блок, — медитируя лицом к сундуку,
     * ритуал нельзя было бы остановить мануалом, оставался только срыв движением с потерей
     * половины набранного.
     */
    @Override
    public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
        Player player = context.getPlayer();
        if (context.getLevel().isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        if (serverPlayer.getData(ModAttachments.RITUAL).active()) {
            RitualService.stop(serverPlayer, true);
        } else {
            RitualService.start(serverPlayer);
        }
        ProfileNetwork.syncRitual(serverPlayer);
        return net.minecraft.world.InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines,
                                TooltipFlag flag) {
        lines.add(Component.translatable("item.murim.manual.hint")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }
}
