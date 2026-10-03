package io.github.verycooltimo.murim.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.QiSword;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Ци-меч на клиенте (03.10): светящийся клинок в пустой руке пробуждённого, пока тот бьёт
 * мечевыми формами. Появляется сам — на старт мечевой техники (TechniqueEvent STARTED) и на взмах
 * основы ЛКМ, гаснет через {@link QiSword#HOLD_TICKS} тиков без мечевых действий; предмет в руке
 * гасит его сразу. Рисуется симуляцией {@link io.github.verycooltimo.murim.client.vfx.QiBladeRenderer}
 * (реф «Keen Qi»): от третьего лица — слоем модели игрока у кулака, от первого — поверх пустой руки в
 * {@link RenderHandEvent}.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class QiSwordClient {

    private static final Vector3f QI_BLUE = new Vector3f(0.62F, 0.32F, 1.0F);

    /** Игровое время последнего мечевого действия по id сущности (кэш клиента, не игровое состояние). */
    private static final Map<Integer, Long> MARKS = new HashMap<>();

    /** Есть ли у своего игрока чем бить основой: меч или пустая рука пробуждённого. */
    public static boolean hasBlade(Player player) {
        return QiSword.holdsSword(player.getMainHandItem())
                || (player.getMainHandItem().isEmpty() && QiSword.available(ClientProfileState.profile()));
    }

    /** Мечевое действие: если рука пуста — ци-меч появляется (с выбросом ци, если его не было). */
    public static void mark(Player player) {
        if (player == null || !player.getMainHandItem().isEmpty()) {
            return;
        }
        boolean was = visible(player);
        MARKS.put(player.getId(), player.level().getGameTime());
        if (!was) {
            burst(player);
        }
    }

    public static boolean visible(Player player) {
        if (!player.getMainHandItem().isEmpty()) {
            return false;
        }
        Long at = MARKS.get(player.getId());
        return at != null && player.level().getGameTime() - at < QiSword.HOLD_TICKS;
    }

    /** Выброс ци у кисти в момент появления клинка: голубая пыль вдоль будущего лезвия. */
    private static void burst(Player p) {
        double yaw = Math.toRadians(p.yBodyRot);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        Vec3 hand = new Vec3(p.getX() + rx * 0.38D + fx * 0.25D, p.getY() + 0.75D, p.getZ() + rz * 0.38D + fz * 0.25D);
        for (int i = 0; i < 14; i++) {
            double k = i / 13.0D;
            p.level().addParticle(new DustParticleOptions(QI_BLUE, 0.6F + (float) (0.4D * (1.0D - k))),
                    hand.x + fx * k * 0.9D, hand.y + k * 0.15D, hand.z + fz * k * 0.9D,
                    (p.getRandom().nextDouble() - 0.5D) * 0.04D, 0.02D, (p.getRandom().nextDouble() - 0.5D) * 0.04D);
        }
    }

    /**
     * Редкие короткие искры ци вдоль клинка (codex 03.10: «частицы у кончика»): раз в 3 тика у
     * каждого, у кого клинок виден; у себя от первого лица — реже, чтобы не мельтешило у глаз.
     */
    @SubscribeEvent
    static void onTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused() || MARKS.isEmpty()) {
            return;
        }
        for (Player p : mc.level.players()) {
            if (!visible(p)) {
                continue;
            }
            boolean self = p == mc.player && mc.options.getCameraType().isFirstPerson();
            // Касание земли (реф, кадры 4 и 6): опущенный клинок упирается в пол — всплеск и
            // разбегающиеся по земле розовые разряды. Только когда рука опущена (нет взмаха).
            if (!self && p.onGround() && p.getAttackAnim(0.0F) == 0.0F && p.tickCount % 2 == 0) {
                double yaw = Math.toRadians(p.yBodyRot);
                double fx = -Math.sin(yaw), fz = Math.cos(yaw);
                double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
                Vec3 hit = new Vec3(p.getX() + rx * 0.37D + fx * 1.15D, p.getY() + 0.02D, p.getZ() + rz * 0.37D + fz * 1.15D);
                net.minecraft.core.BlockPos below = net.minecraft.core.BlockPos.containing(hit.x, hit.y - 0.1D, hit.z);
                if (!p.level().getBlockState(below).isAir()) {
                    for (int i = 0; i < 3; i++) {
                        double ang = p.getRandom().nextDouble() * Math.PI * 2.0D;
                        p.level().addParticle(new DustParticleOptions(new Vector3f(1.0F, 0.62F, 0.98F), 0.6F),
                                hit.x, hit.y + 0.05D, hit.z, Math.cos(ang) * 0.12D, 0.01D, Math.sin(ang) * 0.12D);
                    }
                    if (p.tickCount % 6 == 0) {
                        p.level().addParticle(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, hit.x, hit.y + 0.1D, hit.z, 0.0D, 0.05D, 0.0D);
                    }
                }
            }
            if (p.tickCount % (self ? 7 : 3) != 0) {
                continue;
            }
            double yaw = Math.toRadians(p.yBodyRot);
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);
            double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
            double k = 0.3D + p.getRandom().nextDouble() * 0.7D;
            Vec3 at = new Vec3(p.getX() + rx * 0.38D + fx * (0.3D + k * 0.8D), p.getY() + 0.8D + k * 0.1D,
                    p.getZ() + rz * 0.38D + fz * (0.3D + k * 0.8D));
            p.level().addParticle(new DustParticleOptions(QI_BLUE, 0.45F), at.x, at.y, at.z, 0.0D, 0.015D, 0.0D);
        }
    }

    public static void reset() {
        MARKS.clear();
    }

    /**
     * От первого лица: вместо пустой руки — клинок, теми же преобразованиями, что ваниль даёт
     * предмету в руке. API: reference/minecraft-src/net/minecraft/client/renderer/ItemInHandRenderer.java
     * #renderArmWithItem/applyItemArmTransform/applyItemArmAttackTransform (они приватные — повторены здесь).
     */
    @SubscribeEvent
    static void onRenderHand(RenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getHand() != InteractionHand.MAIN_HAND || mc.player == null || !event.getItemStack().isEmpty() || !visible(mc.player)) {
            return;
        }
        // Рука остаётся ванильной (пустая ладонь), клинок растёт из кулака: те же смещения,
        // что ваниль даёт предмету в руке (кулак), и тот же взмах.
        PoseStack pose = event.getPoseStack();
        float swing = event.getSwingProgress();
        float equip = event.getEquipProgress();
        boolean right = mc.player.getMainArm() == HumanoidArm.RIGHT;
        int i = right ? 1 : -1;
        pose.pushPose();
        float sq = Mth.sqrt(swing);
        pose.translate(i * -0.4F * Mth.sin(sq * Mth.PI), 0.2F * Mth.sin(sq * Mth.TWO_PI), -0.2F * Mth.sin(swing * Mth.PI));
        pose.translate(i * 0.56F, -0.52F + equip * -0.6F, -0.72F);
        float f = Mth.sin(swing * swing * Mth.PI);
        pose.mulPose(Axis.YP.rotationDegrees(i * (45.0F + f * -20.0F)));
        float f1 = Mth.sin(sq * Mth.PI);
        pose.mulPose(Axis.ZP.rotationDegrees(i * f1 * -20.0F));
        pose.mulPose(Axis.XP.rotationDegrees(f1 * -80.0F));
        pose.mulPose(Axis.YP.rotationDegrees(i * -45.0F));
        float time = mc.player.tickCount + event.getPartialTick();
        io.github.verycooltimo.murim.client.vfx.QiBladeRenderer.draw(pose, event.getMultiBufferSource(),
                new Vec3(i * 0.02D, 0.06D, -0.02D), new Vec3(i * -0.18D, 0.86D, -0.48D).normalize(),
                new Vec3(i * 0.2D, -0.7D, 0.7D).normalize(), 1.5D, time,
                mc.player.getId(), 0.75F);
        pose.popPose();
    }

    /** Слой модели игрока: клинок в правой руке от третьего лица (как ванильный ItemInHandLayer). */
    public static final class Layer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

        public Layer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        // API: reference/minecraft-src/net/minecraft/client/renderer/entity/layers/ItemInHandLayer.java#renderArmWithItem
        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing,
                           float limbSwingAmount, float partial, float age, float headYaw, float headPitch) {
            if (!visible(player)) {
                return;
            }
            HumanoidArm arm = player.getMainArm();
            boolean left = arm == HumanoidArm.LEFT;
            pose.pushPose();
            getParentModel().translateToHand(arm, pose);
            // В системе руки: кулак у нижнего конца руки, клинок вперёд и чуть вниз от кулака
            // (рука опущена — лезвие к земле, как на рефе; рука вперёд — лезвие на противника).
            float time = player.tickCount + partial;
            io.github.verycooltimo.murim.client.vfx.QiBladeRenderer.draw(pose, buffers,
                    new Vec3(left ? 0.0625D : -0.0625D, 0.66D, -0.02D), new Vec3(0.0D, 0.6D, -1.0D).normalize(), new Vec3(0.0D, -1.0D, 0.0D), 2.0D, time,
                    player.getId(), 1.0F);
            pose.popPose();
        }
    }

    private QiSwordClient() {
    }
}
