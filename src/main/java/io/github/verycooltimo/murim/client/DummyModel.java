package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.client.bedrock.BedrockGeo;
import io.github.verycooltimo.murim.entity.TrainingDummy;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;

/**
 * Деревянный манекен боевых искусств (木人樁): толстый ствол на низком основании с косыми упорами, три круглые «руки» на
 * разной высоте и одна нижняя «нога», верёвочные обвязки (art/enemies/training_dummy/training_dummy.bbmodel, автор правит там
 * же; геометрия {@code bedrock/training_dummy.geo.json} — экспорт Bedrock того же файла). Круглые части — восьмигранники из
 * четырёх длинных повёрнутых пластин, не лесенка из мелких кубов.
 *
 * <p>Кости: {@code dummy} (основание, упоры) → {@code trunk} (ствол, обвязки, навершие; качается на основании) →
 * {@code arm_a}, {@code arm_b}, {@code arm_f}, {@code leg}. Покачивание после попадания ({@link TrainingDummy#wobble}) —
 * затухающее колебание: ствол чуть кланяется, руки и нога качаются сильнее; чистое попадание — вдвое сильнее обычного.
 * API: reference/minecraft-src/net/minecraft/client/model/HierarchicalModel.java
 */
public class DummyModel extends HierarchicalModel<TrainingDummy> {

    private final ModelPart root;
    private final ModelPart trunk;
    private final ModelPart armA;
    private final ModelPart armB;
    private final ModelPart armF;
    private final ModelPart leg;
    private final ModelPart armR;
    private final ModelPart armS;

    public DummyModel() {
        this.root = BedrockGeo.bake("/assets/murim/bedrock/training_dummy.geo.json");
        ModelPart dummy = root.getChild("dummy");
        this.trunk = dummy.getChild("trunk");
        this.armA = trunk.getChild("arm_a");
        this.armB = trunk.getChild("arm_b");
        this.armF = trunk.getChild("arm_f");
        this.leg = trunk.getChild("leg");
        this.armR = trunk.getChild("arm_r");
        this.armS = trunk.getChild("arm_s");
    }

    @Override
    public ModelPart root() {
        return root;
    }

    @Override
    public void setupAnim(TrainingDummy entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                          float headPitch) {
        // Кости возвращаются в позу покоя на каждый кадр: качание не накапливается.
        trunk.resetPose();
        armA.resetPose();
        armB.resetPose();
        armF.resetPose();
        leg.resetPose();
        armR.resetPose();
        armS.resetPose();
        float w = entity.wobble(ageInTicks - entity.tickCount);
        if (w == 0.0F) {
            return;
        }
        // Where the blow pushes, in the post's own frame (x to the model's left, z to its back): the post sways that way.
        float body = (float) Math.toRadians(entity.yBodyRot);
        float yaw = (float) Math.toRadians(entity.hitYaw());
        float wx = (float) Math.cos(yaw), wz = (float) Math.sin(yaw);
        float lx = (float) (wx * Math.cos(body) + wz * Math.sin(body));
        float lz = (float) (-wx * Math.sin(body) + wz * Math.cos(body));
        float power = Math.max(0.35F, entity.hitStrength());
        float sway = w * power;
        // The top leans along the push: +xRot tips the top to the front (-z), +zRot to the model's right.
        trunk.xRot += -lz * sway * 0.11F;
        trunk.zRot += -lx * sway * 0.11F;
        // The peg on the struck side recoils into the post (a rock about its own root), the others only ride the sway.
        ModelPart[] pegs = {armA, armB, armF, armR, armS, leg};
        float[][] axis = {{1, 0}, {-1, 0}, {0, -1}, {0, 1}, {0, 1}, {0, -1}};
        for (int i = 0; i < pegs.length; i++) {
            // the peg points along axis; the blow comes from the opposite side of the push, so it faces the attacker when axis . (-l) > 0
            float facing = Math.max(0.0F, -(axis[i][0] * lx + axis[i][1] * lz));
            float recoil = facing * facing * sway * 0.25F;
            pegs[i].xRot += axis[i][1] != 0 ? -axis[i][1] * recoil : 0.0F;
            pegs[i].zRot += axis[i][0] != 0 ? axis[i][0] * recoil : 0.0F;
            pegs[i].yRot += sway * 0.05F * (i % 2 == 0 ? 1 : -1);
        }
    }
}
