package io.github.verycooltimo.murim.combat;

import net.minecraft.resources.ResourceLocation;

/**
 * Состояние применяемой техники у одного игрока. Живёт на сервере, хранится Data Attachment'ом.
 *
 * <p><b>Запись неизменяема намеренно.</b> Значение по умолчанию у Data Attachment хранится
 * в самом держателе и разделяется между всеми игроками — изменяемый объект здесь означал бы,
 * что мутация у одного игрока портит состояние всем сразу. Неизменяемая запись делает такое
 * невозможным: изменение — это новый экземпляр через {@code setData}.
 *
 * <p>Не сериализуется: техника длится меньше двух секунд, и состояние, пережившее выход из игры,
 * вернуло бы игрока в мир посреди замаха.
 *
 * @param techniqueId        применяемая техника или {@code null}, если игрок свободен
 * @param tick               сколько тиков прошло с начала применения
 * @param impactDone         сработала ли уже фаза удара; защита от повторного урона
 * @param lastStartGameTime  игровое время последнего запуска; переживает завершение техники,
 *                           потому что по нему считается кулдаун
 */
public record TechniqueState(
        ResourceLocation techniqueId,
        int tick,
        boolean impactDone,
        long lastStartGameTime
) {

    /** Игрок свободен и никогда ничего не применял. Безопасно разделять: запись неизменяема. */
    public static final TechniqueState IDLE = new TechniqueState(null, 0, false, Long.MIN_VALUE);

    public boolean isActive() {
        return techniqueId != null;
    }

    public TechniqueState advanced() {
        return new TechniqueState(techniqueId, tick + 1, impactDone, lastStartGameTime);
    }

    public TechniqueState withImpactDone() {
        return new TechniqueState(techniqueId, tick, true, lastStartGameTime);
    }

    /** Завершение техники с сохранением времени запуска — иначе кулдаун обнулялся бы. */
    public TechniqueState finished() {
        return new TechniqueState(null, 0, false, lastStartGameTime);
    }

    public static TechniqueState started(ResourceLocation techniqueId, long gameTime) {
        return new TechniqueState(techniqueId, 0, false, gameTime);
    }
}
