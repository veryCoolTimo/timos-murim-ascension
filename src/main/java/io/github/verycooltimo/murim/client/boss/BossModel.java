package io.github.verycooltimo.murim.client.boss;

import io.github.verycooltimo.murim.entity.boss.BossMove;
import io.github.verycooltimo.murim.entity.boss.FortressMaster;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Optional;

/**
 * Модель хозяина крепости из Bedrock-файла (кости бандита + {@code pelt}, {@code rings}).
 * Как у бандита (client/bandit/BanditModel): в бою — клип состояния целиком по времени от его
 * начала, вне боя — смесь {@code idle} и {@code walk}/{@code run}; попадание — разница клипа
 * {@code hit} поверх позы; смерть — клип {@code death} по {@code deathTime}.
 */
public class BossModel extends HierarchicalModel<FortressMaster> {

    private static final Vector3f CACHE = new Vector3f();

    private final ModelPart root;
    private final Optional<ModelPart> head;

    public BossModel(ModelPart root) {
        this.root = root;
        this.head = getAnyDescendantWithName("head");
    }

    @Override
    public ModelPart root() {
        return root;
    }

    /** Клип, который играет хозяин в данном состоянии (null — ходьба/покой). */
    public static String clip(FortressMaster e) {
        BossMove m = e.move();
        return switch (e.state()) {
            case FortressMaster.SIT -> "sit";
            case FortressMaster.RISE -> "rise";
            case FortressMaster.WINDUP -> m.windupClip();
            case FortressMaster.STRIKE -> m.strikeClip();
            case FortressMaster.RECOVER -> m.recoverClip();
            case FortressMaster.STAGGER -> "stagger";
            case FortressMaster.STUN -> "stun";
            case FortressMaster.DOWNED -> "downed";
            case FortressMaster.BOIL -> "roar";
            default -> null;
        };
    }

    @Override
    public void setupAnim(FortressMaster entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        root.getAllParts().forEach(ModelPart::resetPose);
        Map<String, AnimationDefinition> clips = BossAnimations.get();
        float partial = ageInTicks - entity.tickCount;
        if (entity.deathTime > 0) {
            AnimationDefinition death = clips.get("death");
            if (death != null) {
                play(death, entity.deathTime + partial, 1.0F);
                return;
            }
        }
        String name = clip(entity);
        AnimationDefinition combat = name == null ? null : clips.get(name);
        if (combat != null) {
            play(combat, entity.stateAge(partial), 1.0F);
        } else {
            float walk = Mth.clamp(limbSwingAmount * 2.5F, 0.0F, 1.0F);
            boolean running = limbSwingAmount > 0.75F && clips.containsKey("run");
            AnimationDefinition idle = clips.get("idle");
            AnimationDefinition step = clips.get(running ? "run" : "walk");
            if (idle != null && walk < 1.0F) {
                play(idle, ageInTicks, 1.0F - walk);
            }
            if (step != null && walk > 0.0F) {
                float cycleTicks = step.lengthInSeconds() * 20.0F;
                play(step, limbSwing / 9.43F * cycleTicks, walk);
            }
            head.ifPresent(h -> {
                h.yRot += netHeadYaw * Mth.DEG_TO_RAD;
                h.xRot += headPitch * Mth.DEG_TO_RAD;
            });
        }
        AnimationDefinition hit = clips.get("hit");
        if (hit != null && entity.hurtTime > 0 && entity.state() != FortressMaster.STAGGER && entity.state() != FortressMaster.SIT) {
            float t = 10 - entity.hurtTime + partial;
            play(hit, t, 1.0F);
            play(hit, 0.0F, -1.0F);
        }
    }

    private void play(AnimationDefinition def, float ticks, float scale) {
        // API: reference/minecraft-src/net/minecraft/client/animation/KeyframeAnimations.java#animate (время в мс)
        KeyframeAnimations.animate(this, def, (long) (ticks * 50.0F), scale, CACHE);
    }
}
