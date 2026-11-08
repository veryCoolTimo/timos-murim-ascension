package io.github.verycooltimo.murim.client;

import com.zigythebird.playeranim.animation.PlayerAnimationController;
import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import com.zigythebird.playeranim.api.PlayerAnimationFactory;
import com.zigythebird.playeranimcore.animation.layered.IAnimation;
import com.zigythebird.playeranimcore.bones.PlayerAnimBone;
import com.zigythebird.playeranimcore.animation.layered.modifier.AbstractFadeModifier;
import com.zigythebird.playeranimcore.easing.EasingType;
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
    /** Цикл дыхания в лотосе (4 с): после того как лотос сел. */
    public static final ResourceLocation MEDITATION_BREATH =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "meditation_breath");

    /** Анимация берётся из описания техники; эта константа осталась запасным вариантом. */
    public static final ResourceLocation DEMON_PALM =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "demon_palm");

    /**
     * Регистрирует фабрику слоя. Вызывается один раз при инициализации клиента: PAL сам создаст
     * контроллер для каждого игрока, включая чужих, — техника видна и со стороны.
     */
    /**
     * Отдельный слой для форм основы меча (ЛКМ): у него модификатор скорости — анимация формы
     * Шести Равновесий растягивается под перезарядку оружия; у основ стилей скорость 1,
     * короткий клип не растягивается вместе с оружием.
     */
    private static final ResourceLocation FOUNDATION_LAYER =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "foundation_layer");

    private static final ResourceLocation GUARD_LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sword_guard_layer");
    private static final ResourceLocation GUARD = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sword_guard");

    /** Arms only; technique layer keeps priority, guard never owns legs or root motion. */
    public static void guard(AbstractClientPlayer player, boolean active) {
        IAnimation layer = PlayerAnimationAccess.getPlayerAnimationLayer(player, GUARD_LAYER);
        if (layer instanceof PlayerAnimationController controller) {
            if (active && !controller.isActive()) start(controller, GUARD, 0, GUARD_LAYER);
            if (!active && controller.isActive() && !owns(controller, REST)) {
                if (blend(controller, REST, AnimationTransitions.STEP_FADE_OUT_TICKS, 0)) {
                    clips.put(controller, REST);
                    trace("stop", GUARD, GUARD_LAYER, true, AnimationTransitions.STEP_FADE_OUT_TICKS);
                }
            }
        }
    }

    public static void register() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(GUARD_LAYER, PRIORITY - 1,
                MurimPlayerAnimations::createController);
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER, PRIORITY,
                MurimPlayerAnimations::createController);
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(FOUNDATION_LAYER, PRIORITY - 2,
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
        return start(controller, animation, 0, FOUNDATION_LAYER);
    }

    private static IAnimation createController(AbstractClientPlayer player) {
        // Обработчик состояния ничего не решает сам: анимация запускается только явным
        // triggerAnimation по событию от сервера, поэтому по умолчанию слой молчит.
        PlayerAnimationController controller = new PlayerAnimationController(player, (c, data, setter) -> PlayState.STOP);
        controller.addModifierLast(new com.zigythebird.playeranimcore.animation.layered.modifier.SpeedModifier(1));
        return controller;
    }

    /**
     * Запускает анимацию у конкретного игрока.
     *
     * @return {@code false}, если слой не найден или анимация не загружена — например, при
     *         опечатке в пути к файлу; молча игнорировать такое нельзя, иначе техника
     *         останется без анимации без единого следа в логе
     */
    public static boolean play(AbstractClientPlayer player, ResourceLocation animation) {
        return play(player, animation, 0);
    }

    /** API: PAL 1.1.5 source PlayerAnimationController#triggerAnimation(ResourceLocation,float),
     * AnimationController documents the starting position in ticks. */
    public static boolean play(AbstractClientPlayer player, ResourceLocation animation, int skipTicks) {
        IAnimation layer = PlayerAnimationAccess.getPlayerAnimationLayer(player, LAYER);
        if (!(layer instanceof PlayerAnimationController controller)) {
            MurimMod.LOGGER.warn("Слой анимаций {} не зарегистрирован у игрока", LAYER);
            return false;
        }
        MurimMod.LOGGER.debug("Запуск анимации {} у сущности {}", animation, player.getId());
        speed(controller, 1);
        if (!start(controller, animation, skipTicks)) {
            MurimMod.LOGGER.warn("Анимация {} не найдена: проверь assets/{}/player_animations/",
                    animation, animation.getNamespace());
            return false;
        }
        return true;
    }

    /** Reuses the authored poses, changing only playback timing. */
    public static boolean playTimed(AbstractClientPlayer player, ResourceLocation animation, float rate) {
        if (!play(player, animation)) return false;
        var controller = techniqueController(player);
        if (controller != null) speed(controller, rate);
        return true;
    }

    // Weak keys follow PAL's per-player controller lifetime; this is client visual ownership only.
    private static final java.util.Map<PlayerAnimationController, ResourceLocation> clips = new java.util.WeakHashMap<>();

    static PlayerAnimationController techniqueController(AbstractClientPlayer player) {
        IAnimation layer = PlayerAnimationAccess.getPlayerAnimationLayer(player, LAYER);
        return layer instanceof PlayerAnimationController controller ? controller : null;
    }

    static boolean owns(PlayerAnimationController controller, ResourceLocation clip) {
        return clip.equals(clips.get(controller));
    }

    static void speed(PlayerAnimationController controller, float value) {
        for (var modifier : controller.getModifiers()) {
            if (modifier instanceof com.zigythebird.playeranimcore.animation.layered.modifier.SpeedModifier speed) speed.speed = value;
        }
    }

    static void trace(String action, ResourceLocation clip, ResourceLocation layer, boolean blend, int ticks) {
        if (System.getProperty("murim.capture.technique") != null) {
            MurimMod.LOGGER.info("ANIM {} {} layer={} mode={} ticks={}", action, clip, layer,
                    blend ? "blend" : "hard", ticks);
        }
    }

    /** PAL AnimationController#replaceAnimationWithFade snapshots activeBones on this controller.
     * Forms and steps share it so the source is the displayed pose, including an interrupted fade. */
    static boolean start(PlayerAnimationController controller, ResourceLocation animation, int skipTicks) {
        return start(controller, animation, skipTicks, LAYER);
    }

    private static boolean start(PlayerAnimationController controller, ResourceLocation animation, int skipTicks,
                                 ResourceLocation layer) {
        boolean active = controller.isActive();
        boolean ok = AnimationTransitions.start(active,
                () -> {
                    boolean started = controller.triggerAnimation(animation, Math.max(0, skipTicks));
                    if (started) controller.removeModifierIf(modifier -> modifier instanceof AbstractFadeModifier);
                    return started;
                },
                ticks -> blend(controller, animation, ticks, skipTicks));
        if (ok) {
            clips.put(controller, animation);
            trace("start", animation, layer, active, active ? AnimationTransitions.CROSSFADE_TICKS : 0);
        }
        return ok;
    }

    private static boolean blend(PlayerAnimationController controller, ResourceLocation animation,
                                 int fadeTicks, int skipTicks) {
        // API: PAL 1.1.5 PlayerAnimationController#registerBones, IAnimation#get3DTransform,
        // PlayerAnimBone#copyOtherBone. Capture the composed output, including an existing fade.
        java.util.Map<String, PlayerAnimBone> pose = new java.util.HashMap<>();
        if (controller.isActive()) {
            for (String bone : new String[]{"body", "torso", "head", "right_arm", "left_arm",
                    "right_leg", "left_leg", "right_item", "left_item", "cape", "elytra"}) {
                pose.put(bone, new PlayerAnimBone(bone).copyOtherBone(
                        controller.get3DTransform(new PlayerAnimBone(bone))));
            }
        }
        // Validate/trigger before removing the old fade, so missing assets leave the pose intact.
        boolean ok = controller.triggerAnimation(animation, Math.max(0, skipTicks));
        if (ok) {
            controller.removeModifierIf(modifier -> modifier instanceof AbstractFadeModifier);
            AbstractFadeModifier fade = AbstractFadeModifier.standardFadeIn(fadeTicks, EasingType.EASE_IN_OUT_SINE);
            if (!pose.isEmpty()) fade.setTransitionAnimation(new IAnimation() {
                @Override public boolean isActive() { return true; }
                @Override public PlayerAnimBone get3DTransform(PlayerAnimBone bone) {
                    PlayerAnimBone saved = pose.get(bone.getName());
                    return saved == null ? bone : bone.copyOtherBone(saved);
                }
            });
            // API: PAL AnimationController#addModifierBefore: the fade clock must run outside
            // SpeedModifier, otherwise a held counter (speed 0) freezes the transition too.
            controller.addModifierBefore(fade);
        }
        return ok;
    }

    /** Пустой клип (0,3 с, без костей): цель плавного выхода — кости уходят к ванильной позе. */
    private static final ResourceLocation REST = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "rest");

    /**
     * Запуск с плавным входом из текущей позы (idle или прошлый клип) за {@code fadeTicks} тиков —
     * для клипов, которые начинаются не в позе покоя (циклы стойки всадника, ношения камня):
     * без этого тело прыгает в первую позу за кадр (автор 10.10: «не дёрганными»).
     *
     * <p>API: PAL 1.1.5 AbstractFadeModifier#standardFadeIn and
     * AnimationController#addModifierBefore; see the composed-pose snapshot in blend().
     */
    public static boolean playBlended(AbstractClientPlayer player, ResourceLocation animation, int fadeTicks) {
        IAnimation layer = PlayerAnimationAccess.getPlayerAnimationLayer(player, LAYER);
        if (!(layer instanceof PlayerAnimationController controller)) {
            return false;
        }
        speed(controller, 1);
        boolean ok = blend(controller, animation, fadeTicks, 0);
        if (ok) {
            clips.put(controller, animation);
            trace("start", animation, LAYER, true, fadeTicks);
        }
        if (!ok) {
            MurimMod.LOGGER.warn("Анимация {} не найдена: проверь assets/{}/player_animations/",
                    animation, animation.getNamespace());
        }
        return ok;
    }

    /**
     * Плавный выход в ванильную позу за {@code fadeTicks} тиков: слой
     * переходит в пустой клип {@code murim:rest}, а когда тот кончается — останавливается сам.
     * [НЕПРОВЕРЕНО: кости, которых нет в целевом клипе, PAL ведёт к ванильной позе; проверено съёмкой
     * выхода из лотоса и стойки всадника на стенде animreel.]
     */
    public static void stopBlended(AbstractClientPlayer player, int fadeTicks) {
        PlayerAnimationController controller = techniqueController(player);
        if (controller != null) stopBlended(controller, fadeTicks);
    }

    static void stopBlended(PlayerAnimationController controller, int fadeTicks) {
        if (!controller.isActive() || owns(controller, REST)) return;
        ResourceLocation clip = clips.get(controller);
        speed(controller, 1);
        if (blend(controller, REST, fadeTicks, 0)) {
            clips.put(controller, REST);
            trace("stop", clip, LAYER, true, fadeTicks);
        } else {
            MurimMod.LOGGER.warn("Cannot fade animation {}: rest clip missing", clip);
        }
    }

    /** A delayed cancellation must not fade a newer sword attack on the shared layer. */
    public static void stopOwned(AbstractClientPlayer player, ResourceLocation animation) {
        var controller = techniqueController(player);
        if (controller != null && owns(controller, animation)) stopBlended(controller, AnimationTransitions.STEP_FADE_OUT_TICKS);
    }

    /** Ends the current clip through a four-tick fade to rest. */
    public static void stop(AbstractClientPlayer player) {
        stopBlended(player, AnimationTransitions.STEP_FADE_OUT_TICKS);
    }

    private MurimPlayerAnimations() {
    }
}
