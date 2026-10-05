package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;
import java.util.Optional;

/**
 * Искусства третьего сорта (docs/design/techniques/junk-arts.md, автор 05.10: «немного мусорных техник»): то, что
 * в мире мурим знает каждый разбойник Зелёного леса и каждый деревенский драчун. Учатся из дешёвых книжек
 * (лут бандитов, торговец) и из «Непобедимого Меча Десяти Тысяч Драконов» — подделки с полок архива.
 *
 * <p>Собраны из существующих поведений (взмах, ладонь, рывок) и одного нового — {@link TechniqueBehavior.SelfArt}.
 * У каждой честный изъян: срыв любым ударом, расплата телом, ничтожный урон. ВИЗУАЛ: слой 0 — без эффектов
 * совсем и на всех слоях (клиент не запускает ни след клинка, ни ладонь, ни импакт-кадр); слой 1 отличается
 * только звуком попадания — это мусор, а не техника мастера. Каталог общий для сервера и клиента.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class JunkArts {

    /** Чем бьют: голые руки, меч (или ци-меч), топор. */
    public enum Weapon { NONE, SWORD, AXE }

    public record Art(ResourceLocation id, Weapon weapon) {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    public static final ResourceLocation BRAWLER_FIST = id("village_brawler_fist");
    public static final ResourceLocation GREEN_FOREST_AXE = id("green_forest_axe");
    public static final ResourceLocation TEN_THOUSAND_DRAGONS = id("ten_thousand_dragons");
    public static final ResourceLocation BURST_BREATH = id("burst_breath");
    public static final ResourceLocation BANDIT_RUSH = id("bandit_rush");
    public static final ResourceLocation SAND_IN_EYES = id("sand_in_eyes");

    public static final List<Art> ALL = List.of(
            new Art(BRAWLER_FIST, Weapon.NONE),
            new Art(GREEN_FOREST_AXE, Weapon.AXE),
            new Art(TEN_THOUSAND_DRAGONS, Weapon.SWORD),
            new Art(BURST_BREATH, Weapon.NONE),
            new Art(BANDIT_RUSH, Weapon.NONE),
            new Art(SAND_IN_EYES, Weapon.NONE));

    /** Книжки, что носят разбойники (лут, торговец): всё, кроме подделки — та только с полок архива. */
    public static final List<ResourceLocation> STREET_BOOKS = List.of(BRAWLER_FIST, GREEN_FOREST_AXE, BURST_BREATH,
            BANDIT_RUSH, SAND_IN_EYES);

    /** Топор тянет тело: замедление после удара, тиков. */
    public static final int AXE_STAGGER = 40;
    /** Наскок выматывает: замедление после рывка, тиков. */
    public static final int RUSH_WINDED = 30;
    /** Сбитые костяшки: урон себе за каждое попадание кулаком. */
    public static final float KNUCKLES = 0.5F;
    /** Песок в глаза: сколько моб не видит цели, тиков. */
    public static final int SAND_BLIND = 60;

    private static final String BACKLASH_KEY = "murim_junk_backlash";

    public static boolean isJunk(ResourceLocation technique) {
        return art(technique).isPresent();
    }

    public static Optional<Art> art(ResourceLocation technique) {
        for (Art a : ALL) {
            if (a.id().equals(technique)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    /** Книжка уличного искусства по броску {@code roll} в [0, 1) — поровну. */
    public static ResourceLocation streetBook(double roll) {
        int i = Math.min(STREET_BOOKS.size() - 1, (int) Math.floor(roll * STREET_BOOKS.size()));
        return STREET_BOOKS.get(Math.max(0, i));
    }

    /**
     * Чего не хватает в руке, чтобы применить искусство: ключ сообщения или пусто. Меч проверяет QiSword
     * (там же ци-меч), здесь — только топор.
     */
    public static Optional<String> weaponProblem(Player player, ResourceLocation technique) {
        Optional<Art> a = art(technique);
        if (a.isPresent() && a.get().weapon() == Weapon.AXE && !player.getMainHandItem().is(ItemTags.AXES)) {
            return Optional.of("murim.junk_art.need_axe");
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ сервер

    /** Попадание искусства по цели (взмах, ладонь, рывок): особенности и мелкие частицы со слоя 1. */
    public static void onHit(ServerPlayer player, ResourceLocation technique, LivingEntity target) {
        if (!isJunk(technique)) {
            return;
        }
        int layer = Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.layer(player, technique));
        if (SAND_IN_EYES.equals(technique)) {
            // Мастера не обмануть горстью песка: босс крепости только щурится.
            if (!(target instanceof io.github.verycooltimo.murim.entity.boss.FortressMaster)) {
                target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, SAND_BLIND, 0, false, true, true));
                if (target instanceof Mob mob && mob.getTarget() == player) {
                    mob.setTarget(null);
                }
            }
        }
        if (BRAWLER_FIST.equals(technique)) {
            player.hurt(player.damageSources().generic(), KNUCKLES);
        }
        if (layer >= 1) {
            // Обжитое искусство звучит увереннее; частиц нет вовсе (правило 03: частицы только через клиентский VfxManager).
            net.minecraft.sounds.SoundEvent sound = SAND_IN_EYES.equals(technique) ? SoundEvents.SAND_BREAK
                    : GREEN_FOREST_AXE.equals(technique) ? SoundEvents.PLAYER_ATTACK_CRIT : SoundEvents.PLAYER_ATTACK_STRONG;
            player.level().playSound(null, target.getX(), target.getY(), target.getZ(), sound, SoundSource.PLAYERS, 0.8F, 0.9F);
        }
    }

    /** После фазы удара: расплата телом у топора и наскока. */
    public static void afterImpact(ServerPlayer player, ResourceLocation technique) {
        if (GREEN_FOREST_AXE.equals(technique)) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, AXE_STAGGER, 1, false, false, true));
        } else if (BANDIT_RUSH.equals(technique)) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, RUSH_WINDED, 2, false, false, true));
            player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, RUSH_WINDED, 1, false, false, true));
        }
    }

    /** Дыхание третьего сорта: сила сразу, расплата — когда она кончится ({@link #tick}). */
    public static boolean selfArt(ServerPlayer player, TechniqueBehavior.SelfArt art, ResourceLocation technique) {
        Optional<Holder.Reference<MobEffect>> buff = BuiltInRegistries.MOB_EFFECT.getHolder(art.buff());
        if (buff.isEmpty()) {
            MurimMod.LOGGER.error("Техника {}: нет эффекта {}", technique, art.buff());
            return false;
        }
        player.addEffect(new MobEffectInstance(buff.get(), art.buffTicks(), art.amplifier(), false, true, true));
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putLong("at", player.level().getGameTime() + art.buffTicks());
        tag.putString("technique", technique.toString());
        player.getPersistentData().put(BACKLASH_KEY, tag);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_BREATH, SoundSource.PLAYERS, 0.8F, 0.7F);
        return false;
    }

    /** Расплата дыхания: когда сила кончилась — слабость, тошнота и кровь из носа. */
    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            backlash(player);
        }
    }

    /** Наступила ли расплата: снять силу долгом. Отдельно от события — его зовёт GameTest. */
    static void backlash(ServerPlayer player) {
        if (!player.getPersistentData().contains(BACKLASH_KEY)) {
            return;
        }
        net.minecraft.nbt.CompoundTag tag = player.getPersistentData().getCompound(BACKLASH_KEY);
        if (player.level().getGameTime() < tag.getLong("at")) {
            return;
        }
        player.getPersistentData().remove(BACKLASH_KEY);
        TechniqueDefinition d = TechniqueLoader.get(ResourceLocation.tryParse(tag.getString("technique")));
        if (d == null || !(d.behavior() instanceof TechniqueBehavior.SelfArt art) || !player.isAlive()) {
            return;
        }
        BuiltInRegistries.MOB_EFFECT.getHolder(art.backlash()).ifPresent(e ->
                player.addEffect(new MobEffectInstance(e, art.backlashTicks(), 1, false, true, true)));
        player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, Math.min(100, art.backlashTicks()), 0, false, false, true));
        if (art.selfDamage() > 0.0F) {
            // API: reference/minecraft-src/net/minecraft/world/damagesource/DamageSources.java#magic (сквозь броню).
            player.hurt(player.damageSources().magic(), art.selfDamage());
        }
        player.displayClientMessage(Component.translatable("murim.junk_art.backlash", Component.translatable(
                "technique." + d.id().getNamespace() + "." + d.id().getPath())).withStyle(ChatFormatting.DARK_RED), true);
    }

    private JunkArts() {
    }
}
