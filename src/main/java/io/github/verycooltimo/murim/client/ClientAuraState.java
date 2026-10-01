package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.AuraPressure;
import io.github.verycooltimo.murim.combat.AuraState;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Ауры существ на клиенте и давление на своего игрока (docs/design/19 §3ж).
 *
 * <p>Давление считается той же функцией, что и на сервере ({@link AuraPressure}), — экран и
 * ноги обязаны совпадать. Показываемое значение сглажено: нарастает за полсекунды и спадает
 * за секунду, иначе на краю радиуса экран мигал бы от шага к шагу.
 *
 * <p>Камера: тяжёлый крен и наклон взгляда вниз — игрока придавливает; на каждый удар сердца —
 * короткий толчок. Без случайных чисел, по той же причине, что и в {@link CameraShakeHandler}.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ClientAuraState {

    private static final Int2ObjectMap<AuraState> AURAS = new Int2ObjectOpenHashMap<>();
    /** Тик, когда аура существа появилась или сменила ранг: от него идёт раскрытие. */
    private static final it.unimi.dsi.fastutil.ints.Int2IntMap SINCE = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();

    private static float target;
    private static float shown;
    private static float shownBefore;
    /** Сильнейший источник давления: к нему сходятся мазки и марево. */
    private static int sourceId = -1;
    private static boolean sourceDemonic;
    private static int ticks;
    /** Тики до следующего удара сердца. */
    private static int beatIn;
    /** Тик последнего фронта давления. */
    private static int frontTick = -1;
    private static int lastTier;

    public static void set(int entityId, AuraState aura) {
        if (aura.present()) {
            AuraState before = AURAS.put(entityId, aura);
            if (!aura.equals(before)) {
                SINCE.put(entityId, ticks);
            }
        } else {
            AURAS.remove(entityId);
            SINCE.remove(entityId);
        }
    }

    public static AuraState of(Entity entity) {
        AuraState aura = AURAS.get(entity.getId());
        return aura == null ? AuraState.NONE : aura;
    }

    /** Сколько тиков аура существа уже раскрыта. */
    public static float ageOf(int entityId, float partial) {
        return ticks - SINCE.getOrDefault(entityId, ticks) + partial;
    }

    public static Int2ObjectMap<AuraState> all() {
        return AURAS;
    }

    /** Давление на экране, 0..1, сглаженное между тиками. */
    public static float pressure(float partial) {
        return Mth.lerp(partial, shownBefore, shown);
    }

    public static int sourceId() {
        return sourceId;
    }

    public static boolean sourceDemonic() {
        return sourceDemonic;
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        AURAS.clear();
        target = shown = shownBefore = 0.0F;
        sourceId = -1;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ticks++;
        shownBefore = shown;
        if (minecraft.level == null || minecraft.player == null || minecraft.isPaused()) {
            return;
        }
        int rank = ClientProfileState.profile().rank();
        float strongest = 0.0F;
        int strongestId = -1;
        boolean demonic = false;
        var it = AURAS.int2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            Entity entity = minecraft.level.getEntity(entry.getIntKey());
            if (entity == null) {
                // Ушла из видимости или умерла: при новом появлении сервер пришлёт ауру снова.
                SINCE.remove(entry.getIntKey());
                it.remove();
                continue;
            }
            if (!(entity instanceof LivingEntity living) || !living.isAlive() || entity == minecraft.player) {
                continue;
            }
            float p = AuraPressure.of(entry.getValue().rank(), rank, minecraft.player.distanceTo(entity));
            if (p > strongest) {
                strongest = p;
                strongestId = entity.getId();
                demonic = entry.getValue().demonic();
            }
        }
        if (minecraft.player.isSpectator() || minecraft.player.isCreative() && !"true".equals(System.getProperty("murim.capture"))) {
            strongest = 0.0F;
        }
        target = strongest;
        if (strongestId >= 0) {
            sourceId = strongestId;
            sourceDemonic = demonic;
        }
        float rate = target > shown ? 0.1F : 0.05F;
        shown = Math.abs(target - shown) <= rate ? target : shown + Math.signum(target - shown) * rate;
        if (shown <= 0.0F) {
            sourceId = -1;
        }

        // Ступень давления выросла — один фронт: толчок камеры, волна марева, вход мазков.
        int tier = tier(shown);
        if (tier > lastTier) {
            frontTick = ticks;
        }
        lastTier = tier;

        // Сердце — только с сильного давления, 60–75 ударов в минуту, тихо: не глушит шаги.
        // Свет и камера удары не повторяют (разбор astra 01.10).
        if (shown >= 0.5F) {
            if (--beatIn <= 0) {
                beatIn = Math.round(Mth.lerp((shown - 0.5F) / 0.5F, 20.0F, 16.0F));
                minecraft.player.level().playLocalSound(minecraft.player.getX(), minecraft.player.getY(),
                        minecraft.player.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS,
                        0.25F + 0.35F * shown, 0.75F, false);
            }
        } else {
            beatIn = 0;
        }
        // Гул и тяжёлый выдох пространства — на фронте.
        if (frontTick == ticks && tier > 0) {
            minecraft.player.level().playLocalSound(minecraft.player.getX(), minecraft.player.getY(),
                    minecraft.player.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE,
                    0.15F + 0.12F * tier, 0.5F, false);
        }
    }

    /** Р0..Р3 по силе давления. */
    public static int tier(float p) {
        return p >= 0.8F ? 3 : p >= 0.5F ? 2 : p >= 0.2F ? 1 : 0;
    }

    /** Возраст последнего фронта в тиках; большой — фронта не было. */
    public static float frontAge(float partial) {
        return frontTick < 0 ? 1000.0F : ticks - frontTick + partial;
    }

    @SubscribeEvent
    static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        float partial = (float) event.getPartialTick();
        float p = pressure(partial);
        if (p <= 0.0F) {
            return;
        }
        double setting = ClientConfig.cameraShake();
        // Один затухающий толчок на фронте: 0,1° / 0,25° / 0,4°, около 0,35 с. Постоянного
        // крена больше нет — он мешал целиться и читался как укачивание.
        float age = frontAge(partial);
        float[] kickByTier = {0.0F, 0.1F, 0.25F, 0.4F};
        float kick = kickByTier[lastTier] * (float) Math.exp(-age / 2.5D) * (float) setting;
        if (kick > 0.001F) {
            float t = ticks + partial;
            event.setPitch(event.getPitch() + Mth.sin(t * 3.7F) * kick * 2.0F);
            event.setRoll(event.getRoll() + Mth.sin(t * 3.1F + 1.3F) * kick * 2.0F);
        }
    }

    @SubscribeEvent
    static void onFov(ViewportEvent.ComputeFov event) {
        float p = pressure((float) event.getPartialTick());
        if (p > 0.5F && event.usedConfiguredFov() && ClientConfig.cameraShake() > 0.0D) {
            // Сужение 1,5° на Р2 и 2,5° на Р3, плавно.
            event.setFOV(event.getFOV() - Mth.lerp((p - 0.5F) / 0.5F, 0.0F, 2.5F));
        }
    }

    private ClientAuraState() {
    }
}
