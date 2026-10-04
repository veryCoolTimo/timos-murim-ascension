package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.BanditSwordsman;
import io.github.verycooltimo.murim.world.camp.BanditCampCommand;
import io.github.verycooltimo.murim.world.camp.CampBuilder;
import io.github.verycooltimo.murim.world.camp.CampLayout;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.List;

/**
 * Dev-only съёмка лагеря бандитов ({@code -Pmurim.technique=banditcamp}, docs/design/24-bandit-camp.md §6):
 * подход к воротам (банда появляется и стоит по постам), вид сверху с двух сторон, бой с бандитом
 * с ци, бой с главарём, разгром и сундуки с добычей. Кадры — {@code screenshots/murim_camp_<stage>_<n>.png}.
 * Мир — любой со сгенерированным лагерем; ищется ближайший к точке входа.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class DevBanditCampHandler {

    private static final boolean ENABLED = Boolean.getBoolean("murim.capture")
            && "banditcamp".equals(System.getProperty("murim.capture.technique"));

    private static final String[] STAGES = {"approach", "overview", "overview2", "qi", "chief", "loot"};

    private static boolean setup;
    private static int stage = -1;
    private static int wait;
    private static int stable;
    private static int lastSections = -1;
    private static int act;
    private static int frame;
    private static volatile boolean targetReady;
    private static volatile int targetId = -1;
    private static volatile BlockPos campCentre;
    private static volatile List<BlockPos> chests = List.of();

    private DevBanditCampHandler() {
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
            mc.options.renderDistance().set(10);
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.hideGui = false;
            server.execute(() -> {
                ServerLevel level = server.overworld();
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
                level.setWeatherParameters(12000, 0, false, false);
                boolean night = "night".equalsIgnoreCase(System.getProperty("murim.capture.time", "day"));
                level.setDayTime(night ? 18000L : Long.parseLong(System.getenv().getOrDefault("MURIM_CAMP_TIME", "12600")));
            });
            next(server);
            return;
        }
        if (stage < 0 || stage >= STAGES.length || !targetReady) {
            return;
        }
        String name = STAGES[stage];
        wait++;
        int sections = mc.levelRenderer.countRenderedSections();
        stable = sections == lastSections && mc.levelRenderer.hasRenderedAllSections() ? stable + 1 : 0;
        lastSections = sections;
        if (act == 0 && !((wait >= 80 && stable >= 30) || wait >= 900)) {
            return;
        }
        act++;
        switch (name) {
            case "approach" -> {
                mc.options.keyUp.setDown(act <= 150);
                if (act % 5 == 0) {
                    grab(mc, name);
                }
                if (act >= 155) {
                    mc.options.keyUp.setDown(false);
                    next(server);
                }
            }
            case "overview", "overview2" -> {
                mc.options.setCameraType(act < 41 ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
                mc.options.hideGui = act < 41;
                if (act == 3 || act == 20 || act == 40) {
                    grab(mc, name);
                }
                if (act >= 41) {
                    next(server);
                }
            }
            case "qi", "chief" -> {
                // Бой — от первого лица: в тесном лагере камера сзади упирается в стволы и шатры.
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                Entity t = mc.level.getEntity(targetId);
                if (t != null && t.isAlive()) {
                    face(mc, t);
                    double d = mc.player.distanceTo(t);
                    mc.options.keyUp.setDown(d > 2.6D);
                    if (d < 3.2D && mc.player.getAttackStrengthScale(0.0F) >= 0.95F && act % 2 == 0) {
                        mc.gameMode.attack(mc.player, t);
                        mc.player.swing(InteractionHand.MAIN_HAND);
                    }
                } else {
                    mc.options.keyUp.setDown(false);
                }
                if (act % 3 == 0) {
                    grab(mc, name);
                }
                if (act >= (name.equals("qi") ? 120 : 165)) {
                    mc.options.keyUp.setDown(false);
                    next(server);
                }
            }
            case "loot" -> {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                if (act == 1) {
                    server.execute(() -> rout(server));
                }
                if (act == 45) {
                    grab(mc, "cleared");
                    openChest(server, 0);
                }
                if (act == 60) {
                    grab(mc, name);
                }
                if (act == 62) {
                    server.execute(() -> server.getPlayerList().getPlayers().forEach(ServerPlayer::closeContainer));
                }
                if (act == 70) {
                    openChest(server, 1);
                }
                if (act == 85) {
                    grab(mc, name);
                }
                if (act == 90) {
                    MurimMod.LOGGER.info("Bandit camp capture: done");
                    stage++;
                }
            }
            default -> {
            }
        }
    }

    private static void face(Minecraft mc, Entity t) {
        double dx = t.getX() - mc.player.getX();
        double dz = t.getZ() - mc.player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.setYRot(yaw);
        mc.player.setYHeadRot(yaw);
        mc.player.setXRot(4.0F);
    }

    private static void grab(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, String.format("murim_camp_%s_%03d.png", name, frame++), mc.getMainRenderTarget(), m -> {
        });
    }

    private static void next(IntegratedServer server) {
        stage++;
        wait = 0;
        stable = 0;
        lastSections = -1;
        act = 0;
        targetReady = false;
        if (stage >= STAGES.length) {
            return;
        }
        String name = STAGES[stage];
        server.execute(() -> {
            ServerLevel level = server.overworld();
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            BanditCampCommand.Found f = BanditCampCommand.nearest(level, campCentre == null ? p.blockPosition() : campCentre);
            if (f == null) {
                MurimMod.LOGGER.warn("Bandit camp capture: no camp");
                return;
            }
            CampLayout plan = CampLayout.plan(f.piece().seed());
            int cx = f.piece().centreX();
            int cz = f.piece().centreZ();
            campCentre = new BlockPos(cx, f.piece().height(0), cz);
            double[] g = plan.gateDirection();
            switch (name) {
                case "approach" -> {
                    p.setGameMode(GameType.CREATIVE);
                    teleport(level, p, cx + g[0] * (plan.radius() + 11), cz + g[1] * (plan.radius() + 11), cx, cz, 6.0F, 0);
                }
                case "overview", "overview2" -> {
                    // Сверху наискось: с ворот и сбоку под 120°.
                    double a = Math.atan2(g[1], g[0]) + (name.equals("overview") ? 0.35D : 2.2D);
                    teleport(level, p, cx + Math.cos(a) * 24, cz + Math.sin(a) * 24, cx, cz, 38.0F, 16);
                    p.getAbilities().flying = true;
                    p.onUpdateAbilities();
                }
                case "qi", "chief" -> {
                    p.setGameMode(GameType.SURVIVAL);
                    p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
                    p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20 * 60, 3, false, false));
                    p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 60, 2, false, false));
                    p.setHealth(p.getMaxHealth());
                    Bandit target = null;
                    for (Bandit b : level.getEntitiesOfClass(Bandit.class, new AABB(campCentre).inflate(40.0D), Entity::isAlive)) {
                        boolean want = name.equals("chief") ? b.isChief() : b.isElite() && !b.isChief() && b instanceof BanditSwordsman;
                        if (want) {
                            target = b;
                        }
                    }
                    if (target == null) {
                        MurimMod.LOGGER.warn("Bandit camp capture: no target for {}", name);
                        targetReady = true;
                        return;
                    }
                    targetId = target.getId();
                    // Поединок на тропе у ворот: открытое место, камера за игроком смотрит в лагерь.
                    double px = cx + g[0] * (plan.radius() - 6.0D), pz = cz + g[1] * (plan.radius() - 6.0D);
                    double tx = cx + g[0] * (plan.radius() - 10.5D), tz = cz + g[1] * (plan.radius() - 10.5D);
                    int ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(tx), (int) Math.floor(tz));
                    target.teleportTo(tx, ty, tz);
                    teleport(level, p, px, pz, tx, tz, 8.0F, 0);
                    target.setTarget(p);
                    MurimMod.LOGGER.info("Bandit camp capture: {} -> {} hp {}", name, target.getName().getString(), target.getMaxHealth());
                }
                case "loot" -> {
                    p.setGameMode(GameType.CREATIVE);
                    List<BlockPos> found = new java.util.ArrayList<>();
                    for (BlockPos q : BlockPos.betweenClosed(campCentre.offset(-16, -8, -16), campCentre.offset(16, 10, 16))) {
                        if (level.getBlockEntity(q) instanceof ChestBlockEntity chest && chest.getLootTable() != null) {
                            if (chest.getLootTable().equals(CampBuilder.LOOT_CHIEF)) {
                                found.add(0, q.immutable());
                            } else if (chest.getLootTable().equals(CampBuilder.LOOT_CART)) {
                                found.add(q.immutable());
                            }
                        }
                    }
                    chests = List.copyOf(found);
                    MurimMod.LOGGER.info("Bandit camp capture: chests {}", chests);
                }
                default -> {
                }
            }
            targetReady = true;
        });
    }

    private static void teleport(ServerLevel level, ServerPlayer p, double x, double z, double lookX, double lookZ, float pitch, int lift) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        level.getChunk(bx >> 4, bz >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) + lift;
        float yaw = (float) Math.toDegrees(Math.atan2(-(lookX - x), lookZ - z));
        p.teleportTo(level, x, y, z, yaw, pitch);
    }

    /** Разгром: добить банду (сообщение о разгроме приходит игроку). */
    private static void rout(IntegratedServer server) {
        ServerLevel level = server.overworld();
        for (Bandit b : level.getEntitiesOfClass(Bandit.class, new AABB(campCentre).inflate(48.0D), Entity::isAlive)) {
            b.kill();
        }
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        teleport(level, p, campCentre.getX() + 2.5D, campCentre.getZ() + 2.5D, campCentre.getX(), campCentre.getZ(), 20.0F, 0);
    }

    /** Открыть сундук с добычей у игрока: 0 — главаря, 1 — телеги. */
    private static void openChest(IntegratedServer server, int index) {
        server.execute(() -> {
            if (chests.size() <= index) {
                MurimMod.LOGGER.warn("Bandit camp capture: no chest {}", index);
                return;
            }
            ServerLevel level = server.overworld();
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            BlockPos at = chests.get(index);
            teleport(level, p, at.getX() + 0.5D, at.getZ() + 2.5D, at.getX() + 0.5D, at.getZ() + 0.5D, 30.0F, 0);
            if (level.getBlockEntity(at) instanceof ChestBlockEntity chest) {
                p.openMenu(chest);
            }
        });
    }
}
