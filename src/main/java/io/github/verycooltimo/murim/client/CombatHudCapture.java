package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Dev stand for the combat HUD rules (author 18.10): idle with a sword, swings at air, hits on a zombie, then silence.
 * The swings are the real {@link FoundationClient#swing} (the same intent the left click sends); the HUD under test is the
 * real one. Frames go to {@code screenshots/combat_<NN>_<label>.png}. Subject {@code combathud}, with
 * {@code MURIM_CAPTURE_STAR=3} (so a hit moves the gauge) and {@code MURIM_CAPTURE_CMDS} summoning the zombie —
 * {@code tools/capture/combat_hud.sh} sets both.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class CombatHudCapture {

    public static final String SUBJECT = "combathud";
    private static final boolean ON = Boolean.getBoolean("murim.capture")
            && SUBJECT.equals(System.getProperty("murim.capture.technique"));

    private static final int WARMUP = 140;
    /** Tick of the first zombie swing; the later frames count from the last one. */
    private static final int HITS = 200;
    private static final int[] AIR_SWINGS = {60, 73, 86};
    private static final int[] HIT_SWINGS = {HITS, HITS + 13, HITS + 26};

    private static int tick = -WARMUP;
    private static boolean done;
    private static int frame;

    private CombatHudCapture() {
    }

    public static boolean active() {
        return ON;
    }

    private static void shot(Minecraft mc, String label) {
        Screenshot.grab(mc.gameDirectory, String.format("combat_%02d_%s.png", frame++, label), mc.getMainRenderTarget(), message -> {
        });
    }

    private static boolean at(int t, int... ticks) {
        for (int tick : ticks) {
            if (tick == t) {
                return true;
            }
        }
        return false;
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
        if (tick++ < 0) {
            if (tick == -WARMUP + 2) {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                mc.options.hideGui = false;
                // The early loading window ignores options.txt overrideWidth; resize here (MURIM_CAPTURE_SIZE=1280x720).
                String[] size = System.getenv().getOrDefault("MURIM_CAPTURE_SIZE", "").split("x");
                if (size.length == 2) {
                    mc.getWindow().setWindowed(Integer.parseInt(size[0].trim()), Integer.parseInt(size[1].trim()));
                }
            }
            return;
        }
        int t = tick;
        if (t == 20 || t == HITS) {
            for (net.minecraft.world.entity.LivingEntity e : mc.level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                    mc.player.getBoundingBox().inflate(8.0D))) {
                MurimMod.LOGGER.info("combathud: t={} {} id={} at {} hurtTime={}", t, e.getType().getDescriptionId(), e.getId(),
                        e.position(), e.hurtTime);
            }
        }
        // 1. Idle with a sword in hand: no slots, no star text.
        if (t == 20) {
            shot(mc, "idle_sword");
        }
        // 2. Swings at air (three: the third is the finisher). Frames while they play and just after.
        if (at(t, AIR_SWINGS)) {
            FoundationClient.swing(mc, true, -1);
        }
        if (at(t, 66, 80, 93, 110, 140)) {
            shot(mc, "air_swing_" + t);
        }
        // 3. Three cuts at the zombie: the slots come in, the star text only where the gauge moved.
        if (at(t, HIT_SWINGS)) {
            Zombie zombie = mc.level.getEntitiesOfClass(Zombie.class, mc.player.getBoundingBox().inflate(6.0D))
                    .stream().min(java.util.Comparator.comparingDouble(mc.player::distanceToSqr)).orElse(null);
            MurimMod.LOGGER.info("combathud: cut at zombie id={}", zombie == null ? -1 : zombie.getId());
            FoundationClient.swing(mc, true, zombie == null ? -1 : zombie.getId());
        }
        if (at(t, HITS + 4, HITS + 10, HITS + 17, HITS + 30, HITS + 36, HITS + 50, HITS + 70)) {
            shot(mc, "hit_zombie_" + (t - HITS));
        }
        // 4. Silence after the last cut (HITS + 26): the slots stay for 6 s (120 ticks), then fade out over 1.5 s.
        int last = HIT_SWINGS[HIT_SWINGS.length - 1];
        if (at(t, last + 80, last + 118, last + 130, last + 142, last + 160, last + 200)) {
            shot(mc, "after_" + (t - last));
        }
        // 5. The wheel (V held, no pick): the selected form's numbers show only now, with no combat slots behind them.
        ModKeyMappings.WHEEL.setDown(t >= last + 205 && t < last + 235);
        if (at(t, last + 222)) {
            shot(mc, "wheel_open");
        }
        if (t > last + 250) {
            done = true;
            MurimMod.LOGGER.info("combathud: done");
            mc.stop();
        }
    }
}
