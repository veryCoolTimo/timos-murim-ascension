package io.github.verycooltimo.murim.client.boss;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.TrainingDummy;
import io.github.verycooltimo.murim.entity.boss.BossMove;
import io.github.verycooltimo.murim.entity.boss.BossRules;
import io.github.verycooltimo.murim.entity.boss.FortressMaster;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.world.fortress.FortressBuilder;
import io.github.verycooltimo.murim.world.fortress.FortressCommand;
import io.github.verycooltimo.murim.world.fortress.FortressData;
import io.github.verycooltimo.murim.world.fortress.Fortresses;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Dev-only съёмка босса M4 ({@code -Pmurim.technique=boss}, docs/design/26-boss.md): крепость
 * строится рядом с игроком, игрок входит в ворота, хозяин встаёт; дальше бой по сценарию —
 * фаза 1 (связка, прыжок, таран), рык на 60 %, фаза 2 (раскол, прыжок), «кровь кипит» на 25 %,
 * вихрь и колено, смерть, сокровищница.
 *
 * <p>Ракурсы ({@code -Pmurim.camera}): {@code back} — игрок дерётся сам (третье лицо сзади,
 * подходит в окна, отходит из меток); {@code wide} — хозяин бьёт манекен, камера — с угла
 * частокола сверху: видны метки на земле целиком. Кадры — {@code screenshots/murim_boss_<stage>_<n>.png}.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class DevBossHandler {

    private static final boolean ENABLED = Boolean.getBoolean("murim.capture")
            && "boss".equals(System.getProperty("murim.capture.technique"));
    private static final boolean WIDE = "wide".equalsIgnoreCase(System.getProperty("murim.capture.camera", "back"));

    private static final String[] STAGES = {"approach", "overview", "fight", "vault"};

    private static boolean setup;
    private static int stage = -1;
    private static int wait;
    private static int stable;
    private static int lastSections = -1;
    private static int act;
    private static int frame;
    private static int segment;
    private static volatile boolean ready;
    private static volatile int bossId = -1;
    private static volatile FortressData.Entry entry;

    private DevBossHandler() {
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (mc.player == null || mc.level == null || server == null) {
            return;
        }
        if (!setup) {
            setup = true;
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            mc.options.renderDistance().set(8);
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            server.execute(() -> build(server));
            return;
        }
        if (stage < 0 || stage >= STAGES.length || !ready) {
            return;
        }
        String name = STAGES[stage];
        mc.getToasts().clear();
        wait++;
        int sections = mc.levelRenderer.countRenderedSections();
        stable = sections == lastSections && mc.levelRenderer.hasRenderedAllSections() ? stable + 1 : 0;
        lastSections = sections;
        if (act == 0 && !((wait >= 60 && stable >= 25) || wait >= 600)) {
            return;
        }
        act++;
        Entity boss = mc.level.getEntity(bossId);
        switch (name) {
            case "approach" -> {
                mc.options.keyUp.setDown(act <= 45);
                if (act % 6 == 0) {
                    grab(mc, name);
                }
                if (act >= 110) {
                    mc.options.keyUp.setDown(false);
                    next(server);
                }
            }
            case "overview" -> {
                if (act == 2 || act == 20) {
                    grab(mc, name);
                }
                if (act >= 21) {
                    next(server);
                }
            }
            case "fight" -> {
                if (!WIDE && boss != null && boss.isAlive()) {
                    fighter(mc, (FortressMaster) boss);
                } else if (WIDE && boss != null) {
                    lookAt(mc, boss.getX(), boss.getY() + 1.0D, boss.getZ(), 0.0F);
                }
                if (act % 3 == 0) {
                    grab(mc, name);
                }
                if (act % 10 == 0) {
                    server.execute(() -> advance(server));
                }
                if (boss == null && act > 60 || act > 2400) {
                    release(mc);
                    next(server);
                }
            }
            case "vault" -> {
                release(mc);
                if (act == 15 || act == 30) {
                    grab(mc, name);
                }
                if (act == 32) {
                    server.execute(() -> openVault(server));
                }
                if (act == 45) {
                    grab(mc, name);
                }
                if (act == 47) {
                    server.execute(() -> server.getPlayerList().getPlayers().forEach(ServerPlayer::closeContainer));
                    MurimMod.LOGGER.info("Boss capture: done");
                    stage++;
                    // Съёмка закончена: клиент выходит сам, capture.sh не ждёт таймаута.
                    mc.stop();
                }
            }
            default -> {
            }
        }
    }

    /**
     * Игрок-бот на ракурсе {@code back}: смотрит на хозяина; в замахе приёма по площади уходит
     * вбок, в окне отката подходит и бьёт мечом, иначе держит 4–5 блоков.
     */
    private static void fighter(Minecraft mc, FortressMaster b) {
        lookAt(mc, b.getX(), b.getY() + 1.6D, b.getZ(), 6.0F);
        // Камера сзади игрока: хозяин сбоку от оси взгляда, игрок его не закрывает.
        mc.player.setYRot(mc.player.getYRot() - 22.0F);
        mc.player.setYHeadRot(mc.player.getYRot());
        double d = mc.player.distanceTo(b);
        int s = b.state();
        BossMove m = b.move();
        boolean area = (s == FortressMaster.WINDUP || s == FortressMaster.STRIKE)
                && (m == BossMove.SPLIT || m == BossMove.RAM || m == BossMove.WHIRL || m == BossMove.POUNCE || m == BossMove.CHAIN);
        boolean window = s == FortressMaster.RECOVER || s == FortressMaster.STAGGER || s == FortressMaster.DOWNED || s == FortressMaster.STUN;
        release(mc);
        if (area) {
            mc.options.keyLeft.setDown(true);
            mc.options.keyDown.setDown(m == BossMove.CHAIN || d < 3.0D);
        } else if (window) {
            mc.options.keyUp.setDown(d > 2.4D);
            if (d < 3.2D && mc.player.getAttackStrengthScale(0.0F) >= 0.9F) {
                mc.gameMode.attack(mc.player, b);
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        } else if (s == FortressMaster.WINDUP && m == BossMove.ROAR) {
            mc.options.keyDown.setDown(true);
        } else {
            mc.options.keyUp.setDown(d > 5.5D);
            mc.options.keyDown.setDown(d < 3.5D);
        }
    }

    private static void release(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
    }

    private static void lookAt(Minecraft mc, double x, double y, double z, float extraPitch) {
        double dx = x - mc.player.getX();
        double dy = y - mc.player.getEyeY();
        double dz = z - mc.player.getZ();
        float yaw = BossRules.yawTo(dx, dz);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))) + extraPitch;
        mc.player.setYRot(yaw);
        mc.player.setYHeadRot(yaw);
        mc.player.setXRot(pitch);
    }

    private static void grab(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, String.format("murim_boss_%s_%03d.png", name, frame++), mc.getMainRenderTarget(), m -> {
        });
    }

    // ------------------------------------------------------------------ сервер

    /** Построить крепость в 40 блоках от игрока и посадить хозяина. */
    private static void build(IntegratedServer server) {
        ServerLevel level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.setWeatherParameters(12000, 0, false, false);
        boolean night = "night".equalsIgnoreCase(System.getProperty("murim.capture.time", "day"));
        level.setDayTime(night ? 18000L : 6000L);
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        // Проверка генерации: настоящая крепость находится через /locate (ответ — в лог).
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack()
                .withPosition(p.position()).withSuppressedOutput().withCallback((ok, result) ->
                        MurimMod.LOGGER.info("Boss capture: locate fortress -> success={} distance={}", ok, result)),
                "locate structure murim:green_forest_fortress");
        int x = p.getBlockX() + 40, z = p.getBlockZ();
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // Убрать прошлую крепость и хозяина стенда (мир devtest переиспользуется).
        for (FortressMaster old : level.getEntitiesOfClass(FortressMaster.class, new AABB(p.blockPosition()).inflate(120.0D))) {
            old.discard();
        }
        FortressData.Entry e = FortressCommand.placeAt(level, new BlockPos(x, y, z), Rotation.CLOCKWISE_90, 77L);
        entry = e;
        FortressMaster m = Fortresses.spawnMaster(level, e);
        bossId = m == null ? -1 : m.getId();
        MurimMod.LOGGER.info("Boss capture: fortress at {} {} {}, master {}", x, y, z, m == null ? "-" : m.getName().getString());
        next(server);
    }

    private static void next(IntegratedServer server) {
        stage++;
        wait = 0;
        stable = 0;
        lastSections = -1;
        act = 0;
        ready = false;
        if (stage >= STAGES.length) {
            return;
        }
        String name = STAGES[stage];
        server.execute(() -> {
            ServerLevel level = server.overworld();
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            FortressData.Entry e = entry;
            switch (name) {
                case "approach" -> {
                    p.setGameMode(WIDE ? GameType.CREATIVE : GameType.SURVIVAL);
                    gear(p);
                    FortressCommand.gate(level, p, e, -3);
                }
                case "overview" -> {
                    p.setGameMode(GameType.CREATIVE);
                    BlockPos corner = FortressBuilder.world(e.yard.getX(), e.yard.getY(), e.yard.getZ(), e.rotation, -16, -18);
                    float yaw = BossRules.yawTo(e.yard.getX() - corner.getX(), e.yard.getZ() - corner.getZ());
                    p.teleportTo(level, corner.getX() + 0.5D, e.yard.getY() + 14.0D, corner.getZ() + 0.5D, yaw, 32.0F);
                    p.getAbilities().flying = true;
                    p.onUpdateAbilities();
                }
                case "fight" -> {
                    FortressMaster m = (FortressMaster) level.getEntity(bossId);
                    if (m == null) {
                        break;
                    }
                    segment = 0;
                    if (WIDE) {
                        TrainingDummy d = ModEntities.DUMMY.get().create(level);
                        d.moveTo(e.yard.getX() + 0.5D, e.yard.getY(), e.yard.getZ() + 0.5D, 0.0F, 0.0F);
                        level.addFreshEntity(d);
                        m.teleportTo(e.yard.getX() + 0.5D, e.yard.getY(), e.yard.getZ() + 3.5D);
                        m.startFight(d);
                        BlockPos corner = FortressBuilder.world(e.yard.getX(), e.yard.getY(), e.yard.getZ(), e.rotation, 15, -15);
                        float yaw = BossRules.yawTo(e.yard.getX() - corner.getX(), e.yard.getZ() - corner.getZ());
                        p.teleportTo(level, corner.getX() + 0.5D, e.yard.getY() + 9.0D, corner.getZ() + 0.5D, yaw, 30.0F);
                        p.getAbilities().flying = true;
                        p.onUpdateAbilities();
                    } else {
                        p.setGameMode(GameType.SURVIVAL);
                        gear(p);
                        BlockPos in = FortressBuilder.world(e.yard.getX(), e.yard.getY(), e.yard.getZ(), e.rotation, 0, -5);
                        p.teleportTo(level, in.getX() + 0.5D, e.yard.getY(), in.getZ() + 0.5D, 0.0F, 0.0F);
                        m.teleportTo(e.yard.getX() + 0.5D, e.yard.getY(), e.yard.getZ() + 0.5D);
                        m.startFight(p);
                    }
                    m.script(BossMove.CHAIN, BossMove.POUNCE, BossMove.RAM);
                }
                case "vault" -> {
                    p.setGameMode(GameType.CREATIVE);
                    p.getAbilities().flying = false;
                    p.onUpdateAbilities();
                    BlockPos stand = FortressBuilder.world(e.yard.getX(), e.yard.getY() + 1, e.yard.getZ(), e.rotation, 0, 22);
                    BlockPos chest = e.vaultChest();
                    float yaw = BossRules.yawTo(chest.getX() - stand.getX(), chest.getZ() - stand.getZ());
                    p.teleportTo(level, stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D, yaw, 20.0F);
                }
                default -> {
                }
            }
            ready = true;
        });
    }

    /** Сценарий боя по сегментам: фаза 1 → 60 % (рык) → фаза 2 → 25 % (кровь кипит) → вихрь → смерть. */
    private static void advance(IntegratedServer server) {
        ServerLevel level = server.overworld();
        if (!(level.getEntity(bossId) instanceof FortressMaster m) || !m.isAlive() || !m.scriptDone()) {
            return;
        }
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        p.setHealth(p.getMaxHealth());
        switch (segment++) {
            case 0 -> {
                m.setHealth(m.getMaxHealth() * 0.58F);
                m.script(BossMove.SPLIT, BossMove.POUNCE);
            }
            case 1 -> {
                m.setHealth(m.getMaxHealth() * 0.22F);
                m.script(BossMove.WHIRL);
            }
            case 2 -> m.hurt(level.damageSources().playerAttack(p), 1000.0F);
            default -> {
            }
        }
        MurimMod.LOGGER.info("Boss capture: segment {} (HP {})", segment, m.getHealth());
    }

    private static void gear(ServerPlayer p) {
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        // Стенд: игрок не должен умереть посреди съёмки — сопротивление, а не бессмертие (удар виден).
        p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20 * 300, 3, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 300, 2, false, false));
        p.setHealth(p.getMaxHealth());
        // Ранг игрока «по замыслу» — третьесортный: давление и чтение ранга как в игре.
        p.setData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE,
                p.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE).withRank(io.github.verycooltimo.murim.cultivation.Realm.THIRD));
        io.github.verycooltimo.murim.profile.ProfileNetwork.sync(p);
    }

    private static void openVault(IntegratedServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        if (entry != null && level.getBlockEntity(entry.vaultChest()) instanceof ChestBlockEntity chest) {
            p.openMenu(chest);
        } else {
            MurimMod.LOGGER.warn("Boss capture: no vault chest");
        }
    }
}
