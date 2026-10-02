package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.TraversePayloads;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.TraverseRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Цингун по миру — Шаг Молнии (docs/design/20-movement-qinggong.md). Бег идёт обычной
 * ванильной физикой игрока: техника лишь даёт временные модификаторы скорости, высоты ступени
 * и безопасного падения (атрибуты синхронизируются на клиент сами), а толчки — длинный прыжок,
 * отталкивание от стены, воздушная коррекция — сервер выдаёт импульсом после проверки.
 *
 * <p>Бег держится, пока игрок спринтует, есть ци и не вышло время; урон и атака его сбивают.
 * Повторный R — выключить.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class TraverseService {

    private static final ResourceLocation SPEED = id("traverse_speed");
    private static final ResourceLocation STEP = id("traverse_step");
    private static final ResourceLocation FALL = id("traverse_fall");

    private static final int ACTIVE = 0;
    private static final int LAYER = 1;
    private static final int LEFT = 2;
    private static final int KICKS = 3;
    private static final int LAST_LEAP = 4;
    private static final int AIR_USED = 5;

    /** Тиков без спринта, после которых бег гаснет (короткая заминка у поворота не рвёт режим). */
    private static final int GRACE = 6;

    public static boolean isActive(ServerPlayer player) {
        return player.getData(ModAttachments.TRAVERSE)[ACTIVE] != 0;
    }

    /** Включить бег (на фазе удара техники). Повторное включение во время бега — выключение. */
    public static void toggle(ServerPlayer player, ResourceLocation technique) {
        if (isActive(player)) {
            stop(player);
            return;
        }
        int layer = Math.max(0, MasteryService.layer(player, technique));
        int[] s = player.getData(ModAttachments.TRAVERSE).clone();
        s[ACTIVE] = 1;
        s[LAYER] = layer;
        s[LEFT] = TraverseRules.maxTicks(layer);
        s[KICKS] = TraverseRules.wallKicks(layer);
        s[AIR_USED] = 0;
        player.setData(ModAttachments.TRAVERSE, s);
        modify(player, Attributes.MOVEMENT_SPEED, SPEED, TraverseRules.speedBonus(layer), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        double step = TraverseRules.stepHeight(layer) - 0.6D;
        if (step > 0.0D) {
            modify(player, Attributes.STEP_HEIGHT, STEP, step, AttributeModifier.Operation.ADD_VALUE);
        }
        modify(player, Attributes.SAFE_FALL_DISTANCE, FALL, TraverseRules.safeFall(layer) - 3.0D, AttributeModifier.Operation.ADD_VALUE);
        player.setSprinting(true);
        send(player, 1, layer, Vec3.ZERO);
    }

    public static void stop(ServerPlayer player) {
        int[] s = player.getData(ModAttachments.TRAVERSE);
        if (s[ACTIVE] == 0) {
            return;
        }
        int[] n = s.clone();
        n[ACTIVE] = 0;
        player.setData(ModAttachments.TRAVERSE, n);
        remove(player, Attributes.MOVEMENT_SPEED, SPEED);
        remove(player, Attributes.STEP_HEIGHT, STEP);
        // Безопасное падение держится до первой посадки: прыжок мог начаться на последнем тике бега.
        if (player.onGround()) {
            remove(player, Attributes.SAFE_FALL_DISTANCE, FALL);
        }
        send(player, 0, n[LAYER], Vec3.ZERO);
    }

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        int[] s = player.getData(ModAttachments.TRAVERSE);
        if (s[ACTIVE] == 0) {
            AttributeInstance fall = player.getAttribute(Attributes.SAFE_FALL_DISTANCE);
            if (player.onGround() && fall != null && fall.hasModifier(FALL)) {
                fall.removeModifier(FALL);
            }
            return;
        }
        try {
            int[] n = s.clone();
            if (player.onGround()) {
                n[KICKS] = TraverseRules.wallKicks(n[LAYER]);
                n[AIR_USED] = 0;
            }
            n[LEFT]--;
            // Бег держится спринтом; в воздухе спринт не теряется, на земле без него — заминка.
            if (!player.isSprinting() && player.onGround()) {
                n[LEFT] = Math.min(n[LEFT], GRACE);
            }
            double cost = TraverseRules.qiPerSecond(n[LAYER]) / 20.0D;
            DantianProfile profile = player.getData(ModAttachments.PROFILE);
            if (n[LEFT] <= 0 || profile.circulating() < cost || player.isPassenger() || player.isFallFlying()
                    || player.isInWater() || !player.isAlive()) {
                player.setData(ModAttachments.TRAVERSE, n);
                stop(player);
                return;
            }
            player.setData(ModAttachments.PROFILE, profile.withCirculating(profile.circulating() - cost));
            if (player.tickCount % 10 == 0) {
                ProfileNetwork.sync(player);
            }
            player.setData(ModAttachments.TRAVERSE, n);
        } catch (RuntimeException e) {
            MurimMod.LOGGER.error("Сбой цингуна у {}", player.getGameProfile().getName(), e);
            stop(player);
        }
    }

    /**
     * Прыжок во время бега. На земле — длинный прыжок (со слоя 1); в воздухе у стены —
     * отталкивание (со слоя 2); в воздухе без стены — одна коррекция направления (слой 4).
     */
    public static void jump(ServerPlayer player) {
        int[] s = player.getData(ModAttachments.TRAVERSE);
        if (s[ACTIVE] == 0) {
            return;
        }
        int layer = s[LAYER];
        Vec3 look = horizontal(player.getLookAngle());
        long now = player.serverLevel().getGameTime();
        // Клиент шлёт прыжок в момент отрыва — сервер может ещё видеть игрока на земле или уже
        // в воздухе на тик-два; «почти на земле» считаем землёй.
        boolean grounded = player.onGround() || player.getDeltaMovement().y > 0.3D && player.fallDistance < 0.5F;
        int[] n = s.clone();
        if (grounded) {
            long last = n[LAST_LEAP];
            if (TraverseRules.leapHorizontal(layer) <= 0.0D || now - last < TraverseRules.LEAP_COOLDOWN
                    || !pay(player, TraverseRules.LEAP_QI)) {
                return;
            }
            n[LAST_LEAP] = (int) now;
            player.setDeltaMovement(look.scale(TraverseRules.leapHorizontal(layer)).add(0.0D, TraverseRules.leapVertical(layer), 0.0D));
            player.hurtMarked = true;
            player.setData(ModAttachments.TRAVERSE, n);
            send(player, 2, layer, look);
            return;
        }
        Vec3 wall = wallNormal(player);
        if (wall != null && n[KICKS] > 0) {
            if (!pay(player, TraverseRules.WALL_KICK_QI)) {
                return;
            }
            n[KICKS]--;
            Vec3 along = look.subtract(wall.scale(look.dot(wall)));
            Vec3 v = wall.scale(TraverseRules.WALL_KICK_OUT).add(along.scale(0.25D)).add(0.0D, TraverseRules.WALL_KICK_UP, 0.0D);
            player.setDeltaMovement(v);
            player.hurtMarked = true;
            player.fallDistance = 0.0F;
            player.setData(ModAttachments.TRAVERSE, n);
            send(player, 3, layer, wall);
            return;
        }
        if (wall == null && TraverseRules.airCorrection(layer) && n[AIR_USED] == 0) {
            if (!pay(player, TraverseRules.AIR_CORRECTION_QI)) {
                return;
            }
            n[AIR_USED] = 1;
            Vec3 d = player.getDeltaMovement();
            // Направление меняется, высота — нет: это не второй прыжок.
            player.setDeltaMovement(look.scale(TraverseRules.AIR_CORRECTION_SPEED).add(0.0D, d.y, 0.0D));
            player.hurtMarked = true;
            player.setData(ModAttachments.TRAVERSE, n);
            send(player, 4, layer, look);
        }
    }

    /** Нормаль ближайшей стены сбоку или спереди (до 0,4 блока от хитбокса), иначе null. */
    private static Vec3 wallNormal(ServerPlayer player) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            Vec3 probe = player.position().add(dir.getStepX() * 0.7D, 0.9D, dir.getStepZ() * 0.7D);
            BlockPos pos = BlockPos.containing(probe);
            if (player.level().getBlockState(pos).isFaceSturdy(player.level(), pos, dir.getOpposite())) {
                return new Vec3(-dir.getStepX(), 0.0D, -dir.getStepZ());
            }
        }
        return null;
    }

    private static boolean pay(ServerPlayer player, double cost) {
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        if (profile.circulating() < cost) {
            return false;
        }
        player.setData(ModAttachments.PROFILE, profile.withCirculating(profile.circulating() - cost));
        ProfileNetwork.sync(player);
        return true;
    }

    /** Урон сбивает бег: цингун — не уклонение. */
    @SubscribeEvent
    static void onDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isActive(player)) {
            stop(player);
        }
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            stop(player);
            remove(player, Attributes.SAFE_FALL_DISTANCE, FALL);
        }
    }

    @SubscribeEvent
    static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            stop(player);
        }
    }

    private static Vec3 horizontal(Vec3 v) {
        Vec3 h = new Vec3(v.x, 0.0D, v.z);
        return h.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : h.normalize();
    }

    private static void modify(ServerPlayer player, Holder<Attribute> attribute, ResourceLocation id, double amount,
                               AttributeModifier.Operation op) {
        AttributeInstance inst = player.getAttribute(attribute);
        if (inst == null) {
            return;
        }
        inst.removeModifier(id);
        inst.addTransientModifier(new AttributeModifier(id, amount, op));
    }

    private static void remove(ServerPlayer player, Holder<Attribute> attribute, ResourceLocation id) {
        AttributeInstance inst = player.getAttribute(attribute);
        if (inst != null) {
            inst.removeModifier(id);
        }
    }

    private static void send(ServerPlayer player, int kind, int layer, Vec3 dir) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new TraversePayloads.Event(player.getId(), kind, layer, (float) dir.x, (float) dir.z));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    private TraverseService() {
    }
}
