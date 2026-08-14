package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.ChooseFoundationPayload;
import io.github.verycooltimo.murim.network.SyncAwakeningPayload;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Сцена создания даньтяня на клиенте: камера, поза и выбор основания.
 *
 * <p>Состояние приходит с сервера целиком и здесь только читается. Клиент ничего не решает
 * о ходе церемонии — он показывает её и отправляет намерение выбрать основание.
 *
 * <p><b>Камера уходит ВПЕРЁД, а не за спину.</b> Автор проекта прямо поправил: в этой
 * сцене важно лицо и грудь, где проступают жилы, а вид со спины показывает затылок.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class AwakeningSceneHandler {

    /** Последнее полученное состояние. Пустая фаза означает, что церемония не идёт. */
    private static String phase = "IDLE";
    private static int tick;
    private static String foundation = "";

    /** Куда вернуть камеру после сцены. Ноль означает «мы её не трогали». */
    private static CameraType restoreCamera;

    public static void accept(SyncAwakeningPayload payload) {
        boolean wasActive = active();
        phase = payload.phase();
        tick = payload.tick();
        foundation = payload.foundation();

        Minecraft minecraft = Minecraft.getInstance();
        if (active() && !wasActive) {
            onSceneStart(minecraft);
        } else if (!active() && wasActive) {
            onSceneEnd(minecraft);
        }
    }

    public static boolean active() {
        return !"IDLE".equals(phase);
    }

    public static String phase() {
        return phase;
    }

    public static int tick() {
        return tick;
    }

    public static String foundation() {
        return foundation;
    }

    /** Ждёт ли сцена выбора игрока прямо сейчас. */
    public static boolean awaitingChoice() {
        return "CHOICE".equals(phase);
    }

    /**
     * Сброс при выходе из мира.
     *
     * <p>Без него камера остаётся фронтальной после выхода посреди церемонии: состояние
     * сцены переживёт смену мира, а сервер уже ничего не пришлёт.
     */
    public static void reset() {
        Minecraft minecraft = Minecraft.getInstance();
        if (active()) {
            onSceneEnd(minecraft);
        }
        phase = "IDLE";
        tick = 0;
        foundation = "";
    }

    private static void onSceneStart(Minecraft minecraft) {
        restoreCamera = minecraft.options.getCameraType();
        minecraft.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        MurimPlayerAnimations.play(minecraft.player, MurimPlayerAnimations.LOTUS);
    }

    private static void onSceneEnd(Minecraft minecraft) {
        if (restoreCamera != null) {
            minecraft.options.setCameraType(restoreCamera);
            restoreCamera = null;
        }
    }

    /**
     * Выбор основания клавишами.
     *
     * <p>Три клавиши, а не меню: сцена намеренно не имеет интерфейса — полоски и окна
     * превращают её в мини-игру, а риск должен читаться через тело (план MVP 2).
     */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        // Та же клавиша: пока церемония не идёт — начинает её, во время подъёма
        // потока — ОСТАНАВЛИВАЕТ его. Отдельная клавиша здесь была бы лишней:
        // действие одно и то же по смыслу — «взяться за поток».
        while (ModKeyMappings.AWAKENING.consumeClick()) {
            if ("VEINS".equals(phase)) {
                PacketDistributor.sendToServer(
                        io.github.verycooltimo.murim.network.HoldFlowPayload.INSTANCE);
            } else if (!active()) {
                PacketDistributor.sendToServer(
                        io.github.verycooltimo.murim.network.StartAwakeningPayload.INSTANCE);
            }
        }
        if (!awaitingChoice()) {
            return;
        }
        String chosen = null;
        if (ModKeyMappings.FOUNDATION_BLOOD.consumeClick()) {
            chosen = "blood";
        } else if (ModKeyMappings.FOUNDATION_VOID.consumeClick()) {
            chosen = "void";
        } else if (ModKeyMappings.FOUNDATION_MOUNTAIN.consumeClick()) {
            chosen = "mountain";
        }
        if (chosen != null) {
            PacketDistributor.sendToServer(new ChooseFoundationPayload(chosen));
        }
    }

    private AwakeningSceneHandler() {
    }
}
