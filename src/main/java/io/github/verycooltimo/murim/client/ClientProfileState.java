package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.network.SyncRitualPayload;
import io.github.verycooltimo.murim.profile.DantianProfile;

/**
 * Зеркало серверного состояния на клиенте — только для отрисовки.
 *
 * <p>Ни одно решение здесь не принимается: клиент показывает то, что прислал сервер.
 * Считать ци на клиенте означало бы дать способ её подделать.
 */
public final class ClientProfileState {

    // volatile стоит копейки и фиксирует контракт: пишет только главный поток по приходу
    // пакета, читает рендер. Одно изменение обработчика на сетевой поток без этого
    // превратилось бы в невоспроизводимый рассинхрон интерфейса.
    private static volatile DantianProfile profile = DantianProfile.INITIAL;
    private static volatile SyncRitualPayload ritual = new SyncRitualPayload(false, 0, 0.0F, 0.0F);

    public static DantianProfile profile() {
        return profile;
    }

    public static SyncRitualPayload ritual() {
        return ritual;
    }

    public static void setProfile(DantianProfile value) {
        profile = value;
    }

    public static void setRitual(SyncRitualPayload value) {
        ritual = value;
    }

    /** Сброс при выходе из мира: чужой профиль не должен подсвечиваться в новом. */
    public static void reset() {
        profile = DantianProfile.INITIAL;
        ritual = new SyncRitualPayload(false, 0, 0.0F, 0.0F);
    }

    private ClientProfileState() {
    }
}
