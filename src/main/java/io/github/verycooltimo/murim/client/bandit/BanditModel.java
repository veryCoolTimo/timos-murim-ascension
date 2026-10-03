package io.github.verycooltimo.murim.client.bandit;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.BanditArcher;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Optional;

/**
 * Модель бандита из Bedrock-файла (кости {@code root → waist → torso → head, arm_r → weapon,
 * arm_l; waist → leg_r, leg_l, skirt_*}) с клипами из {@code bedrock/<имя>.animation.json}.
 *
 * <p>Что играет: в бою — клип состояния ({@code windup*}, {@code attack*}, {@code recover*},
 * {@code hit}, {@code stun}) целиком, по времени от прихода состояния; вне боя — смесь
 * {@code idle} и {@code walk}/{@code run} по скорости шага. Попадание поверх всего — разница
 * клипа {@code hit} относительно его первого кадра (дёргает корпус, не ломая позу удара).
 * Лучник с натянутым луком держит обе руки на цель, как ванильный скелет.
 */
public class BanditModel<T extends Bandit> extends HierarchicalModel<T> implements ArmedModel {

    private static final Vector3f CACHE = new Vector3f();

    private final ModelPart root;
    private final String name;
    private final Optional<ModelPart> head;
    private final Optional<ModelPart> armR;
    private final Optional<ModelPart> armL;

    public BanditModel(ModelPart root, String name) {
        this.root = root;
        this.name = name;
        this.head = getAnyDescendantWithName("head");
        this.armR = getAnyDescendantWithName("arm_r");
        this.armL = getAnyDescendantWithName("arm_l");
    }

    @Override
    public ModelPart root() {
        return root;
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        root.getAllParts().forEach(ModelPart::resetPose);
        Map<String, AnimationDefinition> clips = BanditAnimations.get(name);
        float partial = ageInTicks - entity.tickCount;
        int state = entity.state();
        float age = entity.stateAge(partial);
        String suffix = entity.move().clip();
        String clip = switch (state) {
            case Bandit.WINDUP -> "windup" + suffix;
            case Bandit.STRIKE -> "attack" + suffix;
            case Bandit.RECOVER -> "recover" + suffix;
            case Bandit.STAGGER -> clips.containsKey("stagger") ? "stagger" : "hit";
            case Bandit.STUN -> "stun";
            default -> null;
        };
        AnimationDefinition combat = clip == null ? null : clips.get(clip);
        if (combat != null) {
            play(combat, age, 1.0F);
        } else {
            // Покой и шаг смешиваются по скорости: веса в сумме 1, поэтому смесь — это лерп поз.
            float walk = Mth.clamp(limbSwingAmount * 2.5F, 0.0F, 1.0F);
            boolean running = limbSwingAmount > 0.75F && clips.containsKey("run");
            AnimationDefinition idle = clips.get("idle");
            AnimationDefinition step = clips.get(running ? "run" : "walk");
            if (idle != null && walk < 1.0F) {
                play(idle, ageInTicks, 1.0F - walk);
            }
            if (step != null && walk > 0.0F) {
                // Шаг клипа = один цикл ванильной походки (cos(limbSwing·0,6662): 9,43 ед.).
                float cycleTicks = step.lengthInSeconds() * 20.0F;
                play(step, limbSwing / 9.43F * cycleTicks, walk);
            }
            head.ifPresent(h -> {
                h.yRot += netHeadYaw * Mth.DEG_TO_RAD;
                h.xRot += headPitch * Mth.DEG_TO_RAD;
            });
        }
        // Попадание поверх позы: клип hit минус его первый кадр.
        AnimationDefinition hit = clips.get("hit");
        if (hit != null && entity.hurtTime > 0 && state != Bandit.STAGGER) {
            float t = 10 - entity.hurtTime + partial;
            play(hit, t, 1.0F);
            play(hit, 0.0F, -1.0F);
        }
        if (entity instanceof BanditArcher && entity.isAggressive() && state != Bandit.STUN) {
            bowPose(netHeadYaw, headPitch);
        }
    }

    /** Натянутый лук: как у ванильного скелета (HumanoidModel.ArmPose.BOW_AND_ARROW). */
    private void bowPose(float yaw, float pitch) {
        float hy = yaw * Mth.DEG_TO_RAD;
        float hx = pitch * Mth.DEG_TO_RAD;
        armR.ifPresent(a -> {
            a.yRot = -0.1F + hy;
            a.xRot = -Mth.HALF_PI + hx;
            a.zRot = 0.0F;
        });
        armL.ifPresent(a -> {
            a.yRot = 0.1F + hy + 0.4F;
            a.xRot = -Mth.HALF_PI + hx;
            a.zRot = 0.0F;
        });
    }

    private void play(AnimationDefinition def, float ticks, float scale) {
        // API: reference/minecraft-src/net/minecraft/client/animation/KeyframeAnimations.java#animate (время в мс)
        KeyframeAnimations.animate(this, def, (long) (ticks * 50.0F), scale, CACHE);
    }

    /** Кисть: цепочка костей до руки, дальше ванильный ItemInHandLayer (лук лучника). */
    @Override
    public void translateToHand(HumanoidArm side, PoseStack pose) {
        String arm = side == HumanoidArm.RIGHT ? "arm_r" : "arm_l";
        translateChain(root, arm, pose);
    }

    private static boolean translateChain(ModelPart part, String target, PoseStack pose) {
        // Корень меша без преобразования; дальше — путь до нужной кости.
        for (String child : new String[] {"root", "waist", "torso", target}) {
            if (part.hasChild(child)) {
                part = part.getChild(child);
                part.translateAndRotate(pose);
            }
        }
        return true;
    }
}
