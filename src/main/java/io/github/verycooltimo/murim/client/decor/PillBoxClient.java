package io.github.verycooltimo.murim.client.decor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.IntWorkshop;
import io.github.verycooltimo.murim.decor.PillBoxBlock;
import io.github.verycooltimo.murim.decor.PillBoxBlockEntity;
import io.github.verycooltimo.murim.decor.PillBoxItem;
import io.github.verycooltimo.murim.decor.PillBoxMenu;
import io.github.verycooltimo.murim.decor.TabletopSupport;
import io.github.verycooltimo.murim.network.PillBoxOpenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client side of the lacquer pill box (24.10): the lid is the standalone model {@code block/int_pill_box_lid}, drawn by a block entity
 * renderer and swung about the back hinge (the axis along x at y 3.0, z 10.6 px, see tools/art/pill_art/box_geom.py PIVOT) from 0 to
 * 105 degrees in 0.4 s while the block state {@code open} is on. The base is the normal block model. The menu screen, and the click on a
 * box in an inventory screen that asks the server to open it (a payload; no mixin).
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/client/event/ModelEvent.java#RegisterAdditional, RegisterMenuScreensEvent.java#register,
 * ScreenEvent.java#MouseButtonPressed.Pre, reference/minecraft-src/net/minecraft/client/gui/screens/inventory/AbstractContainerScreen.java
 * #getSlotUnderMouse, client/renderer/blockentity/ChestRenderer.java (lid angle), as client/decor/HallFurnitureClient (standalone model).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class PillBoxClient {

    public static final ModelResourceLocation LID =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "block/int_pill_box_lid"));
    public static final ModelResourceLocation CRATE_LID =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "block/pill_crate_lid"));
    public static final ModelResourceLocation CRATE_PILL =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "block/pill_crate_pill"));
    public static final ModelResourceLocation CRATE_PALE_PILL =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "block/pill_crate_pale"));
    /** Hinge axis in blocks (x is the axis direction), and the opening in degrees. */
    private static final float PIVOT_Y = 3.0F / 16.0F;
    private static final float PIVOT_Z = 10.6F / 16.0F;
    private static final float OPEN_DEGREES = 105.0F;
    /** Twelve ticks (0.6 seconds), including a small settling overshoot. */
    private static final float OPEN_TICKS = 12.0F;

    private PillBoxClient() {
    }

    @SubscribeEvent
    static void onModels(ModelEvent.RegisterAdditional event) {
        event.register(LID);
        event.register(CRATE_LID);
        event.register(CRATE_PILL);
        event.register(CRATE_PALE_PILL);
    }

    @SubscribeEvent
    static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(IntWorkshop.PILL_BOX_ENTITY.get(), LidRenderer::new);
        event.registerBlockEntityRenderer(IntWorkshop.PILL_CRATE_ENTITY.get(), LidRenderer::new);
    }

    @SubscribeEvent
    static void onScreens(RegisterMenuScreensEvent event) {
        event.register(IntWorkshop.PILL_BOX_MENU.get(), PillBoxScreen::new);
    }

    /** Right click on a pill box in an inventory screen (cursor empty): ask the server to open its pouch. */
    @SubscribeEvent
    static void onClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 1 || !(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        Slot slot = screen.getSlotUnderMouse();
        Minecraft mc = Minecraft.getInstance();
        if (slot == null || mc.player == null || slot.container != mc.player.getInventory()
                || !(slot.getItem().getItem() instanceof PillBoxItem) || !screen.getMenu().getCarried().isEmpty()) {
            return;
        }
        if (screen.getMenu() instanceof PillBoxMenu menu && menu.lockedSlot() == slot.getContainerSlot()) {
            return; // the box this menu is open on
        }
        event.setCanceled(true);
        PacketDistributor.sendToServer(new PillBoxOpenPayload(slot.getContainerSlot()));
    }

    /** Draws the lid; the angle follows the block state with a cubic ease-out and a small settling overshoot. */
    static final class LidRenderer implements BlockEntityRenderer<PillBoxBlockEntity> {
        private final BlockRenderDispatcher blocks;
        private final java.util.WeakHashMap<PillBoxBlockEntity, Anim> anims = new java.util.WeakHashMap<>();

        LidRenderer(BlockEntityRendererProvider.Context context) {
            blocks = context.getBlockRenderDispatcher();
        }

        /** Per block entity: where the lid was when the state last flipped and when. */
        private static final class Anim {
            boolean target;
            boolean crate;
            float from;
            float start;
        }

        private float angle(PillBoxBlockEntity be, boolean open, float now) {
            Anim a = anims.get(be);
            float goal = open ? (be.isCrate() ? 103.0F : OPEN_DEGREES) : 0.0F;
            if (a == null) {
                a = new Anim();
                a.crate = be.isCrate();
                a.target = open;
                a.from = goal;
                a.start = now - OPEN_TICKS;
                anims.put(be, a);
            }
            float current = at(a, now);
            if (a.target != open) {
                a.from = current;
                a.start = now;
                a.target = open;
                current = a.from;
            }
            return current;
        }

        private static float at(Anim a, float now) {
            float goal = a.target ? (a.crate ? 103.0F : OPEN_DEGREES) : 0.0F;
            float t = Mth.clamp((now - a.start) / (a.crate ? 6.0F : OPEN_TICKS), 0.0F, 1.0F);
            // Cubic ease-out-back: about 1% overshoot, continuous when reversed mid-swing.
            float back = t - 1.0F;
            t = 1.0F + 1.6F * back * back * back + 0.6F * back * back;
            return Mth.lerp(t, a.from, goal);
        }

        private void renderPills(PillBoxBlockEntity be, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            net.minecraft.world.item.ItemStack main = net.minecraft.world.item.ItemStack.EMPTY;
            int count = 0;
            for (int i = 0; i < be.getContainerSize(); i++) {
                var stack = be.getItem(i);
                int total = 0, mainTotal = 0;
                for (int j = 0; j < be.getContainerSize(); j++) {
                    var other = be.getItem(j);
                    if (other.is(stack.getItem())) total += other.getCount();
                    if (!main.isEmpty() && other.is(main.getItem())) mainTotal += other.getCount();
                }
                if (!stack.isEmpty() && total > mainTotal) main = stack;
                count += stack.getCount();
            }
            if (main.isEmpty()) return;
            var renderer = Minecraft.getInstance().getItemRenderer();
            int visible = Math.min(count, be.isCrate() ? 28 : 5);
            for (int i = 0; i < visible; i++) {
                boolean glow = be.isCrate() && i % 6 == 2;
                pose.pushPose();
                try {
                    if (be.isCrate()) {
                        // Same 5.6/24 scale as the shell; 7x4 separate pellets.
                        // Their main top is two reference units below the rim.
                        int col = i % 7;
                        int row = i / 7;
                        double unit = (5.6 / 24) / 16;
                        pose.translate(.5 + (-9.3 + col * 3.05 + (row % 2) * .3) * unit,
                                (7 + (i * 7 % 5) * .25) * unit,
                                .5 + (-4.6 + row * 3.0) * unit);
                        BakedModel pillModel = Minecraft.getInstance().getModelManager()
                                .getModel(glow ? CRATE_PALE_PILL : CRATE_PILL);
                        blocks.getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()),
                                be.getBlockState(), pillModel, 1.0F, 1.0F, 1.0F,
                                glow ? net.minecraft.client.renderer.LightTexture.FULL_BRIGHT : light,
                                overlay, ModelData.EMPTY, null);
                    } else {
                        pose.translate(.36 + i % 3 * .13, .16, .43 + i / 3 * .13);
                        pose.scale(.12F, .12F, .12F);
                    }
                    if (!be.isCrate()) {
                        pose.mulPose(Axis.XP.rotationDegrees(90));
                        pose.mulPose(Axis.ZP.rotationDegrees(i * 137.5F));
                        renderer.renderStatic(main, net.minecraft.world.item.ItemDisplayContext.FIXED,
                                light, overlay, pose, buffers, be.getLevel(), i);
                    }
                } finally { pose.popPose(); }
            }
        }

        @Override
        public void render(PillBoxBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            BlockState state = be.getBlockState();
            if (!(state.getBlock() instanceof PillBoxBlock) || be.getLevel() == null) {
                return;
            }
            float angle = angle(be, state.getValue(PillBoxBlock.OPEN), be.getLevel().getGameTime() + partialTick);
            Direction facing = state.getValue(PillBoxBlock.FACING);
            // The blockstate turns a model about y by 0/90/180/270 for north/east/south/west
            float yRot = (facing.toYRot() + 180.0F) % 360.0F;
            BakedModel model = Minecraft.getInstance().getModelManager().getModel(be.isCrate() ? CRATE_LID : LID);
            pose.pushPose();
            try {
                pose.translate(0.0F, -state.getValue(TabletopSupport.SINK) / 16.0F, 0.0F);
                pose.translate(0.5F, 0.0F, 0.5F);
                pose.mulPose(Axis.YP.rotationDegrees(-yRot));
                pose.translate(-0.5F, 0.0F, -0.5F);
                if (state.getValue(PillBoxBlock.OPEN) || angle > 5) renderPills(be, pose, buffers, light, overlay);
                float hingeY = be.isCrate() ? (10 * 5.6F / 24) / 16 : PIVOT_Y;
                float hingeZ = be.isCrate() ? (8 + 8 * 5.6F / 24) / 16 : PIVOT_Z;
                pose.translate(0.0F, hingeY, hingeZ);
                pose.mulPose(Axis.XP.rotationDegrees(angle));
                pose.translate(0.0F, -hingeY, -hingeZ);
                blocks.getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()), state, model,
                        1.0F, 1.0F, 1.0F, light, overlay, ModelData.EMPTY, null);
            } finally { pose.popPose(); }
        }
    }
}
