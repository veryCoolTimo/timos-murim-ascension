package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Слой, который добывает мировые позиции костей игрока.
 *
 * <p><b>Зачем.</b> Эффекты жили в мировых координатах и о теле не знали: точка ладони была
 * записана числом в системе «ступни плюс поворот корпуса». Такая точка не может следовать
 * за анимацией, приседанием, покачиванием и масштабом, поэтому эффект отрывался от руки
 * и висел сбоку. Измерение подтвердило это числом: смещение 66 пикселей при пределе 18.
 *
 * <p><b>Как.</b> Внутри слоя {@code PoseStack} уже стоит в модельном пространстве, а кости
 * уже позированы {@code setupAnim} — включая правки Player Animation Library, которая пишет
 * в те же ванильные {@link ModelPart}. Поэтому здесь достаточно повторить трансформацию
 * нужной кости и прочитать получившуюся матрицу.
 *
 * <p>Player Animation Library мировую позицию кости не отдаёт (проверено javap по jar,
 * ADR-76), а собственный миксин в рендер игрока — крайняя мера. Слой решает задачу штатно.
 *
 * <p>Слой ничего не рисует. Он только записывает позиции, а рисуют их обычные рендереры
 * эффектов в стадии мира: смешивать вычисление и отрисовку значило бы получить эффекты,
 * которые видны только когда виден игрок.
 */
public class BoneAnchorLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    /** Кости, чьи позиции нужны эффектам. */
    public enum Bone {
        RIGHT_HAND,
        LEFT_HAND,
        CHEST,
        DANTIAN,
        // Концы конечностей и голова нужны сцене создания даньтяня: жилы растут ОТ них
        // к средоточию, и без них поток некуда было бы вести.
        RIGHT_FOOT,
        LEFT_FOOT,
        HEAD,
        // Колени и плечи — узлы меридианов. Поток идёт снизу вверх через точки на теле,
        // а не по прямой от конечности к центру: прямая читается как спица, а не как
        // канал, проложенный по телу.
        RIGHT_KNEE,
        LEFT_KNEE,
        RIGHT_SHOULDER,
        LEFT_SHOULDER,
        // Середина и кончик меча в правой руке: след формы меча идёт по настоящему движению
        // клинка из анимации, а не по заданной дуге. Смещение — по виду держания меча
        // в третьем лице: клинок выходит из кулака вперёд и чуть вверх.
        BLADE_MID,
        BLADE_TIP
    }

    /**
     * Позиции костей за прошлый кадр по игрокам.
     *
     * <p>Значения живут один кадр и перезаписываются: держать историю здесь незачем,
     * а устаревшая позиция хуже отсутствующей — эффект прилипнет к тому, где рука была.
     */
    private static final Map<UUID, Map<Bone, Vec3>> POSITIONS = new HashMap<>();

    public BoneAnchorLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    /**
     * Мировая позиция кости или {@code null}, если игрок в этом кадре не рисовался.
     *
     * <p>Отсутствие позиции — штатная ситуация: игрок вне поля зрения, в первом лице,
     * невидим. Вызывающий обязан это учитывать и не рисовать эффект вслепую.
     */
    public static Vec3 position(AbstractClientPlayer player, Bone bone) {
        Map<Bone, Vec3> bones = POSITIONS.get(player.getUUID());
        return bones == null ? null : bones.get(bone);
    }

    public static void clear() {
        POSITIONS.clear();
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int light,
                       AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float yaw, float pitch) {
        PlayerModel<AbstractClientPlayer> model = getParentModel();
        Map<Bone, Vec3> bones = POSITIONS.computeIfAbsent(player.getUUID(), key -> new HashMap<>());

        // Смещения внутри кости в модельных единицах: у Minecraft это шестнадцатые доли блока,
        // и ось Y направлена вниз. Кисть — нижний конец руки длиной 12 единиц.
        bones.put(Bone.RIGHT_HAND, pointOf(poseStack, model.rightArm, 0.0F, 10.0F, 0.0F));
        bones.put(Bone.LEFT_HAND, pointOf(poseStack, model.leftArm, 0.0F, 10.0F, 0.0F));
        bones.put(Bone.CHEST, pointOf(poseStack, model.body, 0.0F, 2.0F, 0.0F));
        // Даньтянь — под пупком, ниже центра корпуса.
        bones.put(Bone.DANTIAN, pointOf(poseStack, model.body, 0.0F, 9.0F, -1.0F));
        // Ступни — нижний конец ноги длиной 12 единиц; голова — её центр.
        bones.put(Bone.RIGHT_FOOT, pointOf(poseStack, model.rightLeg, 0.0F, 11.0F, 0.0F));
        bones.put(Bone.LEFT_FOOT, pointOf(poseStack, model.leftLeg, 0.0F, 11.0F, 0.0F));
        bones.put(Bone.HEAD, pointOf(poseStack, model.head, 0.0F, -4.0F, 0.0F));
        // Колено — середина ноги, плечо — верх руки.
        bones.put(Bone.RIGHT_KNEE, pointOf(poseStack, model.rightLeg, 0.0F, 6.0F, 0.0F));
        bones.put(Bone.LEFT_KNEE, pointOf(poseStack, model.leftLeg, 0.0F, 6.0F, 0.0F));
        bones.put(Bone.RIGHT_SHOULDER, pointOf(poseStack, model.rightArm, 0.0F, 1.0F, 0.0F));
        bones.put(Bone.LEFT_SHOULDER, pointOf(poseStack, model.leftArm, 0.0F, 1.0F, 0.0F));
        // [НЕПРОВЕРЕНО: точное положение клинка зависит от модели предмета; подобрано по кадрам]
        bones.put(Bone.BLADE_MID, pointOf(poseStack, model.rightArm, -1.0F, 9.0F, -4.0F));
        bones.put(Bone.BLADE_TIP, pointOf(poseStack, model.rightArm, -1.0F, 5.0F, -15.0F));
    }

    /**
     * Мировая точка внутри кости.
     *
     * <p>Матрица здесь переводит модельные координаты в мировые с учётом всей цепочки:
     * позиция сущности, поворот тела, анимация кости, масштаб. Делить на шестнадцать
     * не нужно — масштаб уже заложен в матрицу рендерером сущности.
     */
    private static Vec3 pointOf(PoseStack poseStack, ModelPart part, float x, float y, float z) {
        poseStack.pushPose();
        try {
            part.translateAndRotate(poseStack);
            Matrix4f matrix = poseStack.last().pose();
            // Модельные единицы — шестнадцатые доли блока. Знак осей НЕ правим руками:
            // рендерер сущности уже применил инверсию по X и Y, и она лежит в матрице.
            // Ручная поправка означала бы двойное отрицание.
            Vector4f local = new Vector4f(x / 16.0F, y / 16.0F, z / 16.0F, 1.0F);
            Vector4f transformed = matrix.transform(local);

            // Матрица переводит в координаты ОТНОСИТЕЛЬНО КАМЕРЫ: рендерер сущности
            // сдвинул начало координат в позицию камеры. Возвращаем абсолютные,
            // иначе точка будет верной только когда камера в начале мира.
            Vec3 camera = net.minecraft.client.Minecraft.getInstance()
                    .gameRenderer.getMainCamera().getPosition();
            return new Vec3(transformed.x() + camera.x,
                            transformed.y() + camera.y,
                            transformed.z() + camera.z);
        } finally {
            poseStack.popPose();
        }
    }
}
