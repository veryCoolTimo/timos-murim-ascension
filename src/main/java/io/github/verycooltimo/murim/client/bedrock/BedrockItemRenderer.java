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
 * <p>Размеры — как в файлах автора (автор 03.10: «в файлах должны быть правильные размеры»): 16 единиц geo = 1 блок,
 * никакой нормировки длины. Начало координат Bedrock-модели (низ-центр) — точка (8, 0, 8) Java-модели, как в режиме
 * Display у Blockbench; трансформы рук/GUI/земли — в JSON модели предмета ({@code builtin/entity}), у меча — ровно
 * из .bbmodel автора. API: reference/minecraft-src/net/minecraft/client/renderer/BlockEntityWithoutLevelRenderer.java#renderByItem;
 * reference/neoforge-src/net/neoforged/neoforge/client/extensions/common/RegisterClientExtensionsEvent.java#registerItem.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class BedrockItemRenderer extends BlockEntityWithoutLevelRenderer {

    /** {@code yaw} — поворот вокруг вертикали, чтобы плоскость клинка легла в XY, как у меча (градусы). */
    private record Spec(BedrockItemMesh mesh, float yaw) {
    }

    public static final BedrockItemMesh HUASHAN_SWORD = new BedrockItemMesh("/assets/murim/bedrock/huashan_sword.geo.json",
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/item/huashan_sword.png"));
    /** Деревянный меч: та же модель автора, текстура дерева (art/items/wooden_sword/wood.png, та же раскладка UV 128×128). */
    public static final BedrockItemMesh WOODEN_SWORD = new BedrockItemMesh("/assets/murim/bedrock/huashan_sword.geo.json",
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/item/wooden_sword.png"));

    private static final Map<Item, Spec> SPECS = new IdentityHashMap<>();
    private static BedrockItemRenderer instance;

    public BedrockItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @SubscribeEvent
    static void onRegister(RegisterClientExtensionsEvent event) {
        // Клинок кинжала в модели лежит в YZ, у меча — в XY: кинжал докручен на 90°, чтобы делить трансформы меча.
        SPECS.put(ModItems.TANG_DAGGER.get(), new Spec(TangDaggerMesh.MESH, 90.0F));
        SPECS.put(ModItems.HUASHAN_SWORD.get(), new Spec(HUASHAN_SWORD, 0.0F));
        SPECS.put(ModItems.WOODEN_SWORD.get(), new Spec(WOODEN_SWORD, 0.0F));
        IClientItemExtensions ext = new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (instance == null) {
                    instance = new BedrockItemRenderer();
                }
                return instance;
            }
        };
        event.registerItem(ext, ModItems.TANG_DAGGER.get(), ModItems.HUASHAN_SWORD.get(), ModItems.WOODEN_SWORD.get());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack ps, MultiBufferSource buffers, int light, int overlay) {
        Spec spec = SPECS.get(stack.getItem());
        if (spec == null) {
            return;
        }
        BedrockItemMesh mesh = spec.mesh();
        ps.pushPose();
        try {
            ps.translate(0.5F, 0.0F, 0.5F);
            ps.mulPose(new Quaternionf().rotationY((float) Math.toRadians(spec.yaw())));
            mesh.render(ps, buffers.getBuffer(mesh.renderType()), light, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F);
        } finally {
            ps.popPose();
        }
    }
}
