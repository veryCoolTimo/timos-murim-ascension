package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.AwakeningSceneHandler;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Сцена создания даньтяня: жилы по телу, собирающееся ядро и три огня выбора.
 *
 * <p>Палитра холодная и светящаяся, а не тёмная — по референсу автора. Плотность растёт
 * к средоточию: жилы сходятся и утолщаются, а в точке под пупком собирается ядро.
 *
 * <p>Стадия — {@code AFTER_PARTICLES}, как у остальных мировых эффектов мода (правило 04).
 * Собственного состояния у рендерера нет: он целиком читает {@link AwakeningSceneHandler},
 * который в свою очередь получает фазу с сервера.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class AwakeningVfxRenderer {

    /** Жил в пучке от одной конечности. Единицы, не десятки: сеть на модели нечитаема. */
    private static final int VEINS_PER_LIMB = 3;

    /** Семя рисунка жил. Постоянное: узор не должен мерцать между кадрами. */
    private static final long SEED = 0x0DA07A19L;

    /** Холодная палитра сцены. */
    private static final VfxColour VEIN = new VfxColour(0.42F, 0.78F, 1.0F);
    private static final VfxColour VEIN_DEEP = new VfxColour(0.24F, 0.52F, 0.95F);
    private static final VfxColour CORE = new VfxColour(0.86F, 0.96F, 1.0F);

    /** Цвета трёх оснований: их видно ДО выбора, и они не похожи друг на друга. */
    private static final VfxColour BLOOD = new VfxColour(1.0F, 0.28F, 0.30F);
    private static final VfxColour VOID_TINT = new VfxColour(0.62F, 0.42F, 1.0F);
    private static final VfxColour MOUNTAIN = new VfxColour(0.95F, 0.76F, 0.34F);

    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                || !AwakeningSceneHandler.active()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !(minecraft.player instanceof AbstractClientPlayer player)) {
            return;
        }
        // Кость даньтяня приходит из слоя рендера игрока. Нет её — игрок в этом кадре
        // не рисовался, и ставить сцену вслепую нельзя: она окажется не на теле.
        Vec3 core = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.DANTIAN);
        if (core == null) {
            return;
        }

        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float age = AwakeningSceneHandler.tick() + partial;
        String phase = AwakeningSceneHandler.phase();
        float glow = glowOf(phase, age);
        if (glow <= 0.0F) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        try {
            poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
            PoseStack.Pose pose = poseStack.last();
            // Камера в МИРОВЫХ координатах: сцена рисуется без поворота к телу игрока,
            // потому что все точки уже приходят из костей в мировой системе.
            Vec3 cameraLocal = cameraPos;

            drawVeins(minecraft, player, pose, cameraLocal, core, phase, age, glow);
            drawCore(minecraft, pose, cameraLocal, core, phase, age, glow);
            if ("CHOICE".equals(phase)) {
                drawChoices(minecraft, pose, cameraLocal, core, age);
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Меридианы: поток снизу вверх по телу через узлы.
     *
     * <p>Порядок ветвей задан руками: ступни → колени → средоточие → грудь → плечи →
     * кисти, и отдельной ветвью грудь → голова. Автор прямо указал, что каналы должны
     * идти ПО ТЕЛУ через точки, а не сходиться спицами к центру.
     */
    private static void drawVeins(Minecraft minecraft, AbstractClientPlayer player,
                                  PoseStack.Pose pose, Vec3 cameraLocal, Vec3 core,
                                  String phase, float age, float glow) {
        float front = switch (phase) {
            case "SETTLE" -> 0.0F;
            case "VEINS" -> Mth.clamp(age / 80.0F, 0.0F, 1.0F);
            // Дойдя до верха, каналы ГАСНУТ, и только потом собирается ядро.
            // Это ритм сцены: сначала тело наполняется, затем отдаёт.
            default -> 0.0F;
        };
        if (front <= 0.0F) {
            return;
        }
        Vec3 rightFoot = bone(player, BoneAnchorLayer.Bone.RIGHT_FOOT);
        Vec3 leftFoot = bone(player, BoneAnchorLayer.Bone.LEFT_FOOT);
        Vec3 rightKnee = bone(player, BoneAnchorLayer.Bone.RIGHT_KNEE);
        Vec3 leftKnee = bone(player, BoneAnchorLayer.Bone.LEFT_KNEE);
        Vec3 chest = bone(player, BoneAnchorLayer.Bone.CHEST);
        Vec3 rightShoulder = bone(player, BoneAnchorLayer.Bone.RIGHT_SHOULDER);
        Vec3 leftShoulder = bone(player, BoneAnchorLayer.Bone.LEFT_SHOULDER);
        Vec3 rightHand = bone(player, BoneAnchorLayer.Bone.RIGHT_HAND);
        Vec3 leftHand = bone(player, BoneAnchorLayer.Bone.LEFT_HAND);
        Vec3 head = bone(player, BoneAnchorLayer.Bone.HEAD);

        java.util.List<Vec3[]> spine = java.util.List.of(
                new Vec3[] {rightFoot, rightKnee},
                new Vec3[] {rightKnee, core},
                new Vec3[] {core, chest},
                new Vec3[] {chest, head});
        java.util.List<Vec3[]> left = java.util.List.of(
                new Vec3[] {leftFoot, leftKnee},
                new Vec3[] {leftKnee, core});
        java.util.List<Vec3[]> arms = java.util.List.of(
                new Vec3[] {chest, rightShoulder},
                new Vec3[] {rightShoulder, rightHand},
                new Vec3[] {chest, leftShoulder},
                new Vec3[] {leftShoulder, leftHand});

        VertexConsumer channel = minecraft.renderBuffers().bufferSource()
                .getBuffer(MurimRenderTypes.bodyGlow());
        // Ветви идут не одновременно: ноги наполняются первыми, руки и голова следом.
        MeridianFlow.draw(channel, channel, pose, cameraLocal, spine, front,
                          0.030D, 0.95F, VEIN, CORE);
        MeridianFlow.draw(channel, channel, pose, cameraLocal, left, front * 1.15F,
                          0.030D, 0.95F, VEIN_DEEP, CORE);
        MeridianFlow.draw(channel, channel, pose, cameraLocal, arms,
                          (front - 0.45F) / 0.55F, 0.026D, 0.90F, VEIN, CORE);
        minecraft.renderBuffers().bufferSource().endBatch(MurimRenderTypes.bodyGlow());
    }

    /** Кость или {@code null}: сцена не рисуется по несуществующей точке. */
    private static Vec3 bone(AbstractClientPlayer player, BoneAnchorLayer.Bone which) {
        return BoneAnchorLayer.position(player, which);
    }

    /**
     * Средоточие СОБИРАЕТСЯ на глазах.
     *
     * <p>Замечание автора: «не видно, что что-то формируется — просто частицы бегают,
     * взрываются, и всё». Прежняя версия и правда только сыпала искры в точку.
     *
     * <p>Теперь у ядра есть <b>оболочка</b>: широкое кольцо стягивается к центру и
     * уплотняется в шар с видимой границей. Радиус падает, яркость и плотность растут —
     * зритель видит объект, а не поток частиц. Искры остались, но они уже не главные.
     */
    private static void drawCore(Minecraft minecraft, PoseStack.Pose pose, Vec3 cameraLocal,
                                 Vec3 core, String phase, float age, float glow) {
        float density = switch (phase) {
            case "SETTLE", "VEINS" -> 0.0F;
            case "CORE" -> Mth.clamp(age / 60.0F, 0.0F, 1.0F);
            default -> 1.0F;
        };
        if (density <= 0.0F) {
            return;
        }
        VertexConsumer consumer = minecraft.renderBuffers().bufferSource()
                .getBuffer(MurimRenderTypes.impactCore());
        // Ядро приподнято над точкой кости: на кадре свечение упиралось в пол.
        Vec3 lifted = core.add(0.0D, 0.12D, 0.0D);

        // ОБОЛОЧКА: кольцо стягивается от полуметра к пяти сантиметрам. Именно её
        // сжатие читается как «собирается объект».
        int shell = 18;
        double radius = 0.52D * (1.0D - density) + 0.06D;
        for (int i = 0; i < shell; i++) {
            double angle = i * (Math.PI * 2.0D / shell) + age * 0.05D;
            double tilt = Math.sin(i * 2.3D + age * 0.03D) * radius * 0.55D;
            Vec3 point = lifted.add(Math.cos(angle) * radius, tilt, Math.sin(angle) * radius);
            // Чем плотнее ядро, тем крупнее и ярче его куски: граница уплотняется.
            VfxDraw.billboard(consumer, pose, point, cameraLocal,
                              0.020D + 0.055D * density, (0.25F + 0.75F * density) * glow,
                              VEIN.red(), VEIN.green(), VEIN.blue());
        }

        CoreGlow.draw(consumer, pose, lifted, cameraLocal, age,
                      0.06D + 0.13D * density, density * glow, VEIN_DEEP, CORE);

        // Искры втягиваются в ядро и разлетаются только на печати.
        if ("SEAL".equals(phase)) {
            BillboardBurst.outward(consumer, pose, lifted, cameraLocal, 40, age, 0.48D,
                                   glow, VEIN, CORE);
        } else {
            BillboardBurst.inward(consumer, pose, lifted, cameraLocal, 28, age, 0.42D,
                                  density * glow * 0.7F, VEIN, CORE);
        }
        minecraft.renderBuffers().bufferSource().endBatch(MurimRenderTypes.impactCore());
    }

    /**
     * Три огня выбора вокруг игрока.
     *
     * <p>Цена основания читается текстом в сцене, а огни дают ей цвет и место: выбор
     * должен быть виден на теле и вокруг него, а не в окне интерфейса.
     */
    private static void drawChoices(Minecraft minecraft, PoseStack.Pose pose, Vec3 cameraLocal,
                                    Vec3 core, float age) {
        VertexConsumer consumer = minecraft.renderBuffers().bufferSource()
                .getBuffer(MurimRenderTypes.impactCore());
        VfxColour[] tints = {BLOOD, VOID_TINT, MOUNTAIN};
        for (int i = 0; i < tints.length; i++) {
            // Огни стоят ДУГОЙ ПЕРЕД игроком, а не по кругу. По кругу один из трёх
            // всегда оказывается за спиной, а камера в этой сцене фронтальная: игрок
            // физически не видел бы третий вариант в момент выбора.
            double sway = Math.sin(age * 0.015D + i) * 0.18D;
            double angle = Math.PI * 0.5D + (i - 1) * 0.72D + sway;
            double radius = 1.15D;
            // Огни плавают по высоте вразнобой: одинаковая высота читалась бы как
            // элемент интерфейса, а не как часть сцены.
            double lift = 0.35D + 0.12D * Math.sin(age * 0.06D + i * 2.1D);
            Vec3 spot = core.add(Math.cos(angle) * radius, lift, Math.sin(angle) * radius);
            float pulse = 0.72F + 0.28F * Mth.sin(age * 0.09F + i * 1.7F);
            CoreGlow.draw(consumer, pose, spot, cameraLocal, age, 0.13D, pulse,
                          tints[i], CORE);
        }
        minecraft.renderBuffers().bufferSource().endBatch(MurimRenderTypes.impactCore());
    }

    /**
     * Общая кривая яркости сцены.
     *
     * <p>Повторяет {@code AwakeningState.glow()} по смыслу, но считается по ДРОБНОМУ
     * возрасту: серверная копия шагает раз в тик, и рендер по ней давал бы ступеньку
     * на шестидесяти кадрах в секунду (правило 04).
     */
    private static float glowOf(String phase, float age) {
        return switch (phase) {
            case "SETTLE" -> 0.12F * Mth.clamp(age / 50.0F, 0.0F, 1.0F);
            case "VEINS" -> 0.12F + 0.58F * Mth.clamp(age / 80.0F, 0.0F, 1.0F);
            case "CORE" -> 0.70F + 0.30F * Mth.clamp(age / 60.0F, 0.0F, 1.0F);
            case "CHOICE" -> 1.0F;
            case "SEAL" -> 1.0F - 0.55F * Mth.clamp(age / 45.0F, 0.0F, 1.0F);
            default -> 0.0F;
        };
    }

    private AwakeningVfxRenderer() {
    }
}
