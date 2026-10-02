package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.FoundationVfx;
import io.github.verycooltimo.murim.combat.FoundationForms;
import io.github.verycooltimo.murim.network.FoundationPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Optional;

/**
 * Основа меча на клиенте: обычная атака (ЛКМ) мечом превращается в формы Меча Шести
 * Равновесий по очереди (автор 01.10).
 *
 * <p>Ничего не решает: урон и попадание — ванильные, их ведёт обычная атака. Здесь — выбор
 * следующей формы, анимация (растянута под перезарядку оружия), эффект и сообщение серверу.
 *
 * <p>Если в сборке стоит Better Combat, ЛКМ не трогаем: он сам заменяет обычные удары.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class FoundationClient {

    private static int ticks;
    private static int step;
    private static int lastSwing = -10000;

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        ticks++;
    }

    @SubscribeEvent
    static void onInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isAttack()) {
            swing(Minecraft.getInstance(), false);
        }
    }

    /**
     * Взмах формой, если основа выбрана и в руке меч.
     *
     * @param ignoreBlocks не проверять, что под прицелом блок (стенд снимает со стороны —
     *                     прицел там у камеры, а не у игрока)
     * @return сделан ли взмах формой
     */
    public static boolean swing(Minecraft minecraft, boolean ignoreBlocks) {
        LocalPlayer player = minecraft.player;
        if (player == null || ModList.get().isLoaded("bettercombat")) {
            return false;
        }
        Optional<ResourceLocation> foundation = ClientLoadoutState.foundation();
        if (foundation.isEmpty() || !player.getMainHandItem().is(ItemTags.SWORDS)) {
            return false;
        }
        // Копание блока — не удар: форма только по воздуху и по существу.
        if (!ignoreBlocks && minecraft.hitResult != null && minecraft.hitResult.getType() == HitResult.Type.BLOCK) {
            return false;
        }
        // Недозаряженный удар — ванильный: форма никогда не быстрее и не медленнее ванили.
        if (player.getAttackStrengthScale(0.5F) < 0.9F) {
            return false;
        }
        int layer = Math.max(0, ClientMasteryState.layer(foundation.get()));
        float cooldown = player.getCurrentItemAttackStrengthDelay();
        if (ticks - lastSwing > FoundationForms.chainWindow(cooldown)) {
            step = 0;
        }
        FoundationForms.Form form = FoundationForms.at(step, layer);
        step++;
        lastSwing = ticks;
        play(player, form, layer, FoundationForms.speed(cooldown), cooldown, target(minecraft),
                foundation.get().getPath().equals("seven_plum_basic"));
        PacketDistributor.sendToServer(new FoundationPayloads.Swing(form.ordinal()));
        return true;
    }

    /** Точка попадания: середина существа под прицелом, если оно есть. */
    private static Vec3 target(Minecraft minecraft) {
        if (minecraft.hitResult instanceof EntityHitResult hit) {
            Entity e = hit.getEntity();
            return e.position().add(0.0D, e.getBbHeight() * 0.6D, 0.0D);
        }
        return null;
    }

    /** Чужой игрок сделал форму — показать её и у нас. */
    public static void onRemoteForm(FoundationPayloads.Form payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || payload.entityId() == minecraft.player.getId()) {
            return;
        }
        if (payload.form() < 0 || payload.form() >= FoundationForms.Form.values().length) {
            return;
        }
        if (minecraft.level.getEntity(payload.entityId()) instanceof AbstractClientPlayer other) {
            float cooldown = FoundationForms.NOMINAL_TICKS / Math.max(0.1F, payload.speed());
            play(other, FoundationForms.Form.values()[payload.form()], payload.layer(), payload.speed(), cooldown, null, false);
        }
    }

    private static void play(AbstractClientPlayer player, FoundationForms.Form form, int layer, float speed,
                             float cooldown, Vec3 hit, boolean plum) {
        MurimPlayerAnimations.playForm(player, ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, form.animation()), speed);
        FoundationVfx.start(player.getId(), form, layer, cooldown, hit, plum);
    }

    private FoundationClient() {
    }
}
