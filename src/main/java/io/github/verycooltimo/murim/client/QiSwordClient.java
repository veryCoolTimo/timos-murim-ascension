package io.github.verycooltimo.murim.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.QiSword;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
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
 * гасит его сразу. Рисуется предметом {@code murim:qi_sword} с полным светом: от третьего лица —
 * слоем модели игрока (как ванильный ItemInHandLayer), от первого — подменой пустой руки в
 * {@link RenderHandEvent}.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class QiSwordClient {

    /** Полный свет: клинок из ци светится сам и ночью не темнеет. */
    private static final int FULL_BRIGHT = 0xF000F0;

    private static final Vector3f QI_BLUE = new Vector3f(0.45F, 0.80F, 1.0F);

    /** Игровое время последнего мечевого действия по id сущности (кэш клиента, не игровое состояние). */
    private static final Map<Integer, Long> MARKS = new HashMap<>();

    private static ItemStack stack;

    private static ItemStack stack() {
        if (stack == null) {
            stack = new ItemStack(ModItems.QI_SWORD.get());
        }
        return stack;
    }

    /** Есть ли у своего игрока чем бить основой: меч или пустая рука пробуждённого. */
    public static boolean hasBlade(Player player) {
        return QiSword.holdsSword(player.getMainHandItem())
                || (player.getMainHandItem().isEmpty() && ClientProfileState.profile().isAwakened());
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
        event.setCanceled(true);
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
        mc.getEntityRenderDispatcher().getItemInHandRenderer().renderItem(mc.player, stack(),
                right ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
                !right, pose, event.getMultiBufferSource(), FULL_BRIGHT);
        pose.popPose();
    }

    /** Слой модели игрока: клинок в правой руке от третьего лица (как ванильный ItemInHandLayer). */
    public static final class Layer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

        private final ItemInHandRenderer items;

        public Layer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent, ItemInHandRenderer items) {
            super(parent);
            this.items = items;
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
            pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
            pose.mulPose(Axis.YP.rotationDegrees(180.0F));
            pose.translate((left ? -1 : 1) / 16.0F, 0.125F, -0.625F);
            items.renderItem(player, stack(), left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    left, pose, buffers, FULL_BRIGHT);
            pose.popPose();
        }
    }

    private QiSwordClient() {
    }
}
