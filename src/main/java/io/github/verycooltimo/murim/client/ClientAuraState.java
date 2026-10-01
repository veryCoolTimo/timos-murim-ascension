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

    private static float target;
    private static float shown;
    private static float shownBefore;
    /** Сильнейший источник давления: к нему сходятся мазки и марево. */
    private static int sourceId = -1;
    private static boolean sourceDemonic;
    private static int ticks;
    /** Тики до следующего удара сердца. */
    private static int beatIn;
    /** Тиков с последнего удара: толчок камеры затухает от него. */
    private static int sinceBeat = 100;

    public static void set(int entityId, AuraState aura) {
        if (aura.present()) {
            AURAS.put(entityId, aura);
        } else {
            AURAS.remove(entityId);
        }
    }

    public static AuraState of(Entity entity) {
        AuraState aura = AURAS.get(entity.getId());
        return aura == null ? AuraState.NONE : aura;
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

    /** Возраст текущего удара сердца в тиках: экран вздрагивает вместе с ним. */
    public static float beatAge(float partial) {
        return sinceBeat + partial;
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

        // Сердце: чем тяжелее, тем чаще и громче. Удар — и звук, и толчок камеры.
        sinceBeat++;
        if (shown > 0.08F) {
            if (--beatIn <= 0) {
                beatIn = Math.round(Mth.lerp(shown, 30.0F, 11.0F));
                sinceBeat = 0;
                minecraft.player.level().playLocalSound(minecraft.player.getX(), minecraft.player.getY(),
                        minecraft.player.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS,
                        0.35F + 0.75F * shown, 0.8F - 0.15F * shown, false);
                if (shown > 0.6F && ticks % 3 == 0) {
                    // Низкий гул: воздух сам стал тяжёлым.
                    minecraft.player.level().playLocalSound(minecraft.player.getX(), minecraft.player.getY(),
                            minecraft.player.getZ(), SoundEvents.WARDEN_AMBIENT, SoundSource.HOSTILE,
                            0.25F * shown, 0.5F, false);
                }
            }
        } else {
            beatIn = 0;
        }
    }

    @SubscribeEvent
    static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        float partial = (float) event.getPartialTick();
        float p = pressure(partial);
        if (p <= 0.0F) {
            return;
        }
        double setting = ClientConfig.cameraShake();
        float t = ticks + partial;
        // Давит вниз: взгляд опускается, голову клонит набок — медленно, как под тяжестью.
        float lean = (float) (p * (3.0D + 1.2D * Math.sin(t * 0.07D)));
        event.setPitch(event.getPitch() + 4.0F * p * p);
        event.setRoll(event.getRoll() + lean * (float) Math.max(setting, 0.3D));
        // Толчок на удар сердца.
        float kick = (float) Math.exp(-beatAge(partial) / 2.5D) * p * (float) setting;
        if (kick > 0.001F) {
            event.setYaw(event.getYaw() + Mth.sin(t * 2.9F) * 1.2F * kick);
            event.setPitch(event.getPitch() + Mth.sin(t * 3.7F + 1.1F) * 1.0F * kick);
            event.setRoll(event.getRoll() + Mth.sin(t * 3.3F + 2.2F) * 1.6F * kick);
        }
    }

    @SubscribeEvent
    static void onFov(ViewportEvent.ComputeFov event) {
        float p = pressure((float) event.getPartialTick());
        if (p > 0.0F && event.usedConfiguredFov()) {
            // Поле зрения сужается к противнику.
            event.setFOV(event.getFOV() * (1.0D - 0.1D * p));
        }
    }

    private ClientAuraState() {
    }
}
