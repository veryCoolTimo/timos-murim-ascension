package io.github.verycooltimo.murim.profile;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Ритуал даньтяня: сбор ци, циркуляция и решение «остановиться или рискнуть».
 *
 * <p>Смысл механики в том, что каждый следующий круг ценнее предыдущего, но и опаснее.
 * Игрок в любой момент может встать и забрать накопленное; продолжив — получает больше,
 * но с растущим шансом отката. Без этого выбора медитация была бы просто ожиданием.
 *
 * <p>Расплата отложенная, а не мгновенная: сорвавшийся ритуал не убивает на месте, он
 * портит чистоту и оставляет травму. Так подсказывает первоисточник — искажённый путь
 * сперва даёт ускоренный рост и лишь потом предъявляет счёт, и это играется лучше.
 */
public final class RitualService {

    /** Сколько кругов проходят без всякого риска. Первые два — «бесплатные». */
    private static final int SAFE_CYCLES = 2;

    /** Прирост напряжения за круг сверх безопасных. */
    private static final double STRAIN_PER_CYCLE = 0.18D;

    /** Порог, за которым круг может сорваться. */
    private static final double STRAIN_LIMIT = 1.0D;

    /** Сколько ци даёт один круг при базовой чистоте. */
    private static final double GAIN_PER_CYCLE = 4.0D;

