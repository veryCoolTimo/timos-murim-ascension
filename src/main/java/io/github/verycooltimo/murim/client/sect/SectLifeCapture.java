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

    private static final boolean BENCH = "1".equals(System.getenv("MURIM_SECT_BENCH"));

    /**
     * Сцена: время суток, точка камеры и куда смотреть (площадка, смещение, высота над площадкой), игрок в строю.
     */
    private record Scene(String name, int time, String zone, double du, double dv, double dy,
                         String lookZone, double lu, double lv, double ly, boolean join, String act) {
        Scene(String name, int time, String zone, double du, double dv, double dy,
              String lookZone, double lu, double lv, double ly, boolean join) {
            this(name, time, zone, du, dv, dy, lookZone, lu, lv, ly, join, "");
        }
    }

    /**
     * Где стоит игрок-новичок в сценах иерархии (С3, часть 2): {@code intercept} — перед главным залом, глава внутри,
     * охранник у входа; {@code guard} — у западного края казны, идёт внутрь (зажат «вперёд»).
     */
    private static final java.util.Map<String, double[]> ACT_START = java.util.Map.of(
            "intercept", new double[] {0.0D, -17.0D},
            "guard", new double[] {-15.0D, -4.0D},
            // Итог дня у наставника: игрок перед ним на площади (наставник вечером стоит у первого ряда).
            "summary", new double[] {0.6D, SectSchedule.FRONT_ROW + 0.6D});
    private static final java.util.Map<String, String> ACT_ZONE = java.util.Map.of("intercept", "main_hall", "guard", "treasury",
            "summary", "training");
    /**
     * Сцены членов секты (автор 05.10) — свои кадры и шаг: {кадров, тиков между кадрами}. Действия {@code porters},
     * {@code wound}, {@code shift} ставят людей после расстановки, чтобы дело случилось в кадре.
     */
    private static final java.util.Map<String, int[]> PACE = java.util.Map.of(
            "council", new int[] {60, 6}, "report", new int[] {60, 4}, "treasury", new int[] {80, 6},
            "healer", new int[] {80, 5}, "shift", new int[] {80, 5}, "summary", new int[] {130, 4});
    /** Игрок сам в кадре (выживание, без невидимости): перехват, охрана, итог дня. */
    private static final java.util.Set<String> PLAYER_ACTS = java.util.Set.of("intercept", "guard", "summary");

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
            new Scene("join", 23500, "training", 12.5D, 9.5D, 2.5D, "training", 4.5D, 3.0D, 1.0D, true),
            // С3, часть 2: слуги днём — носильщики со ступеней в кладовую, управляющий, метельщик на площади.
            new Scene("workers", 4300, "training", 21.0D, -3.0D, 4.0D, "treasury", -6.0D, -2.0D, 0.5D, false),
            // Носильщики принимают груз у ворот (наверху лестницы) и несут в кладовую.
            new Scene("porters", 4350, "training", 18.0D, -12.0D, 3.0D, "sect_gate", 12.0D, -2.0D, 1.0D, false),
            // Завтрак: повар раздаёт, водонос носит воду на кухню.
            new Scene("kitchen", 1300, "dining", -9.0D, -9.0D, 5.0D, "dining", 2.0D, 0.0D, 0.0D, false),
            // Новичок заговаривает с главой — охранник у главного зала перехватывает (окно разговора с ним).
            new Scene("intercept", 4400, "main_hall", 7.0D, -20.0D, 3.0D, "main_hall", -2.0D, -11.0D, 1.0D, false, "intercept"),
            // Новичок идёт в казну — охранник встаёт на пути и отталкивает.
            new Scene("guard", 4500, "treasury", -8.0D, -16.0D, 4.0D, "treasury", -9.0D, -4.0D, 0.5D, false, "guard"),
            // Члены секты (автор 05.10). Совет старейшин в главном зале: глава во главе, старейшины двумя рядами.
            new Scene("council", 6300, "main_hall", 0.5D, -4.5D, 6.0D, "main_hall", 0.0D, 2.5D, 0.0D, false),
            // Доклад: Ун Ам в завтрак перед главой в главном зале — поклон, говорит; глава отвечает.
            new Scene("report", 1300, "main_hall", 4.5D, 3.2D, 1.8D, "main_hall", 0.0D, 3.1D, 1.0D, false, "report"),
            // Казна: Хён Ён у стола с книгой учёта, носильщики приносят груз — принимает, они кланяются.
            new Scene("treasury", 3300, "treasury", 12.0D, 2.5D, 2.2D, "treasury", 7.0D, -2.5D, 0.6D, false, "porters"),
            // Лекарь: после поединка раненый сидит у края площадки, Ун Гак на колене лечит.
            new Scene("healer", 7600, "sparring", -8.0D, -14.5D, 3.0D, "sparring", -7.5D, -8.5D, 0.3D, false, "wound"),
            // Смена охраны у ворот секты в сумерках: сменщик приходит, оба кланяются, сменённый уходит.
            new Scene("shift", 13040, "sect_gate", 8.5D, 4.5D, 2.0D, "sect_gate", 4.5D, 1.0D, 1.0D, false, "shift"),
            // Итог дня: вечером игрок говорит с наставником — журнал дня и «зачем всё это».
            new Scene("summary", 12300, "training", 3.0D, 14.0D, 2.0D, "training", 0.0D, 10.0D, 1.0D, false, "summary"));

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
        if (BENCH) {
            // Server cost bench (sect/SectBench): the client draws nothing, llvmpipe does not compete for CPU.
            mc.noRender = true;
        }
        if (!setup) {
            setup = true;
            if (BENCH) {
                mc.options.framerateLimit().set(10);
            }
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
        // Сцена «охрана»: новичок упрямо идёт в казну (настоящее нажатие «вперёд»), охрана отталкивает. Камера —
        // за спиной игрока: ходьба клиента работает, только когда камера — сам игрок.
        boolean walk = "guard".equals(s.act());
        if (walk) {
            if (mc.getCameraEntity() != mc.player) {
                mc.setCameraEntity(mc.player);
            }
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        } else if ("summary".equals(s.act())) {
            // Разговор снимает камера разговора (DialogueCamera): до него — глаза игрока.
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            if (!DialogueCamera.active() && mc.getCameraEntity() != mc.player) {
                mc.setCameraEntity(mc.player);
            }
            if (shooting && wait == 260 && mc.screen instanceof DialogueScreen ds) {
                // «Зачем всё это?» — наставник объясняет, зачем строй и распорядок.
                ds.press(0);
            }
        } else {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            follow(mc);
        }
        mc.options.keyUp.setDown(walk && shooting);
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
                if (!s.act().isEmpty() && !"intercept".equals(s.act()) && !"guard".equals(s.act())) {
                    server.execute(() -> act(server, s));
                }
                if ("intercept".equals(s.act())) {
                    // Новичок заговаривает с главой: тот же путь, что ПКМ (DialogueService.open).
                    server.execute(() -> {
                        ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
                        for (io.github.verycooltimo.murim.entity.SectDisciple d : server.overworld().getEntitiesOfClass(
                                io.github.verycooltimo.murim.entity.SectDisciple.class, sp.getBoundingBox().inflate(32.0D),
                                d -> "hyun_jong".equals(d.memberKey()))) {
                            io.github.verycooltimo.murim.sect.DialogueService.open(sp, d);
                            MurimMod.LOGGER.info("Стенд секты: новичок заговорил с главой в {} блоках", String.format("%.1f", d.distanceTo(sp)));
                        }
                    });
                }
                server.execute(() -> {
                    for (String line : SectLife.report(server.overworld())) {
                        MurimMod.LOGGER.info("Стенд секты [{}]: {}", s.name(), line);
                    }
                });
            }
            return;
        }
        int[] pace = PACE.get(s.name());
        if (wait % (pace != null ? pace[1] : env("MURIM_SECT_EVERY", 4)) == 0) {
            String name = String.format("murim_sect_%s_%03d.png", s.name(), frame++);
            Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), m -> {
            });
            if (frame >= (pace != null ? pace[0] : env("MURIM_SECT_FRAMES", 24))) {
                if (mc.screen instanceof DialogueScreen) {
                    mc.screen.onClose();
                }
                next(server);
            }
        }
    }

    /**
     * Сервер, начало съёмки: люди ставятся так, чтобы дело случилось в кадре (члены секты, автор 05.10) —
     * носильщики с грузом в нескольких шагах от кладовой, раненый у края площадки, сменщик на подходе к посту,
     * игрок заговаривает с наставником.
     */
    private static void act(IntegratedServer server, Scene s) {
        ServerLevel level = server.overworld();
        SectLayout layout = SectLife.layout(level);
        ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
        if (layout == null) {
            return;
        }
        List<io.github.verycooltimo.murim.entity.SectDisciple> people = level.getEntitiesOfClass(
                io.github.verycooltimo.murim.entity.SectDisciple.class, sp.getBoundingBox().inflate(96.0D), d -> !d.memberKey().isEmpty());
        switch (s.act()) {
            case "porters" -> {
                for (io.github.verycooltimo.murim.entity.SectDisciple d : people) {
                    if (d.role() != io.github.verycooltimo.murim.sect.SectRole.PORTER) {
                        continue;
                    }
                    SectLife.Resolved r = SectLife.resolve(d);
                    SectSchedule.Task t = r == null ? null : r.task();
                    if (t == null || !t.route()) {
                        continue;
                    }
                    Vec3 from = layout.at(t.toZone(), t.toU(), t.toV());
                    Vec3 dir = from.subtract(r.spot()).multiply(1.0D, 0.0D, 1.0D).normalize();
                    double back = "porter_oh".equals(d.memberKey()) ? 10.0D : 6.0D;
                    Vec3 at = SectLife.stand(level, r.spot().add(dir.scale(back)));
                    d.moveTo(at.x, at.y, at.z, d.getYRot(), 0.0F);
                    d.setCarrying(true);
                    d.hold("porter_oh".equals(d.memberKey()) ? Items.BARREL : Items.HAY_BLOCK);
                    d.workPose("carry");
                    MurimMod.LOGGER.info("Стенд секты: {} с грузом в {} блоках от кладовой", d.memberKey(), back);
                }
            }
            case "wound" -> {
                Vec3 ring = layout.at("sparring", -6.5D, -8.5D);
                io.github.verycooltimo.murim.entity.SectDisciple best = null;
                for (io.github.verycooltimo.murim.entity.SectDisciple d : people) {
                    if (d.member().map(m -> m.generation() == 3 && m.disciple()).orElse(false)
                            && (best == null || d.distanceToSqr(ring) < best.distanceToSqr(ring))) {
                        best = d;
                    }
                }
                if (best != null) {
                    best.stopBout();
                    Vec3 at = SectLife.stand(level, ring);
                    best.moveTo(at.x, at.y, at.z, 120.0F, 0.0F);
                    best.setHealth(best.getMaxHealth() * 0.45F);
                    best.setWounded(true);
                    MurimMod.LOGGER.info("Стенд секты: {} ранен в поединке", best.memberKey());
                }
            }
            case "shift" -> {
                // Смена могла пройти до съёмки (сменщик вдали «переходит» к посту без ходьбы): ставим её заново —
                // сменяемый на посту, сменщик в нескольких шагах.
                for (io.github.verycooltimo.murim.entity.SectDisciple d : people) {
                    if ("baek_mu".equals(d.memberKey())) {
                        d.relieve(Long.MIN_VALUE);
                        SectSchedule.Task post = SectSchedule.task(d.member().orElseThrow(), SectSchedule.Period.TRAINING, 0);
                        Vec3 at = SectLife.stand(level, layout.at(post.zone(), post.du(), post.dv()));
                        d.moveTo(at.x, at.y, at.z, layout.yaw(post.faceU(), post.faceV()), 0.0F);
                        d.getNavigation().stop();
                    }
                }
                for (io.github.verycooltimo.murim.entity.SectDisciple d : people) {
                    if ("baek_un".equals(d.memberKey())) {
                        d.setHandedOver(Long.MIN_VALUE);
                        d.setKeepAwake(true);
                        Vec3 at = SectLife.stand(level, layout.at("sect_gate", 6.0D, 9.0D));
                        d.moveTo(at.x, at.y, at.z, 180.0F, 0.0F);
                        MurimMod.LOGGER.info("Стенд секты: сменщик {} идёт к посту", d.memberKey());
                    }
                }
            }
            case "report" -> {
                // Ун Ам заново подходит к главе: доклад начинается с поклона в кадре.
                for (io.github.verycooltimo.murim.entity.SectDisciple d : people) {
                    if ("un_am".equals(d.memberKey())) {
                        Vec3 at = SectLife.stand(level, layout.at("main_hall", 4.0D, -3.0D));
                        d.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
                        d.getNavigation().stop();
                    }
                }
            }
            case "summary" -> {
                for (io.github.verycooltimo.murim.entity.SectDisciple d : people) {
                    if ("un_geom".equals(d.memberKey())) {
                        io.github.verycooltimo.murim.sect.DialogueService.open(sp, d);
                        MurimMod.LOGGER.info("Стенд секты: итог дня — разговор с наставником, журнал {}",
                                io.github.verycooltimo.murim.sect.SectAttendance.report(sp));
                    }
                }
            }
            default -> {
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
        // Время заморожено во всех сценах: смену охраны стенд ставит заново в начале съёмки (act shift).
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        for (ServerLevel l : server.getAllLevels()) {
            l.setDayTime(day + s.time());
        }
        Vec3 cam = layout.at(s.zone(), s.du(), s.dv()).add(0.0D, s.dy(), 0.0D);
        Vec3 look = layout.at(s.lookZone(), s.lu(), s.lv()).add(0.0D, s.ly(), 0.0D);
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        // Свой ученик: глава не выходит встречать чужака к воротам. В сценах иерархии — свежий новичок.
        if ("summary".equals(s.act())) {
            // Итог дня (автор 05.10: «строй ✓, подъём ✓, занятие пропущено ✗»): журнал дня задан заранее.
            p.setData(ModAttachments.SECT, io.github.verycooltimo.murim.sect.SectState.NONE.joined().with("met_mentor"));
            long sectDay = SectSchedule.day(day + s.time());
            p.setData(ModAttachments.SECT_ATTENDANCE, new io.github.verycooltimo.murim.sect.SectAttendance.Log(
                    new io.github.verycooltimo.murim.sect.SectAttendance.Day(sectDay, java.util.Set.of("formation", "lesson"),
                            java.util.Set.of("formation", "climb", "meal")),
                    io.github.verycooltimo.murim.sect.SectAttendance.Day.NONE, 2, 0, false, Long.MIN_VALUE));
        } else if (PLAYER_ACTS.contains(s.act())) {
            p.setData(ModAttachments.SECT, io.github.verycooltimo.murim.sect.SectState.NONE.joined());
        } else if (!p.getData(ModAttachments.SECT).member()) {
            p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).joined());
        }
        // Овцы и свиньи на площадке не мешают кадру.
        for (net.minecraft.world.entity.animal.Animal a : level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
                new net.minecraft.world.phys.AABB(cam, cam).inflate(48.0D))) {
            a.discard();
        }
        // Сначала игрок у площадки: чанки грузятся, люди секты просыпаются.
        p.setGameMode(GameType.CREATIVE);
        if (PLAYER_ACTS.contains(s.act())) {
            // Новичок пешком, видимый, в режиме выживания: охрана творческих не трогает.
            double[] st = ACT_START.get(s.act());
            Vec3 at = SectLife.stand(level, layout.at(ACT_ZONE.get(s.act()), st[0], st[1]));
            Vec3 to = "summary".equals(s.act()) ? layout.at("training", 0.0D, SectSchedule.FRONT_ROW + 3.0D) : layout.at(ACT_ZONE.get(s.act()), 0.0D, 0.0D);
            p.setGameMode(GameType.SURVIVAL);
            p.removeEffect(MobEffects.INVISIBILITY);
            p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20 * 600, 4, false, false, false));
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            p.teleportTo(level, at.x, at.y, at.z, SectLayout.yawOf(to.x - at.x, to.z - at.z), 0.0F);
        } else if (s.join()) {
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
        if ("guard".equals(s.act()) || "summary".equals(s.act())) {
            MurimMod.LOGGER.info("Стенд секты: сцена {} — камера за игроком", s.name());
            return;
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
