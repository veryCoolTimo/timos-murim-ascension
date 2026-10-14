package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.FoundationPayloads;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import io.github.verycooltimo.murim.network.ChannelSafe;

import java.util.Optional;

/**
 * Основа меча на сервере: проверка взмаха, освоение и поведение форм при попадании.
 *
 * <p>Урон не трогается — он ванильный, от оружия (правило профиля: без множителей). Формы
 * меняют поведение: удар сверху сбивает стойку, восходящий подбрасывает, блок на миг
 * прикрывает. Боковой разрез — ванильный размах меча. Укол — просто точный.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = io.github.verycooltimo.murim.MurimMod.MODID)
public final class FoundationService {

    /** Сколько тиков взмах считается «тем самым» для попадания: пакет атаки идёт следом. */
    private static final int HIT_LINK_TICKS = 4;

    /** Основа меча игрока, если выбрана, выучена и в руке меч. */
    public static Optional<ResourceLocation> active(ServerPlayer player) {
        Optional<ResourceLocation> id = player.getData(ModAttachments.LOADOUT).foundation();
        if (id.isEmpty() || !MasteryService.knows(player, id.get())
                || !player.getData(ModAttachments.PROFILE).isAwakened()
                || !QiSword.holdsSword(player.getMainHandItem())) {
            return Optional.empty();
        }
        return id;
    }

    /** Клиент сообщил о взмахе формой. */
    public static void onSwing(ServerPlayer player, int formIndex) {
        Optional<ResourceLocation> id = active(player);
        if (id.isEmpty() || formIndex < 0 || formIndex >= FoundationForms.Form.values().length) {
            return;
        }
        if (!player.isAlive() || player.isSpectator()
                || player.getData(ModAttachments.TECHNIQUE_STATE).isActive()) {
            return;
        }
        boolean plum = SwordBasicCombo.isBasic(id.get());
        int executionTier = Math.max(0, MasteryService.executionTier(player, id.get()));
        if (plum) {
            long now = player.level().getGameTime();
            SwordBasicCombo.State combo = player.getData(ModAttachments.BASIC_COMBO);
            if (combo.recovering(id.get(), now)
                    && player.getAttackStrengthScale(SwordBasicCombo.CONTACT_TICK) < 0.99F) return;
            int step = combo.next(id.get(), now);
            // The client predicts, the server owns the order (including misses).
            formIndex = SwordBasicCombo.at(step).ordinal();
            player.setData(ModAttachments.BASIC_COMBO,
                    new SwordBasicCombo.State(id.get(), (step + 1) % 3, now));
        } else if (player.getAttackStrengthScale(0.0F) < 0.9F
                || formIndex >= FoundationForms.unlocked(executionTier)) {
            return;
        }
        player.setData(ModAttachments.FOUNDATION_SWING, new int[] {formIndex, player.tickCount});
        // Каждый правильный взмах — тренировка формы; попадание добавит своё.
        MasteryService.onMiss(player, id.get());
        float speed = plum ? 1.0F : FoundationForms.speed(player.getCurrentItemAttackStrengthDelay());
        ChannelSafe.nearEntity(player,
                new FoundationPayloads.Form(player.getId(), formIndex, executionTier, speed, plum), false);
        // Утренний строй секты: форма в такт засчитывается в «Утреннюю тренировку».
        io.github.verycooltimo.murim.sect.SectLife.onPlayerForm(player);
    }

    /**
     * Попадание обычной атакой при выбранной основе. Вызывается из {@link SchoolStyle}.
     *
     * @return {@code true}, если удар обработан основой (старая серия стиля школы не нужна)
     */
    public static boolean onHit(ServerPlayer player, LivingEntity target) {
        Optional<ResourceLocation> id = active(player);
        if (id.isEmpty()) {
            return false;
        }
        // Style feedback/mastery is awarded after successful hurt, not at AttackEntityEvent.
        if (SwordBasicCombo.isBasic(id.get())) return true;
        int[] swing = player.getData(ModAttachments.FOUNDATION_SWING);
        if (swing[1] < 0 || player.tickCount - swing[1] > HIT_LINK_TICKS) {
            return true;
        }
        MasteryService.onHit(player, id.get(), target);
        switch (FoundationForms.Form.values()[swing[0]]) {
            case OVERHEAD -> target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 1, false, true, true));
            case RISING -> {
                target.push(0.0D, 0.25D, 0.0D);
                target.hurtMarked = true;
            }
            case BLOCK -> player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 10, 0, false, false, false));
            default -> {
            }
        }
        return true;
    }

    /** Only confirmed direct vanilla damage counts; canceled attacks and arrows cannot fake a hit. */
    @net.neoforged.bus.api.SubscribeEvent
    static void onDamage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0.0F
                || !(event.getSource().getEntity() instanceof ServerPlayer player)
                || event.getSource().getDirectEntity() != player) return;
        Optional<ResourceLocation> id = active(player);
        if (id.isEmpty() || !SwordBasicCombo.isBasic(id.get())
                || player.getData(ModAttachments.TECHNIQUE_STATE).isActive()
                || !id.get().equals(player.getData(ModAttachments.BASIC_COMBO).basic())) return;
        int[] swing = player.getData(ModAttachments.FOUNDATION_SWING);
        if (swing[1] < 0 || player.tickCount - swing[1] > HIT_LINK_TICKS) return;
        // Consume once: sweeping damage must not repeat sound, mastery or contact VFX.
        player.setData(ModAttachments.FOUNDATION_SWING, new int[] {swing[0], -1});
        MasteryService.onHit(player, id.get(), event.getEntity());
        player.level().playSound(null, event.getEntity().getX(), event.getEntity().getY(), event.getEntity().getZ(),
                io.github.verycooltimo.murim.registry.ModSounds.SWORD_HIT.get(),
                net.minecraft.sounds.SoundSource.PLAYERS, 0.45F, 1.2F);
        // Closest point on the damaged entity's surface, captured now, not its centre on receipt.
        var box = event.getEntity().getBoundingBox();
        var eye = player.getEyePosition();
        var contact = new net.minecraft.world.phys.Vec3(
                net.minecraft.util.Mth.clamp(eye.x, box.minX, box.maxX),
                net.minecraft.util.Mth.clamp(eye.y, box.minY, box.maxY),
                net.minecraft.util.Mth.clamp(eye.z, box.minZ, box.maxZ));
        boolean finisher = SwordBasicCombo.isFinisher(FoundationForms.Form.values()[swing[0]]);
        ChannelSafe.nearEntity(player,
                new FoundationPayloads.Hit(player.getId(), event.getEntity().getId(), finisher, contact), true);
    }

    private FoundationService() {
    }
}
