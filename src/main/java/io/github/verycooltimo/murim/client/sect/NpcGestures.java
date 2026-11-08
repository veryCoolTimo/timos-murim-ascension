package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.sect.SectRole;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * Жесты NPC на репликах диалога — простые клипы поверх позы модели бандита (кости
 * {@code waist → torso → head, arm_r, arm_l}): кивок, поклон «кулак в ладонь» (포권, план §4.4),
 * указующий жест, раскрытая ладонь «объясняет», отказ головой. Пока NPC говорит с игроком — лёгкое
 * покачивание корпуса и руки, чтобы он не стоял статуей. Блокинг; позы доведёт автор.
 */
final class NpcGestures {

    /** Длина жеста, тиков. */
    private static final float LENGTH = 26.0F;

    private NpcGestures() {
    }

    static void apply(DiscipleRenderer.Model model, SectDisciple npc, float partial) {
        ModelPart waist = model.getAnyDescendantWithName("waist").orElse(null);
        ModelPart head = model.getAnyDescendantWithName("head").orElse(null);
        ModelPart armR = model.getAnyDescendantWithName("arm_r").orElse(null);
        ModelPart armL = model.getAnyDescendantWithName("arm_l").orElse(null);
        // Меч в руке — только у старшего (спарринг); наставник, глава и ученики фоном без оружия.
        model.getAnyDescendantWithName("weapon").ifPresent(w -> w.visible = npc.role() == SectRole.SENIOR);
        if (npc.spar() == SectDisciple.Spar.FIGHT || !npc.anim().isEmpty() && npc.role() == SectRole.SENIOR && npc.spar() != SectDisciple.Spar.NONE) {
            return;
        }
        // Своя фаза покачивания у каждого (автор 06.10: не хором).
        float t = npc.tickCount + partial + LoopVariety.phase(LoopVariety.seed(npc.getUUID())) * 200.0F;
        if (npc.talkingTo() >= 0 && armR != null && waist != null) {
            // Говорит: дыхание корпуса и ладонь чуть вперёд, медленно.
            waist.xRot += 0.04F * Mth.sin(t * 0.12F);
            armR.xRot += -0.25F - 0.12F * Mth.sin(t * 0.16F);
            armR.zRot += 0.08F;
        }
        String g = npc.gesture();
        float age = npc.gestureAge(partial);
        float length = "point_far".equals(g) ? SectDisciple.POINT_TICKS : "wave".equals(g) ? 56.0F : LENGTH;
        if (g.isEmpty() || age < 0.0F || age > length) {
            return;
        }
        // Огибающая: быстрый вход (4 тика), удержание, мягкий выход.
        float env = Mth.clamp(age / 4.0F, 0.0F, 1.0F) * Mth.clamp((length - age) / 8.0F, 0.0F, 1.0F);
        switch (g) {
            case "nod" -> {
                if (head != null) {
                    head.xRot += env * 0.32F * Math.max(0.0F, Mth.sin(age * 0.55F));
                }
            }
            case "shake" -> {
                if (head != null) {
                    head.yRot += env * 0.4F * Mth.sin(age * 0.7F);
                }
            }
            case "bow" -> {
                // Кулак в ладонь у груди, корпус вперёд.
                if (waist != null) {
                    waist.xRot += env * 0.55F;
                }
                if (head != null) {
                    head.xRot += env * 0.2F;
                }
                if (armR != null) {
                    armR.xRot += env * -1.15F;
                    armR.yRot += env * -0.45F;
                }
                if (armL != null) {
                    armL.xRot += env * -1.15F;
                    armL.yRot += env * 0.45F;
                }
            }
            case "point" -> {
                if (armR != null) {
                    armR.xRot += env * -1.45F;
                    armR.zRot += env * 0.1F;
                }
            }
            case "wave" -> {
                // A greeting across a distance (docs/adr/0020): the right arm up beside the head, the hand swinging.
                if (armR != null) {
                    armR.xRot += env * -2.55F;
                    armR.zRot += env * (0.35F + 0.3F * Mth.sin(age * 0.55F));
                }
                if (head != null) {
                    head.xRot += env * -0.05F;
                }
            }
            case "point_far" -> {
                // Showing the way (docs/adr/0020): the right arm straight out at shoulder height and held, the other hand
                // at the belt, the body leaning a little into the direction. The body itself is turned by the entity.
                float stab = 1.0F + 0.07F * Mth.clamp(1.0F - Math.abs(age - 6.0F) / 5.0F, 0.0F, 1.0F);
                if (armR != null) {
                    armR.xRot += env * -1.3F * stab;
                    armR.yRot += env * -0.04F;
                    armR.zRot += env * 0.05F;
                }
                if (armL != null) {
                    armL.xRot += env * -0.2F;
                    armL.zRot += env * -0.25F;
                }
                if (waist != null) {
                    waist.xRot += env * 0.08F;
                }
            }
            case "explain" -> {
                if (armR != null) {
                    armR.xRot += env * (-0.9F - 0.15F * Mth.sin(age * 0.35F));
                    armR.zRot += env * 0.35F;
                }
                if (armL != null) {
                    armL.xRot += env * -0.6F;
                    armL.zRot += env * -0.3F;
                }
            }
            case "fist" -> {
                // Кулак у пояса, подбородок вверх — вызов на поединок.
                if (armR != null) {
                    armR.xRot += env * -0.5F;
                }
                if (head != null) {
                    head.xRot += env * -0.15F;
                }
                if (waist != null) {
                    waist.xRot += env * -0.06F;
                }
            }
            default -> {
            }
        }
    }
}
