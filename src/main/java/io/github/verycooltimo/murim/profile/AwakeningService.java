package io.github.verycooltimo.murim.profile;

import io.github.verycooltimo.murim.network.SyncAwakeningPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Создание даньтяня: серверная сторона церемонии.
 *
 * <p>Все решения принимаются здесь. Клиент получает только состояние и рисует по нему
 * сцену; выбор основания приходит от клиента как НАМЕРЕНИЕ и проверяется на сервере —
 * иначе выбрать основание можно было бы пакетом в любой момент, включая повторно.
 */
public final class AwakeningService {

    /** Урон слабее этого церемонию не срывает: капля дождя не должна ломать сцену. */
    private static final float DAMAGE_THRESHOLD = 1.0F;

    /**
     * Начинает церемонию.
     *
     * @return {@code false}, если начать нельзя, — причина уже показана игроку
     */
    public static boolean start(ServerPlayer player) {
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        if (profile.isAwakened()) {
            // Даньтянь создаётся ОДИН раз. Без этой проверки игрок мог бы перевыбрать
            // основание, а весь смысл выбора в его необратимости.
            player.displayClientMessage(
                    Component.translatable("murim.awakening.already"), true);
            return false;
        }
        if (player.getData(ModAttachments.AWAKENING).active()) {
            return false;
        }
        if (!player.onGround()) {
            player.displayClientMessage(
                    Component.translatable("murim.awakening.need_ground"), true);
            return false;
        }
        // Идущая циркуляция останавливается: две сцены одновременно не бывает.
        if (player.getData(ModAttachments.RITUAL).active()) {
            RitualService.stop(player, true);
        }
        set(player, AwakeningState.IDLE.withPhase(AwakeningState.Phase.SETTLE));
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.5F, 0.7F);
        return true;
    }

    /**
     * Один тик церемонии.
     *
     * @return {@code false}, если церемония закончилась или не шла
     */
    public static boolean tick(ServerPlayer player) {
        AwakeningState state = player.getData(ModAttachments.AWAKENING);
        if (!state.active()) {
            return false;
        }
        // Неподвижность проверяется по СМЕЩЕНИЮ ПОЗИЦИИ, а не по вектору скорости: сервер
        // не выставляет игроку deltaMovement при обычной ходьбе, он двигает его напрямую
        // по пакету от клиента. Та же ошибка уже была поймана ревью в циркуляции.
        double dx = player.getX() - player.xOld;
        double dz = player.getZ() - player.zOld;
        if (dx * dx + dz * dz > 1.0E-6D || !player.onGround()) {
            interrupt(player, "murim.awakening.broken.moved");
            return false;
        }

        AwakeningState advanced = state.advanced();
        AwakeningState.Phase phase = advanced.phase();

        // ПЕРЕЛИВ. Поток поднимается сам, но остановить его обязан игрок. Дотянул до
        // верха и не остановил — церемония срывается: это и есть цена жадности.
        // Раньше фаза кончалась по таймеру, результат не зависел ни от чего, и автор
        // справедливо спросил, где здесь геймплей.
        if (phase == AwakeningState.Phase.VEINS && advanced.tick() >= phase.ticks()) {
            interrupt(player, "murim.awakening.broken.overflow");
            return false;
        }

        if (!phase.waitsForPlayer() && advanced.tick() >= phase.ticks()) {
            AwakeningState.Phase next = switch (phase) {
                case SETTLE -> AwakeningState.Phase.VEINS;
                case CORE -> AwakeningState.Phase.CHOICE;
                case SEAL -> null;
                default -> null;
            };
            if (next == null) {
                complete(player, advanced);
                return false;
            }
            advanced = advanced.withPhase(next);
            onPhaseStart(player, next);
        }
        set(player, advanced);
        return true;
    }

    /**
     * Игрок выбрал основание. Вызывается по пакету намерения.
     *
     * <p>Проверка фазы обязательна: без неё выбор можно прислать в любой момент, в том
     * числе до того, как игрок увидел цену каждого варианта.
     */
    public static void choose(ServerPlayer player, Foundation foundation) {
        AwakeningState state = player.getData(ModAttachments.AWAKENING);
        if (!state.awaitingChoice() || foundation == null) {
            return;
        }
        set(player, state.withFoundation(foundation).withPhase(AwakeningState.Phase.SEAL));
        onPhaseStart(player, AwakeningState.Phase.SEAL);
        player.displayClientMessage(foundation.title(), true);
    }

    /**
     * Игрок останавливает поток. Высота остановки решает силу даньтяня.
     *
     * <p>Проверка фазы обязательна: остановить можно только пока поток идёт. Пакет,
     * присланный в другой момент, игнорируется — иначе силу можно было бы выставить
     * в любой точке церемонии.
     */
    public static void hold(ServerPlayer player) {
        AwakeningState state = player.getData(ModAttachments.AWAKENING);
        if (!state.active() || state.phase() != AwakeningState.Phase.VEINS) {
            return;
        }
        float where = state.tick() / (float) AwakeningState.Phase.VEINS.ticks();
        AwakeningState stopped = state.stoppedAt(where)
                .withPhase(AwakeningState.Phase.CORE);
        set(player, stopped);
        onPhaseStart(player, AwakeningState.Phase.CORE);
        player.displayClientMessage(Component.translatable(
                "murim.awakening.held", Math.round(where * 100.0F)), true);
    }

    /** Срыв церемонии: даньтянь не создан, ничего не сохраняется. */
    public static void interrupt(ServerPlayer player, String reason) {
        if (!player.getData(ModAttachments.AWAKENING).active()) {
            return;
        }
        set(player, AwakeningState.IDLE);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.6F, 0.6F);
        player.displayClientMessage(Component.translatable(reason), true);
    }

    /** Срыв уроном. Порог отсекает мелочь, чтобы сцену не ломал случайный тычок. */
    public static void onDamage(ServerPlayer player, float amount) {
        if (amount < DAMAGE_THRESHOLD) {
            return;
        }
        interrupt(player, "murim.awakening.broken.damage");
    }

    private static void complete(ServerPlayer player, AwakeningState state) {
        Foundation foundation = state.foundation();
        if (foundation == null) {
            // Такого быть не должно: печать наступает только после выбора. Но молча
            // создать даньтянь без основания хуже, чем откатить церемонию.
            interrupt(player, "murim.awakening.broken.no_choice");
            return;
        }
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        // Высота остановки умножает силу основания: выбор игрока влияет на РЕЗУЛЬТАТ,
        // а не только на природу центра.
        player.setData(ModAttachments.PROFILE,
                foundation.apply(profile, state.strength()));
        set(player, AwakeningState.IDLE);
        ProfileNetwork.sync(player);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.8F, 1.0F);
        player.displayClientMessage(
                Component.translatable("murim.awakening.done", foundation.title()), false);
    }

    private static void onPhaseStart(ServerPlayer player, AwakeningState.Phase phase) {
        switch (phase) {
            case VEINS -> {
                play(player, SoundEvents.AMETHYST_BLOCK_CHIME, 0.6F, 0.8F);
                // Подсказка обязательна: игрок не может догадаться, что поток нужно
                // остановить, а цена ошибки — вся церемония.
                player.displayClientMessage(
                        Component.translatable("murim.awakening.hint"), false);
            }
            case CORE -> play(player, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.7F, 0.6F);
            case CHOICE -> {
                play(player, SoundEvents.BEACON_AMBIENT, 0.7F, 1.0F);
                // Цена каждого основания показывается ДО выбора: открытый вопрос плана
                // прямо предупреждает, что иначе первое необратимое решение станет лотереей.
                for (Foundation option : Foundation.values()) {
                    player.displayClientMessage(
                            Component.translatable("murim.awakening.option",
                                    option.title(), option.cost()), false);
                }
            }
            case SEAL -> play(player, SoundEvents.BEACON_POWER_SELECT, 0.8F, 0.8F);
            default -> {
            }
        }
    }

    private static void play(ServerPlayer player, net.minecraft.sounds.SoundEvent sound,
                             float volume, float pitch) {
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                sound, SoundSource.PLAYERS, volume, pitch);
    }

    private static void set(ServerPlayer player, AwakeningState state) {
        player.setData(ModAttachments.AWAKENING, state);
        // Состояние синхронизируется КАЖДУЮ смену: клиент рисует сцену по нему, и
        // пропущенный переход фазы означает застрявший на экране эффект.
        PacketDistributor.sendToPlayer(player, new SyncAwakeningPayload(
                state.phase().name(), state.tick(),
                state.foundation() == null ? "" : state.foundation().id()));
    }

    private AwakeningService() {
    }
}
