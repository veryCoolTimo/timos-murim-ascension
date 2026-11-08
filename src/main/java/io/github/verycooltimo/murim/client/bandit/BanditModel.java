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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

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

    private final Map<T, StunPoseBlend> stunFrames = new WeakHashMap<>();
    private final List<ModelPart> parts;

    private final ModelPart root;
    private final String name;
    private final Optional<ModelPart> head;
    private final Optional<ModelPart> armR;
    private final Optional<ModelPart> armL;

    public BanditModel(ModelPart root, String name) {
        this.root = root;
        this.parts = root.getAllParts().toList();
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
        boolean airborneThrow = io.github.verycooltimo.murim.client.vfx.BambooVfx.airborneThrow(entity.getId());
        int state = airborneThrow ? Bandit.IDLE : entity.state();
        float age = entity.stateAge(partial);
        // Variant A compresses existing normal sword clips to a 10–12 tick contact cycle.
        if (entity instanceof io.github.verycooltimo.murim.entity.BanditSwordsman swordsman
                && entity.move() != io.github.verycooltimo.murim.entity.BanditMove.QI_DASH) {
            int original = switch (state) {
                case Bandit.WINDUP -> entity.move().windup();
                case Bandit.STRIKE -> entity.move().strike();
                default -> entity.move().recover();
            };
            if (state == Bandit.WINDUP || state == Bandit.STRIKE || state == Bandit.RECOVER)
                age *= (float) original / swordsman.duelPhaseTicks(state);
        }
        String suffix = entity.move().clip();
        String clip = combatClip(state, suffix, clips.containsKey("stagger"));
        AnimationDefinition combat = clip == null ? null : clips.get(clip);
        if (combat != null) {
            play(combat, age, 1.0F);
        } else {
            // Покой и шаг смешиваются по скорости: веса в сумме 1, поэтому смесь — это лерп поз.
            Map<String, AnimationDefinition> moves = BanditAnimations.get(moveSet(entity));
            if (moves.isEmpty()) {
                moves = clips;
            }
            float walk = Mth.clamp(limbSwingAmount * 2.5F, 0.0F, 1.0F);
            boolean running = limbSwingAmount > 0.75F && moves.containsKey("run");
            AnimationDefinition idle = moves.get("idle");
            AnimationDefinition step = moves.get(running ? "run" : "walk");
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
        if (entity.campPose() != 0 && entity.getTarget() == null && state == Bandit.IDLE && limbSwingAmount < 0.05F) {
            var pose = entity.campPose() == 2 ? io.github.verycooltimo.murim.entity.SectPose.SLEEP
                    : io.github.verycooltimo.murim.entity.SectPose.SIT;
            AnimationDefinition rest = io.github.verycooltimo.murim.client.sect.PalClips.get(pose.clip());
            if (rest != null) {
                root.getAllParts().forEach(ModelPart::resetPose);
                play(rest, ageInTicks, 1.0F);
            }
        }
        // Попадание поверх позы: клип hit минус его первый кадр.
        AnimationDefinition hit = clips.get("hit");
        if (hit != null && entity.hurtTime > 0 && state != Bandit.STAGGER && state != Bandit.STUN) {
            float t = 10 - entity.hurtTime + partial;
            play(hit, t, 1.0F);
            play(hit, 0.0F, -1.0F);
        }
        if (entity instanceof io.github.verycooltimo.murim.entity.BanditSwordsman
                && state != Bandit.STUN
                && io.github.verycooltimo.murim.client.SwordGuardClient.pose(entity)) {
            armR.ifPresent(a -> { a.xRot = -1.6F; a.yRot = 0.55F; a.zRot = 0.55F; });
            armL.ifPresent(a -> { a.xRot = -0.9F; a.zRot = -0.2F; });
        }
        if (entity instanceof BanditArcher && entity.isAggressive() && state != Bandit.STUN) {
            bowPose(netHeadYaw, headPitch);
        }
        blendStun(entity, ageInTicks, state == Bandit.STUN);
        // API: MC ModelPart rotation/position fields; presentation from confirmed Bamboo facts only.
        float recoil = io.github.verycooltimo.murim.client.vfx.BambooVfx.recoilStrength(entity.getId(), partial);
        if (recoil > 0 || airborneThrow) {
            // Absolute targets override the forward slump contributed by the generic stun clip.
            if (airborneThrow) root.getAllParts().forEach(ModelPart::resetPose);
            float lean = airborneThrow ? 1 : recoil;
            root.xRot = 0; root.zRot = 0;
            root.z += (airborneThrow ? 5 : 8) * lean;
            getAnyDescendantWithName("waist").ifPresent(t -> { t.xRot = 0; t.zRot = 0; });
            getAnyDescendantWithName("torso").ifPresent(t -> { t.xRot = (airborneThrow ? -.45F : -.72F) * lean; t.zRot = 0; });
            armR.ifPresent(a -> {
                a.xRot += (-2.4F - a.xRot) * lean;
                a.yRot += (.5F - a.yRot) * lean;
                a.zRot += (.65F - a.zRot) * lean;
            });
            armL.ifPresent(a -> { a.xRot = -.9F * lean; a.zRot = -.65F * lean; });
        }
        // Bamboo's six-tick contact reaction throws the weapon off-line without adding a gameplay stun.
        float offLine = io.github.verycooltimo.murim.client.vfx.BambooVfx.staggerStrength(entity.getId(), partial);
        if (offLine > 0 && state != Bandit.STUN) {
            armR.ifPresent(a -> {
                a.xRot += (-.8F - a.xRot) * offLine;
                a.yRot += (1.2F - a.yRot) * offLine;
                a.zRot += (1.1F - a.zRot) * offLine;
            });
            getAnyDescendantWithName("torso").ifPresent(t -> { t.yRot += .35F * offLine; t.zRot -= .18F * offLine; });
        }
    }

    static String combatClip(int state, String suffix, boolean hasStagger) {
        return switch (state) {
            case Bandit.WINDUP -> "windup" + suffix;
            case Bandit.STRIKE -> "attack" + suffix;
            case Bandit.RECOVER -> "recover" + suffix;
            case Bandit.STAGGER -> hasStagger ? "stagger" : "hit";
            case Bandit.STUN -> "stun";
            default -> null;
        };
    }

    private void blendStun(T entity, float now, boolean stunned) {
        stunFrames.computeIfAbsent(entity, ignored -> new StunPoseBlend()).apply(parts, now, stunned);
    }

    /** Набор клипов покоя и шага ({@code idle}, {@code walk}, {@code run}); бой — всегда из своего набора. */
    protected String moveSet(T entity) {
        return name;
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
