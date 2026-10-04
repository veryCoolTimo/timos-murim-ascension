package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.SectLayout;
import io.github.verycooltimo.murim.sect.SectLife;
import io.github.verycooltimo.murim.sect.SectSchedule;
import io.github.verycooltimo.murim.sect.SectService;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Стенд «жизнь секты» ({@code ./capture.sh … sectlife}, только {@code -Dmurim.capture=true}): по очереди сцены
 * распорядка на горе Хуа — рассветный строй, поединки пар, ужин, вечер, игрок в строю. Для каждой сцены
 * время мира ставится и замораживается, люди секты переходят на места ({@link SectLife#settleAll}), камера —
 * невидимая стойка над площадкой; кадры {@code screenshots/murim_sect_<сцена>_<n>.png}.
 *
 * <p>Сцены — {@code MURIM_SECT_SCENES} (через запятую, по умолчанию все); {@code MURIM_SECT_FRAMES} — кадров на сцену
 * (по умолчанию 24), {@code MURIM_SECT_EVERY} — тиков между кадрами (по умолчанию 4). Игрок — творческий и
 * невидимый у камеры (иначе NPC вдали от игроков засыпают); в сцене {@code join} он стоит в строю и бьёт формы в
 * такт настоящим нажатием атаки.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class SectLifeCapture {

    private static final boolean ENABLED = Boolean.getBoolean("murim.capture")
            && "sectlife".equals(System.getProperty("murim.capture.technique"));

    static final String CAMERA = "murim_sect_cam";

    /**
     * Сцена: время суток, точка камеры и куда смотреть (площадка, смещение, высота над площадкой), игрок в строю.
     */
    private record Scene(String name, int time, String zone, double du, double dv, double dy,
                         String lookZone, double lu, double lv, double ly, boolean join) {
    }

    private static final List<Scene> ALL = List.of(
            // Рассвет: строй лицом к помосту, камера над помостом смотрит на ряды; наставник ходит между ними.
            new Scene("dawn", 23300, "training", 3.0D, SectSchedule.FRONT_ROW + 7.0D, 3.5D, "training", 0.0D, 2.0D, 0.5D, false),
            // Тот же строй сбоку и выше — видно ряды целиком.
            new Scene("dawn_side", 23400, "training", 13.0D, 3.0D, 3.0D, "training", 0.0D, 2.5D, 0.5D, false),
            // Днём: пары поединков на песке.
            new Scene("spar", 4200, "sparring", 0.0D, -14.0D, 6.0D, "sparring", 0.0D, 0.0D, 0.0D, false),
            // Днём: столбы и хозяйство в лагере.
            new Scene("poles", 4300, "poles", 7.0D, 6.0D, 3.5D, "poles", 0.0D, 0.0D, 0.5D, false),
            // Ужин: столовая и стол во дворе лагеря.
            new Scene("meal", 9300, "camp", 14.0D, 20.0D, 6.0D, "camp", 5.0D, 10.0D, 0.0D, false),
            // Вечер: кружки в лагере, медитация в роще.
            new Scene("evening", 11300, "camp", -12.0D, -2.0D, 6.0D, "camp", 0.0D, 0.0D, 0.0D, false),
            // Игрок в строю: с края второго ряда бьёт формы вместе со всеми.
            new Scene("join", 23500, "training", 12.5D, 9.5D, 2.5D, "training", 4.5D, 3.0D, 1.0D, true));

    private static boolean setup;
    private static List<Scene> scenes;
    private static int scene = -1;
    private static int wait;
    private static int frame;
    private static int stable;
    private static int lastSections = -1;
    private static boolean shooting;

    private SectLifeCapture() {
    }

    private static int env(String name, int fallback) {
        try {
            String raw = System.getenv(name);
            return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
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
            mc.options.hideGui = !"1".equals(System.getenv("MURIM_CAPTURE_GUI"));
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.renderDistance().set(env("MURIM_PANO_RD", 10));
            mc.options.broadcastOptions();
            mc.options.fov().set(Math.max(30, Math.min(110, env("MURIM_CAPTURE_FOV", 70))));
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            String raw = System.getenv("MURIM_SECT_SCENES");
            scenes = new ArrayList<>();
            for (Scene s : ALL) {
                if (raw == null || raw.isBlank() || List.of(raw.split(",")).contains(s.name())) {
                    scenes.add(s);
                }
            }
            server.execute(() -> {
                ServerLevel level = server.overworld();
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
                level.setWeatherParameters(12000, 0, false, false);
            });
            next(server);
            return;
        }
        if (scene < 0 || scene >= scenes.size()) {
            return;
        }
        Scene s = scenes.get(scene);
        follow(mc);
        if (s.join() && mc.player.level().getGameTime() % SectSchedule.BEAT == SectSchedule.BEAT_STRIKE - 2) {
            // Настоящее нажатие атаки: форма основы идёт тем же путём, что у игрока (клиент → сервер → строй).
            KeyMapping.click(mc.options.keyAttack.getKey());
        }
        wait++;
        if (!shooting && (wait == 40 || wait == 140)) {
            // Дважды (TickTask сервер выполняет сразу, задержку считаем здесь): первый раз встают недостающие
            // (их чанки только загрузились), второй — все на местах.
            server.execute(() -> MurimMod.LOGGER.info("Стенд секты: сцена {} — {} людей на местах", s.name(),
                    SectLife.settleAll(server.overworld())));
        }
        if (!shooting) {
            int sections = mc.levelRenderer.countRenderedSections();
            stable = sections == lastSections && mc.levelRenderer.hasRenderedAllSections() ? stable + 1 : 0;
            lastSections = sections;
            boolean ready = wait >= env("MURIM_SECT_WARM", 240) && stable >= 40;
            if (ready || wait >= 1600) {
                shooting = true;
                wait = 0;
                MurimMod.LOGGER.info("Стенд секты: сцена {} — съёмка", s.name());
                server.execute(() -> {
                    for (String line : SectLife.report(server.overworld())) {
                        MurimMod.LOGGER.info("Стенд секты [{}]: {}", s.name(), line);
                    }
                });
            }
            return;
        }
        if (wait % env("MURIM_SECT_EVERY", 4) == 0) {
            String name = String.format("murim_sect_%s_%03d.png", s.name(), frame++);
            Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), m -> {
            });
            if (frame >= env("MURIM_SECT_FRAMES", 24)) {
                next(server);
            }
        }
    }

    /** Камера — стойка сцены, как только она пришла на клиент. */
    private static void follow(Minecraft mc) {
        Entity newest = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ArmorStand && e.isAlive() && e.getCustomName() != null && CAMERA.equals(e.getCustomName().getString())
                    && (newest == null || e.getId() > newest.getId())) {
                newest = e;
            }
        }
        if (newest != null && mc.getCameraEntity() != newest) {
            mc.setCameraEntity(newest);
        }
    }

    private static void next(IntegratedServer server) {
        scene++;
        wait = 0;
        frame = 0;
        stable = 0;
        lastSections = -1;
        shooting = false;
        if (scene >= scenes.size()) {
            MurimMod.LOGGER.info("Стенд секты: все сцены сняты ({})", scenes.size());
            return;
        }
        Scene s = scenes.get(scene);
        server.execute(() -> stage(server, s));
    }

    /** Сервер: время, люди на местах, игрок, стойка-камера. */
    private static void stage(IntegratedServer server, Scene s) {
        ServerLevel level = server.overworld();
        MountHuaSite site = MountHuaSites.get(server);
        if (site == null) {
            MurimMod.LOGGER.warn("Стенд секты: горы нет");
            return;
        }
        SectLayout layout = SectLayout.hua(site);
        long day = Math.floorDiv(level.getDayTime(), 24000L) * 24000L + 24000L;
        for (ServerLevel l : server.getAllLevels()) {
            l.setDayTime(day + s.time());
        }
        Vec3 cam = layout.at(s.zone(), s.du(), s.dv()).add(0.0D, s.dy(), 0.0D);
        Vec3 look = layout.at(s.lookZone(), s.lu(), s.lv()).add(0.0D, s.ly(), 0.0D);
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        // Свой ученик: глава не выходит встречать чужака к воротам.
        if (!p.getData(ModAttachments.SECT).member()) {
            p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).joined());
        }
        // Овцы и свиньи на площадке не мешают кадру.
        for (net.minecraft.world.entity.animal.Animal a : level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
                new net.minecraft.world.phys.AABB(cam, cam).inflate(48.0D))) {
            a.discard();
        }
        // Сначала игрок у площадки: чанки грузятся, люди секты просыпаются.
        p.setGameMode(GameType.CREATIVE);
        if (s.join()) {
            double[] free = SectSchedule.slot(1, SectSchedule.COLUMNS - 1);
            Vec3 at = SectLife.stand(level, layout.at("training", free[0], free[1]));
            p.teleportTo(level, at.x, at.y, at.z, layout.yaw(0.0D, 1.0D), 0.0F);
            p.removeEffect(MobEffects.INVISIBILITY);
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY).with(SectService.SIX, TechniqueProgress.learned(4, 6)));
            MasteryService.sync(p);
            p.setData(ModAttachments.LOADOUT, p.getData(ModAttachments.LOADOUT).withFoundation(Optional.of(SectService.SIX)));
            io.github.verycooltimo.murim.mastery.LoadoutService.sync(p);
        } else {
            p.teleportTo(level, cam.x, cam.y + 1.0D, cam.z, 0.0F, 0.0F);
            p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 20 * 600, 0, false, false, false));
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
        }
        for (ArmorStand old : level.getEntitiesOfClass(ArmorStand.class, p.getBoundingBox().inflate(256.0D),
                a -> a.getCustomName() != null && CAMERA.equals(a.getCustomName().getString()))) {
            old.discard();
        }
        ArmorStand stand = new ArmorStand(level, cam.x, cam.y - 1.62D, cam.z);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setCustomName(Component.literal(CAMERA));
        Vec3 d = look.subtract(cam);
        float yaw = SectLayout.yawOf(d.x, d.z);
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        stand.setYRot(yaw);
        stand.setYHeadRot(yaw);
        stand.setXRot(pitch);
        level.addFreshEntity(stand);
        // Люди секты — на места этой части суток (на следующем тике: чанки у площадки уже грузятся).
        MurimMod.LOGGER.info("Стенд секты: сцена {}, камера {} → {}", s.name(), cam, look);
    }
}
