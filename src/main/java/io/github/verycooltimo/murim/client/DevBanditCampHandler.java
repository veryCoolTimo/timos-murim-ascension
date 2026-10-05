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

    /**
     * Сцены по порядку; MURIM_CAMP_STAGES — свой список через запятую (tents, brawl, trade — полировка 04.10:
     * шатры вблизи, бой «по двое», покупка у торговца). После последней сцены клиент закрывается.
     */
    private static final String[] STAGES = System.getenv().getOrDefault("MURIM_CAMP_STAGES",
            "approach,overview,overview2,qi,chief,loot").split(",");

    /** Точки съёмки сцены tents: x, y, z, yaw, pitch. */
    private static volatile List<double[]> views = List.of();
    private static volatile int peddlerId = -1;
    private static volatile BlockPos tradeVillage;
    private static int tradeBase;
    /** Сцена brawl: камера отъезжает на 18 блоков назад-вверх, чтобы в кадре был весь двор и кольцо ждущих. */
    private static boolean wideCamera;

    /** API: reference/neoforge-src/net/neoforged/neoforge/client/event/CalculateDetachedCameraDistanceEvent.java */
    @SubscribeEvent
    static void onCameraDistance(net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent event) {
        if (ENABLED && wideCamera) {
            event.setDistance(18.0F);
        }
    }

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
        if (stage >= STAGES.length) {
            if (stage == STAGES.length) {
                stage++;
                MurimMod.LOGGER.info("Bandit camp capture: all stages done");
                mc.stop();
            }
            return;
        }
        if (stage < 0 || !targetReady) {
            return;
        }
        String name = STAGES[stage];
        mc.getToasts().clear();
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
                if (act == 87) {
                    server.execute(() -> server.getPlayerList().getPlayers().forEach(ServerPlayer::closeContainer));
                }
                // Обрывки: три страницы одной книги в руке → название над хотбаром → ПКМ — сшиты в рваный манускрипт.
                if (act == 90) {
                    server.execute(() -> {
                        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        p.setGameMode(GameType.SURVIVAL);
                        ItemStack pages = new ItemStack(io.github.verycooltimo.murim.registry.ModItems.MANUAL_PAGE.get(), 3);
                        pages.set(io.github.verycooltimo.murim.registry.ModDataComponents.TECHNIQUE.get(),
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "dark_fragrance_step"));
                        p.getInventory().setItem(1, pages);
                    });
                }
                if (act == 94) {
                    mc.player.getInventory().selected = 1;
                }
                if (act == 100) {
                    grab(mc, "page");
                }
                if (act == 104) {
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                }
                if (act == 110) {
                    grab(mc, "page");
                    mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                }
                if (act == 118) {
                    grab(mc, "page");
                    mc.setScreen(null);
                }
                if (act == 120) {
                    MurimMod.LOGGER.info("Bandit camp capture: loot done");
                    next(server);
                }
            }
            case "tents" -> {
                // Шатры, навес, костёр и ворота вблизи: по точке на 30 тиков, кадр на 24-м.
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                mc.options.hideGui = true;
                int i = (act - 1) / 30;
                int t = (act - 1) % 30;
                if (i >= views.size()) {
                    mc.options.hideGui = false;
                    next(server);
                } else if (t == 0) {
                    double[] v = views.get(i);
                    server.execute(() -> {
                        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        p.teleportTo(server.overworld(), v[0], v[1], v[2], (float) v[3], (float) v[4]);
                    });
                } else if (t == 24) {
                    grab(mc, name);
                }
            }
            case "brawl" -> {
                // Бой без заморозки: тревога поднимает лагерь, вблизи дерутся двое, остальные ждут кольцом.
                // Сзади и сверху на центр банды: в кадре и двое в бою, и кольцо ждущих.
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                wideCamera = act < 300;
                Entity t = mc.level.getEntity(targetId);
                Entity near = null;
                double best = 9.0D;
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e instanceof Bandit b && b.isAlive() && b.isAggressive() && mc.player.distanceToSqr(e) < best) {
                        best = mc.player.distanceToSqr(e);
                        near = e;
                    }
                }
                double gx = 0.0D, gz = 0.0D;
                int n = 0;
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e instanceof Bandit b && b.isAlive() && mc.player.distanceToSqr(e) < 20.0D * 20.0D) {
                        gx += e.getX();
                        gz += e.getZ();
                        n++;
                    }
                }
                if (n > 0) {
                    double dx = gx / n - mc.player.getX(), dz = gz / n - mc.player.getZ();
                    float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    mc.player.setYRot(yaw);
                    mc.player.setYHeadRot(yaw);
                    mc.player.setXRot(60.0F);
                } else if (t != null) {
                    face(mc, t);
                }
                if (near != null && best < 3.2D * 3.2D && mc.player.getAttackStrengthScale(0.0F) >= 0.95F && act % 14 == 0) {
                    mc.gameMode.attack(mc.player, near);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
                if (act % 4 == 0) {
                    grab(mc, name);
                }
                if (act % 20 == 0) {
                    server.execute(() -> logFight(server));
                }
                if (act >= 300) {
                    next(server);
                }
            }
            case "trade" -> {
                // Покупка у торговца: дождаться его в деревне, открыть его экран, выбрать пилюлю, забрать за серебро.
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                mc.options.hideGui = false;
                if (peddlerId < 0) {
                    if (act % 20 == 0) {
                        boolean force = act >= 600;
                        int waited = act;
                        server.execute(() -> findPeddler(server, waited, force));
                    }
                    tradeBase = act;
                    return;
                }
                int k = act - tradeBase;
                Entity ped = mc.level.getEntity(peddlerId);
                if (ped != null && k < 30) {
                    face(mc, ped);
                    mc.player.setXRot(8.0F);
                }
                if (k == 20) {
                    grab(mc, name);
                }
                if (k == 30 && ped != null) {
                    mc.gameMode.interact(mc.player, ped, InteractionHand.MAIN_HAND);
                }
                if (k == 50) {
                    grab(mc, name);
                }
                if (k == 55 && mc.player.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu menu) {
                    int pick = -1;
                    for (int q = 0; q < menu.getOffers().size(); q++) {
                        if (menu.getOffers().get(q).getResult().is(io.github.verycooltimo.murim.registry.ModItems.PILL_SNOW_PLUM.get())) {
                            pick = q;
                        }
                    }
                    MurimMod.LOGGER.info("Bandit camp capture: trade offers {}, pill at {}", menu.getOffers().size(), pick);
                    if (pick >= 0) {
                        menu.setSelectionHint(pick);
                        menu.tryMoveItems(pick);
                        mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSelectTradePacket(pick));
                    }
                }
                if (k == 65) {
                    grab(mc, name);
                }
                if (k == 70 && mc.player.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu menu) {
                    mc.gameMode.handleInventoryMouseClick(menu.containerId, 2, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, mc.player);
                }
                if (k == 80) {
                    grab(mc, name);
                }
                if (k == 85) {
                    mc.player.closeContainer();
                }
                if (k == 95) {
                    mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                }
                if (k == 105) {
                    grab(mc, name);
                    server.execute(() -> {
                        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        MurimMod.LOGGER.info("Bandit camp capture: after trade silver {}, pills {}",
                                p.getInventory().countItem(io.github.verycooltimo.murim.registry.ModItems.SILVER_TAEL.get()),
                                p.getInventory().countItem(io.github.verycooltimo.murim.registry.ModItems.PILL_SNOW_PLUM.get()));
                    });
                }
                if (k == 110) {
                    mc.setScreen(null);
                    next(server);
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
                    // Стенд: остальная банда замирает, чтобы поединок читался (в игре по тревоге бегут все).
                    for (Bandit other : level.getEntitiesOfClass(Bandit.class, new AABB(campCentre).inflate(40.0D), Entity::isAlive)) {
                        other.setNoAi(other != target);
                        if (other != target) {
                            other.setTarget(null);
                        }
                    }
                    // Поединок на тропе у ворот: игрок снаружи, бандит выходит из ворот — в кадре
                    // частокол, перекладина и двор за ним, а не стенка шатра вплотную.
                    double px = cx + g[0] * (plan.radius() + 5.0D), pz = cz + g[1] * (plan.radius() + 5.0D);
                    double tx = cx + g[0] * (plan.radius() - 0.5D), tz = cz + g[1] * (plan.radius() - 0.5D);
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
                case "tents" -> {
                    p.setGameMode(GameType.SPECTATOR);
                    List<double[]> v = new java.util.ArrayList<>();
                    for (CampLayout.Spot s : plan.spots()) {
                        if (s.kind() != CampLayout.Kind.TENT && s.kind() != CampLayout.Kind.CHIEF_TENT
                                && s.kind() != CampLayout.Kind.LEAN_TO && s.kind() != CampLayout.Kind.FIRE) {
                            continue;
                        }
                        int[] fw = CampLayout.step(s.facing());
                        int[] rt = CampLayout.step(s.facing() + 1);
                        double sx = cx + s.dx() + 0.5D, sz = cz + s.dz() + 0.5D;
                        if (s.kind() == CampLayout.Kind.FIRE) {
                            // Вертел вдоль x: смотреть с юга, наискось сверху.
                            v.add(view(level, sx + 1.0D, sz + 6.0D, sx, sz, 26.0F));
                            continue;
                        }
                        double d = s.kind() == CampLayout.Kind.CHIEF_TENT ? 8.0D : 6.5D;
                        // Две точки с чистым видом: из-под углов к входу (анфас с наклоном, скат и терраса).
                        double front = Math.atan2(fw[1], fw[0]);
                        int taken = 0;
                        for (double off : new double[] {0.6D, -0.6D, 1.1D, -1.1D, 0.25D, -0.25D, 1.6D, -1.6D}) {
                            double a = front + off;
                            double vx = sx + Math.cos(a) * d, vz = sz + Math.sin(a) * d;
                            double[] cand = view(level, vx, vz, sx, sz, 16.0F);
                            if (clear(level, cand, sx, cy(level, sx, sz), sz)) {
                                v.add(cand);
                                if (++taken == 2) {
                                    break;
                                }
                            }
                        }
                    }
                    // Ворота: снаружи по тропе и изнутри.
                    double gx = cx + g[0] * plan.radius(), gz = cz + g[1] * plan.radius();
                    v.add(view(level, cx + g[0] * (plan.radius() + 8.0D), cz + g[1] * (plan.radius() + 8.0D), gx, gz, 10.0F));
                    v.add(view(level, cx + g[0] * (plan.radius() - 7.0D), cz + g[1] * (plan.radius() - 7.0D), gx, gz, 8.0F));
                    views = List.copyOf(v);
                    MurimMod.LOGGER.info("Bandit camp capture: {} tent views", v.size());
                }
                case "brawl" -> {
                    p.setGameMode(GameType.SURVIVAL);
                    p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
                    p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20 * 60, 3, false, false));
                    p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 60, 3, false, false));
                    p.setHealth(p.getMaxHealth());
                    // Ночные мобы в лагере мешают кадру: убрать их (спавн мобов на стенде выключен).
                    level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, new AABB(campCentre).inflate(48.0D),
                            m -> !(m instanceof Bandit)).forEach(Entity::discard);
                    List<Bandit> band = level.getEntitiesOfClass(Bandit.class, new AABB(campCentre).inflate(40.0D), Entity::isAlive);
                    for (Bandit b : band) {
                        b.setNoAi(false);
                        b.setTarget(null);
                    }
                    // Игрок у костра со стороны ворот; первым его замечает ближайший — тревога поднимает всех.
                    // Стенд: игрока не отбрасывает, чтобы бой шёл во дворе, а не у частокола.
                    java.util.Objects.requireNonNull(p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE)).setBaseValue(1.0D);
                    double px = cx + g[0] * 2.5D, pz = cz + g[1] * 2.5D;
                    teleport(level, p, px, pz, cx, cz, 10.0F, 0);
                    Bandit first = band.stream().filter(b -> !b.isChief())
                            .min(java.util.Comparator.comparingDouble(b -> b.distanceToSqr(p))).orElse(null);
                    if (first != null) {
                        first.setTarget(p);
                        targetId = first.getId();
                    }
                    MurimMod.LOGGER.info("Bandit camp capture: brawl with {} bandits", band.size());
                }
                case "trade" -> {
                    p.setGameMode(GameType.SURVIVAL);
                    p.getInventory().clearContent();
                    p.getInventory().add(new ItemStack(io.github.verycooltimo.murim.registry.ModItems.SILVER_TAEL.get(), 20));
                    BlockPos village = nearestVillage(level, campCentre);
                    if (village == null) {
                        MurimMod.LOGGER.warn("Bandit camp capture: no village");
                        break;
                    }
                    teleport(level, p, village.getX() + 0.5D, village.getZ() + 0.5D, village.getX() + 4.5D, village.getZ() + 0.5D, 8.0F, 0);
                    // Торговец приходит сам, когда игрок в деревне (PeddlerSpawns, раз в 5 с): клиентский тик
                    // опрашивает раз в секунду. Не самоперезаказ TickTask: такая очередь не даёт серверу
                    // догружать чанки между тиками, и центр деревни так и не прогружался (стенд 04.10).
                    tradeVillage = village;
                    peddlerId = -1;
                }
                default -> {
                }
            }
            targetReady = true;
        });
    }

    private static double[] view(ServerLevel level, double x, double z, double lookX, double lookZ, float pitch) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        level.getChunk(bx >> 4, bz >> 4);
        double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) + 0.2D;
        float yaw = (float) Math.toDegrees(Math.atan2(-(lookX - x), lookZ - z));
        return new double[] {x, y, z, yaw, pitch};
    }

    private static double cy(ServerLevel level, double x, double z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z)) - 0.5D;
    }

    /** От глаз до цели нет блоков на первых трёх четвертях пути. */
    private static boolean clear(ServerLevel level, double[] v, double tx, double ty, double tz) {
        net.minecraft.world.phys.Vec3 eye = new net.minecraft.world.phys.Vec3(v[0], v[1] + 1.62D, v[2]);
        net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(tx, ty, tz);
        net.minecraft.world.phys.Vec3 end = eye.add(to.subtract(eye).scale(0.75D));
        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.VISUAL,
                net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS
                && level.getBlockState(BlockPos.containing(eye)).isAir();
    }

    /** Журнал боя: сколько дерутся вблизи, стреляют, ждут (проверка «по двое» по логу). */
    private static void logFight(IntegratedServer server) {
        ServerLevel level = server.overworld();
        List<Bandit> band = level.getEntitiesOfClass(Bandit.class, new AABB(campCentre).inflate(48.0D), Entity::isAlive);
        long melee = band.stream().filter(b -> !(b instanceof io.github.verycooltimo.murim.entity.BanditArcher) && b.getTarget() != null
                && io.github.verycooltimo.murim.world.camp.CampFight.mayFight(b)).count();
        long shoot = band.stream().filter(b -> b instanceof io.github.verycooltimo.murim.entity.BanditArcher && b.getTarget() != null
                && io.github.verycooltimo.murim.world.camp.CampFight.mayFight(b)).count();
        long wait = band.stream().filter(io.github.verycooltimo.murim.world.camp.CampFight::waiting).count();
        boolean chief = band.stream().anyMatch(b -> b.isChief() && b.getTarget() != null && io.github.verycooltimo.murim.world.camp.CampFight.mayFight(b));
        MurimMod.LOGGER.info("Bandit camp capture: fight alive {} melee {} shooting {} waiting {} chief engaged {}",
                band.size(), melee, shoot, wait, chief);
    }

    private static BlockPos nearestVillage(ServerLevel level, BlockPos from) {
        var tag = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                .getTag(net.minecraft.tags.StructureTags.VILLAGE).orElse(null);
        if (tag == null) {
            return null;
        }
        var found = level.getChunkSource().getGenerator().findNearestMapStructure(level, tag, from, 100, false);
        return found == null ? null : found.getFirst();
    }

    /** Пришёл ли торговец (через {@code waited} тиков сцены); {@code force} — не пришёл за 30 с: поставить (стенд). */
    private static void findPeddler(IntegratedServer server, int waited, boolean force) {
        ServerLevel level = server.overworld();
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        List<io.github.verycooltimo.murim.trade.Peddler> found = level.getEntitiesOfClass(io.github.verycooltimo.murim.trade.Peddler.class,
                new AABB(p.blockPosition()).inflate(96.0D), Entity::isAlive);
        if (found.isEmpty() && !force) {
            return;
        }
        BlockPos village = tradeVillage;
        io.github.verycooltimo.murim.trade.Peddler ped = found.isEmpty() || village == null
                ? (village == null ? null : io.github.verycooltimo.murim.trade.PeddlerSpawns.arrive(level, village,
                        io.github.verycooltimo.murim.trade.Peddler.VILLAGE_STAY))
                : found.get(0);
        MurimMod.LOGGER.info("Bandit camp capture: peddler {} after {} ticks at {}", found.isEmpty() ? "placed" : "came", waited,
                ped == null ? "-" : ped.blockPosition().toShortString());
        if (ped == null) {
            return;
        }
        float yaw = ped.getYRot();
        double fx = ped.getX() - Math.sin(Math.toRadians(yaw)) * 2.6D, fz = ped.getZ() + Math.cos(Math.toRadians(yaw)) * 2.6D;
        teleport(level, p, fx, fz, ped.getX(), ped.getZ(), 8.0F, 0);
        peddlerId = ped.getId();
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
