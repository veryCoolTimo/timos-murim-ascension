package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.Styles;
import io.github.verycooltimo.murim.technique.TechniqueBehavior;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Меч в руке и ци-меч (этап M1, автор 03.10).
 *
 * <p>Мечевые техники (Семь Цветков, 24 Движения, Падающий Цветок, основы ЛКМ, выхваты) работают
 * только с мечом в руке: ванильным ({@code #minecraft:swords}) или будущим мечом мода
 * ({@code #murim:swords}, тег уже включает ванильные). С пустой рукой у пробуждённого клинок
 * появляется сам — ци-меч: без новой клавиши, на первый взмах или первую мечевую форму, и гаснет
 * через {@link #HOLD_TICKS} без мечевых действий. Ладонь и шаги меча не требуют.
 *
 * <p>Ци-меч бьёт как железный меч: пока он «вынут», на пустую руку действует временный
 * модификатор урона +5 и скорости атаки −2,4 (итог 6 и 1,6 — как у железного меча). Ци-меч есть
 * только с устоявшегося Пика ({@link #MIN_RANK}, {@link #MIN_STAGE}); ниже мечевые формы с пустой
 * рукой не работают, как без меча.
 *
 * <p>Состояние «вынут» — тик последнего мечевого действия в {@link Player#getPersistentData()}:
 * оно серверное, на клиент не синхронизируется. Клиент рисует клинок сам по тем же событиям
 * (старт мечевой техники, взмах основы), см. client/QiSwordClient.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class QiSword {

    /** Мечи мода и ванильные: {@code data/murim/tags/item/swords.json} включает #minecraft:swords. */
    public static final TagKey<Item> SWORDS = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "swords"));

    /** Сколько ци-меч держится после последнего мечевого действия, тиков. */
    public static final int HOLD_TICKS = 200;

    private static final String DRAWN_KEY = "murim_qi_sword_drawn";

    /** Прибавка урона ци-меча к пустой руке (1 + 5 = 6, как железный меч). */
    public static final double DAMAGE_BONUS = 5.0D;

    private static final ResourceLocation DAMAGE_ID = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "qi_sword_damage");
    private static final ResourceLocation SPEED_ID = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "qi_sword_speed");

    /** Меч в руке: ванильный или мода. */
    public static boolean holdsSword(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(SWORDS);
    }

    /**
     * Ранг, с которого доступен ци-меч. Канон (Absolute Regression, «Keen Qi»): «его не натренировать,
     * только постичь; без устойчивого Пика не достичь» — Пик у нас ранг 4, и автор (03.10) включил канон.
     */
    public static final int MIN_RANK = io.github.verycooltimo.murim.cultivation.Realm.PEAK;

    /** Подступень Пика, с которой есть ци-меч: «устоявшийся Пик» — утвердившаяся (автор 03.10). */
    public static final int MIN_STAGE = io.github.verycooltimo.murim.cultivation.Realm.STAGE_SETTLED;

    /**
     * С какого ранга ци-меч — высшей ступени (автор 03.10: «аура-меч для evolutionary realm»):
     * больше вихря, завитков и чёрные волны. Ранг 5 — «Безграничный», следующий за Пиком.
     * [НЕПРОВЕРЕНО: какую ступень автор называет «evolutionary realm» — уточнить; одна константа.]
     */
    public static final int EVOLVED_RANK = 5;

    /** Может ли ци-меч появиться: рука пуста, даньтянь сформирован, Пик устоялся ({@link #available}). */
    public static boolean qiAvailable(Player player) {
        return player.getMainHandItem().isEmpty() && available(player.getData(ModAttachments.PROFILE));
    }

    /** Общая для сервера и клиента проверка профиля. */
    public static boolean available(io.github.verycooltimo.murim.profile.DantianProfile profile) {
        return profile.isAwakened() && (profile.rank() > MIN_RANK
                || profile.rank() == MIN_RANK && profile.stage() >= MIN_STAGE);
    }

    /** Есть ли чем бить мечевой техникой: меч в руке или ци-меч. */
    public static boolean hasBlade(Player player) {
        return holdsSword(player.getMainHandItem()) || qiAvailable(player);
    }

    /** Мечевая ли техника: формы стилей меча, их основы ЛКМ, Падающий Цветок, выхваты. */
    public static boolean needsSword(TechniqueDefinition d) {
        if (d == null) {
            return false;
        }
        java.util.Optional<Styles.Style> style = Styles.of(d.id());
        if (style.isPresent() && (style.get() == Styles.SEVEN_PLUM || style.get() == Styles.TWENTY_FOUR_PLUM)) {
            return true;
        }
        if (Styles.ofBasic(d.id()).isPresent() || d.foundation()) {
            return true;
        }
        return d.behavior() instanceof TechniqueBehavior.MeleeArc || d.behavior() instanceof TechniqueBehavior.FallingPetal;
    }

    public static boolean needsSword(ResourceLocation technique) {
        return needsSword(TechniqueLoader.get(technique));
    }

    /** Мечевое действие с пустой рукой — ци-меч вынут (или продлён). */
    public static void draw(ServerPlayer player) {
        if (!qiAvailable(player)) {
            return;
        }
        player.getPersistentData().putLong(DRAWN_KEY, player.level().getGameTime());
        apply(player, true);
    }

    /** Вынут ли сейчас ци-меч. */
    public static boolean drawn(Player player) {
        long at = player.getPersistentData().getLong(DRAWN_KEY);
        return at > 0L && player.level().getGameTime() - at < HOLD_TICKS && qiAvailable(player);
    }

    /** Урон руки без прибавки ци-меча: для техник, которым меч не нужен (ладонь). */
    public static double bonus(Player player) {
        AttributeInstance a = player.getAttribute(Attributes.ATTACK_DAMAGE);
        return a != null && a.hasModifier(DAMAGE_ID) ? DAMAGE_BONUS : 0.0D;
    }

    // API: reference/minecraft-src/net/minecraft/world/entity/ai/attributes/AttributeInstance.java#addTransientModifier/removeModifier
    private static void apply(Player player, boolean on) {
        AttributeInstance damage = player.getAttribute(Attributes.ATTACK_DAMAGE);
        AttributeInstance speed = player.getAttribute(Attributes.ATTACK_SPEED);
        if (damage == null || speed == null) {
            return;
        }
        if (on) {
            if (!damage.hasModifier(DAMAGE_ID)) {
                damage.addTransientModifier(new AttributeModifier(DAMAGE_ID, DAMAGE_BONUS, AttributeModifier.Operation.ADD_VALUE));
            }
            if (!speed.hasModifier(SPEED_ID)) {
                speed.addTransientModifier(new AttributeModifier(SPEED_ID, -2.4D, AttributeModifier.Operation.ADD_VALUE));
            }
        } else {
            damage.removeModifier(DAMAGE_ID);
            speed.removeModifier(SPEED_ID);
        }
    }

    /** Гасим ци-меч, когда в руку взяли предмет или он долго не нужен. */
    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        AttributeInstance damage = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damage != null && damage.hasModifier(DAMAGE_ID) && !drawn(player)) {
            apply(player, false);
        }
    }

    private QiSword() {
    }
}
