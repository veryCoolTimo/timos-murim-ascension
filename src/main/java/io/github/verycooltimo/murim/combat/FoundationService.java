package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.FoundationPayloads;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Optional;

/**
 * Основа меча на сервере: проверка взмаха, освоение и поведение форм при попадании.
 *
 * <p>Урон не трогается — он ванильный, от оружия (правило профиля: без множителей). Формы
 * меняют поведение: удар сверху сбивает стойку, восходящий подбрасывает, блок на миг
 * прикрывает. Боковой разрез — ванильный размах меча. Укол — просто точный.
 */
public final class FoundationService {

    /** Сколько тиков взмах считается «тем самым» для попадания: пакет атаки идёт следом. */
    private static final int HIT_LINK_TICKS = 4;

    /** Основа меча игрока, если выбрана, выучена и в руке меч. */
    public static Optional<ResourceLocation> active(ServerPlayer player) {
        Optional<ResourceLocation> id = player.getData(ModAttachments.LOADOUT).foundation();
        if (id.isEmpty() || !MasteryService.knows(player, id.get())
                || !player.getMainHandItem().is(ItemTags.SWORDS)) {
            return Optional.empty();
        }
        return id;
    }

    /** Клиент сообщил о взмахе формой. */
    public static void onSwing(ServerPlayer player, int formIndex) {
        Optional<ResourceLocation> id = active(player);
        if (id.isEmpty() || formIndex < 0 || formIndex >= FoundationForms.Form.values().length) {
            return;
        }
        // Спам-клик формой не считается: ваниль за него наказывает слабым ударом.
        if (player.getAttackStrengthScale(0.0F) < 0.9F) {
            return;
        }
        int layer = Math.max(0, MasteryService.layer(player, id.get()));
        if (formIndex >= FoundationForms.unlocked(layer)) {
            return;
        }
        player.setData(ModAttachments.FOUNDATION_SWING, new int[] {formIndex, player.tickCount});
        // Каждый правильный взмах — тренировка формы; попадание добавит своё.
        MasteryService.onMiss(player, id.get());
        float speed = FoundationForms.speed(player.getCurrentItemAttackStrengthDelay());
        PacketDistributor.sendToPlayersTrackingEntity(player,
                new FoundationPayloads.Form(player.getId(), formIndex, layer, speed));
    }

    /**
     * Попадание обычной атакой при выбранной основе. Вызывается из {@link SchoolStyle}.
     *
     * @return {@code true}, если удар обработан основой (старая серия стиля школы не нужна)
     */
    public static boolean onHit(ServerPlayer player, LivingEntity target) {
        Optional<ResourceLocation> id = active(player);
        if (id.isEmpty()) {
            return false;
        }
        int[] swing = player.getData(ModAttachments.FOUNDATION_SWING);
        if (swing[1] < 0 || player.tickCount - swing[1] > HIT_LINK_TICKS) {
            return true;
        }
        MasteryService.onHit(player, id.get(), target);
        switch (FoundationForms.Form.values()[swing[0]]) {
            case OVERHEAD -> target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 1, false, true, true));
            case RISING -> {
                target.push(0.0D, 0.25D, 0.0D);
                target.hurtMarked = true;
            }
            case BLOCK -> player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 10, 0, false, false, false));
            default -> {
            }
        }
        return true;
    }

    private FoundationService() {
    }
}
