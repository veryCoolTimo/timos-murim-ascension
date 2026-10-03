package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.bedrock.BedrockItemMesh;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;

/**
 * Модель кинжала Тан от автора ({@code assets/murim/bedrock/tang_dagger.geo.json}) для летящих кинжалов и
 * кинжалов в руках мастера (розетка, ладонь). Чтение geo — общий {@link BedrockItemMesh}.
 *
 * <p>Остриё по +Y модели, гарда на y≈8 единиц; клинок лежит плашмя в плоскости YZ. Натуральная длина ~12
 * единиц = 0,76 блока; в полёте рисуется в масштабе {@link #FLIGHT} (≈0,5 блока, автор 03.10: «слишком большая»).
 */
public final class TangDaggerMesh {

    public static final BedrockItemMesh MESH = new BedrockItemMesh("/assets/murim/bedrock/tang_dagger.geo.json",
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/item/tang_dagger.png"));
    /** Гарда — точка хвата (единицы модели по Y). */
    public static final float GUARD_Y = 8.0F;
    /** Масштаб летящего кинжала: длина ≈ 0,5 блока. */
    public static final float FLIGHT = 0.68F;

    public static RenderType renderType() {
        return MESH.renderType();
    }

    /** Нарисовать кинжал гардой в начале позы, остриём по +Y; {@code scale} — множитель к {@link #FLIGHT}. */
    public static void render(PoseStack ps, VertexConsumer v, int light, float scale, float r, float g, float b, float alpha) {
        MESH.render(ps, v, light, FLIGHT * scale, GUARD_Y, r, g, b, alpha);
    }

    /** Повернуть позу так, чтобы остриё (+Y модели) смотрело по {@code dir}, и докрутить на {@code roll} вокруг клинка. */
    public static void orient(PoseStack ps, double dx, double dy, double dz, float roll) {
        ps.mulPose(new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, (float) dx, (float) dy, (float) dz));
        ps.mulPose(new Quaternionf().rotationY(roll));
    }

    private TangDaggerMesh() {
    }
}
