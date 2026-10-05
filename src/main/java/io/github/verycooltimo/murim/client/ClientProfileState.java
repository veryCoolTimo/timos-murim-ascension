package io.github.verycooltimo.murim.client;

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

    public static DantianProfile profile() {
        return profile;
    }

    public static void setProfile(DantianProfile value) {
        profile = value;
        // Тот же профиль — в attachment своего игрока: общий код блоков (холодное железо) читает ранг и ци
        // через player.getData и на клиенте, чтобы предсказать «не поддаётся» так же, как сервер.
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null) {
            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE, value);
        }
    }

    /** Сброс при выходе из мира: чужой профиль не должен подсвечиваться в новом. */
    public static void reset() {
        profile = DantianProfile.INITIAL;
    }

    private ClientProfileState() {
    }
}
