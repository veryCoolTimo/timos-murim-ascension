package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.FoundationVfx;
import io.github.verycooltimo.murim.combat.FoundationForms;
import io.github.verycooltimo.murim.combat.SwordBasicCombo;
import io.github.verycooltimo.murim.network.FoundationPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
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
 * <p>Ничего не решает: урон и попадание решает сервер; стиль вызывает ванильную атаку на тике контакта. Здесь — выбор
 * следующей формы, анимация (короткая для стиля, под перезарядку для Шести Равновесий), эффект и сообщение серверу.
 *
 * <p>Если в сборке стоит Better Combat, ЛКМ не трогаем: он сам заменяет обычные удары.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class FoundationClient {

    private static int ticks;
    private static int step;
    private static int lastSwing = -10000;
    private static ResourceLocation lastFoundation;

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            step = 0;
            lastSwing = -10000;
            lastFoundation = null;
        } else if (!minecraft.isPaused()) {
            ticks++;
        }
    }

    @SubscribeEvent
    static void onInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isAttack() && !event.isCanceled()) {
            Minecraft minecraft = Minecraft.getInstance();
            // A style cut owns the attack intent; sending the vanilla interaction as well doubles damage.
            boolean style = minecraft.player != null && SwordBasicCombo.ownsAttack(ClientLoadoutState.foundation().orElse(null),
                    ClientProfileState.profile().isAwakened()
                            && ClientLoadoutState.foundation().filter(id -> TechniqueSlotsHud.mastery(id) != null).isPresent()
                            && io.github.verycooltimo.murim.combat.QiSword.holdsSword(minecraft.player.getMainHandItem()),
                    ModList.get().isLoaded("bettercombat"))
                    && (minecraft.hitResult == null || minecraft.hitResult.getType() != HitResult.Type.BLOCK);
            swing(minecraft, false);
            if (style) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
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
        return swing(minecraft, ignoreBlocks, -2);
    }

    /** Capture selects its target before moving the camera; -2 uses the normal crosshair, -1 is air. */
    public static boolean swing(Minecraft minecraft, boolean ignoreBlocks, int captureTarget) {
        LocalPlayer player = minecraft.player;
        if (player == null || ModList.get().isLoaded("bettercombat")) {
            return false;
        }
        Optional<ResourceLocation> foundation = ClientLoadoutState.foundation();
        if (foundation.isEmpty() || !ClientProfileState.profile().isAwakened()
                || TechniqueSlotsHud.mastery(foundation.get()) == null
                || !io.github.verycooltimo.murim.combat.QiSword.holdsSword(player.getMainHandItem())) {
            return false;
        }
        // Копание блока — не удар: форма только по воздуху и по существу.
        if (!ignoreBlocks && minecraft.hitResult != null && minecraft.hitResult.getType() == HitResult.Type.BLOCK) {
            return false;
        }
        boolean plum = SwordBasicCombo.isBasic(foundation.get());
        // Weak accepted clicks keep vanilla damage scaling. Fully charged fast swords can chain early.
        if (plum && foundation.get().equals(lastFoundation) && ticks - lastSwing < SwordBasicCombo.SWING_TICKS
                && player.getAttackStrengthScale(0.0F) < 0.99F) {
            return false;
        }
        if (!plum && player.getAttackStrengthScale(0.0F) < 0.9F) {
            return false;
        }
        int executionTier = Math.max(0, ClientMasteryState.executionTier(foundation.get()));
        float cooldown = player.getCurrentItemAttackStrengthDelay();
        if (!foundation.get().equals(lastFoundation)
                || (plum ? ticks - lastSwing >= SwordBasicCombo.RESET_TICKS
                         : ticks - lastSwing > FoundationForms.chainWindow(cooldown))) {
            step = 0;
        }
        FoundationForms.Form form = plum ? SwordBasicCombo.at(step) : FoundationForms.at(step, executionTier);
        step++;
        lastSwing = ticks;
        lastFoundation = foundation.get();
        if (plum && SwordBasicCombo.isFinisher(form)) {
            io.github.verycooltimo.murim.client.vfx.TechniqueNameLayer.show(
                    net.minecraft.network.chat.Component.translatable("technique.murim.sword_finisher.name"), 0);
            CombatMode.engage();
        }
        play(player, form, executionTier, plum ? 1.0F : FoundationForms.speed(cooldown),
                plum ? SwordBasicCombo.SWING_TICKS : cooldown,
                !plum && minecraft.hitResult instanceof EntityHitResult hit
                        ? hit.getEntity().position().add(0.0D, hit.getEntity().getBbHeight() * 0.6D, 0.0D) : null, plum);
        int targetId = captureTarget >= -1 ? captureTarget
                : minecraft.hitResult instanceof EntityHitResult hit ? hit.getEntity().getId() : -1;
        PacketDistributor.sendToServer(new FoundationPayloads.Swing(form.ordinal(), plum ? targetId : -1));
        if (plum) {
            // The native hand swing supplies immediate first-person feedback; it sends only
            // an animation packet, not a second attack. PAL controls the third-person cut.
            player.resetAttackStrengthTicker();
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
        return true;
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
            float cooldown = payload.plum() ? SwordBasicCombo.SWING_TICKS
                    : FoundationForms.NOMINAL_TICKS / Math.max(0.1F, payload.speed());
            play(other, FoundationForms.Form.values()[payload.form()], payload.executionTier(), payload.speed(), cooldown, null, payload.plum());
        } else if (minecraft.level.getEntity(payload.entityId()) instanceof net.minecraft.world.entity.LivingEntity npc) {
            // NPC секты: тело анимирует его модель (SectDisciple.anim), здесь — только серп.
            float cooldown = payload.plum() ? SwordBasicCombo.SWING_TICKS
                    : FoundationForms.NOMINAL_TICKS / Math.max(0.1F, payload.speed());
            FoundationVfx.start(npc.getId(), FoundationForms.Form.values()[payload.form()], payload.executionTier(), cooldown, null, payload.plum());
        }
    }

    private static void play(AbstractClientPlayer player, FoundationForms.Form form, int executionTier, float speed,
                             float cooldown, Vec3 hit, boolean plum) {
        MurimPlayerAnimations.playForm(player, ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, plum ? SwordBasicCombo.animation(form) : form.animation()), speed);
        FoundationVfx.start(player.getId(), form, executionTier, cooldown, hit, plum);
    }

    private FoundationClient() {
    }
}
