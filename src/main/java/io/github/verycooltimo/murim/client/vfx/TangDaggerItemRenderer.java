package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * Предмет «Кинжал Тан» — та же модель автора, что у летящих кинжалов ({@link TangDaggerMesh}).
 *
 * <p>Модель предмета {@code builtin/entity} с трансформами ванильного {@code item/handheld}: этот рендер
 * кладёт кинжал в куб 0..1 по диагонали (+X, +Y), как лежит спрайт меча, — в руке он держится как меч,
 * в инвентаре стоит наискосок. API: reference/minecraft-src/net/minecraft/client/renderer/
 * BlockEntityWithoutLevelRenderer.java#renderByItem; reference/neoforge-src/.../RegisterClientExtensionsEvent#registerItem.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class TangDaggerItemRenderer extends BlockEntityWithoutLevelRenderer {

    private static TangDaggerItemRenderer instance;

    public TangDaggerItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @SubscribeEvent
    static void onRegister(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (instance == null) {
                    instance = new TangDaggerItemRenderer();
                }
                return instance;
            }
        }, ModItems.TANG_DAGGER.get());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack ps, MultiBufferSource buffers, int light, int overlay) {
        ps.pushPose();
        try {
            // Кинжал по диагонали куба: длина модели ~12 пикселей → 1,1 блока, середина клинка в центре.
            float scale = context == ItemDisplayContext.GUI ? 1.55F
                    : context.firstPerson() ? 1.0F : 1.25F;
            double k = Math.sqrt(0.5D);
            double back = 1.4D / 16.0D * scale;
            ps.translate(0.5D - k * back, 0.5D - k * back, 0.5D);
            // Плоскость клинка в модели — YZ: докрутка на 90° разворачивает его плашмя к зрителю, как спрайт меча.
            TangDaggerMesh.orient(ps, k, k, 0.0D, (float) (Math.PI / 2.0D));
            TangDaggerMesh.render(ps, buffers.getBuffer(TangDaggerMesh.renderType()), light, scale, 1.0F, 1.0F, 1.0F, 1.0F);
        } finally {
            ps.popPose();
        }
    }
}
