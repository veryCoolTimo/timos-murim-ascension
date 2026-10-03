package io.github.verycooltimo.murim.client.bedrock;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.TangDaggerMesh;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import org.joml.Quaternionf;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Один рендер на все предметы с моделями автора из Blockbench ({@link BedrockItemMesh}): кинжал Тан, Меч Хуашань.
 *
 * <p>Модель предмета — {@code builtin/entity} с трансформами ванильного {@code item/handheld}. Здесь модель
 * кладётся в куб 0..1 так, как лежит спрайт ванильного меча: навершие у (0,12; 0,12), остриё по диагонали
 * (+X, +Y), плоскость клинка — к зрителю (XY). Поэтому в руке держится как меч, в инвентаре стоит наискосок.
 * API: reference/minecraft-src/net/minecraft/client/renderer/BlockEntityWithoutLevelRenderer.java#renderByItem;
 * reference/neoforge-src/net/neoforged/neoforge/client/extensions/common/RegisterClientExtensionsEvent.java#registerItem.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class BedrockItemRenderer extends BlockEntityWithoutLevelRenderer {

    /**
     * {@code length} — длина модели в кубе предмета (спрайт ванильного меча ≈ 1,1); {@code roll} — докрутка вокруг
     * клинка, чтобы плоскость клинка легла в XY.
     */
    private record Spec(BedrockItemMesh mesh, float length, float roll) {
    }

    public static final BedrockItemMesh HUASHAN_SWORD = new BedrockItemMesh("/assets/murim/bedrock/huashan_sword.geo.json",
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/item/huashan_sword.png"));

    private static final Map<Item, Spec> SPECS = new IdentityHashMap<>();
    private static BedrockItemRenderer instance;

    public BedrockItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @SubscribeEvent
    static void onRegister(RegisterClientExtensionsEvent event) {
        // Кинжал короче меча: ~0,7 против ~1,15; клинок кинжала в модели лежит в YZ — докрутка на 90°.
        SPECS.put(ModItems.TANG_DAGGER.get(), new Spec(TangDaggerMesh.MESH, 0.72F, (float) (Math.PI / 2.0D)));
        SPECS.put(ModItems.HUASHAN_SWORD.get(), new Spec(HUASHAN_SWORD, 1.15F, 0.0F));
        IClientItemExtensions ext = new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (instance == null) {
                    instance = new BedrockItemRenderer();
                }
                return instance;
            }
        };
        event.registerItem(ext, ModItems.TANG_DAGGER.get(), ModItems.HUASHAN_SWORD.get());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack ps, MultiBufferSource buffers, int light, int overlay) {
        Spec spec = SPECS.get(stack.getItem());
        if (spec == null) {
            return;
        }
        BedrockItemMesh mesh = spec.mesh();
        float span = Math.max(1.0E-3F, mesh.maxY() - mesh.minY());
        float scale = spec.length() * 16.0F / span;
        float k = (float) Math.sqrt(0.5D);
        float start = (1.0F - spec.length() * k) * 0.5F;
        ps.pushPose();
        try {
            ps.translate(start, start, 0.5F);
            ps.mulPose(new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, k, k, 0.0F));
            ps.mulPose(new Quaternionf().rotationY(spec.roll()));
            mesh.render(ps, buffers.getBuffer(mesh.renderType()), light, scale, mesh.minY(), 1.0F, 1.0F, 1.0F, 1.0F);
        } finally {
            ps.popPose();
        }
    }
}
