package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

/**
 * Стиль школы на обычной атаке.
 *
 * <p>Смысл: культивация должна ощущаться не только в техниках. Обычный удар мечом у
 * пробуждённого персонажа отличается от удара крестьянина — иначе весь профиль живёт
 * в отдельном углу и на игру не влияет.
 *
 * <p>Реализовано как <b>серия</b>: три удара подряд в темпе складываются в связку, третий
 * бьёт заметно сильнее. Это дешёвый способ дать обычной атаке ритм, не трогая ванильную
 * систему боя и не ломая совместимость со сборками.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SchoolStyle {

    /** Сколько тиков держится связка между ударами. Дольше — и серия перестаёт быть серией. */
    private static final int COMBO_WINDOW_TICKS = 24;

    /** На какой удар приходится усиление. */
    private static final int FINISHER_STEP = 3;

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    static void onAttack(AttackEntityEvent event) {
        // Низший приоритет и проверка отмены: без них финишер срабатывал бы поверх чужой
        // защиты территории или PvP-флага, отменившей атаку позже по цепочке.
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(event.getTarget() instanceof LivingEntity target)) {
            return;
        }
        // Выбрана основа меча — удар ведёт она, старая серия стиля не нужна (автор 01.10).
        if (FoundationService.onHit(player, target)) {
            return;
        }
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        if (!profile.isAwakened()) {
            return;
        }

        // Спам-клик не набирает серию: ваниль за него наказывает ослабленным ударом,
        // и связка не должна давать обходной путь.
        if (player.getAttackStrengthScale(0.0F) < 0.9F) {
            return;
        }

        ComboState combo = player.getData(ModAttachments.COMBO);
        long now = player.serverLevel().getGameTime();
        int step = combo.continues(now, COMBO_WINDOW_TICKS) ? combo.step() + 1 : 1;

        if (step >= FINISHER_STEP) {
            // Завершающий удар меняет ПОВЕДЕНИЕ, а не силу.
            //
            // Прежняя версия добавляла урон вторым вызовом hurt до ванильного, и это было
            // неверно дважды. Во-первых, кадры неуязвимости съедали прибавку целиком: ваниль
            // добивала лишь разницу, итог совпадал с обычным ударом. Во-вторых, при слабом
            // оружии ванильный удар отменялся вовсе — терялись зачарования, крит и отбрасывание.
            //
            // И главное: дизайн профиля прямо запрещает множители к урону. Поэтому финишер
            // отбрасывает и сбивает шаг, а не бьёт больнее.
            double push = 0.8D + 0.4D * profile.efficiency();
            target.knockback(push, player.getX() - target.getX(), player.getZ() - target.getZ());
            target.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 30, 1,
                    false, true, true));
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 0.8F, 1.25F);
            step = 0;
        }
        player.setData(ModAttachments.COMBO, new ComboState(step, now));
    }

    /**
     * Шаг серии и время последнего удара.
     *
     * @param step     сколько ударов уже в связке
     * @param lastTick игровое время последнего удара
     */
    public record ComboState(int step, long lastTick) {

        public static final ComboState IDLE = new ComboState(0, Long.MIN_VALUE);

        public ComboState {
            if (step < 0) {
                throw new IllegalArgumentException("Отрицательный шаг серии");
            }
        }

        /**
         * Продолжается ли связка.
         *
         * <p>Сравнение через вычитание, а не {@code lastTick + window > now}: при
         * {@code Long.MIN_VALUE} сложение переполняется и первый удар в мире засчитался бы
         * как продолжение несуществующей серии.
         */
        public boolean continues(long now, int windowTicks) {
            return step > 0 && now - lastTick <= windowTicks;
        }
    }

    private SchoolStyle() {
    }
}