    public static boolean start(ServerPlayer player) {
        // Те же условия, что у запуска техники: мёртвый и зритель не медитируют.
        if (!player.isAlive() || player.isRemoved() || player.isSpectator()) {
            return false;
        }
        RitualState state = player.getData(ModAttachments.RITUAL);
        if (state.active()) {
            return false;
        }
        player.setData(ModAttachments.RITUAL, RitualState.started());
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.35F, 1.4F);
        player.displayClientMessage(Component.translatable("murim.ritual.begin"), true);
        return true;
    }

    /**
     * Прекращает ритуал и зачисляет накопленное в профиль.
     *
     * @param voluntary остановился ли игрок сам; сорванный ритуал теряет часть набранного
     */
    public static void stop(ServerPlayer player, boolean voluntary) {
        RitualState state = player.getData(ModAttachments.RITUAL);
        if (!state.active()) {
            return;
        }
        player.setData(ModAttachments.RITUAL, RitualState.IDLE);

        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        // Сорванный ритуал отдаёт половину: полная потеря наказывала бы за случайный урон
        // сильнее, чем за жадность, а жадность здесь и есть предмет выбора.
        double kept = voluntary ? state.gathered() : state.gathered() * 0.5D;

        // Чистота меняется ТОЛЬКО за завершённые круги. Раньше условие выполнялось и при нуле
        // кругов, и спам «начал–остановил» поднимал чистоту до предела секунд за тринадцать,
        // вообще без медитации. Поймано ревью.
        double purityShift;
        if (state.cycles() == 0) {
            purityShift = 0.0D;
        } else if (voluntary && state.cycles() <= SAFE_CYCLES) {
            purityShift = 0.015D;
        } else if (state.cycles() > SAFE_CYCLES) {
            purityShift = -0.01D * (state.cycles() - SAFE_CYCLES);
        } else {
            purityShift = 0.0D;
        }
        double meridianShift = 0.004D * state.cycles();

        DantianProfile updated = profile
                .withPool(profile.pool() + kept)
                .withCirculating(profile.circulating() + kept * 0.5D)
                .withAxes(profile.capacity() + 0.35D * state.cycles(),
                          profile.purity() + purityShift,
                          profile.meridians() + meridianShift);

        if (!updated.isAwakened() && state.cycles() >= 1) {
            // Первое пробуждение: теги задаются тем, КАК прошёл первый ритуал, а не выбором
            // из меню. Осторожный получает чистую природу, жадный — мутную.
            String nature = state.cycles() <= SAFE_CYCLES ? "clear" : "turbid";
            updated = updated.withTags(nature, "meditation");
            player.displayClientMessage(
                    Component.translatable("murim.ritual.awakened." + nature), false);
        }

        player.setData(ModAttachments.PROFILE, updated);
        ProfileNetwork.sync(player);
        player.displayClientMessage(Component.translatable("murim.ritual.end",
                String.format(java.util.Locale.ROOT, "%.1f", kept), state.cycles()), true);
    }

    /** Один тик ритуала. Возвращает {@code false}, если ритуал закончился. */
    public static boolean tick(ServerPlayer player) {
        RitualState state = player.getData(ModAttachments.RITUAL);
        if (!state.active()) {
            return false;
        }
        // Неподвижность проверяется по СМЕЩЕНИЮ ПОЗИЦИИ, а не по вектору скорости.
        // Сервер не выставляет игроку deltaMovement при обычной ходьбе — он двигает его
        // напрямую по пакету от клиента, поэтому прежняя проверка пропускала бег по ровной
        // земле, и ци копилась на ходу. Поймано ревью.
        double dx = player.getX() - player.xOld;
        double dz = player.getZ() - player.zOld;
        if (dx * dx + dz * dz > 1.0E-6D || !player.onGround()) {
            player.displayClientMessage(Component.translatable("murim.ritual.broken.moved"), true);
            stop(player, false);
            return false;
        }

        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        // Напряжение копится с первого круга, а не с третьего: иначе первая проверка риска
        // наступала только на восьмом круге, и оптимальной стратегией было сидеть ровно
        // до неё и всегда останавливаться добровольно. Риск обязан быть настоящим.
        double strainGain = STRAIN_PER_CYCLE / RitualState.CYCLE_TICKS;
        state = state.advanced(0.0D, strainGain);

        if (state.tick() >= RitualState.CYCLE_TICKS) {
            // Ци начисляется ЦЕЛЫМ кругом и только по его завершении. При потиковом
            // начислении игрок забирал накопленное за миг до проверки срыва.
            state = state.advanced(GAIN_PER_CYCLE * profile.efficiency(), 0.0D);
            state = state.cycleDone();
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS,
                    0.4F, 0.8F + 0.1F * Math.min(6, state.cycles()));

            if (state.strain() >= STRAIN_LIMIT && backlashHappens(player, state)) {
                backlash(player, state);
                return false;
            }
        }
        player.setData(ModAttachments.RITUAL, state);
        return true;
    }

    /**
     * Срывается ли круг. Вероятность растёт с напряжением, но не мгновенно: даже за порогом
     * первый лишний круг чаще проходит, чем нет — иначе игрок никогда не рискнёт второй раз.
     */
    private static boolean backlashHappens(ServerPlayer player, RitualState state) {
        double chance = Math.min(0.75D, (state.strain() - STRAIN_LIMIT) * 0.5D + 0.15D);
        return player.getRandom().nextDouble() < chance;
    }

    private static void backlash(ServerPlayer player, RitualState state) {
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        // Расплата бьёт по чистоте и фундаменту, а не по здоровью: смерть за жадность
        // в медитации ощущается как несправедливость, а испорченный центр — как последствие.
        DantianProfile damaged = profile
                .withAxes(profile.capacity(), profile.purity() - 0.06D, profile.meridians())
                .withFoundation(profile.foundation() - 0.05D);
        player.setData(ModAttachments.PROFILE, damaged);
        player.setData(ModAttachments.RITUAL, RitualState.IDLE);

        // Половина набранного возвращается: полная потеря делает риск невыгодным всегда,
        // и игрок просто перестаёт рисковать — механика выбора умирает.
        DantianProfile withHalf = damaged
                .withPool(damaged.pool() + state.gathered() * 0.5D);
        player.setData(ModAttachments.PROFILE, withHalf);

        // Урон не должен убивать: расплата за жадность в медитации — испорченный центр,
        // а не смерть. Смерть тут ощущается как несправедливость.
        float safe = Math.min(2.0F, Math.max(0.0F, player.getHealth() - 1.0F));
        if (safe > 0.0F) {
            player.hurt(player.damageSources().magic(), safe);
        }
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 0.7F, 0.6F);
        player.displayClientMessage(Component.translatable("murim.ritual.backlash"), false);
        ProfileNetwork.sync(player);
        MurimMod.LOGGER.debug("Срыв ритуала у {} на {} кругах", player.getGameProfile().getName(),
                state.cycles());
    }

    private RitualService() {
    }
}
