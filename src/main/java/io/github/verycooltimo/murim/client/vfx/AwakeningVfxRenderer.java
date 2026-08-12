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
     * Жилы от конечностей и головы к средоточию.
     *
     * <p>Рост идёт ОТ конечностей внутрь: сначала проступают концы, поток стекается вниз.
     */
    private static void drawVeins(Minecraft minecraft, AbstractClientPlayer player,
                                  PoseStack.Pose pose, Vec3 cameraLocal, Vec3 core,
                                  String phase, float age, float glow) {
        float grown = switch (phase) {
            case "SETTLE" -> 0.0F;
            case "VEINS" -> Mth.clamp(age / 80.0F, 0.0F, 1.0F);
            default -> 1.0F;
        };
        if (grown <= 0.0F) {
            return;
        }
        VertexConsumer consumer = minecraft.renderBuffers().bufferSource()
                .getBuffer(MurimRenderTypes.bodyGlow());
        BoneAnchorLayer.Bone[] limbs = {
                BoneAnchorLayer.Bone.RIGHT_HAND, BoneAnchorLayer.Bone.LEFT_HAND,
                BoneAnchorLayer.Bone.RIGHT_FOOT, BoneAnchorLayer.Bone.LEFT_FOOT,
                BoneAnchorLayer.Bone.HEAD
        };
        for (int i = 0; i < limbs.length; i++) {
            Vec3 from = BoneAnchorLayer.position(player, limbs[i]);
            if (from == null) {
                continue;
            }
            // Конечности прорастают не одновременно: смещение по индексу даёт волну
            // от рук к ногам, а не одновременную вспышку всего тела.
            float offset = i * 0.09F;
            // Яркость жил НЕ привязана к общей кривой сцены: в собственной фазе они —
            // предмет показа, а общая кривая там ещё только разгорается (0.12..0.70).
            // На кадрах фаза «жилы» выглядела пустой при работающей геометрии.
            float veinAlpha = "VEINS".equals(phase) ? 0.55F + 0.45F * grown : glow * 0.85F;
            BodyVeins.draw(consumer, pose, from, core, cameraLocal, VEINS_PER_LIMB,
                           grown - offset, 0.055D, veinAlpha, SEED + i * 31L,
                           i % 2 == 0 ? VEIN : VEIN_DEEP);
        }
        minecraft.renderBuffers().bufferSource().endBatch(MurimRenderTypes.bodyGlow());
    }

    /** Средоточие: ядро собирается по мере того, как потоки стекаются вниз. */
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
        // Ядро приподнято над точкой кости: на кадре свечение упиралось в пол и
        // заливало его аддитивным светом, из-за чего сцена читалась как струя вниз.
        Vec3 lifted = core.add(0.0D, 0.12D, 0.0D);
        CoreGlow.draw(consumer, pose, lifted, cameraLocal, age, 0.17D, density * glow,
                      VEIN_DEEP, CORE);
        // Искры стекаются В ядро: направление читается и говорит «собирается», а не
        // «взрывается». Наружу они пойдут только на печати.
        boolean sealing = "SEAL".equals(phase);
        if (sealing) {
            BillboardBurst.outward(consumer, pose, lifted, cameraLocal, 40, age, 0.48D,
                                   glow, VEIN, CORE);
        } else {
            BillboardBurst.inward(consumer, pose, lifted, cameraLocal, 40, age, 0.48D,
                                  density * glow, VEIN, CORE);
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
