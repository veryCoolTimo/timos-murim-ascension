package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.dev.CaptureView;

import io.github.verycooltimo.murim.network.StartTechniquePayload;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Съёмка серии кадров техники для отладки визуала.
 *
 * <p>Зачем это существует: агент не видит экран, а снимать экран операционной системы по SSH
 * нельзя — блокирует TCC на macOS, а на Linux-сервере нет графической сессии. Но игра умеет
 * фотографировать саму себя, и её собственные скриншоты никаких системных разрешений не требуют.
 * Серия кадров с шагом в пару тиков даёт не только композицию, но и <b>тайминг</b>: по полосе
 * кадров видно и замах, и момент удара, и затухание. Обоснование — ADR-74.
 *
 * <p>Запуск полностью автономный: клиент стартует под виртуальным дисплеем с флагом
 * {@code -Dmurim.capture=true}, сам входит в мир, сам применяет технику и сам снимает кадры.
 * Человек в цикле не нужен.
 *
 * <p><b>Только для разработки.</b> Класс не делает ничего, пока не выставлено системное свойство.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class DevCaptureHandler {

    /** Включает автосъёмку: {@code ./gw runClient -Dmurim.capture=true}. */
    private static final String ENABLE_PROPERTY = "murim.capture";

    /**
     * Ракурс съёмки: {@code back} — игровой, из-за спины; {@code front} — контрольный.
     *
     * <p>Одного ракурса мало. Рубящий удар проходит перед корпусом, и при виде из-за спины
     * вторую половину дуги закрывает сам игрок — по таким кадрам нельзя отличить неверную
     * геометрию от честного перекрытия. Игровой ракурс отвечает на вопрос «что увидит игрок»,
     * контрольный — «правильна ли дуга вообще». Нужны оба.
     */
    private static final String CAMERA_PROPERTY = "murim.capture.camera";

    /** Сколько тиков стенд бежит цингуном (Шаг Молнии). */
    private static final int TRAVEL_CAPTURE_TICKS = 70;
    private static int travelTicks;
    private static int sneakTicks;
    /** Бег без прыжков (Тень с 03.10 — бегом): спринт вперёд весь отрезок. */
    private static int dashRunTicks;

    /** Какую технику снимать: {@code -Pmurim.technique=wedge_fan}. По умолчанию первая. */
    public static final String TECHNIQUE_PROPERTY = "murim.capture.technique";

    /**
     * За сколько тиков до запуска техники снимается ОПОРНЫЙ кадр.
     *
     * <p>Без него нельзя отделить эффект от сцены: маска эффекта получается вычитанием
     * опорного кадра из каждого последующего. Всё измерение построено на этом.
     */
    private static final int BASELINE_BEFORE_TICKS = 3;

    /** Файл телеметрии рядом с кадрами: по нему python считает проекции и метрики. */
    private static java.io.PrintWriter telemetry;

    /** Сколько тиков ждать после входа в мир, прежде чем применять технику. */
    private static final int WARMUP_TICKS = 60;

    /**
     * Шаг между кадрами. Один тик — обязательное значение для разбора тайминга: при шаге
     * в два тика начало движения выпадает из выборки, и анимация выглядит незапустившейся.
     * Ошибочный вывод на этой почве пойман 2026-08-10, см. docs/agent-log.md.
     */
    /** Кадр раз в N тиков (env MURIM_CAPTURE_EVERY): длинные техники снимаются реже, но целиком. */
    private static final int FRAME_INTERVAL_TICKS = envInt("MURIM_CAPTURE_EVERY", 1);

    /** Сколько кадров снять. 100 кадров по тику перекрывают технику в 92 тика. */
    private static final int FRAME_COUNT = envInt("MURIM_CAPTURE_FRAMES", 100);
    private static int footworkWaitTicks;
    private static int footworkAge = -1;
    private static net.minecraft.resources.ResourceLocation footworkSubject;
    private static int secondPressTicks;
    private static net.minecraft.resources.ResourceLocation secondPressId;

    private static net.minecraft.world.phys.Vec3 trackForward;
    private static net.minecraft.world.phys.Vec3 trackEye;
    private static net.minecraft.world.phys.Vec3 trackAim;
    private static double trackDistance = Double.NaN;
    private static int trackTicks;

    private static boolean plumRejected;
    private static int plumStillTicks;
    private static int plumAimTicks;
    private static int plumReaims;
    private static boolean plumCameraReady;
    private static double plumClearOrbit = Double.NaN;
    private static net.minecraft.world.entity.LivingEntity plumStagedTarget;
    private static net.minecraft.world.phys.Vec3 plumCasterMark;
    private static net.minecraft.world.phys.Vec3 plumTargetMark;

    /** Client-only staging gate; no technique or film clock advances until the rendered view passes.
     * API: supplied MC 1.21.1 Entity#position, Vec3#distanceToSqr, Minecraft#getCameraEntity.
     */
    private static boolean preparePlumCamera(Minecraft mc) {
        var target = plumTarget(mc);
        if (target == null || !(mc.getCameraEntity() instanceof net.minecraft.world.entity.decoration.ArmorStand)) {
            plumStillTicks = 0;
            plumAimTicks = 0;
            plumCameraReady = false;
            return false;
        }
        var casterPos = mc.player.position();
        var targetPos = target.position();
        if (target != plumStagedTarget || plumCasterMark == null
                || casterPos.distanceToSqr(plumCasterMark) > .0001
                || targetPos.distanceToSqr(plumTargetMark) > .0001) {
            plumStagedTarget = target;
            plumCasterMark = casterPos;
            plumTargetMark = targetPos;
            plumStillTicks = 0;
            plumAimTicks = 0;
            plumCameraReady = false;
        }
        if (++plumStillTicks < 5) return false;
        if (plumAimTicks == 0) {
            trackForward = null;
            plumClearOrbit = Double.NaN;
            trackPlum(mc);
            plumAimTicks = 1;
            MurimMod.LOGGER.info("CAPTURE-PLUM-CAMERA staged still_ticks={} reaims={}", plumStillTicks, plumReaims);
        } else if (plumAimTicks < 3) {
            plumAimTicks++;
        }
        return !plumRejected && plumCameraReady;
    }

    private static void rejectPlum() {
        plumRejected = true;
        framesLeft = 0;
        framePending = false;
        MurimMod.LOGGER.info("CAPTURE-PLUM-CAMERA rejected reason=subjects_out_of_view");
    }

    private static String viewMode() {
        return System.getProperty(CAMERA_PROPERTY, "back").toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean playerView() {
        return switch (viewMode()) {
            case "eye", "third", "victim", "victim_up" -> true;
            default -> false;
        };
    }

    private static net.minecraft.world.entity.LivingEntity viewTarget;
    private static boolean viewLogged;

    private static CaptureView.Point point(net.minecraft.world.phys.Vec3 v) {
        return new CaptureView.Point(v.x, v.y, v.z);
    }

    /** API: supplied MC Entity#getEyePosition(float), Minecraft#setCameraEntity,
     * Camera#setup/getMaxZoom (interpolation and eight wall probes), LevelRenderer#renderLevel
     * (first-person camera entity is excluded). No observer stand is needed. */
    private static void trackPlayerView(Minecraft mc) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY) || !playerView()) return;
        if (viewTarget == null || !viewTarget.isAlive()) {
            viewTarget = null;
            double nearest = Double.POSITIVE_INFINITY;
            for (var actor : mc.level.entitiesForRendering()) {
                if (!(actor instanceof net.minecraft.world.entity.LivingEntity living)
                        || actor == mc.player || !actor.isAlive()
                        || actor instanceof net.minecraft.world.entity.decoration.ArmorStand) continue;
                double distance = actor.position().distanceToSqr(mc.player.position());
                if (distance < nearest) { viewTarget = living; nearest = distance; }
            }
        }
        boolean victim = viewMode().startsWith("victim");
        var subject = victim && viewTarget != null ? viewTarget : mc.player;
        if (mc.getCameraEntity() != subject) mc.setCameraEntity(subject);
        mc.options.setCameraType("third".equals(viewMode()) ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
        mc.options.hideGui = !"eye".equals(viewMode());
        if ("third".equals(viewMode()) && viewTarget != null) {
            var pose = CaptureView.third(
                    point(mc.player.getEyePosition()), point(viewTarget.getEyePosition()), 4);
            mc.player.setYRot(pose.yaw());
            mc.player.setXRot(0);
        }
    }

    /** API: supplied NeoForge ViewportEvent.ComputeCameraAngles#setYaw/setPitch/setRoll,
     * ViewportEvent#getPartialTick. Camera#setup applies these before vanilla positioning. */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    static void onViewAngles(net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles event) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY) || !playerView()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || viewTarget == null || "eye".equals(viewMode())) return;
        float partial = (float) event.getPartialTick();
        var caster = point(mc.player.getEyePosition(partial));
        var target = point(viewTarget.getEyePosition(partial));
        var pose = "third".equals(viewMode())
                ? CaptureView.third(caster, target, 4)
                : CaptureView.victim(target, caster,
                        "victim_up".equals(viewMode())
                                ? Double.valueOf(System.getenv().getOrDefault("MURIM_CAPTURE_VICTIM_PITCH", "55")) : null);
        event.setYaw(pose.yaw());
        event.setPitch(pose.pitch());
        event.setRoll(0);
    }

    private static boolean plumFilm() { return !playerView() && "1".equals(System.getenv("MURIM_CAPTURE_PLUM_CAMERA")); }

    private static net.minecraft.world.entity.LivingEntity plumTarget(Minecraft mc) {
        net.minecraft.world.entity.LivingEntity target = null;
        double nearest = 400;
        for (var actor : mc.level.entitiesForRendering()) {
            if (!(actor instanceof net.minecraft.world.entity.Mob mob) || !mob.isAlive()
                    || actor instanceof net.minecraft.world.entity.decoration.ArmorStand) continue;
            double distance = actor.position().distanceToSqr(mc.player.position());
            if (distance < nearest) { target = mob; nearest = distance; }
        }
        return target;
    }
    /** Plum film uses the observer's eyes in BOTH shots; THIRD_PERSON_FRONT reverses the view.
     * API: read-only MC 1.21.1 Entity#position/moveTo/getEyeHeight/setYHeadRot,
     * LivingEntity#yHeadRotO, Options#setCameraType, ClientLevel#entitiesForRendering.
     */
    private static void trackPlum(Minecraft mc) {
        if (!plumFilm() || !(mc.getCameraEntity() instanceof net.minecraft.world.entity.decoration.ArmorStand stand)) return;
        var target = plumTarget(mc);
        if (target == null) return;
        var aim = mc.player.position().lerp(target.position(), .5).add(0, 1.1, 0);
        if (trackForward == null) {
            var delta = target.position().subtract(mc.player.position());
            trackForward = new net.minecraft.world.phys.Vec3(delta.x, 0, delta.z).normalize();
        }
        double orbit = Math.toRadians(Double.parseDouble(System.getenv().getOrDefault("MURIM_CAPTURE_CAM_ORBIT", "30")));
        double distance = Double.parseDouble(System.getenv().getOrDefault("MURIM_CAPTURE_CAM_DIST", "7"));
        double height = Double.parseDouble(System.getenv().getOrDefault("MURIM_CAPTURE_PLUM_HEIGHT", "1.4"));
        var right = new net.minecraft.world.phys.Vec3(-trackForward.z, 0, trackForward.x);
        // Front takes keep their distance and lens; find an unobstructed azimuth before staging.
        boolean front = "front".equalsIgnoreCase(System.getProperty(CAMERA_PROPERTY, "back"));
        if (front && Double.isNaN(plumClearOrbit)) {
            for (double turn : new double[]{0, -20, 20, -40, 40, -60, 60, -90, 90}) {
                double candidate = orbit + Math.toRadians(turn);
                var test = aim.add(right.scale(Math.cos(candidate) * distance))
                        .add(trackForward.scale(Math.sin(candidate) * distance)).add(0, height - 1.1, 0);
                if (plumClear(mc, test, mc.player) && plumClear(mc, test, target)) {
                    plumClearOrbit = candidate;
                    break;
                }
            }
        }
        if (front && !Double.isNaN(plumClearOrbit)) orbit = plumClearOrbit;
        double flower = io.github.verycooltimo.murim.client.vfx.RainVfx.filmFlowerFrame(mc.player.getId());
        // Every flower tier needs room for the centre fifteen blocks above the target.
        distance += flower * 22;
        aim = aim.add(0, flower * 7.5, 0);
        var eye = aim.add(right.scale(Math.cos(orbit) * distance)).add(trackForward.scale(Math.sin(orbit) * distance))
                .add(0, height - 1.1, 0);
        // Stage occlusion before frame zero; passing posts must not discard an active take.
        // Tick-only raycasts: never access block shapes in a render callback.
        if (frameIndex == 0 && (!plumClear(mc, eye, mc.player) || !plumClear(mc, eye, target))) {
            rejectPlum();
            MurimMod.LOGGER.info("CAPTURE-PLUM-CAMERA rejected reason=block_occlusion frame={}", frameIndex);
            return;
        }
        var delta = aim.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        stand.moveTo(eye.x, eye.y - stand.getEyeHeight(), eye.z, yaw, pitch);
        stand.setYHeadRot(yaw);
        stand.yHeadRotO = yaw;
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        mc.options.hideGui = true;
    }
    /** API verified in supplied MC 1.21.1 BlockGetter#clip, ClipContext constructor,
     * Block.OUTLINE, Fluid.NONE and HitResult#getType. Check head, torso and lower body.
     * OUTLINE catches visible posts and fences even when their collider is narrower.
     */
    private static boolean plumClear(Minecraft mc, net.minecraft.world.phys.Vec3 eye,
                                     net.minecraft.world.entity.Entity actor) {
        var box = actor.getBoundingBox();
        for (double fraction : new double[]{.2, .5, .85}) {
            var point = new net.minecraft.world.phys.Vec3((box.minX + box.maxX) * .5,
                    box.minY + (box.maxY - box.minY) * fraction, (box.minZ + box.maxZ) * .5);
            var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, point,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) return false;
        }
        return true;
    }
    /** Check all eight body corners against the actual rendered camera and projection.
     * API: MC Camera#getPosition/getXRot/getYRot; Entity#getBoundingBox; AABB fields;
     * NeoForge RenderLevelStageEvent#getCamera/getProjectionMatrix (supplied 1.21.1 sources).
     * Projection m00/m11 are the vanilla horizontal/vertical cotangents (GameRenderer).
     */
    private static boolean plumInView(RenderLevelStageEvent event, net.minecraft.world.entity.Entity actor) {
        if (actor == null) return false;
        var camera = event.getCamera();
        double yaw = Math.toRadians(camera.getYRot()), pitch = Math.toRadians(camera.getXRot());
        var forward = new net.minecraft.world.phys.Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        var right = new net.minecraft.world.phys.Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        var up = forward.cross(right);
        var box = actor.getBoundingBox();
        for (double x : new double[]{box.minX, box.maxX})
            for (double y : new double[]{box.minY, box.maxY})
                for (double z : new double[]{box.minZ, box.maxZ}) {
                    var delta = new net.minecraft.world.phys.Vec3(x, y, z).subtract(camera.getPosition());
                    double depth = delta.dot(forward);
                    if (depth <= 0 || Math.abs(delta.dot(right) * event.getProjectionMatrix().m00()) > depth * .95
                            || Math.abs(delta.dot(up) * event.getProjectionMatrix().m11()) > depth * .95) return false;
                }
        return true;
    }

    private static void trackFootwork(Minecraft mc) {
        boolean airScene = System.getenv().getOrDefault("MURIM_CAPTURE_SCENE", "").startsWith("dark_fragrance_")
                && !System.getenv().getOrDefault("MURIM_CAPTURE_SCENE", "").contains("rain");
        if ((!airScene && !"1".equals(System.getenv("MURIM_CAPTURE_TRACK")))
                || System.getenv().getOrDefault("MURIM_CAPTURE_FOOTWORK", "").isEmpty()
                || !(mc.getCameraEntity() instanceof net.minecraft.world.entity.decoration.ArmorStand stand)
                || mc.player == null || mc.level == null) return;
        if (trackForward == null) {
            double yaw = Math.toRadians(mc.player.getYRot());
            trackForward = new net.minecraft.world.phys.Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        }
        var subject = mc.player.position();
        if (airScene) {
            // Exact follow keeps the full ascent/descent in frame without smoothing lag.
            var aim = subject.add(0, 1, 0);
            var offset = footworkBack() ? trackForward.scale(-6).add(0, 1.5, 0)
                    : new net.minecraft.world.phys.Vec3(-trackForward.z * 6, 1.5, trackForward.x * 6);
            var eye = clearCameraEye(mc, aim, subject.add(offset));
            var direction = aim.subtract(eye);
            stand.setPos(eye.x, eye.y - stand.getEyeHeight(), eye.z);
            float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
            stand.setYRot(yaw); stand.setYHeadRot(yaw);
            stand.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z))));
            return;
        }
        var offset = "front".equalsIgnoreCase(System.getProperty(CAMERA_PROPERTY, "back")) ? trackForward.scale(6).add(0, 2.2, 0) : footworkBack() ? trackForward.scale(-6).add(0, 2.8, 0)
                : new net.minecraft.world.phys.Vec3(-trackForward.z * 6, 2.2, trackForward.x * 6);
        net.minecraft.world.entity.Mob nearest = null;
        for (var enemy : mc.level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                mc.player.getBoundingBox().inflate(32), e -> e.isAlive())) {
            if (nearest == null || enemy.position().distanceToSqr(subject) < nearest.position().distanceToSqr(subject)) nearest = enemy;
        }
        var aim = nearest == null ? subject.add(0, 1, 0) : subject.lerp(nearest.position(), .5).add(0, 1, 0);
        boolean pairCamera = "1".equals(System.getenv("MURIM_CAPTURE_PAIR_CAMERA"));
        var wanted = subject.add(offset);
        if (pairCamera) {
            // API: /tmp/mr-ref-1121/minecraft-src: Entity#getBoundingBox, AABB fields,
            // ClientLevel#entitiesForRendering, LivingEntity#getMainHandItem, Vec3 operations.
            var points = new java.util.ArrayList<net.minecraft.world.phys.Vec3>();
            var actors = new java.util.ArrayList<net.minecraft.world.entity.Entity>();
            actors.add(mc.player);
            for (var actor : mc.level.entitiesForRendering()) {
                var name = actor.getCustomName();
                if (actor.isAlive() && name != null && "murim_airhand_film".equals(name.getString())) actors.add(actor);
            }
            for (var actor : actors) {
                var box = actor.getBoundingBox();
                boolean lying = actor instanceof net.minecraft.world.entity.LivingEntity living
                        && io.github.verycooltimo.murim.client.vfx.BambooVfx.lying(living);
                for (double x : new double[]{box.minX, box.maxX})
                    for (double y : new double[]{box.minY, box.maxY})
                        for (double z : new double[]{box.minZ, box.maxZ}) {
                            // Same world Z rotation and lift as BambooVfx's grounded presentation.
                            points.add(lying ? new net.minecraft.world.phys.Vec3(actor.getX() - (y - actor.getY()),
                                    actor.getY() + .15 + x - actor.getX(), z)
                                    : new net.minecraft.world.phys.Vec3(x, y, z));
                        }
                if (actor instanceof net.minecraft.world.entity.LivingEntity living
                        && io.github.verycooltimo.murim.combat.QiSword.holdsSword(living.getMainHandItem())) {
                    var tip = io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.position(actor,
                            io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.BLADE_TIP);
                    var hand = io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.position(actor,
                            io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.RIGHT_FINGERS);
                    if (hand == null) hand = io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.position(actor,
                            io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.RIGHT_HAND);
                    if (tip != null && hand != null && tip.y > hand.y + .15) points.add(tip);
                }
            }
            // Round 3: preserve the readability pair shot. Only material stalk tops
            // enlarge the vertical fit; no speculative storm/radius bounding cube.
            String bambooScene = System.getenv("MURIM_CAPTURE_SCENE");
            boolean bambooGrove = "bamboo_rustling_grove".equals(bambooScene);
            boolean bambooWall = "bamboo_iron_wall".equals(bambooScene);
            if (bambooGrove || bambooWall) {
                double tier = Math.clamp((Integer.parseInt(System.getenv().getOrDefault("MURIM_CAPTURE_STAR", "12")) - 1) / 11.0, 0, 1);
                if (bambooGrove) {
                    var centre = nearest == null ? subject : nearest.position();
                    int count = 12 + (int) (tier * 4);
                    for (int i = 0; i < count; i++) {
                        double angle = (i + .5) * Math.PI * 2 / count;
                        double noise = Math.sin((i + 7) * 127.1 + 311.7) * 43758.5453;
                        double height = (2.6 + noise - Math.floor(noise)) * (1 + tier * 3);
                        double radius = 1.2 + tier * 2.3;
                        points.add(centre.add(Math.cos(angle) * radius, height, Math.sin(angle) * radius));
                    }
                } else {
                    points.add(subject.add(0, 6.2 * (.24 + .76 * tier), 0));
                }
            }
            double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
            for (var point : points) {
                minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x);
                minY = Math.min(minY, point.y); maxY = Math.max(maxY, point.y);
                minZ = Math.min(minZ, point.z); maxZ = Math.max(maxZ, point.z);
            }
            aim = new net.minecraft.world.phys.Vec3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
            trackAim = trackAim == null ? aim : trackAim.lerp(aim, .65);
            var side = footworkBack() ? trackForward.scale(-1)
                    : new net.minecraft.world.phys.Vec3(-trackForward.z, 0, trackForward.x);
            var screenRight = new net.minecraft.world.phys.Vec3(-side.z, 0, side.x);
            // API: MC Options#fov/OptionInstance#get; Window#getWidth/getHeight.
            double tanV = Math.tan(Math.toRadians(mc.options.fov().get()) / 2);
            double tanH = tanV * mc.getWindow().getWidth() / Math.max(1.0, mc.getWindow().getHeight());
            double fit = 2.6;
            for (var point : points) {
                var relative = point.subtract(trackAim);
                double depth = relative.dot(side) + .4;
                fit = Math.max(fit, depth + (Math.abs(relative.dot(screenRight)) + .4) / tanH);
                fit = Math.max(fit, depth + (Math.abs(relative.y) + .4) / tanV);
            }
            // Expand immediately to prevent clipping; gently close in after separation shrinks.
            trackDistance = Double.isNaN(trackDistance) ? fit : Math.max(fit, trackDistance + (fit - trackDistance) * .35);
            var eye = clearCameraEye(mc, trackAim, trackAim.add(side.scale(trackDistance)));
            var direction = trackAim.subtract(eye);
            stand.setPos(eye.x, eye.y - stand.getEyeHeight(), eye.z);
            float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
            stand.setYRot(yaw); stand.setYHeadRot(yaw);
            stand.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z))));
            return;
        }
        if ("high".equalsIgnoreCase(System.getenv("MURIM_CAPTURE_FOOTWORK_CAM"))) {
            // Duel setup replaces the warmup mobs. Re-anchor to the tagged actor rather than
            // retaining the warmup focus. Ten blocks gives the 40-degree lens room for every point.
            net.minecraft.world.entity.Mob duel = null;
            for (var enemy : mc.level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                    mc.player.getBoundingBox().inflate(32), e -> e.getTags().contains("murim_capture_duel"))) duel = enemy;
            if (duel != null || trackEye == null) {
                var centre = duel != null ? duel.position() : nearest == null ? subject.add(trackForward.scale(2)) : nearest.position();
                trackAim = centre.add(0, .6, 0);
                // MURIM_CAPTURE_HIGH_SCALE < 1 brings the eye closer along the same line (animation reviews).
                double k = Double.parseDouble(System.getenv().getOrDefault("MURIM_CAPTURE_HIGH_SCALE", "1"));
                trackEye = trackAim.add(trackForward.scale(-4.53 * k)).add(-trackForward.z * 4.53 * k, 7.68 * k, trackForward.x * 4.53 * k);
            }
            var direction = trackAim.subtract(trackEye);
            stand.setPos(trackEye.x, trackEye.y - stand.getEyeHeight(), trackEye.z);
            float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
            stand.setYRot(yaw); stand.setYHeadRot(yaw);
            stand.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z))));
            return;
        }
        var desired = clearCameraEye(mc, aim, wanted);
        boolean occluded = desired.distanceToSqr(wanted) > 1.0E-8;
        double distance = aim.distanceTo(desired);
        boolean snap = trackEye == null || Math.abs(distance - trackDistance) > 2;
        // Exponential follow: 1-exp(-.05/.2). Large obstruction changes snap immediately.
        trackEye = snap ? desired : trackEye.lerp(desired, .2211992169);
        // Smoothing can leave the eye behind a collider even when the wanted ray is clear.
        var safeEye = clearCameraEye(mc, aim, trackEye);
        occluded |= safeEye.distanceToSqr(trackEye) > 1.0E-8;
        trackEye = safeEye;
        trackDistance = distance;
        trackAim = snap || trackAim == null ? aim : trackAim.lerp(aim, .2211992169);
        if (trackTicks++ % 10 == 0) {
            MurimMod.LOGGER.info("CAPTURE-CAM {} {} {} {} occluded={}", trackTicks - 1,
                    trackEye.x, trackEye.y, trackEye.z, occluded ? 1 : 0);
        }
        var direction = trackAim.subtract(trackEye);
        stand.setPos(trackEye.x, trackEye.y - stand.getEyeHeight(), trackEye.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        stand.setYRot(yaw);
        stand.setYHeadRot(yaw);
        stand.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z))));
    }

    public static net.minecraft.world.phys.Vec3 clearCameraEye(Minecraft mc,
            net.minecraft.world.phys.Vec3 target, net.minecraft.world.phys.Vec3 eye) {
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/level/BlockGetter.java#clip
        // ClipContext COLLIDER/NONE checks block collision shapes only, never entities or fluids.
        var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(target, eye,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
        // API: minecraft-src/net/minecraft/world/phys/HitResult.java#getLocation; Vec3.java#lerp
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return eye;
        double distance = target.distanceTo(hit.getLocation());
        return distance <= .4 ? target : hit.getLocation().lerp(target, .4 / distance);
    }

    private static boolean footworkBack() {
        return !System.getenv().getOrDefault("MURIM_CAPTURE_FOOTWORK", "").isEmpty()
                && "back".equalsIgnoreCase(System.getProperty(CAMERA_PROPERTY, "back"));
    }

    private static int envInt(String name, int fallback) {
        String raw = System.getenv(name);
        try {
            return raw == null ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean armed = Boolean.getBoolean(ENABLE_PROPERTY);
    private static int warmup;
    private static int framesLeft;
    private static int tickCounter;
    private static int frameIndex;

    /**
     * Кадр запрошен тиком и будет снят ДВАЖДЫ за один проход рендера.
     *
     * <p>Эффект рисуется на стадии {@code AFTER_PARTICLES}. Снимок до неё и снимок после
     * отличаются ровно на эффект, причём поза тела в них совпадает побитово — это и есть
     * единственный способ отделить VFX от анимации. Два отдельных прогона так выровнять
     * нельзя: доля тика произвольна, а корпус в анимации поворачивается примерно на 21°
     * за тик, и промах в полтика оставляет остаток больше самого эффекта.
     */
    private static boolean framePending;

    /** Строка «прорыв начался» пишется один раз за съёмку. */
    private static boolean breakthroughLogged;

    /**
     * Начинает съёмку немедленно. Вызывается автозапуском и может быть вызвана вручную,
     * если понадобится снять что-то другое.
     */
    /**
     * Сбрасывает состояние съёмки при выходе из мира. Без этого повторный вход досчитывает
     * прогрев и отправляет пакет техники без действия игрока, а нумерация кадров продолжается
     * с прежнего места и затирает уже снятое.
     */
    public static void reset() {
        if (telemetry != null) {
            telemetry.close();
            telemetry = null;
        }
        viewTarget = null;
        viewLogged = false;
        armed = Boolean.getBoolean(ENABLE_PROPERTY);
        plumRejected = false;
        plumStillTicks = 0;
        plumAimTicks = 0;
        plumReaims = 0;
        plumCameraReady = false;
        plumStagedTarget = null;
        plumCasterMark = null;
        plumTargetMark = null;
        trackForward = null;
        trackEye = null;
        trackAim = null;
        trackDistance = Double.NaN;
        trackTicks = 0;
        warmup = 0;
        footworkWaitTicks = 0;
        footworkAge = -1;
        footworkSubject = null;
        framesLeft = 0;
        // Иначе запрошенный, но не снятый кадр «выстрелит» при следующем входе в мир
        // и запишет пару снимков с телеметрией, когда съёмка не запущена.
        framePending = false;
        breakthroughLogged = false;
        meditationTicks = 0;
        benchCapture=false;benchCaptureTicks=0;
        meditationIdle = 0;
        uiTicks = 0;
        tickCounter = 0;
        frameIndex = 0;
    }

    public static void startCapture() {
        framesLeft = FRAME_COUNT;
        tickCounter = 0;
        frameIndex = 0;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (io.github.verycooltimo.murim.client.sect.SmokeJourneyCapture.ownsWorld()) return;
        Minecraft minecraft = Minecraft.getInstance();
        // Panorama of Mount Hua has its own driver (DevPanoramaHandler).
        if ("mounthua".equals(System.getProperty(TECHNIQUE_PROPERTY))
                || "placecheck".equals(System.getProperty(TECHNIQUE_PROPERTY))
                // The abandoned archive walk-through has its own driver (client/library/LibraryCapture).
                || "library".equals(System.getProperty(TECHNIQUE_PROPERTY))
                || "sectlife".equals(System.getProperty(TECHNIQUE_PROPERTY))
                || "sectposes".equals(System.getProperty(TECHNIQUE_PROPERTY))
                || "training".equals(System.getProperty(TECHNIQUE_PROPERTY))
                // Sect seals: cold iron and the vault trial (client/seal/SealCapture).
                || "seals".equals(System.getProperty(TECHNIQUE_PROPERTY))
                || "banditcamp".equals(System.getProperty(TECHNIQUE_PROPERTY))
                || "boss".equals(System.getProperty(TECHNIQUE_PROPERTY))
                // Location round trip and biome: client/DevLocationHandler.
                || "location".equals(System.getProperty(TECHNIQUE_PROPERTY))
                // HUD and screen gallery: client/HudGalleryCapture.
                || HudGalleryCapture.SUBJECT.equals(System.getProperty(TECHNIQUE_PROPERTY))
                // Combat HUD rules (air swing, hits, silence): client/CombatHudCapture.
                || CombatHudCapture.SUBJECT.equals(System.getProperty(TECHNIQUE_PROPERTY))
                // The senior spar stand (scripted player, own frames): client/SparFilmCapture.
                || HeadIntroFilmCapture.SUBJECT.equals(System.getProperty(TECHNIQUE_PROPERTY))
                || "bladeexamfilm".equals(System.getProperty(TECHNIQUE_PROPERTY))
                || SparFilmCapture.SUBJECT.equals(System.getProperty(TECHNIQUE_PROPERTY))) {
            return;
        }
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        if (armed) {
            armed = false;
            warmup = WARMUP_TICKS;
            // Анимация тела видна только от третьего лица: в первом лице снимать нечего.
            boolean front = "front".equalsIgnoreCase(System.getProperty(CAMERA_PROPERTY, "back"));
            boolean side = "side".equalsIgnoreCase(System.getProperty(CAMERA_PROPERTY, "back"))
                    || "fp".equalsIgnoreCase(System.getProperty(CAMERA_PROPERTY, "back"))
                    || footworkBack();
            minecraft.options.setCameraType(side ? CameraType.FIRST_PERSON
                    : front ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK);
            // Сбоку снимаем глазами чужой сущности в первом лице: без скрытого интерфейса
            // в кадр лезут рука игрока и прицел.
            // От первого лица с MURIM_CAPTURE_HAND (empty — ци-меч, sword — меч для сравнения) снимается рука,
            // поэтому интерфейс (а с ним и рука) не прячется.
            boolean handShot = "fp".equalsIgnoreCase(System.getProperty(CAMERA_PROPERTY, "back"))
                    && System.getenv("MURIM_CAPTURE_HAND") != null;
            if (side && !handShot) {
                minecraft.options.hideGui = true;
            }
            // Приближение кадра без правки build.gradle: переменная окружения доходит до
            // клиента через демон Gradle. Камера третьего лица стоит в четырёх блоках, и при
            // обычных 70° кисть занимает на кадре 854x480 десяток пикселей — дуги не разобрать.
            // API: reference/minecraft-src/net/minecraft/client/Options.java#fov (IntRange 30..110)
            // Площадка стенда на высоте облаков: ночью они тёмными плитами ложатся поверх
            // высоких эффектов (дерево Сливы в 12 блоков) — в съёмке их нет.
            minecraft.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            // Frame size for close-ups (MURIM_CAPTURE_SIZE=1920x1080), as HudGalleryCapture does it: the early
            // loading window ignores options.txt overrideWidth/Height.
            // API: com/mojang/blaze3d/platform/Window.java#setWindowed (neoforge sources jar).
            String[] size = System.getenv().getOrDefault("MURIM_CAPTURE_SIZE", "").split("x");
            if (size.length == 2) {
                minecraft.getWindow().setWindowed(Integer.parseInt(size[0].trim()), Integer.parseInt(size[1].trim()));
            }
            if (playerView()) minecraft.options.fov().set(70);
            String fov = System.getenv("MURIM_CAPTURE_FOV");
            if (fov != null) {
                minecraft.options.fov().set(Mth.clamp(Integer.parseInt(fov.trim()), 30, 110));
            }
            // Breakthrough camera director (client/camera): MURIM_CAMERA_SHOTS="1,3" or "orbit:2,crane:1" plays exactly
            // those shots; MURIM_CAMERA_MOTION=0 films the reduced-shake look (rest poses); MURIM_CAMERA_OFF=1 the
            // player's own camera.
            io.github.verycooltimo.murim.client.camera.BreakthroughCamera.debugShots(System.getenv("MURIM_CAMERA_SHOTS"));
            String cameraMotion = System.getenv("MURIM_CAMERA_MOTION");
            io.github.verycooltimo.murim.client.camera.BreakthroughCamera.debugMotion(
                    cameraMotion == null ? null : Float.valueOf(cameraMotion.trim()));
            io.github.verycooltimo.murim.client.camera.BreakthroughCamera.debugOff("1".equals(System.getenv("MURIM_CAMERA_OFF")));
            MurimMod.LOGGER.info("Автосъёмка: техника через {} тиков, FOV {}, стиль дуг {}",
                    WARMUP_TICKS, System.getenv("MURIM_CAPTURE_FOV"), System.getenv("MURIM_ARC_STYLE"));
        }

        // Камера «сбоку»: стойку ставит сервер с задержкой, поэтому ищем её каждый тик
        // прогрева. В сохранённом мире может остаться стойка прошлой съёмки (клиент гасят без
        // сохранения, и её удаление не записывается) — берём самую новую (наибольший id) и
        // переключаемся на неё, пока идёт прогрев. Опорный кадр снимается уже с неё.
        if (warmup > 0 && (footworkBack() || "front".equalsIgnoreCase(System.getProperty(CAMERA_PROPERTY, "back")) || "side".equalsIgnoreCase(System.getProperty(CAMERA_PROPERTY, "back")))) {
            net.minecraft.world.entity.Entity newest = null;
            for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity instanceof net.minecraft.world.entity.decoration.ArmorStand
                        && entity.isAlive()
                        && entity.getCustomName() != null
                        && io.github.verycooltimo.murim.combat.DevSetupEvents.CAMERA_STAND_NAME
                                .equals(entity.getCustomName().getString())
                        && (newest == null || entity.getId() > newest.getId())) {
                    newest = entity;
                }
            }
            if (newest != null && minecraft.getCameraEntity() != newest) {
                minecraft.setCameraEntity(newest);
                // MURIM_CAPTURE_CAMTYPE=third: the real third-person camera (4 blocks behind the stand) instead of
                // first-person-from-the-stand, so the mod's own isFirstPerson() checks see what a player sees.
                if ("third".equals(System.getenv("MURIM_CAPTURE_CAMTYPE"))) {
                    minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                }
                MurimMod.LOGGER.info("Автосъёмка: камера сбоку на {}", newest.position());
            }
        }

        trackPlayerView(minecraft);
        trackFootwork(minecraft);
        if ("light_test".equals(System.getenv("MURIM_CAPTURE_SCENE"))) LightTestCapture.camera(minecraft);
        if (plumCameraReady) trackPlum(minecraft);

        // Камера на стойке: LocalPlayer шлёт позицию серверу только когда сам — камера
        // (LocalPlayer#sendPosition → isControlledCamera), и сервер видел игрока на старте
        // весь рывок: удары по дистанции не проходили. Шлём позицию сами.
        // API: reference/minecraft-src/net/minecraft/client/player/LocalPlayer.java#sendPosition
        if (minecraft.getCameraEntity() != minecraft.player && minecraft.getConnection() != null) {
            net.minecraft.client.player.LocalPlayer p = minecraft.player;
            minecraft.getConnection().send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                    p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.onGround()));
        }

        // Опорный кадр: сцена в той же позе и с той же камерой, но БЕЗ эффекта.
        if (warmup == 20 && "quickswap".equals(System.getProperty(TECHNIQUE_PROPERTY))) {
            TechniqueWheel.prepareCaptureTap();
        }
        if (warmup == BASELINE_BEFORE_TICKS) {
            Screenshot.grab(minecraft.gameDirectory, "baseline_" + anglePrefix() + ".png",
                    minecraft.getMainRenderTarget(), message -> {
                    });
        }

        // Release the staged zombies and record one second of pursuit before R.
        if (warmup == 20 && "1".equals(System.getenv("MURIM_CAPTURE_ENEMY_AI"))
                && !System.getenv().getOrDefault("MURIM_CAPTURE_FOOTWORK", "").isEmpty()) {
            var server = minecraft.getSingleplayerServer();
            var local = minecraft.player;
            if (server != null && local != null) {
                var uuid = local.getUUID();
                server.execute(() -> {
                    var subject = server.getPlayerList().getPlayer(uuid);
                    if (subject == null) return;
                    subject.setHealth(subject.getMaxHealth());
                    var enemies = subject.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                            subject.getBoundingBox().inflate(32));
                    enemies.sort(java.util.Comparator.comparingInt(net.minecraft.world.entity.Entity::getId));
                    double yaw = Math.toRadians(subject.getYRot() + 20);
                    for (int i = 0; i < enemies.size(); i++) {
                        var enemy = enemies.get(i);
                        double distance = 6 + 2 * i;
                        enemy.moveTo(subject.getX() - Math.sin(yaw) * distance, subject.getY(),
                                subject.getZ() + Math.cos(yaw) * distance, subject.getYRot() + 180, 0);
                        enemy.setNoAi(false);
                        enemy.setTarget(subject);
                    }
                });
            }
            startCapture();
        }

        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/resources/ResourceLocation.java#fromNamespaceAndPath
        // Wait for the server's real slot/form selection to arrive before starting the film clock.
        // The stand setup and client login ticks do not share a clock.
        if (warmup == 1 && !System.getenv().getOrDefault("MURIM_CAPTURE_FOOTWORK", "").isEmpty()) {
            String mode = System.getenv("MURIM_CAPTURE_FOOTWORK");
            var expected = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID,
                    io.github.verycooltimo.murim.dev.FootworkCapturePlan.subject(
                            System.getProperty(TECHNIQUE_PROPERTY, "wind_god_steps"), mode));
            boolean selected = ClientLoadoutState.activeTechnique().filter(expected::equals).isPresent();
            // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/Entity.java#onGround
            if (!selected || !minecraft.player.onGround()) {
                if (footworkWaitTicks++ % 40 == 0) {
                    MurimMod.LOGGER.info("CAPTURE-FOOTWORK waiting subject={} selected={} grounded={}",
                            expected, selected, minecraft.player.onGround());
                }
                return;
            }
        }

        if (plumRejected) return;
        if (warmup == 1 && plumFilm() && !preparePlumCamera(minecraft)) return;

        if (warmup > 0 && --warmup == 0) {
            // Снимать можно не только технику, но и сцену создания даньтяня: у неё те же
            // требования к проверке — привязка к телу, фазы, отсутствие пересвета.
            String subject = System.getProperty(TECHNIQUE_PROPERTY, "ceremonial_draw");
            if ("quickswap".equals(subject)) {
                // Requires two learned selectable forms; MURIM_CAPTURE_GUI=1 records the HUD.
                TechniqueWheel.captureTap();
                startCapture();
                return;
            }
            if (FOUNDATION.equals(subject)) {
                foundationTicks = FOUNDATION_CAPTURE_TICKS;
                return;
            }
            if (AURA.equals(subject)) {
                auraTicks = AURA_CAPTURE_TICKS;
                return;
            }
            if ("ui".equals(subject)) {
                uiTicks = UI_CAPTURE_TICKS;
                return;
            }
            if ("manual".equals(subject)) {
                // MURIM_CAPTURE_MANUAL_PAGES=<n> — сколько раз перелистнуть (по 12 тиков), по умолчанию 4.
                manualSpan = 30 + 12 * envInt("MURIM_CAPTURE_MANUAL_PAGES", 4);
                manualTicks = manualSpan;
                return;
            }
            // Бросок кинжала рукой (ПКМ, 04.10): стенд жмёт «использовать» MURIM_CAPTURE_THROWS раз через 12 тиков.
            if ("throw".equals(subject)) {
                throwTicks = THROW_SPAN;
                startCapture();
                return;
            }
            // Разговор с NPC секты (03.10): сценарий — client/sect/DialogueCapture.
            if ("dialogue".equals(subject)) {
                io.github.verycooltimo.murim.client.sect.DialogueCapture.begin();
                return;
            }
            // Player animation reel (old | new comparisons): client/AnimReelCapture.
            if (AnimReelCapture.SUBJECT.equals(subject)) {
                AnimReelCapture.begin(minecraft);
                startCapture();
                return;
            }
            if(subject.startsWith("bench:")) {
                minecraft.options.hideGui=true;
                String element=subject.substring(6);
                benchCapture=io.github.verycooltimo.murim.client.vfx.preset.BenchRuntime.start(element,
                        Double.parseDouble(System.getenv().getOrDefault("MURIM_BENCH_FROM","-1")),
                        Double.parseDouble(System.getenv().getOrDefault("MURIM_BENCH_TO","-1")),false)==1;
                benchCaptureTicks=0;
                return;
            }
            if (MEDITATION.equals(subject)) {
                sitDown();
                meditationTicks = MEDITATION_CAPTURE_TICKS;
                return;
            }
            net.minecraft.resources.ResourceLocation subjectId = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    MurimMod.MODID, subject);
            // MURIM_CAPTURE_LOCK=1 — перед приёмом захватить цель (как клавиша Z).
            if ("1".equals(System.getenv("MURIM_CAPTURE_LOCK"))) {
                io.github.verycooltimo.murim.client.vfx.LockOn.lockNow();
            }
            String mode = System.getenv().getOrDefault("MURIM_CAPTURE_FOOTWORK", "");
            if ("autorun".equals(mode) || "autoshadow".equals(mode)) {
                // Автоматика шагов (03.10): стенд ничего не шлёт, только держит спринт или присед.
                if ("autorun".equals(mode)) {
                    travelTicks = TRAVEL_CAPTURE_TICKS;
                } else {
                    stillSneakTicks = TRAVEL_CAPTURE_TICKS;
                }
            } else if (!mode.isEmpty() || !System.getenv().getOrDefault("MURIM_CAPTURE_SCENE", "").isEmpty()) {
                // Шаги: стенд жмёт R с контекстом — run (спринт+вперёд, бежит и дважды прыгает),
                // left/back (уклонение в сторону), plain (простой R).
                // A scripted technique scene has no footwork mode; it still needs the scene clock.
                String resolved = mode.isEmpty() ? subject
                        : io.github.verycooltimo.murim.dev.FootworkCapturePlan.subject(subject, mode);
                footworkSubject = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, resolved);
                footworkAge = 0;
                if ("run".equals(mode)) {
                    travelTicks = TRAVEL_CAPTURE_TICKS;
                }
                if ("shadowrun".equals(mode)) {
                    dashRunTicks = TRAVEL_CAPTURE_TICKS;
                }
                if ("shadow".equals(mode)) {
                    // Walk normally after R.
                    dashRunTicks = TRAVEL_CAPTURE_TICKS;
                }
            } else {
                PacketDistributor.sendToServer(new StartTechniquePayload(subjectId));
                // MURIM_CAPTURE_SECOND=<тики> — повторное R той же формы (кинжалы Тан: удар звёзд, отзыв).
                secondPressTicks = envInt("MURIM_CAPTURE_SECOND", 0);
                secondPressId = subjectId;
            }
            if (framesLeft == 0) startCapture();
        }

        if (footworkAge >= 0 && framesLeft > 0 && footworkSubject != null) {
            String mode = System.getenv().getOrDefault("MURIM_CAPTURE_FOOTWORK", "");
            String sequence = System.getenv().getOrDefault("MURIM_CAPTURE_SEQUENCE", "");
            String scene = System.getenv().getOrDefault("MURIM_CAPTURE_SCENE", "");
            if (scene.equals("light_test")) {
                LightTestCapture.tick(minecraft, footworkAge);
            } else if (!scene.isEmpty()) {
                int age = footworkAge;
                boolean floating = scene.contains("floating");
                boolean rising = scene.contains("rising");
                // API: minecraft-src/net/minecraft/client/KeyMapping.java#setDown;
                // Drive vanilla walking/steering; server teleport is only the initial ledge fixture.
                minecraft.options.keyUp.setDown(floating ? age >= 12 && age < 70 : rising && age >= 27 && age < 65);
                minecraft.options.keyLeft.setDown(floating && age >= 50 && age < 65);
                // Same cue grammar as the server driver: custom early/long releases keep input in sync.
                int bloomRelease = 115;
                if (scene.startsWith("full_bloom")) {
                    for (var cue : io.github.verycooltimo.murim.dev.AirhandCapturePlan.parse(
                            System.getenv().getOrDefault("MURIM_CAPTURE_SCENE_CUES", "stage@0,cast@10"))) {
                        if (cue.action().equals("release")) bloomRelease = cue.tick();
                    }
                }
                ModKeyMappings.TECHNIQUE.setDown(scene.startsWith("full_bloom") && age >= 5 && age < bloomRelease
                        || scene.equals("bamboo_wind_leaf") && age >= 10 && age < 55);
                if (minecraft.getSingleplayerServer() != null && minecraft.player != null) {
                    var server = minecraft.getSingleplayerServer();
                    var uuid = minecraft.player.getUUID();
                    var form = footworkSubject;
                    String cues = System.getenv().getOrDefault("MURIM_CAPTURE_SCENE_CUES", "stage@0,cast@10");
                    server.execute(() -> {
                        var player = server.getPlayerList().getPlayer(uuid);
                        if (player != null) io.github.verycooltimo.murim.dev.AirhandFilmCapture.tick(player, scene, form, cues, age);
                    });
                }
            }

            if (!sequence.isBlank() && minecraft.getSingleplayerServer() != null && minecraft.player != null) {
                int age = footworkAge;
                var server = minecraft.getSingleplayerServer();
                var uuid = minecraft.player.getUUID();
                server.execute(() -> {
                    var player = server.getPlayerList().getPlayer(uuid);
                    if (player != null) io.github.verycooltimo.murim.dev.FootworkDuelCapture.tick(player, sequence, age);
                });
            }
            if (scene.isEmpty() && sequence.isBlank() && io.github.verycooltimo.murim.dev.FootworkCapturePlan.due(mode, footworkAge,
                    envInt("MURIM_CAPTURE_REPEAT", 40))) {
                int input = io.github.verycooltimo.murim.dev.FootworkCapturePlan.input(mode);
                // API: /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/network/PacketDistributor.java#sendToServer
                PacketDistributor.sendToServer(new io.github.verycooltimo.murim.network.TraversePayloads.Request(footworkSubject, input));
                MurimMod.LOGGER.info("CAPTURE-FOOTWORK sent subject={} input={} tick={}", footworkSubject, input, footworkAge);
            }
            footworkAge++;
        }

        if (secondPressTicks > 0 && --secondPressTicks == 0 && secondPressId != null) {
            PacketDistributor.sendToServer(new StartTechniquePayload(secondPressId));
        }
        if (throwTicks > 0 && minecraft.player != null && minecraft.gameMode != null) {
            int t = THROW_SPAN - throwTicks--;
            if (t % 12 == 0 && t / 12 < envInt("MURIM_CAPTURE_THROWS", 3)) {
                // Целиться в ближайшего зомби, как игрок мышью: поворот уходит на сервер вместе с «использовать».
                net.minecraft.world.entity.Entity aim = null;
                for (net.minecraft.world.entity.Entity e : minecraft.level.entitiesForRendering()) {
                    if (e instanceof net.minecraft.world.entity.monster.Zombie && (aim == null
                            || e.distanceToSqr(minecraft.player) < aim.distanceToSqr(minecraft.player))) {
                        aim = e;
                    }
                }
                if (aim != null) {
                    net.minecraft.world.phys.Vec3 d = aim.position().add(0.0D, aim.getBbHeight() * 0.7D, 0.0D).subtract(minecraft.player.getEyePosition());
                    minecraft.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                    minecraft.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, d.horizontalDistance())));
                }
                minecraft.gameMode.useItem(minecraft.player, net.minecraft.world.InteractionHand.MAIN_HAND);
            }
        }
        if (manualTicks > 0) {
            int t = manualSpan - manualTicks--;
            if (t == 4 && minecraft.player != null && minecraft.gameMode != null) {
                minecraft.gameMode.useItem(minecraft.player, net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            if (t >= 26 && (t - 26) % 12 == 0 && minecraft.screen != null) {
                minecraft.screen.mouseScrolled(0, 0, 0, -1);
            }
            if (t >= 6) {
                grab(minecraft, String.format("murim_%s_%03d.png", anglePrefix(), frameIndex));
                grab(minecraft, String.format("clean_%s_%03d.png", anglePrefix(), frameIndex));
                frameIndex++;
            }
            if (manualTicks == 0) {
                minecraft.setScreen(null);
            }
        }
        if (stillSneakTicks > 0) {
            stillSneakTicks--;
            minecraft.options.keyShift.setDown(stillSneakTicks > 0);
        }
        // MURIM_CAPTURE_STEER=<градусы за тик> — рулить на ходу (бег, тень): повороты для лент шагов.
        if ((sneakTicks > 0 || travelTicks > 0 || dashRunTicks > 0) && minecraft.player != null && System.getenv("MURIM_CAPTURE_STEER") != null) {
            int t = TRAVEL_CAPTURE_TICKS - Math.max(Math.max(sneakTicks, travelTicks), dashRunTicks);
            float steer = Float.parseFloat(System.getenv("MURIM_CAPTURE_STEER").trim());
            // Змейка: 12 тиков прямо, 10 тиков поворот, смена стороны.
            int phase = t % 44;
            float turn = phase >= 12 && phase < 22 ? steer : phase >= 34 ? -steer : 0.0F;
            minecraft.player.setYRot(minecraft.player.getYRot() + turn);
        }
        // Камера на стойке: LocalPlayer не переносит ввод в xxa/zza, пока сам не камера
        // (LocalPlayer#serverAiStep → isControlledCamera) — бег и тень сбоку стояли бы на месте.
        // API: reference/minecraft-src/net/minecraft/client/player/LocalPlayer.java#serverAiStep
        if (dashRunTicks > 0) {
            dashRunTicks--;
            boolean on = dashRunTicks > 0;
            minecraft.options.keyUp.setDown(on);
            minecraft.options.keySprint.setDown(on && !"shadow".equals(System.getenv("MURIM_CAPTURE_FOOTWORK")));
        }
        if ((sneakTicks > 0 || travelTicks > 0 || dashRunTicks > 0) && minecraft.player != null && minecraft.getCameraEntity() != minecraft.player) {
            minecraft.player.zza = minecraft.player.input.forwardImpulse;
            minecraft.player.xxa = minecraft.player.input.leftImpulse;
            minecraft.player.setJumping(minecraft.player.input.jumping);
        }
        if (sneakTicks > 0) {
            sneakTicks--;
            boolean on = sneakTicks > 0;
            minecraft.options.keyShift.setDown(on);
            minecraft.options.keyUp.setDown(on);
        }
        if (travelTicks > 0) {
            int t = TRAVEL_CAPTURE_TICKS - travelTicks--;
            boolean run = travelTicks > 0;
            minecraft.options.keyUp.setDown(run);
            minecraft.options.keySprint.setDown(run);
            minecraft.options.keyJump.setDown(run && (t == 16 || t == 17 || t == 46 || t == 47));
        }

        if(benchCapture) {
            var time=io.github.verycooltimo.murim.client.vfx.preset.BenchRuntime.renderedSeconds();
            if(!io.github.verycooltimo.murim.client.vfx.preset.BenchRuntime.active())benchCapture=false;
            else if(benchCaptureTicks++ % Math.max(1,envInt("MURIM_CAPTURE_STEP",2))==0 && Double.isFinite(time)) {
                grab(minecraft,String.format("murim_%s_%03d.png",anglePrefix(),frameIndex));
                MurimMod.LOGGER.info("Camera frame {} bench {} t {}",frameIndex,
                        io.github.verycooltimo.murim.client.vfx.preset.BenchRuntime.element(),time);
                frameIndex++;
            }
        }
        if (meditationTicks > 0) {
            tickMeditation(minecraft);
        }
        if (foundationTicks > 0) {
            int t = FOUNDATION_CAPTURE_TICKS - foundationTicks--;
            // Удар формой каждые 14 тиков — чуть дольше перезарядки меча: цепочка идёт по
            // формам, как при размеренных кликах. Цель — ближайший манекен в досягаемости.
            if (t % 14 == 2 && minecraft.player != null && minecraft.gameMode != null) {
                net.minecraft.world.entity.LivingEntity target = minecraft.level.getEntitiesOfClass(
                                net.minecraft.world.entity.LivingEntity.class, minecraft.player.getBoundingBox().inflate(3.5D),
                                e -> e != minecraft.player && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand))
                        .stream().min(java.util.Comparator.comparingDouble(minecraft.player::distanceToSqr)).orElse(null);
                boolean delayed = io.github.verycooltimo.murim.combat.SwordBasicCombo.ownsAttack(
                        ClientLoadoutState.foundation().orElse(null),
                        io.github.verycooltimo.murim.combat.QiSword.holdsSword(minecraft.player.getMainHandItem()),
                        net.neoforged.fml.ModList.get().isLoaded("bettercombat"));
                FoundationClient.swing(minecraft, true, target == null ? -1 : target.getId());
                // Seven Plum already sends a delayed server intent. A vanilla packet here dealt a second hit.
                if (!delayed) {
                    if (target != null) minecraft.gameMode.attack(minecraft.player, target);
                    minecraft.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                }
            }
            // MURIM_CAPTURE_FOUNDATION_STEP=1: a frame every tick (one-tick impact frames are lost at the default 2).
            if (t % Math.max(1, (int) Double.parseDouble(System.getenv().getOrDefault("MURIM_CAPTURE_FOUNDATION_STEP", "2"))) == 0) {
                grab(minecraft, String.format("murim_%s_%03d.png", anglePrefix(), frameIndex));
                frameIndex++;
            }
        }
        if (auraTicks > 0) {
            int t = AURA_CAPTURE_TICKS - auraTicks--;
            runClientCommands(minecraft);
            if (t % AURA_FRAME_STEP == 0) {
                grab(minecraft, String.format("murim_%s_%03d.png", anglePrefix(), frameIndex));
                frameIndex++;
            }
        }
        if (uiTicks > 0) {
            tickUi(minecraft);
        }
        AnimReelCapture.tick(minecraft, frameIndex);

        if (framesLeft > 0 && tickCounter++ % FRAME_INTERVAL_TICKS == 0) {
            MurimMod.LOGGER.debug("Кадр {} на клиентском тике {}", frameIndex, tickCounter - 1);
            // Сам снимок делается в проходе рендера, а не здесь: нужны две точки внутри
            // ОДНОГО кадра, до и после стадии эффектов. Счётчик уменьшается ТАМ ЖЕ,
            // когда кадр действительно снят: под Xvfb с программным OpenGL частота
            // кадров ниже двадцати в секунду, и списание по тику молча теряло бы
            // половину серии, оставляя в отчёте разрежённую и неравномерную выборку.
            if ("eye".equals(viewMode())) {
                framePending = true;
            } else if (guiFrames()) {
                // Полный кадр с интерфейсом: титры и экранные вспышки видны только так.
                grab(minecraft, String.format("murim_%s_%03d.png", anglePrefix(), frameIndex));
                grab(minecraft, String.format("clean_%s_%03d.png", anglePrefix(), frameIndex));
                frameIndex++;
                framesLeft--;
            } else {
                framePending = true;
            }
        }
    }

    /**
     * Телеметрия кадра.
     *
     * <p>Позиции пишутся в МИРОВЫХ координатах вместе с параметрами камеры, а проекцию
     * на экран считает анализатор. Так проще: не надо доставать матрицы проекции из игры,
     * и та же телеметрия годится для любых будущих метрик.
     */
    private static void writeTelemetry(Minecraft minecraft, int frame) {
        try {
            if (telemetry == null) {
                java.io.File file = new java.io.File(minecraft.gameDirectory,
                        "screenshots/telemetry_" + anglePrefix() + ".jsonl");
                file.getParentFile().mkdirs();
                telemetry = new java.io.PrintWriter(new java.io.FileWriter(file, false), true);
            }
            net.minecraft.client.Camera camera = minecraft.gameRenderer.getMainCamera();
            net.minecraft.world.entity.player.Player player = minecraft.player;
            if (player == null) {
                return;
            }
            net.minecraft.world.phys.Vec3 cam = camera.getPosition();
            // Позиция кости руки — точка отсчёта для метрики привязки. Сравнивать эффект
            // с центром торса неверно: у техники ладони эффект И ДОЛЖЕН быть в стороне
            // от торса, и метрика штрафовала правильное поведение.
            net.minecraft.world.phys.Vec3 hand = player instanceof net.minecraft.client.player.AbstractClientPlayer client
                    ? io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.position(
                            client, io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.RIGHT_HAND)
                    : null;
            // Подмена кости позицией игрока помечается ЯВНО. Молча подставлять ступни
            // нельзя: метрика привязки и справка «центр эффекта в N px от кости» тогда
            // считаются от ступней, и об этом никто не узнаёт.
            boolean boneMissing = hand == null;
            if (boneMissing) {
                hand = player.position();
            }
            // Поле зрения в телеметрии берётся из НАСТРОЙКИ, а настоящее поле зрения
            // рендера домножается на модификатор: спринт, эффект скорости, натянутый
            // лук, погружение в жидкость. Прочитать его нельзя — GameRenderer#getFov
            // приватный, а access transformer в проекте требует отдельного согласования.
            // Поэтому пишутся сами состояния, и анализатор отказывается считать, если
            // хоть одно из них возникло: ошибка масштаба радиальна от центра кадра и
            // точкой на оси камеры не ловится.
            boolean fovDisturbed = player.isSprinting() || player.isInWater()
                    || player.isInLava() || player.isUsingItem()
                    || player.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED);
            // Возраст техники, а не номер кадра. Съёмка стартует в тик отправки запроса
            // на сервер, эффект — после ответа; задержка плавает, поэтому «тик 40» в разных
            // прогонах попадал в разные фазы, и метрики фаз сравнивали несравнимое.
            float[] phase = io.github.verycooltimo.murim.client.vfx.PalmVfxRenderer
                    .captureAgeOf(player.getId());
            // Фаза сцены медитации: по номеру кадра её не разметить.
            String scenePhase = ClientMeditationState.state().active()
                    ? "beat" + ClientMeditationState.state().beats() : "";
            // Сколько костей конечностей доступно в этом кадре. Ноль означает, что жилам
            // просто неоткуда расти, и это НЕ дефект рендерера — отличить одно от другого
            // по картинке невозможно.
            int limbs = 0;
            if (player instanceof net.minecraft.client.player.AbstractClientPlayer client) {
                for (io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone bone
                        : new io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone[] {
                        io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.RIGHT_HAND,
                        io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.LEFT_HAND,
                        io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.RIGHT_FOOT,
                        io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.LEFT_FOOT,
                        io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.HEAD}) {
                    if (io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer
                            .position(client, bone) != null) {
                        limbs++;
                    }
                }
            }
            int sceneTick = ClientMeditationState.state().active() ? ClientMeditationState.sessionTicks() : -1;
            // Куда эффект РЕАЛЬНО поставил ладонь. Сравнение с позицией кости отделяет
            // ошибку привязки от ошибки измерения — снаружи они выглядят одинаково.
            net.minecraft.world.phys.Vec3 drawn =
                    io.github.verycooltimo.murim.client.vfx.PalmVfxRenderer.drawnPalm(player.getId());
            // Every limb point (for the Blockbench -> game pose calibration, tools/art/calib_poses.py).
            StringBuilder limbPts = new StringBuilder();
            if (player instanceof net.minecraft.client.player.AbstractClientPlayer client) {
                for (io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone bone
                        : io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.values()) {
                    net.minecraft.world.phys.Vec3 at = io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.position(client, bone);
                    if (at != null) {
                        limbPts.append(String.format(java.util.Locale.ROOT, ",\"%s\":[%.4f,%.4f,%.4f]",
                                bone.name(), at.x, at.y, at.z));
                    }
                }
            }
            telemetry.printf(java.util.Locale.ROOT,
                    "{\"frame\":%d,\"tick\":%d,\"px\":%.4f,\"py\":%.4f,\"pz\":%.4f,"
                            + "\"eye\":%.4f,\"yaw\":%.3f,\"bodyYaw\":%.3f,"
                            + "\"cx\":%.4f,\"cy\":%.4f,\"cz\":%.4f,"
                            + "\"cyaw\":%.3f,\"cpitch\":%.3f,\"fov\":%.3f,"
                            + "\"hx\":%.4f,\"hy\":%.4f,\"hz\":%.4f,"
                            + "\"age\":%.2f,\"impactAge\":%.2f,"
                            + "\"rx\":%.4f,\"ry\":%.4f,\"rz\":%.4f,"
                            + "\"boneMissing\":%b,\"fovDisturbed\":%b,"
                            + "\"scenePhase\":\"%s\",\"sceneTick\":%d,\"limbs\":%d,"
                            + "\"w\":%d,\"h\":%d%s}%n",
                    frame, tickCounter - 1,
                    player.getX(), player.getY(), player.getZ(), player.getEyeHeight(),
                    player.getYRot(), player.yBodyRot,
                    cam.x, cam.y, cam.z,
                    camera.getYRot(), camera.getXRot(),
                    minecraft.options.fov().get().doubleValue(),
                    hand.x, hand.y, hand.z,
                    phase == null ? -1.0F : phase[0], phase == null ? -1.0F : phase[1],
                    drawn == null ? 0.0D : drawn.x, drawn == null ? 0.0D : drawn.y,
                    drawn == null ? 0.0D : drawn.z,
                    boneMissing, fovDisturbed, scenePhase, sceneTick, limbs,
                    minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight(), limbPts);
        } catch (java.io.IOException exception) {
            MurimMod.LOGGER.warn("Телеметрия не пишется: {}", exception.getMessage());
        }
    }

    /** Основа меча: удары ЛКМ формами по очереди, кадр каждые два тика. */
    private static final String FOUNDATION = "foundation";
    private static final int FOUNDATION_CAPTURE_TICKS = 150;
    private static int foundationTicks;

    /** Давление ауры: игрок стоит перед противником, кадр каждые три тика. */
    /** MURIM_CAPTURE_CLIENT_CMDS="wait 10;murim dev vfx backend aaa;murim dev vfx shockwave": client commands, run during the aura subject. */
    private static final java.util.ArrayDeque<String> CLIENT_CMDS = new java.util.ArrayDeque<>();
    private static boolean clientCmdsRead;
    private static int clientCmdWait;

    private static void runClientCommands(Minecraft minecraft) {
        if (!clientCmdsRead) {
            clientCmdsRead = true;
            for (String cmd : System.getenv().getOrDefault("MURIM_CAPTURE_CLIENT_CMDS", "").split(";")) {
                if (!cmd.isBlank()) CLIENT_CMDS.add(cmd.trim());
            }
        }
        if (clientCmdWait > 0) { clientCmdWait--; return; }
        while (!CLIENT_CMDS.isEmpty() && minecraft.player != null) {
            String cmd = CLIENT_CMDS.poll();
            if (cmd.startsWith("wait ")) { clientCmdWait = Integer.parseInt(cmd.substring(5).trim()); return; }
            MurimMod.LOGGER.info("Capture client command: {}", cmd);
            minecraft.player.connection.sendCommand(cmd);
        }
    }

    private static final String AURA = "aura";
    /** Aura / still-scene capture span and frame step (env MURIM_CAPTURE_AURA_TICKS, _AURA_STEP: long orbit shots). */
    private static final int AURA_CAPTURE_TICKS = envInt("MURIM_CAPTURE_AURA_TICKS", 150);
    private static final int AURA_FRAME_STEP = Math.max(1, envInt("MURIM_CAPTURE_AURA_STEP", 3));
    private static int auraTicks;

    /** Сценарий интерфейсов техник: слоты боя → кольцо → экран раскладки. */
    private static final int UI_CAPTURE_TICKS = 200;
    private static int uiTicks;
    private static int stillSneakTicks;
    private static int manualTicks;
    private static int manualSpan = 72;
    private static int throwTicks;
    private static final int THROW_SPAN = 80;

    private static void tickUi(Minecraft minecraft) {
        int t = UI_CAPTURE_TICKS - uiTicks--;
        if (t < 60) {
            CombatMode.engage();
        }
        boolean ring2 = "2".equals(System.getenv("MURIM_CAPTURE_UI_STYLE"));
        if (ring2) {
            // Двойное кольцо: форма Казнь → наружу на слот «24 Движения» (внутри его Ливень) →
            // обратно внутрь на Ливень → отпустить.
            ModKeyMappings.WHEEL.setDown(t >= 60 && t < 124);
            float[][] path = {{0, 0}, {24, 16}, {24, 16}, {42, -44}, {42, -44}, {0, -29}, {0, -29}};
            int[] at = {66, 76, 88, 96, 104, 112, 124};
            for (int i = 0; i + 1 < at.length; i++) {
                if (t >= at[i] && t < at[i + 1]) {
                    float k = (t - at[i]) / (float) (at[i + 1] - at[i]);
                    TechniqueWheel.setCursor(Mth.lerp(k, path[i][0], path[i + 1][0]), Mth.lerp(k, path[i][1], path[i + 1][1]));
                }
            }
        } else {
            // Кольцо: зажать клавишу, повести курсор к третьему сектору, отпустить.
            ModKeyMappings.WHEEL.setDown(t >= 60 && t < 110);
            if (t >= 70 && t < 110) {
                float k = Math.min(1.0F, (t - 70) / 15.0F);
                TechniqueWheel.setCursor(44.0F * k, 22.0F * k);
            }
        }
        if (t == 130 && !ring2) {
            LoadoutScreen screen = new LoadoutScreen();
            minecraft.setScreen(screen);
            screen.pickForCapture(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "wedge_fan"));
        }
        boolean scrollShot = "1".equals(System.getenv("MURIM_CAPTURE_SCROLL"));
        if (scrollShot ? t >= 128 && t < 150 : ring2 ? t >= 56 && t < 140 && t % 2 == 0 : t % 5 == 0) {
            grab(minecraft, String.format("murim_%s_%03d.png", anglePrefix(), frameIndex));
            grab(minecraft, String.format("clean_%s_%03d.png", anglePrefix(), frameIndex));
            frameIndex++;
        }
        if (uiTicks == 0) {
            minecraft.setScreen(null);
        }
    }

    private static final String MEDITATION = "meditation";

    /** Две сессии по 30 с плюс вспышка семени и запас на пересадку. */
    private static final int MEDITATION_CAPTURE_TICKS = 1600;

    /** Медитация медленная: полный кадр раз в полсекунды, вместе с интерфейсом. */
    private static final int MEDITATION_FRAME_TICKS = 10;

    private static boolean benchCapture;
    private static int benchCaptureTicks;
    private static int meditationTicks;
    private static int meditationIdle;

    private static void sitDown() {
        PacketDistributor.sendToServer(new io.github.verycooltimo.murim.network.MeditationInputPayload(
                io.github.verycooltimo.murim.network.MeditationInputPayload.Action.TOGGLE, true));
    }

    /**
     * Сценарий медитации: удержание кольца идёт через ту же клавишу прыжка, что у игрока,
     * чтобы съёмка проверяла настоящий путь ввода, а не обходной пакет.
     */
    private static void tickMeditation(Minecraft minecraft) {
        meditationTicks--;
        var state = ClientMeditationState.state();
        // Бот мини-игры: держит, когда кольцо шире центра полосы. С 4-й по 6-ю секунду
        // каждой игры он нарочно перетягивает — в кадры должно попасть красное состояние.
        // MURIM_CAPTURE_FAIL=1 — не жмёт вовсе, чтобы снять искажение ци.
        boolean fail = "1".equals(System.getenv("MURIM_CAPTURE_FAIL"));
        boolean hold = false;
        if (ClientMeditationState.minigame() && !fail) {
            var ring = ClientMeditationState.ring(1.0F);
            int t = ClientMeditationState.sessionTicks();
            hold = (t >= 80 && t < 120) || ring.radius() > ring.centre();
        }
        minecraft.options.keyJump.setDown(hold);
        absorbBot(minecraft);
        // MURIM_CAPTURE_TURN=1 — «крутить мышью» во время ритуала: тело обязано стоять.
        if ("1".equals(System.getenv("MURIM_CAPTURE_TURN")) && state.active()) {
            minecraft.player.turn(8.0D, 0.0D);
        }
        // MURIM_CAPTURE_LEAVE=1 — на десятой секунде нажать Shift: проверка выхода.
        if ("1".equals(System.getenv("MURIM_CAPTURE_LEAVE"))) {
            minecraft.options.keyShift.setDown(state.active() && ClientMeditationState.sessionTicks() >= 200);
        }
        // Сессия прервалась, а семени ещё нет — садимся снова через секунду.
        if (!state.active() && state.beats() < 3 && !fail) {
            if (++meditationIdle == 20) {
                sitDown();
            }
        } else {
            meditationIdle = 0;
        }
        // Сцена семени снимается чаще: вспышка длится полсекунды и между кадрами
        // по десять тиков пропадала целиком.
        // Прорыв — чаще, выход ауры — как вспышка семени: иначе короткие фазы между кадрами пропадают.
        int step = ClientMeditationState.seedSceneAge() >= 0 || ClientMeditationState.rankUpAge() >= 0
                || ClientPillState.finaleAge() >= 0 || ClientPillState.absorbing() || ClientPillState.deviationAge() >= 0 ? 2
                : ClientMeditationState.breakthroughAge() >= 0 ? envInt("MURIM_CAPTURE_STEP", 5) : envInt("MURIM_CAPTURE_STEP", MEDITATION_FRAME_TICKS);
        // Видно в журнале стенда, началась ли сцена прорыва (иначе её отсутствие замечают только по кадрам).
        if (!breakthroughLogged && ClientMeditationState.breakthroughAge() >= 0) {
            breakthroughLogged = true;
            int target = ClientMeditationState.breakthroughTarget();
            MurimMod.LOGGER.info("Breakthrough target {}: scene {} ticks, tail {} ticks; lab clock starts at scene age 0",
                    target, io.github.verycooltimo.murim.cultivation.Realm.breakthroughTicks(target),
                    io.github.verycooltimo.murim.cultivation.Realm.releaseTicks(target));
            MurimMod.LOGGER.info("Автосъёмка: прорыв начался на тике медитации {}", meditationTicks);
        }
        if (meditationTicks % step == 0) {
            // Снимок в тике берёт последний отрисованный кадр целиком, с интерфейсом:
            // HUD медитации — часть того, что проверяется.
            grab(minecraft, String.format("murim_%s_%03d.png", anglePrefix(), frameIndex));
            grab(minecraft, String.format("clean_%s_%03d.png", anglePrefix(), frameIndex));
            // One line per frame: tools/capture/camera_sheet.py reads these to label contact sheets and cut GIFs.
            MurimMod.LOGGER.info("Camera frame {} warn {} scene {} rankup {} director {} | {}", frameIndex,
                    ClientMeditationState.warningAge(), ClientMeditationState.breakthroughAge(),
                    ClientMeditationState.rankUpAge(), io.github.verycooltimo.murim.client.camera.BreakthroughCamera.active(),
                    io.github.verycooltimo.murim.client.camera.BreakthroughCamera.debugLine());
            frameIndex++;
        }
        if (meditationTicks == 0) {
            minecraft.options.keyJump.setDown(false);
        }
    }

    /**
     * The stand's hands for the pill game «Провести поток»: {@code MURIM_CAPTURE_FLOW=auto} plays it with the autopilot of the game
     * itself (restrains before closed spots and in a surge, breaks poison, picks the light branch; see {@code FlowClient#start}),
     * anything else leaves the hands off the keys. {@code MURIM_CAPTURE_HIT=1} strikes the player on the {@code MURIM_CAPTURE_HIT_AT}-th
     * tick of the absorption: the qi deviation (the recoil, the red edge).
     */
    private static void absorbBot(Minecraft minecraft) {
        if (minecraft.player != null && "1".equals(System.getenv("MURIM_CAPTURE_HIT")) && ClientPillState.deviationAge() == 6) {
            var server = minecraft.getSingleplayerServer();
            if (server != null) {
                java.util.UUID id = minecraft.player.getUUID();
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayer(id);
                    if (sp != null) {
                        sp.getAbilities().invulnerable = true;
                        sp.onUpdateAbilities();
                    }
                });
            }
        }
        if (!ClientPillState.absorbing() || minecraft.player == null) {
            return;
        }
        if ("1".equals(System.getenv("MURIM_CAPTURE_HIT"))
                && ClientPillState.activeTicks() == envInt("MURIM_CAPTURE_HIT_AT", 100)) {
            var server = minecraft.getSingleplayerServer();
            if (server != null) {
                java.util.UUID id = minecraft.player.getUUID();
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayer(id);
                    if (sp != null) {
                        sp.getAbilities().invulnerable = false;
                        sp.onUpdateAbilities();
                        sp.hurt(sp.damageSources().magic(), 2.0F);
                    }
                });
            }
        }
    }

    /** Метка ракурса в именах файлов: back, front или угол поворота игрока. */
    private static String anglePrefix() {
        String camera = System.getProperty(CAMERA_PROPERTY, "back");
        String yaw = System.getProperty("murim.capture.yaw", "0");
        return camera + "_" + yaw.replace("-", "m");
    }

    /**
     * Две точки съёмки внутри одного кадра.
     *
     * <p>{@code AFTER_TRIPWIRE_BLOCKS} — последняя стадия перед {@code AFTER_PARTICLES},
     * на которой рисуются наши эффекты. Снимок здесь содержит сцену и позу игрока, но не
     * эффект. Второй снимок берётся на {@code AFTER_PARTICLES} с НАИНИЗШИМ приоритетом,
     * то есть после всех обработчиков этой стадии, включая наши рендереры.
     * Glow-кадры ждут AFTER_LEVEL: только там заканчивается общий проход свечения.
     *
     * <p>Раньше второй снимок делался на {@code AFTER_LEVEL}, а между стадиями рисуются
     * ещё облака, погода, граница мира и отладочный слой. Всё это попадало в разницу и
     * засчитывалось как «энергия эффекта»: облака над сценой ползут каждый кадр.
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    static void onRenderStage(RenderLevelStageEvent event) {
        if (plumRejected) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (plumFilm() && warmup == 1 && plumAimTicks >= 3 && !plumCameraReady
                && event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) {
            boolean caster = plumInView(event, minecraft.player);
            boolean target = plumInView(event, plumTarget(minecraft));
            MurimMod.LOGGER.info("CAPTURE-PLUM-CAMERA preflight caster_in_view={} target_in_view={}", caster, target);
            if (caster && target) {
                plumCameraReady = true;
            } else if (plumReaims++ == 0) {
                // Re-aim on the next client tick, then allow the render camera to settle again.
                plumAimTicks = 0;
                trackForward = null;
            } else {
                rejectPlum();
            }
            return;
        }
        if (!framePending || "eye".equals(viewMode())) return;
        if (plumFilm() && frameIndex == 0 && event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) {
            boolean caster = plumInView(event, minecraft.player);
            boolean target = plumInView(event, plumTarget(minecraft));
            MurimMod.LOGGER.info("CAPTURE-PLUM-CAMERA frame=0 caster_in_view={} target_in_view={}", caster, target);
            if (!caster || !target) {
                rejectPlum();
                return;
            }
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) {
            grab(minecraft, String.format("clean_%s_%03d.png", anglePrefix(), frameIndex));
        } else if ((event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES
                && !io.github.verycooltimo.murim.client.vfx.bt.BtGlow.active())
                || event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            // Glow frames wait for the shared AFTER_LEVEL composite (NORMAL priority), before this LOWEST capture.
            // Телеметрия пишется здесь же: возраст техники должен относиться именно к тому
            // кадру, который снят, а не к моменту тика.
            writeTelemetry(minecraft, frameIndex);
            grab(minecraft, String.format("murim_%s_%03d.png", anglePrefix(), frameIndex));
            frameIndex++;
            framesLeft--;
            framePending = false;
        }
    }

    /**
     * Нужен ли кадр с интерфейсом. Обычная пара {@code clean_}/{@code murim_} снимается внутри прохода рендера
     * мира (стадии {@code AFTER_TRIPWIRE_BLOCKS} и {@code AFTER_PARTICLES}), ДО того как нарисованы слои интерфейса,
     * поэтому ни один экранный слой в такой кадр попасть не может. Титр озарения, вспышки и полосы — слои
     * интерфейса: {@code MURIM_CAPTURE_GUI=1} включает съёмку по тику (в буфере уже готовый кадр с интерфейсом),
     * а {@code MURIM_CAPTURE_INSIGHT=1} (титр озарения виден только в интерфейсе) включает её сам.
     */
    private static boolean guiFrames() {
        return "1".equals(System.getenv("MURIM_CAPTURE_GUI")) || "1".equals(System.getenv("MURIM_CAPTURE_INSIGHT"));
    }

    /** API: supplied NeoForge RenderFrameEvent.Post runs after GameRenderer#render,
     * including the hand pass (which follows RenderLevelStageEvent.AFTER_LEVEL). */
    @SubscribeEvent
    static void onViewFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY) || !"eye".equals(viewMode()) || !framePending) return;
        Minecraft mc = Minecraft.getInstance();
        writeTelemetry(mc, frameIndex);
        grab(mc, String.format("murim_%s_%03d.png", anglePrefix(), frameIndex));
        frameIndex++;
        framesLeft--;
        framePending = false;
    }

    /** Номер кадра — с ведущими нулями: иначе сортировка ставит 10 перед 2. */
    private static void grab(Minecraft minecraft, String name) {
        if (playerView() && !viewLogged && name.startsWith("murim_")) {
            var camera = minecraft.gameRenderer.getMainCamera();
            var position = camera.getPosition();
            MurimMod.LOGGER.info("CAPTURE-VIEW mode={} pos=<{},{},{}> yaw={} pitch={} fov={}",
                    viewMode(), position.x, position.y, position.z,
                    camera.getYRot(), camera.getXRot(), minecraft.options.fov().get());
            viewLogged = true;
        }
        Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(), message -> {
        });
    }

    private DevCaptureHandler() {
    }
}
