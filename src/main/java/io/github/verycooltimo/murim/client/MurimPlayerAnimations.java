package io.github.verycooltimo.murim.client;

import com.zigythebird.playeranim.animation.PlayerAnimationController;
import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import com.zigythebird.playeranim.api.PlayerAnimationFactory;
import com.zigythebird.playeranimcore.animation.layered.IAnimation;
import com.zigythebird.playeranimcore.enums.PlayState;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

/**
 * Слой анимаций мода поверх ванильной анимации игрока (PAL).
 *
 * <p>Своя анимация тела игрока иначе не встраивается: событие до отрисовки затирается ванильным
 * `setupAnim`, событие после — уже поздно, а точки подмены рендерера игрока не существует.
 * PAL решает это инъекцией внутри себя, поэтому мы работаем через его API и своих миксинов
 * не пишем. Обоснование выбора — docs/design/research/r1-player-animation.md.
 */
public final class MurimPlayerAnimations {

    /** Идентификатор нашего слоя в стеке PAL. */
    private static final ResourceLocation LAYER =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "technique_layer");

    /**
     * Приоритет слоя. Чем выше, тем позже применяется поверх остальных: техника должна
     * перебивать и ванильную ходьбу, и чужие эмоции.
     */
    private static final int PRIORITY = 1000;

    /** Файл анимации: assets/murim/player_animations/ceremonial_draw.json */
    public static final ResourceLocation CEREMONIAL_DRAW =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "ceremonial_draw");

    /**
     * Поза лотоса для церемонии создания даньтяня.
     *
     * <p>Зациклена: церемония длится дольше анимации и ждёт выбора игрока неопределённое
     * время. Незацикленная поза «отпустила» бы тело посреди сцены.
     */
    public static final ResourceLocation LOTUS =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "lotus");

    /** Анимация берётся из описания техники; эта константа осталась запасным вариантом. */
    public static final ResourceLocation DEMON_PALM =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "demon_palm");

    /**
     * Регистрирует фабрику слоя. Вызывается один раз при инициализации клиента: PAL сам создаст
     * контроллер для каждого игрока, включая чужих, — техника видна и со стороны.
     */
    /**
     * Отдельный слой для форм основы меча (ЛКМ): у него модификатор скорости — анимация формы
     * растягивается под перезарядку оружия и никогда не медленнее ванильного удара.
     */
    private static final ResourceLocation FOUNDATION_LAYER =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "foundation_layer");

    public static void register() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER, PRIORITY,
                MurimPlayerAnimations::createController);
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(FOUNDATION_LAYER, PRIORITY - 1,
                player -> {
                    PlayerAnimationController controller =
                            new PlayerAnimationController(player, (c, data, setter) -> PlayState.STOP);
                    // API: com.zigythebird.playeranimcore.animation.layered.modifier.SpeedModifier (javap, PAL 1.1.5)
                    controller.addModifierLast(new com.zigythebird.playeranimcore.animation.layered.modifier.SpeedModifier(1.0F));
                    return controller;
                });
    }

    /** Форма основы меча с заданной скоростью воспроизведения. */
    public static boolean playForm(AbstractClientPlayer player, ResourceLocation animation, float speed) {
        IAnimation layer = PlayerAnimationAccess.getPlayerAnimationLayer(player, FOUNDATION_LAYER);
        if (!(layer instanceof PlayerAnimationController controller)) {
            return false;
        }
        for (var modifier : controller.getModifiers()) {
            if (modifier instanceof com.zigythebird.playeranimcore.animation.layered.modifier.SpeedModifier speedModifier) {
                speedModifier.speed = speed;
            }
        }
        return controller.triggerAnimation(animation);
    }

    private static IAnimation createController(AbstractClientPlayer player) {
        // Обработчик состояния ничего не решает сам: анимация запускается только явным
        // triggerAnimation по событию от сервера, поэтому по умолчанию слой молчит.
        return new PlayerAnimationController(player, (controller, data, setter) -> PlayState.STOP);
    }

    /**
     * Запускает анимацию у конкретного игрока.
     *
     * @return {@code false}, если слой не найден или анимация не загружена — например, при
     *         опечатке в пути к файлу; молча игнорировать такое нельзя, иначе техника
     *         останется без анимации без единого следа в логе
     */
    public static boolean play(AbstractClientPlayer player, ResourceLocation animation) {
        IAnimation layer = PlayerAnimationAccess.getPlayerAnimationLayer(player, LAYER);
        if (!(layer instanceof PlayerAnimationController controller)) {
            MurimMod.LOGGER.warn("Слой анимаций {} не зарегистрирован у игрока", LAYER);
            return false;
        }
        MurimMod.LOGGER.debug("Запуск анимации {} у сущности {}", animation, player.getId());
        if (!controller.triggerAnimation(animation)) {
            MurimMod.LOGGER.warn("Анимация {} не найдена: проверь assets/{}/player_animations/",
                    animation, animation.getNamespace());
            return false;
        }
        return true;
    }

    /**
     * Останавливает текущую анимацию слоя у игрока — например, позу лотоса, когда
     * медитация закончилась.
     *
     * <p>API: com.zigythebird.playeranimcore.animation.AnimationController#stop()
     * (javap по jar PAL 1.1.x; у PlayerAnimationController своего метода нет, он наследуется).
     */
    public static void stop(AbstractClientPlayer player) {
        IAnimation layer = PlayerAnimationAccess.getPlayerAnimationLayer(player, LAYER);
        if (layer instanceof PlayerAnimationController controller) {
            controller.stop();
        }
    }

    private MurimPlayerAnimations() {
    }
}
