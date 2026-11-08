package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Dev stand for the senior spar (author 24.10, combat): a scripted player fights a senior through the real client, the real packets
 * and the real AI of the server, and the stand films it. Subject {@code sparfilm}; the server side is staged by
 * {@code MURIM_CAPTURE_CMDS} ({@code /murim dev spar <member>} or {@code /murim dev spar trial <id>}). Modes ({@code MURIM_FILM_MODE}):
 * <ul>
 *   <li>{@code spam}: closes in and swings every time the sword is charged; never guards. The player who only spams.</li>
 *   <li>{@code parry}: the same, but at the flash of the senior's strike (his {@code tell}) the right mouse button is pressed
 *       {@code MURIM_FILM_REACT} ticks later and held for 7 ticks; no swing while it is held. A player who has learned to answer the flash.</li>
 *   <li>{@code guard}: holds the right mouse button the whole bout (the plain block).</li>
 *   <li>{@code dagger} / {@code daggerplain}: no bout. Thrown Tang daggers (the capture commands run {@code /murim dev spar dagger}) fly at the
 *       player; in {@code dagger} he clicks (the swing that opens the parry window) when a dagger is {@code MURIM_FILM_DIST} blocks away
 *       (default 12) and presses the right mouse button three ticks later, in {@code daggerplain} he only presses at that distance.
 *       Each dagger's end is logged ({@code sparfilm: dagger}); the server logs the parries ({@code Parry:}).</li>
 *   <li>{@code talk}: no bout. Looks at the person, clicks the right mouse button through the real path with the sword in hand and logs
 *       what opened ({@code sparfilm: talk}): the guard must not swallow the click that opens a dialogue.</li>
 * </ul>
 * The fight starts for the stand when the senior's bow (clip {@code spar_bow}) is over. Frames go to
 * {@code screenshots/spar_<NNNN>.png}, one every {@code MURIM_FILM_EVERY} client ticks (default 2); the stand stops
 * {@code MURIM_FILM_TICKS} ticks after the fight began (default 700) or {@code 40} ticks after the closing bow. The server logs the
 * outcome of the bout ({@code Sect bout ended}); this class logs the swings it made ({@code sparfilm:}).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class SparFilmCapture {

    public static final String SUBJECT = "sparfilm";
    private static final boolean ON = Boolean.getBoolean("murim.capture") && SUBJECT.equals(System.getProperty("murim.capture.technique"));

    private static final int WARMUP = Integer.parseInt(System.getenv().getOrDefault("MURIM_FILM_WARMUP", "160"));
    private static final int EVERY = Math.max(1, Integer.parseInt(System.getenv().getOrDefault("MURIM_FILM_EVERY", "2")));
    private static final int TICKS = Integer.parseInt(System.getenv().getOrDefault("MURIM_FILM_TICKS", "700"));
    private static final int REACT = Integer.parseInt(System.getenv().getOrDefault("MURIM_FILM_REACT", "2"));
    private static final String MODE = System.getenv().getOrDefault("MURIM_FILM_MODE", "spam");
    private static final String CAMERA = System.getenv().getOrDefault("MURIM_FILM_CAM", "back");
    private static final int HOLD = 7;
    /** Plain mode: the press comes when the dagger is this close (blocks), two ticks before it arrives. */
    private static final double PLAIN_DIST = Double.parseDouble(System.getenv().getOrDefault("MURIM_FILM_PLAIN_DIST", "5"));
    private static final double DAGGER_DIST = Double.parseDouble(System.getenv().getOrDefault("MURIM_FILM_DIST", "12"));
    private static final int DAGGER_TICKS = Integer.parseInt(System.getenv().getOrDefault("MURIM_FILM_DAGGER_TICKS", "600"));
    private static int daggerPress = -1;
    private static int daggerRelease = -1;
    private static boolean daggerSeen;
    private static int daggerEnd = -1;
    private static int daggersGone;

    private static int tick = -WARMUP;
    private static boolean done;
    private static int frame;
    private static boolean sawBow;
    private static int fightAt = -1;
    private static int endAt = -1;
    private static boolean lastTell;
    private static int pressAt = -1;
    private static int holdLeft;
    private static int swings;
    private static int presses;
    private static java.lang.reflect.Method startAttack;
    private static java.lang.reflect.Method startUse;
    private static int talkClicks;

    private static int flushWait;
    private static boolean finishing;

    private SparFilmCapture() {
    }

    /**
     * Stops the client once every frame is on the disk: {@code Screenshot.grab} writes on a pool thread, and on a loaded machine with
     * software GL it is far behind the game (28 of 122 frames survived the first film). Returns true when the game was stopped.
     */
    private static boolean finish(Minecraft mc) {
        java.io.File dir = new java.io.File(mc.gameDirectory, "screenshots");
        String[] names = dir.list((d, n) -> n.startsWith("spar_") && n.endsWith(".png"));
        int written = names == null ? 0 : names.length;
        if (written >= frame || ++flushWait > 20 * 240) {
            MurimMod.LOGGER.info("sparfilm: {} of {} frames on the disk, stopping", written, frame);
            mc.stop();
            return true;
        }
        return false;
    }

    public static boolean active() {
        return ON;
    }

    private static void shot(Minecraft mc) {
        Screenshot.grab(mc.gameDirectory, String.format("spar_%04d.png", frame++), mc.getMainRenderTarget(), message -> {
        });
    }

    /** The click of the left mouse button, through the same path as a real one (events, packets). */
    private static boolean click(Minecraft mc) {
        try {
            if (startAttack == null) {
                startAttack = Minecraft.class.getDeclaredMethod("startAttack");
                startAttack.setAccessible(true);
            }
            startAttack.invoke(mc);
            return true;
        } catch (ReflectiveOperationException e) {
            MurimMod.LOGGER.warn("sparfilm: no startAttack, using the game mode", e);
            return false;
        }
    }

    /** The partner of the bout: the one the stand locked on when his bow began (the ring is busy with other people). */
    private static int lockedId = -1;
    private static int lostFor;

    private static SectDisciple senior(Minecraft mc) {
        if (lockedId >= 0 && mc.level.getEntity(lockedId) instanceof SectDisciple locked && locked.isAlive()
                && locked.distanceToSqr(mc.player) <= 24.0D * 24.0D) {
            return locked;
        }
        if (lockedId >= 0 && fightAt >= 0) {
            return null;
        }
        SectDisciple near = mc.level.getEntitiesOfClass(SectDisciple.class, mc.player.getBoundingBox().inflate(10.0D), d -> d.isAlive())
                .stream().min(java.util.Comparator.comparingDouble(mc.player::distanceToSqr)).orElse(null);
        if (near != null && near.anim().endsWith("spar_bow")) {
            lockedId = near.getId();
        }
        return near;
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (!ON || done) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (finishing) {
            // no more frames: wait for the pool thread to write the ones taken
            mc.options.keyUp.setDown(false);
            mc.options.keyUse.setDown(false);
            if (finish(mc)) {
                done = true;
            }
            return;
        }
        if (tick++ < 0) {
            if (tick == -WARMUP + 2) {
                mc.options.setCameraType("front".equals(CAMERA) ? CameraType.THIRD_PERSON_FRONT
                        : "fp".equals(CAMERA) ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
                mc.options.hideGui = false;
                String[] size = System.getenv().getOrDefault("MURIM_CAPTURE_SIZE", "").split("x");
                if (size.length == 2) {
                    mc.getWindow().setWindowed(Integer.parseInt(size[0].trim()), Integer.parseInt(size[1].trim()));
                }
            }
            return;
        }
        if (MODE.startsWith("dagger")) {
            daggers(mc);
            return;
        }
        SectDisciple d = senior(mc);
        if (d == null) {
            if (tick % 40 == 0) {
                MurimMod.LOGGER.info("sparfilm: t={} no senior near", tick);
            }
            // The partner is gone after a bout (he walks off): the film is over a little after, not never.
            if (fightAt >= 0 && ++lostFor > 60) {
                finishing = true;
                MurimMod.LOGGER.info("sparfilm: the partner left, {} frames, {} swings, {} presses", frame, swings, presses);
            }
            return;
        }
        lostFor = 0;
        if (MODE.equals("talk")) {
            talk(mc, d);
            return;
        }

        boolean bowing = d.anim().endsWith("spar_bow");
        if (bowing) {
            if (!sawBow) {
                MurimMod.LOGGER.info("sparfilm: t={} the bow begins", tick);
            }
            if (fightAt >= 0 && endAt < 0) {
                endAt = tick + 40;
                MurimMod.LOGGER.info("sparfilm: t={} the closing bow; {} swings, {} presses", tick, swings, presses);
            }
            sawBow = true;
        } else if (sawBow && fightAt < 0) {
            fightAt = tick;
            MurimMod.LOGGER.info("sparfilm: t={} the fight begins (mode {}, react {})", tick, MODE, REACT);
        }
        boolean fighting = fightAt >= 0 && endAt < 0;
        if (fightAt >= 0 || bowing) {
            if (tick % EVERY == 0) {
                shot(mc);
            }
        }
        if (fighting) {
            act(mc, d);
        } else {
            mc.options.keyUp.setDown(false);
            mc.options.keyUse.setDown(false);
        }
        if (endAt >= 0 && tick >= endAt || fightAt >= 0 && tick - fightAt > TICKS) {
            finishing = true;
            MurimMod.LOGGER.info("sparfilm: done, {} frames, {} swings, {} presses", frame, swings, presses);
            return;
        }
    }

    /** The dagger mode: face the senior (who stands behind the daggers), answer each dagger by the distance, film all of it. */
    private static void daggers(Minecraft mc) {
        var p = mc.player;
        if (tick % EVERY == 0) {
            shot(mc);
        }
        mc.options.keyUse.setDown(false);
        var daggers = mc.level.getEntitiesOfClass(io.github.verycooltimo.murim.technique.TangDagger.class, p.getBoundingBox().inflate(40.0D),
                x -> x.isAlive());
        io.github.verycooltimo.murim.technique.TangDagger near = daggers.stream()
                .min(java.util.Comparator.comparingDouble(p::distanceToSqr)).orElse(null);
        if (near != null) {
            daggerSeen = true;
            double dist = Math.sqrt(p.distanceToSqr(near));
            if (tick % 2 == 0) {
                MurimMod.LOGGER.info("sparfilm: dagger t={} dist {} mode {} delta {}", tick, String.format("%.1f", dist), near.mode(),
                        String.format("%.2f", near.getDeltaMovement().length()));
            }
            // aim at the dagger's line: the player faces where it comes from
            Vec3 to = near.position().subtract(p.getEyePosition());
            p.setYRot((float) (Mth.atan2(-to.x, to.z) * Mth.RAD_TO_DEG));
            p.setXRot(0.0F);
            if (daggerPress == -1) {
                if (MODE.equals("dagger") && dist <= DAGGER_DIST) {
                    click(mc);
                    swings++;
                    daggerPress = tick + 3;
                    MurimMod.LOGGER.info("sparfilm: dagger t={} dist {} -> click, press at t={}", tick, String.format("%.1f", dist), daggerPress);
                } else if (MODE.equals("daggerplain") && dist <= PLAIN_DIST) {
                    daggerPress = tick;
                    MurimMod.LOGGER.info("sparfilm: dagger t={} dist {} -> plain press now", tick, String.format("%.1f", dist));
                }
            }
        } else if (daggerSeen) {
            daggerSeen = false;
            daggersGone++;
            daggerPress = -1;
            MurimMod.LOGGER.info("sparfilm: dagger t={} gone ({} so far)", tick, daggersGone);
            if (daggerEnd < 0) {
                daggerEnd = tick + 40;
            }
        }
        if (daggerPress >= 0 && tick == daggerPress) {
            holdLeft = HOLD;
            presses++;
        }
        if (holdLeft > 0) {
            mc.options.keyUse.setDown(true);
            holdLeft--;
        }
        if (tick > DAGGER_TICKS || daggersGone >= Integer.parseInt(System.getenv().getOrDefault("MURIM_FILM_DAGGERS", "5")) && tick > daggerEnd) {
            finishing = true;
            MurimMod.LOGGER.info("sparfilm: dagger done, {} daggers, {} swings, {} presses, {} frames", daggersGone, swings, presses, frame);
        }
    }

    /** Walks up to the person, aims at him and clicks the right button three times, ten ticks apart; logs the screen after each. */
    private static void talk(Minecraft mc, SectDisciple d) {
        var p = mc.player;
        Vec3 to = d.position().add(0.0D, d.getBbHeight() * 0.6D, 0.0D).subtract(p.getEyePosition());
        double flat = Math.sqrt(to.x * to.x + to.z * to.z);
        p.setYRot((float) (Mth.atan2(-to.x, to.z) * Mth.RAD_TO_DEG));
        p.setXRot((float) -(Mth.atan2(to.y, flat) * Mth.RAD_TO_DEG));
        double dist = Math.sqrt(p.distanceToSqr(d));
        mc.options.keyUp.setDown(dist > 2.0D);
        if (tick % 5 == 0) {
            shot(mc);
        }
        if (mc.screen != null) {
            MurimMod.LOGGER.info("sparfilm: talk t={} screen {} opened by {} clicks (sword in hand: {})", tick, mc.screen.getClass().getSimpleName(),
                    talkClicks, io.github.verycooltimo.murim.combat.QiSword.holdsSword(p.getMainHandItem()));
            done = true;
            mc.stop();
            return;
        }
        boolean onTarget = mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == d;
        if (dist <= 2.6D && onTarget && tick % 10 == 0 && talkClicks < 3) {
            talkClicks++;
            try {
                if (startUse == null) {
                    startUse = Minecraft.class.getDeclaredMethod("startUseItem");
                    startUse.setAccessible(true);
                }
                startUse.invoke(mc);
                MurimMod.LOGGER.info("sparfilm: talk t={} right click {} on {} (sword in hand: {})", tick, talkClicks, d.memberKey(),
                        io.github.verycooltimo.murim.combat.QiSword.holdsSword(p.getMainHandItem()));
            } catch (ReflectiveOperationException e) {
                MurimMod.LOGGER.warn("sparfilm: no startUseItem", e);
            }
        }
        if (tick > 400) {
            MurimMod.LOGGER.info("sparfilm: talk t={} nothing opened after {} clicks (sword in hand: {})", tick, talkClicks,
                    io.github.verycooltimo.murim.combat.QiSword.holdsSword(p.getMainHandItem()));
            done = true;
            mc.stop();
        }
    }

    private static void act(Minecraft mc, SectDisciple d) {
        var p = mc.player;
        Vec3 eye = p.getEyePosition();
        Vec3 aim = d.position().add(0.0D, d.getBbHeight() * 0.6D, 0.0D);
        Vec3 to = aim.subtract(eye);
        double flat = Math.sqrt(to.x * to.x + to.z * to.z);
        float yaw = (float) (Mth.atan2(-to.x, to.z) * Mth.RAD_TO_DEG);
        float pitch = (float) -(Mth.atan2(to.y, flat) * Mth.RAD_TO_DEG);
        p.setYRot(Mth.approachDegrees(p.getYRot(), yaw, 40.0F));
        p.setXRot(Mth.approachDegrees(p.getXRot(), pitch, 30.0F));
        double dist = Math.sqrt(p.distanceToSqr(d));
        mc.options.keyUp.setDown(dist > 2.4D && holdLeft <= 0);

        if (MODE.equals("guard")) {
            mc.options.keyUse.setDown(true);
            return;
        }
        boolean tell = d.tell();
        if (MODE.equals("parry")) {
            if (tell && !lastTell) {
                pressAt = tick + REACT;
            }
            if (pressAt >= 0 && tick >= pressAt) {
                pressAt = -1;
                holdLeft = HOLD;
                presses++;
            }
        }
        lastTell = tell;
        if (holdLeft > 0) {
            mc.options.keyUse.setDown(true);
            holdLeft--;
            return;
        }
        mc.options.keyUse.setDown(false);
        boolean onTarget = mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == d;
        if (dist <= 3.0D && onTarget && p.getAttackStrengthScale(0.5F) >= 0.95F && click(mc)) {
            swings++;
        }
    }
}
