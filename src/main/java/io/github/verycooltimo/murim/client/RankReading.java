package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.AuraState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Чтение ранга взглядом (docs/design/19 §3ж, 01 «Чтение ранга»).
 *
 * <p>Навёл взгляд на существо с аурой — через полсекунды под прицелом проступает грубая
 * категория («слабее тебя», «примерно равен», «намного сильнее»). Всмотрелся полторы секунды —
 * вторая строка: примерный ранг и тяжесть давления. Словами, без чисел.
 *
 * <p>Точность растёт с собственным рангом: новичок видит только категорию; со второго ранга
 * называет ранг, но не выше своего на ступень — дальше «выше твоего понимания»; с Пика — почти
 * точно. Так мастера в книгах и чувствуют стадию: «ранний Пик», «первый ранг».
 *
 * <p>Всё на клиенте: ранги аур клиент и так знает (их рисует аура), игровых решений тут нет.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class RankReading {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "rank_reading");

    /** Насколько далеко достаёт взгляд. */
    private static final double RANGE = 32.0D;
    /** Тики до первой строки и до полного чтения. */
    private static final int HOVER_TICKS = 10;
    private static final int READ_TICKS = 30;
    /** Сколько текст держится после того, как взгляд ушёл. */
    private static final int LINGER_TICKS = 16;

    private static int targetId = -1;
    private static int lookTicks;
    private static int lingerTicks;

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, LAYER, RankReading::render);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.isPaused()) {
            return;
        }
        Entity seen = lookedAt(minecraft);
        if (seen != null && seen.getId() == targetId) {
            lookTicks++;
            lingerTicks = LINGER_TICKS;
        } else if (seen != null) {
            targetId = seen.getId();
            lookTicks = 1;
            lingerTicks = LINGER_TICKS;
        } else if (lingerTicks > 0) {
            lingerTicks--;
        } else {
            targetId = -1;
            lookTicks = 0;
        }
    }

    /** Существо с аурой под прицелом, не загороженное блоками. */
    private static Entity lookedAt(Minecraft minecraft) {
        Entity viewer = minecraft.getCameraEntity();
        if (viewer == null) {
            return null;
        }
        Vec3 eye = viewer.getEyePosition();
        Vec3 look = viewer.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(RANGE));
        HitResult block = minecraft.level.clip(new ClipContext(eye, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, viewer));
        double reach = block.getType() == HitResult.Type.MISS ? RANGE : block.getLocation().distanceTo(eye);
        end = eye.add(look.scale(reach));
        AABB box = viewer.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0D);
        // API: reference/minecraft-src/net/minecraft/world/entity/projectile/ProjectileUtil.java#getEntityHitResult
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(viewer, eye, end, box,
                e -> e instanceof LivingEntity && e != minecraft.player && ClientAuraState.of(e).present(), reach * reach);
        return hit == null ? null : hit.getEntity();
    }

    /** Грубая категория по разнице рангов. */
    static String categoryKey(int gap) {
        if (gap <= -1) {
            return "murim.read.weaker";
        }
        if (gap == 0) {
            return "murim.read.equal";
        }
        if (gap == 1) {
            return "murim.read.stronger";
        }
        return gap <= 4 ? "murim.read.much" : "murim.read.beyond";
    }

    /**
     * Примерный ранг словами или {@code null}, если читающий пока не умеет его назвать.
     *
     * @param own    ранг читающего
     * @param target ранг ауры цели
     */
    static Component rankWords(int own, int target) {
        if (own <= 1) {
            return null;
        }
        if (own < 4 && target > own + 1) {
            return Component.translatable("murim.read.unknowable");
        }
        if (own >= 4 && target >= 6) {
            return Component.translatable("murim.read.past_peak");
        }
        return Component.translatable("murim.read.rank." + target);
    }

    /** Тяжесть давления словами. */
    static String forceKey(int gap) {
        return gap <= 0 ? "murim.read.force.0" : gap == 1 ? "murim.read.force.1" : gap <= 3 ? "murim.read.force.2" : "murim.read.force.3";
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if ((minecraft.options.hideGui && !"true".equals(System.getProperty("murim.capture")))
                || targetId < 0 || lookTicks < HOVER_TICKS || minecraft.level == null) {
            return;
        }
        Entity target = minecraft.level.getEntity(targetId);
        if (target == null) {
            return;
        }
        AuraState aura = ClientAuraState.of(target);
        if (!aura.present()) {
            return;
        }
        int own = ClientProfileState.profile().rank();
        int gap = aura.rank() - own;
        float partial = delta.getGameTimeDeltaPartialTick(false);
        float fadeIn = Mth.clamp((lookTicks - HOVER_TICKS + partial) / 6.0F, 0.0F, 1.0F);
        float fadeOut = lingerTicks < LINGER_TICKS ? Mth.clamp((lingerTicks - partial) / LINGER_TICKS, 0.0F, 1.0F) : 1.0F;
        float alpha = fadeIn * fadeOut;
        if (alpha <= 0.02F) {
            return;
        }
        Font font = minecraft.font;
        int cx = graphics.guiWidth() / 2;
        int y = graphics.guiHeight() / 2 + 22;
        // Цвет строки — по угрозе: холодный серый, белый, тревожный янтарь, красный.
        int[] tint = {0xB8C2CC, 0xF2F2F2, 0xFFC46B, 0xFF5A4A, 0xFF3030};
        int category = gap <= -1 ? 0 : Math.min(4, gap + 1);
        line(graphics, font, Component.translatable(categoryKey(gap)), cx, y, tint[category], alpha);
        if (lookTicks >= READ_TICKS) {
            float full = Mth.clamp((lookTicks - READ_TICKS + partial) / 8.0F, 0.0F, 1.0F) * fadeOut;
            Component rank = rankWords(own, aura.rank());
            int row = y + 11;
            if (rank != null) {
                line(graphics, font, rank, cx, row, 0xD8DEE6, full);
                row += 10;
            }
            line(graphics, font, Component.translatable(forceKey(gap)), cx, row, 0xA9B3BD, full);
            row += 10;
            if (aura.demonic()) {
                line(graphics, font, Component.translatable("murim.read.demonic"), cx, row, 0xE0473C, full);
            }
        }
    }

    /** Строка по центру с мягкой тенью; прозрачность — в альфе цвета. */
    private static void line(GuiGraphics graphics, Font font, Component text, int cx, int y, int rgb, float alpha) {
        int a = Mth.clamp(Math.round(alpha * 255.0F), 0, 255);
        if (a < 8) {
            return;
        }
        int w = font.width(text);
        // Плотная подложка: на вспышках ауры и красном фоне строка иначе тонула (разбор codex 01.10).
        graphics.fill(cx - w / 2 - 4, y - 2, cx + w / 2 + 4, y + 10, (Math.round(a * 0.62F) << 24) | 0x05070B);
        graphics.drawString(font, text, cx - w / 2, y, (a << 24) | rgb, true);
    }

    private RankReading() {
    }
}
