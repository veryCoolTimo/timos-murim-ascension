package io.github.verycooltimo.murim.client;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Клиентские звуки мода: разовые с разбросом высоты и петли, привязанные к сущности.
 *
 * <p>Разовый звук — {@code Level#playLocalSound}: без сети, позиционный, затухание по
 * расстоянию даёт движок (файлы mono, см. docs/01-neoforge/12-sound.md §1). Высота тона
 * гуляет ±7 %, чтобы одинаковые удары не звучали «пулемётом» (там же, §5 п.4).
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/Level.java#playLocalSound,
 * reference/minecraft-src/net/minecraft/client/resources/sounds/AbstractTickableSoundInstance.java,
 * reference/minecraft-src/net/minecraft/client/resources/sounds/SimpleSoundInstance.java#forUI.
 */
public final class Sfx {

    private static final RandomSource RANDOM = RandomSource.create();
    /** Живые петли по ключу «что-у-кого», чтобы одна и та же петля не запускалась дважды. */
    private static final Map<String, Loop> LOOPS = new HashMap<>();

    /** Разовый звук в точке мира, высота ±7 %. Сигнатура — как у {@code playLocalSound}. */
    public static void play(double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch,
            boolean distanceDelay) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        mc.level.playLocalSound(x, y, z, sound, source, volume, jitter(pitch), distanceDelay);
    }

    public static void play(Vec3 at, Supplier<SoundEvent> sound, float volume, float pitch) {
        play(at.x, at.y, at.z, sound.get(), SoundSource.PLAYERS, volume, pitch, false);
    }

    public static void play(Entity e, Supplier<SoundEvent> sound, float volume, float pitch) {
        play(e.getX(), e.getY() + e.getBbHeight() * 0.5D, e.getZ(), sound.get(), SoundSource.PLAYERS, volume, pitch, false);
    }

    /** Звук интерфейса: не позиционный, без затухания. */
    public static void ui(Supplier<SoundEvent> sound, float volume, float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound.get(), jitter(pitch), volume));
    }

    private static float jitter(float pitch) {
        return pitch * (0.93F + 0.14F * RANDOM.nextFloat());
    }

    /**
     * Петля, привязанная к сущности: играет, пока {@code alive} истинно, с нарастанием и
     * затуханием по {@code fade} тиков (резкий обрыв петли щёлкает — 12-sound.md §3).
     *
     * @param key    ключ петли; повторный запуск с тем же ключом, пока петля жива, ничего не делает
     * @param volume громкость каждый тик (можно менять по ходу: скорость бега и т.п.)
     */
    public static void loop(String key, Entity e, Supplier<SoundEvent> sound, Supplier<Float> volume, float pitch,
            BooleanSupplier alive, int fade) {
        Loop old = LOOPS.get(key);
        // Движок мог снять петлю сам (выход из мира, нехватка каналов) — тогда флаг stopped
        // не выставлен; живой считаем только ту, что звучит или запущена только что.
        if (old != null && !old.isStopped()
                && (Minecraft.getInstance().getSoundManager().isActive(old) || System.nanoTime() - old.born < 500_000_000L)) {
            return;
        }
        Loop l = new Loop(key, e, sound.get(), volume, pitch, alive, Math.max(1, fade));
        LOOPS.put(key, l);
        Minecraft.getInstance().getSoundManager().play(l);
    }

    private static final class Loop extends AbstractTickableSoundInstance {
        private final String key;
        private final Entity entity;
        private final Supplier<Float> target;
        private final BooleanSupplier alive;
        private final int fade;
        private final long born = System.nanoTime();
        private float level;

        Loop(String key, Entity entity, SoundEvent sound, Supplier<Float> target, float pitch, BooleanSupplier alive, int fade) {
            super(sound, SoundSource.PLAYERS, RANDOM);
            this.key = key;
            this.entity = entity;
            this.target = target;
            this.alive = alive;
            this.fade = fade;
            this.looping = true;
            this.delay = 0;
            this.pitch = pitch;
            this.volume = 0.0F;
            follow();
        }

        private void follow() {
            this.x = entity.getX();
            this.y = entity.getY() + entity.getBbHeight() * 0.5D;
            this.z = entity.getZ();
        }

        /** Петля начинается с нуля громкости и нарастает — без этого движок её отбросит. */
        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            boolean on = !entity.isRemoved() && alive.getAsBoolean();
            float goal = Math.max(0.0F, target.get());
            float step = Math.max(goal, 0.05F) / fade;
            level = on ? Math.min(goal, level + step) : Math.max(0.0F, level - step);
            if (on && level > goal) {
                level = Math.max(goal, level - step);
            }
            volume = level;
            follow();
            if (!on && level <= 0.0F) {
                stop();
                if (LOOPS.get(key) == this) {
                    LOOPS.remove(key);
                }
            }
        }
    }

    private Sfx() {
    }
}
