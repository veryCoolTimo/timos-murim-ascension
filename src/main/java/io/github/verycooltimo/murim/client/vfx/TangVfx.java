package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.CameraShakeHandler;
import io.github.verycooltimo.murim.client.ClientAuraState;
import io.github.verycooltimo.murim.network.TangPayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.technique.TangDagger;
import io.github.verycooltimo.murim.technique.TangRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Скрытое Оружие Клана Тан — весь клиентский рисунок (docs/design/techniques/tang-daggers-spec.md, рефы
 * «daggers techniques», «hidden dagger/dagger1–4», «12th dagger technique», «tang reference»).
 *
 * <p>Всё — симуляция: следы кинжалов по истории их положений (белое ядро, циановая кромка, синий край),
 * розетка из настоящих кинжалов между ладонями, разорванные вращающиеся кольца, игольчатые венцы звёзд,
 * нити ци к рукавам, змейка «карпа» с конусом колец и вихрем пыли, частицы со скоростью и сопротивлением.
 * Урон, тайминги и попадания — на сервере; удар (импакт-кадр, тряска, дым) — только по факту попадания
 * (пакет {@link TangPayload#HIT}/{@link TangPayload#EXPLODE}). Слой 0 — ничего, кроме голого кинжала.
 *
 * <p>Стадия {@code AFTER_PARTICLES}; типы: {@link MurimRenderTypes#airBand()} (ленты, иглы, кольца),
 * {@link MurimRenderTypes#solid()} (кинжалы розетки и ладони), {@link MurimRenderTypes#mote()} (свечение),
 * {@link MurimRenderTypes#dustPuffs()} и {@link MurimRenderTypes#smokeCel()} (пыль и дым манхвы).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class TangVfx {

    // Палитра из рефов (hex — описания codex hidden dagger 1–4).
    private static final VfxColour CORE = hex(0xF4FFFF);
    private static final VfxColour CYAN = hex(0x62F5FF);
    private static final VfxColour PALE = hex(0x9CF8FF);
    private static final VfxColour EDGE_BLUE = hex(0x3266EF);
    private static final VfxColour AURA_BLUE = hex(0x4DA9F5);
    private static final VfxColour CROWN = hex(0xA1E7FF);
    private static final VfxColour CROWN_RIM = hex(0x598DFA);
    private static final VfxColour STEEL = hex(0xD8E4EC);
    private static final VfxColour WHITE = hex(0xFFFFFF);
    private static final VfxColour GREY = hex(0x9AA3AD);
    /** Монеты Тан: бронза (tang-coins-spec.md) — чтобы не путались с циановыми лезвиями. */
    private static final VfxColour BRONZE = hex(0xB08D57);
    private static final VfxColour BRONZE_DARK = hex(0x8C6A3A);
    private static final VfxColour BRONZE_HI = hex(0xF4E6C2);
    private static final VfxColour INK = hex(0x22262B);
    private static final VfxColour POISON = hex(0x3E7A5A);
    private static final VfxColour POISON_PALE = hex(0x9FCFA8);

    private static final List<Cast> CASTS = new ArrayList<>();
    private static final Fx FX = new Fx();
    private static final Map<Integer, Deque<Vec3>> TRAILS = new HashMap<>();
    private static final java.util.Set<Integer> REVEALED = new java.util.HashSet<>();
    private static final Random RANDOM = new Random();
    private static int clientTicks;

    private static VfxColour hex(int rgb) {
        return new VfxColour(((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F);
    }

    // ------------------------------------------------------------------ частицы

    /** Свободная частица: искра, лента ветра, осколок, игла лучей, ядовитая струйка. */
    private static final class Mote {
        static final int SPARK = 0;
        static final int WIND = 1;
        static final int SHARD = 2;
        static final int POISON = 3;
        static final int DUST_SWIRL = 4;
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        final int life;
        final int kind;
        final double size;
        final Vec3[] trail;
        int count;
        double drag = 0.9D;
        double gravity;
        double turbulence;
        VfxColour colour = CORE;
        /** Для вихря пыли: центр, вокруг которого кружит. */
        Vec3 centre;

        Mote(Vec3 pos, Vec3 vel, int life, int kind, double size, int trail) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.life = life;
            this.kind = kind;
            this.size = size;
            this.trail = new Vec3[Math.max(1, trail)];
        }
    }

    /** Пыль и дым манхвы. */
    private static final class Puff {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        int delay;
        final int life;
        final int cell;
        final double size;
        final boolean smoke;
        final float gray;
        final float spin;

        Puff(Vec3 pos, Vec3 vel, int life, int cell, double size, boolean smoke, float gray, float spin) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.life = life;
            this.cell = cell;
            this.size = size;
            this.smoke = smoke;
            this.gray = gray;
            this.spin = spin;
        }
    }

    /** Кольцо в плоскости, перпендикулярной {@code axis}: расширяется и гаснет, с разрывами. */
    private record Ring(Vec3 centre, Vec3 axis, int born, double r0, double r1, int life, double width, VfxColour col, double gap) {
    }

    /** Лучи звезды: из центра наружу, изогнутые, с яркой точкой на конце (12-й, s11). */
    private record Burst(Vec3 centre, int born, int rays, double len, int life, long seed, boolean curved, boolean dark) {
    }

    /** Отпечаток взрыва на земле: тёмные штрихи-звезда, тают. */
    private record Scar(Vec3 centre, int born, long seed) {
    }

    private static final class Fx {
        final List<Mote> motes = new ArrayList<>();
        final List<Puff> puffs = new ArrayList<>();
        final List<Ring> rings = new ArrayList<>();
        final List<Burst> bursts = new ArrayList<>();
        final List<Scar> scars = new ArrayList<>();
        /** Вспышка звёзд (телеграф) — до этого клиентского тика у мастера. */
        final Map<Integer, Integer> flare = new HashMap<>();
        /** Мастер, у кого висит зависший кинжал Тёмного Взрыва (нить к рукаву). */
        final Map<Integer, Integer> hangOwner = new HashMap<>();
    }

    private static final class Cast {
        final int entityId;
        final int form;
        final int layer;
        int start;
        final double density;

        Cast(int entityId, int form, int layer) {
            this.entityId = entityId;
            this.form = form;
            this.layer = layer;
            this.start = clientTicks;
            this.density = TangRules.density(layer);
        }

        int t() {
            return clientTicks - start;
        }

        int n(double full) {
            return (int) Math.ceil(full * density);
        }

        boolean own() {
            Minecraft mc = Minecraft.getInstance();
            return mc.player != null && mc.player.getId() == entityId;
        }
    }

    // ------------------------------------------------------------------ события

    public static void onTechniqueEvent(TechniqueEventPayload payload) {
        if (payload.event() != TechniqueEventPayload.Event.STARTED) {
            return;
        }
        int form = TangRules.form(payload.techniqueId());
        if (form < 0) {
            return;
        }
        CASTS.removeIf(c -> c.entityId == payload.sourceId());
        Cast c = new Cast(payload.sourceId(), form, payload.layer());
        CASTS.add(c);
        if (c.layer >= 3) {
            // Холодная синяя аура в стойке (рефы s01, d4-01: синие языки у плеч и головы).
            // У Пяти Громов аура — только у руки по смыслу: короче и слабее, чтобы не глушить выпуски (codex 03.10).
            ClientAuraState.techniqueAura(c.entityId, form == TangRules.STARS ? 2 + Math.min(1, c.layer / 4) : 2, 0,
                    TangRules.windup(form) + (form == TangRules.FIVE ? 0 : 24));
        }
        Minecraft mc = Minecraft.getInstance();
        if (c.layer >= 1 && mc.level != null && mc.level.getEntity(c.entityId) instanceof Entity e) {
            dust(e.position(), form == TangRules.FIVE ? 2 : c.n(8) + 2, 0.12D);
            sound(e.position(), SoundEvents.ARMOR_EQUIP_CHAIN.value(), 0.6F, 1.6F);
        }
    }

    public static void onTang(TangPayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        int layer = p.layer();
        Entity master = mc.level.getEntity(p.entityId());
        boolean own = mc.player != null && mc.player.getId() == p.entityId();
        switch (p.stage()) {
            case TangPayload.SHOT -> shot(p, master, own);
            case TangPayload.HIT -> hit(p, own, mc);
            case TangPayload.CLANG -> clang(p.pos(), p.dir(), layer);
            case TangPayload.EXPLODE -> explode(p, own, mc);
            case TangPayload.BURST -> {
                sound(p.pos(), SoundEvents.TRIDENT_RIPTIDE_1.value(), 1.0F, 0.7F);
                if (p.b() == 1) {
                    // Удвоение: удар кинжала в кинжал — звон «до лопнувших перепонок» и белая звезда.
                    sound(p.pos(), SoundEvents.ANVIL_LAND, 0.5F, 1.9F);
                    FX.bursts.add(new Burst(p.pos(), clientTicks, 8, 1.1D, 5, RANDOM.nextLong(), false, false));
                }
                if (layer >= 6) {
                    // Кольца рвутся назад туннелем (s10).
                    // Туннель s10: 6 колец через 0,9 блока по ходу рывка, живут 4 тика.
                    for (int i = 0; i < 6; i++) {
                        FX.rings.add(new Ring(p.pos().add(p.dir().scale(0.9D * i)), p.dir(), clientTicks + i / 2, 0.25D, 0.7D + 0.12D * i,
                                4, 0.045D, i % 2 == 0 ? WHITE : PALE, 0.3D));
                    }
                }
                if (layer >= 1) {
                    for (int i = 0; i < 8; i++) {
                        Vec3 v = p.dir().scale(-0.25D).add(RANDOM.nextGaussian() * 0.12D, RANDOM.nextGaussian() * 0.12D, RANDOM.nextGaussian() * 0.12D);
                        wind(p.pos(), v, 10, 0.06D);
                    }
                }
                if (own) {
                    SpeedLines.directional(0.0F, 0.18F, 3, SpeedLines.WHITE);
                }
            }
            case TangPayload.STAR -> {
                if (layer >= 3) {
                    FX.rings.add(new Ring(p.pos(), camAxis(p.pos()), clientTicks, 0.1D, 0.55D, 6, 0.04D, WHITE, 0.0D));
                }
                sound(p.pos(), SoundEvents.AMETHYST_BLOCK_CHIME, 0.5F, 1.7F + 0.05F * p.a());
            }
            case TangPayload.FLARE -> {
                FX.flare.put(p.entityId(), clientTicks + 8);
                sound(p.pos(), SoundEvents.FIRE_EXTINGUISH, 0.7F, 1.8F);
            }
            case TangPayload.STRIKE -> sound(p.pos(), SoundEvents.TRIDENT_THROW.value(), 1.0F, 1.6F);
            case TangPayload.CUT -> {
                clang(p.pos(), Vec3.ZERO, layer);
                sound(p.pos(), SoundEvents.CHAIN_HIT, 0.7F, 1.6F);
            }
            case TangPayload.HANG -> {
                FX.hangOwner.put(p.a(), p.entityId());
                sound(p.pos(), SoundEvents.TRIDENT_HIT, 0.5F, 1.8F);
            }
            case TangPayload.RECALL -> {
                sound(p.pos(), SoundEvents.ARROW_SHOOT, p.form() == TangRules.RETURN ? 0.35F : 1.0F, p.form() == TangRules.RETURN ? 1.2F : 0.6F);
                if (layer >= 1) {
                    // Возврат в Рукав: лезвий много — кольцо маленькое и короткое, чтобы не закрывать прямые линии (codex 03.10).
                    boolean many = p.form() == TangRules.RETURN;
                    FX.rings.add(new Ring(p.pos(), camAxis(p.pos()), clientTicks, 0.1D, many ? 0.4D : 0.9D, many ? 4 : 7, 0.05D, WHITE, 0.2D));
                }
            }
            case TangPayload.CAUGHT -> {
                FX.hangOwner.remove(p.a());
                if (master != null && layer >= 1) {
                    for (int i = 0; i < 5; i++) {
                        wind(p.pos(), new Vec3(RANDOM.nextGaussian() * 0.08D, 0.05D, RANDOM.nextGaussian() * 0.08D), 8, 0.04D);
                    }
                }
                sound(p.pos(), SoundEvents.ARMOR_EQUIP_CHAIN.value(), 0.7F, 1.4F);
            }
            case TangPayload.DOWN -> {
                clang(p.pos(), Vec3.ZERO, layer);
                sound(p.pos(), SoundEvents.ANVIL_LAND, 0.25F, 2.0F);
            }
            case TangPayload.POISON -> {
                if (layer >= 0) {
                    // Ядовитая дымка у раны: тёмно-зелёные струйки поднимаются и тают (рефы poison).
                    for (int i = 0; i < 6; i++) {
                        Mote m = new Mote(p.pos().add(RANDOM.nextGaussian() * 0.2D, RANDOM.nextGaussian() * 0.25D, RANDOM.nextGaussian() * 0.2D),
                                new Vec3(RANDOM.nextGaussian() * 0.01D, 0.025D + 0.02D * RANDOM.nextDouble(), RANDOM.nextGaussian() * 0.01D),
                                22 + RANDOM.nextInt(10), Mote.POISON, 0.06D + 0.04D * RANDOM.nextDouble(), 7);
                        m.drag = 0.96D;
                        m.turbulence = 0.012D;
                        FX.motes.add(m);
                    }
                }
            }
            case TangPayload.LEAP -> {
                dust(p.pos(), 10, 0.22D);
                FX.rings.add(new Ring(p.pos().add(0.0D, 0.05D, 0.0D), new Vec3(0.0D, 1.0D, 0.0D), clientTicks, 0.3D, 2.4D, 10, 0.12D, GREY, 0.15D));
                for (int i = 0; i < 6; i++) {
                    wind(p.pos().add(0.0D, 0.3D, 0.0D), new Vec3(RANDOM.nextGaussian() * 0.15D, -0.05D, RANDOM.nextGaussian() * 0.15D), 10, 0.06D);
                }
                sound(p.pos(), SoundEvents.ENDER_DRAGON_FLAP, 0.6F, 1.5F);
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ выпуск и удар

    private static void shot(TangPayload p, Entity master, boolean own) {
        int layer = p.layer();
        Vec3 at = p.pos();
        Vec3 dir = p.dir();
        switch (p.form()) {
            case TangRules.FIVE -> {
                sound(at, SoundEvents.ARROW_SHOOT, 0.9F, 1.5F + 0.12F * p.a());
                if (layer >= 3) {
                    // Поперечная белая дуга у кисти на каждом щелчке (d3-03, «кольцевые следы у точки старта»).
                    FX.rings.add(new Ring(at.add(dir.scale(0.3D)), dir, clientTicks, 0.18D, 0.32D + 0.02D * p.a(), 3, 0.035D, WHITE, 0.35D));
                }
                if (layer >= 1) {
                    for (int i = 0; i < 3; i++) {
                        wind(at, dir.scale(0.25D).add(RANDOM.nextGaussian() * 0.06D, RANDOM.nextGaussian() * 0.06D, RANDOM.nextGaussian() * 0.06D), 8, 0.04D);
                    }
                }
                if (own) {
                    SpeedLines.directional(0.0F, 0.12F, 2, SpeedLines.WHITE);
                }
            }
            case TangRules.TWELVE -> {
                if (p.b() == 1) {
                    // 12-й с неба: вспышка «против солнца» у мастера.
                    sound(at, SoundEvents.TRIDENT_THROW.value(), 1.0F, 0.8F);
                    if (layer >= 1) {
                        FX.bursts.add(new Burst(at.add(0.0D, 0.6D, 0.0D), clientTicks, 10, 1.6D, 8, RANDOM.nextLong(), false, false));
                    }
                    return;
                }
                if (p.a() == 0) {
                    sound(at, SoundEvents.TRIDENT_THROW.value(), 1.0F, 1.3F);
                    if (layer >= 6) {
                        FX.rings.add(new Ring(at, dir, clientTicks, 0.4D, 2.6D, 9, 0.07D, WHITE, 0.3D));
                    }
                    if (own) {
                        SpeedLines.directional(0.0F, 0.28F, 3, SpeedLines.WHITE);
                    }
                }
                if (layer >= 2) {
                    // Веер из кисти (s03): белая стрела-всполох по направлению каждого кинжала.
                    Mote m = new Mote(at, dir.scale(0.6D), 5, Mote.SPARK, 0.07D, 5);
                    m.drag = 0.7D;
                    m.colour = WHITE;
                    FX.motes.add(m);
                }
            }
            case TangRules.STARS -> {
                if (p.a() == 0) {
                    sound(at, SoundEvents.PLAYER_ATTACK_STRONG, 1.0F, 1.6F);
                    if (own) {
                        SpeedLines.radial(0.5F, 0.5F, 0.25F, 3, SpeedLines.WHITE);
                    }
                }
                if (layer >= 1) {
                    // Рукав раскрывается: лента ветра по дуге вылета (d4-02).
                    wind(at, dir.scale(0.3D), 10, 0.07D);
                }
            }
            case TangRules.THREE -> {
                if (p.a() == 0) {
                    // «Фат!» — один хлёст кисти, три лезвия разом.
                    sound(at, SoundEvents.ARROW_SHOOT, 1.0F, 1.3F);
                    if (own) {
                        SpeedLines.directional(0.0F, 0.15F, 2, SpeedLines.WHITE);
                    }
                }
                if (layer >= 1) {
                    wind(at, dir.scale(0.3D), 7, 0.045D);
                }
                if (layer >= 3 && p.a() == 0) {
                    FX.rings.add(new Ring(at.add(dir.scale(0.3D)), dir, clientTicks, 0.15D, 0.4D, 3, 0.035D, WHITE, 0.35D));
                }
            }
            case TangRules.COINS -> {
                // Звон монет в ладони и щелчок — веер уходит.
                sound(at, SoundEvents.CHAIN_PLACE, 0.8F, 1.8F);
                sound(at, SoundEvents.ARROW_SHOOT, 0.6F, 1.6F);
                if (own && layer >= 3) {
                    SpeedLines.directional(0.0F, 0.15F, 2, SpeedLines.WHITE);
                }
            }
            case TangRules.FLASH -> {
                // «Пааа!» — щелчок пальцами; самого лезвия не видно: читается только жест (spec Б).
                sound(at, SoundEvents.PLAYER_ATTACK_SWEEP, 0.9F, 1.9F);
                if (layer >= 1) {
                    FX.bursts.add(new Burst(at.add(dir.scale(0.25D)), clientTicks, 5, 0.35D, 3, RANDOM.nextLong(), false, false));
                }
                if (own) {
                    SpeedLines.directional(0.0F, 0.2F, 2, SpeedLines.WHITE);
                }
            }
            default -> {
                if (p.b() == 2) {
                    // Второй кинжал вдогонку: короткий хлёсткий выброс.
                    sound(at, SoundEvents.TRIDENT_THROW.value(), 1.0F, 1.4F);
                    if (layer >= 1) {
                        wind(at, dir.scale(0.4D), 6, 0.05D);
                    }
                    return;
                }
                sound(at, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 0.5F, 0.5F);
            }
        }
    }

    private static void hit(TangPayload p, boolean own, Minecraft mc) {
        int layer = p.layer();
        Vec3 at = p.pos();
        Vec3 dir = p.dir().lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : p.dir().normalize();
        int strength = p.b();
        boolean main = strength >= 2;
        sound(at, SoundEvents.TRIDENT_HIT, 0.8F, main ? 0.8F : 1.4F);
        if (p.form() == TangRules.FIVE) {
            // «Канг!» по металлу на каждом контакте (гл. 195).
            sound(at, SoundEvents.ANVIL_LAND, 0.18F, 1.8F + 0.04F * strength);
        }
        if (layer <= 0) {
            return;
        }
        // Звёздочка удара: четыре-шесть белых игл из точки.
        int needles = main ? 10 : strength == 1 ? 6 : 4;
        FX.bursts.add(new Burst(at, clientTicks, needles, main ? 1.4D : 0.6D + 0.15D * strength, main ? 7 : 4, RANDOM.nextLong(), false, false));
        // Стальные осколки и искры по ходу удара.
        int shards = main ? 14 : 5;
        for (int i = 0; i < shards; i++) {
            Vec3 v = dir.scale(0.15D + 0.2D * RANDOM.nextDouble()).add(RANDOM.nextGaussian() * 0.18D, RANDOM.nextGaussian() * 0.15D + 0.05D,
                    RANDOM.nextGaussian() * 0.18D);
            Mote m = new Mote(at, v, 7 + RANDOM.nextInt(5), Mote.SHARD, 0.035D + 0.02D * RANDOM.nextDouble(), 4);
            m.drag = 0.84D;
            m.gravity = 0.03D;
            m.colour = i % 3 == 0 ? CYAN : STEEL;
            FX.motes.add(m);
        }
        if (layer >= 3) {
            FX.rings.add(new Ring(at, dir, clientTicks, 0.15D, main ? 1.5D : 0.6D, main ? 9 : 6, main ? 0.07D : 0.04D, PALE, 0.2D));
        }
        float distance = mc.player == null ? 99.0F : (float) mc.player.position().distanceTo(at);
        if (main) {
            // У Пяти Громов импакт-кадра нет (автор 03.10: «импакт фрейм лишний»): пятый — звезда и тряска.
            if (own && (p.form() == TangRules.STARS || p.form() == TangRules.TWELVE)) {
                ImpactFrames.trigger(at);
                SpeedLines.radial(0.5F, 0.5F, 0.5F, 5, SpeedLines.WHITE);
            }
            if (distance < 24.0F) {
                float q = distance < 8.0F ? 1.0F : 1.0F - (distance - 8.0F) / 16.0F;
                CameraShakeHandler.quake(Math.max(q, own ? 0.8F : 0.0F), 14);
            }
            if (p.form() == TangRules.TWELVE) {
                // 12-й: огромная звезда изогнутых лучей с точками на концах (рефы «12th dagger», s11).
                FX.bursts.add(new Burst(at, clientTicks, 12, 5.0D * (layer >= 8 ? 1.25D : 1.0D), 18, RANDOM.nextLong(), true, false));
                smoke(at, 1.3D, 4);
                sound(at, SoundEvents.GENERIC_EXPLODE.value(), 0.6F, 1.5F);
            } else if (p.form() == TangRules.THREE) {
                // Три в один миг: тройная звезда «Канг!»×3 в одной точке.
                for (int i = 0; i < 3; i++) {
                    FX.bursts.add(new Burst(at, clientTicks + i, 7, 1.0D + 0.4D * i, 6, RANDOM.nextLong(), false, false));
                }
                sound(at, SoundEvents.ANVIL_LAND, 0.3F, 1.7F);
                smoke(at, 0.45D, 2);
            } else if (p.form() == TangRules.COINS) {
                // Монета: маленькая бронзовая искра и звон — без дыма (лёгкое оружие).
                // Бронзовое колечко 0,3 блока вместо циановой вспышки (codex 04.10: циан забивал бронзу монет).
                FX.rings.add(new Ring(at, camAxis(at), clientTicks, 0.05D, 0.3D, 4, 0.04D, BRONZE, 0.2D));
                sound(at, SoundEvents.CHAIN_HIT, 0.6F, 1.9F);
            } else if (p.form() == TangRules.FLASH) {
                // Сорванный замах: белый разрыв у самого лица и кольцо.
                // Одна короткая вспышка (codex 03.10: длинный разрыв закрывал срыв замаха).
                FX.rings.add(new Ring(at, camAxis(at), clientTicks, 0.1D, 0.6D, 3, 0.05D, WHITE, 0.2D));
                sound(at, SoundEvents.SHIELD_BLOCK, 0.6F, 1.6F);
            } else if (p.form() == TangRules.STARS) {
                // Белые полосы сходятся в точке у горла (d4-05): клинья наружу.
                FX.bursts.add(new Burst(at, clientTicks, 9, 2.8D, 10, RANDOM.nextLong(), false, false));
                smoke(at, 0.55D, 2);
            } else {
                smoke(at, 0.55D);
            }
        } else if (distance < 14.0F) {
            CameraShakeHandler.quake(0.12F + 0.08F * strength, 3);
        }
    }

    private static void clang(Vec3 at, Vec3 dir, int layer) {
        sound(at, SoundEvents.ANVIL_LAND, 0.12F, 2.0F);
        if (layer <= 0) {
            return;
        }
        for (int i = 0; i < 6; i++) {
            Vec3 v = new Vec3(RANDOM.nextGaussian(), RANDOM.nextGaussian() * 0.6D + 0.4D, RANDOM.nextGaussian()).normalize().scale(0.18D);
            Mote m = new Mote(at, v, 6 + RANDOM.nextInt(4), Mote.SPARK, 0.03D, 3);
            m.drag = 0.82D;
            m.gravity = 0.03D;
            m.colour = i % 2 == 0 ? WHITE : CYAN;
            FX.motes.add(m);
        }
    }

    /** Взрыв ци Тёмного Взрыва (чёрно-белое, гл. 196): тёмный вал, белые рваные лучи, осколки, отпечаток. */
    private static void explode(TangPayload p, boolean own, Minecraft mc) {
        Vec3 at = p.pos();
        boolean main = p.b() >= 2;
        double k = TangRules.density(p.layer()) * 0.6D + 0.4D;
        sound(at, SoundEvents.GENERIC_EXPLODE.value(), 1.0F, 1.2F);
        sound(at, SoundEvents.PLAYER_ATTACK_STRONG, 1.0F, 0.6F);
        // 6–8 белых клиньев, вытянутых вперёд по ходу рывка (codex 03.10).
        FX.bursts.add(new Burst(at.add(p.dir().scale(0.01D)), clientTicks, 7, 3.0D * k, 9, RANDOM.nextLong() ^ 0x5DEECE66DL, false, true));
        FX.rings.add(new Ring(at, camAxis(at), clientTicks, 0.4D, 3.0D * k, 10, 0.1D, WHITE, 0.3D));
        FX.rings.add(new Ring(at, camAxis(at), clientTicks + 2, 0.4D, 4.2D * k, 12, 0.05D, GREY, 0.45D));
        for (int i = 0; i < 26; i++) {
            Vec3 v = new Vec3(RANDOM.nextGaussian(), RANDOM.nextGaussian() * 0.7D + 0.3D, RANDOM.nextGaussian()).normalize()
                    .scale(0.3D + 0.35D * RANDOM.nextDouble());
            Mote m = new Mote(at, v, 9 + RANDOM.nextInt(6), Mote.SHARD, 0.04D + 0.03D * RANDOM.nextDouble(), 5);
            m.drag = 0.86D;
            m.gravity = 0.035D;
            m.colour = i % 3 == 0 ? CYAN : i % 3 == 1 ? WHITE : GREY;
            FX.motes.add(m);
        }
        // Автор 03.10 «чуть странновато» → codex: дым не должен глушить вспышку — меньше, реже, короче.
        smokeLight(at, 0.7D * k, 2);
        BlockPos below = BlockPos.containing(at.add(0.0D, -1.5D, 0.0D));
        if (mc.level != null && !mc.level.getBlockState(below).isAir()) {
            FX.scars.add(new Scar(new Vec3(at.x, below.getY() + 1.02D, at.z), clientTicks, RANDOM.nextLong()));
        }
        float distance = mc.player == null ? 99.0F : (float) mc.player.position().distanceTo(at);
        if (main && own) {
            ImpactFrames.trigger(at);
            SpeedLines.radial(0.5F, 0.5F, 0.6F, 6, SpeedLines.WHITE);
            TechniqueCaption.impact(1.0F);
        }
        if (distance < 26.0F) {
            float q = distance < 8.0F ? 1.0F : 1.0F - (distance - 8.0F) / 18.0F;
            CameraShakeHandler.quake(Math.max(q, own ? 0.85F : 0.0F) * (main ? 1.0F : 0.6F), 16);
        }
    }

    /** Короткий дым после взрыва: 6 клубов разного размера, тают за ~0,8 с. */
    private static void smokeLight(Vec3 at, double scale, int lag) {
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2.0D * i / 6 + RANDOM.nextDouble() * 0.5D;
            Vec3 out = new Vec3(Math.cos(a), 0.25D * RANDOM.nextGaussian(), Math.sin(a)).normalize();
            double size = (0.35D + 0.55D * RANDOM.nextDouble()) * scale;
            Puff p = new Puff(at.add(out.scale(0.4D * scale)), out.scale(0.08D + 0.1D * RANDOM.nextDouble()).add(0.0D, 0.02D, 0.0D),
                    14 + RANDOM.nextInt(6), RANDOM.nextInt(16), size, true, 0.55F + 0.3F * RANDOM.nextFloat(), (float) (RANDOM.nextDouble() * 6.28D));
            p.delay = lag + RANDOM.nextInt(2);
            FX.puffs.add(p);
        }
    }

    /** Дым манхвы по факту удара: низкий вал одной массой, через 5 тиков над ним облако. */
    private static void smoke(Vec3 at, double scale) {
        smoke(at, scale, 0);
    }

    /** {@code lag} — задержка дыма в тиках: звезда 12-го сначала читается, потом её накрывает вал. */
    private static void smoke(Vec3 at, double scale, int lag) {
        Minecraft mc = Minecraft.getInstance();
        boolean ground = mc.level != null && !mc.level.getBlockState(BlockPos.containing(at.add(0.0D, -1.6D, 0.0D))).isAir();
        Vec3 base = ground ? new Vec3(at.x, Math.floor(at.y - 1.0D) + 0.3D, at.z) : at;
        int n = (int) (10 + 10 * scale);
        double r = 1.6D * scale;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2.0D * i / n + RANDOM.nextDouble() * 0.3D;
            Vec3 out = new Vec3(Math.cos(a), ground ? 0.0D : RANDOM.nextGaussian() * 0.5D, Math.sin(a)).normalize();
            double size = (0.45D + 0.7D * Math.pow(RANDOM.nextDouble(), 1.5D)) * scale;
            Puff bank = new Puff(base.add(out.scale(r * (0.3D + 0.4D * RANDOM.nextDouble()))).add(0.0D, size * 0.4D, 0.0D),
                    out.scale(0.1D + 0.18D * RANDOM.nextDouble()), 30 + RANDOM.nextInt(14), RANDOM.nextInt(16), size, true,
                    i % 3 == 0 ? 0.5F : 0.78F + 0.12F * RANDOM.nextFloat(), (float) (RANDOM.nextDouble() * 6.28D));
            bank.delay = lag + 1 + RANDOM.nextInt(3);
            FX.puffs.add(bank);
        }
        for (int i = 0; i < n / 2; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2.0D;
            Puff rise = new Puff(base.add(Math.cos(a) * r * 0.3D, 0.6D * scale, Math.sin(a) * r * 0.3D),
                    new Vec3(0.0D, 0.05D + 0.03D * RANDOM.nextDouble(), 0.0D), 36 + RANDOM.nextInt(12), RANDOM.nextInt(16),
                    (0.7D + 0.8D * RANDOM.nextDouble()) * scale, true, 0.7F + 0.15F * RANDOM.nextFloat(), (float) (RANDOM.nextDouble() * 6.28D));
            rise.delay = lag + 5 + RANDOM.nextInt(6);
            FX.puffs.add(rise);
        }
    }

    private static void dust(Vec3 feet, int n, double speed) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getBlockState(BlockPos.containing(feet.add(0.0D, -0.2D, 0.0D))).isAir()) {
            return;
        }
        for (int i = 0; i < n; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2.0D;
            Vec3 out = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
            FX.puffs.add(new Puff(feet.add(out.scale(0.3D)).add(0.0D, 0.1D, 0.0D), out.scale(speed * (0.6D + 0.8D * RANDOM.nextDouble())),
                    16 + RANDOM.nextInt(8), RANDOM.nextInt(16), 0.2D + 0.16D * RANDOM.nextDouble(), false, 0.62F, 0.0F));
        }
    }

    private static void wind(Vec3 at, Vec3 vel, int life, double size) {
        Mote m = new Mote(at, vel, life, Mote.WIND, size, 9);
        m.drag = 0.9D;
        m.turbulence = 0.025D;
        FX.motes.add(m);
    }

    private static void sound(Vec3 at, SoundEvent s, float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.level.playLocalSound(at.x, at.y, at.z, s, SoundSource.PLAYERS, volume, pitch, false);
        }
    }

    /** Ось, смотрящая на камеру: кольцо в плоскости экрана. */
    private static Vec3 camAxis(Vec3 at) {
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Vec3 d = cam.subtract(at);
        return d.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : d.normalize();
    }

    // ------------------------------------------------------------------ тело мастера

    private static Vec3 forward(Entity e) {
        float yaw = e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
        Vec3 f = Vec3.directionFromRotation(0.0F, yaw);
        return new Vec3(f.x, 0.0D, f.z).normalize();
    }

    /** Кисть: позиция кости из слоя анимации, иначе по повороту корпуса. */
    private static Vec3 hand(Entity e, boolean right) {
        if (e instanceof AbstractClientPlayer p) {
            Vec3 b = BoneAnchorLayer.position(p, right ? BoneAnchorLayer.Bone.RIGHT_HAND : BoneAnchorLayer.Bone.LEFT_HAND);
            if (b != null) {
                return b;
            }
        }
        float yaw = e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
        double r = Math.toRadians(yaw);
        Vec3 side = new Vec3(-Math.cos(r), 0.0D, -Math.sin(r)).scale(right ? 0.38D : -0.38D);
        return e.position().add(0.0D, 1.15D, 0.0D).add(forward(e).scale(0.35D)).add(side);
    }

    private static Vec3 shoulder(Entity e, boolean right) {
        float yaw = e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
        double r = Math.toRadians(yaw);
        return e.position().add(0.0D, 1.38D, 0.0D).add(new Vec3(-Math.cos(r), 0.0D, -Math.sin(r)).scale(right ? 0.36D : -0.36D));
    }

    /** Ось диска розетки: вверх и немного вперёд (ладони сверху и снизу). */
    private static Vec3 rosetteNormal(Vec3 f) {
        return new Vec3(0.0D, 0.8D, 0.0D).add(f.scale(0.6D)).normalize();
    }

    /** Центр розетки между ладонями (s01–s02): перед грудью. */
    private static Vec3 rosetteCentre(Entity e) {
        Vec3 r = hand(e, true);
        Vec3 l = hand(e, false);
        Vec3 mid = r.add(l).scale(0.5D);
        Vec3 chest = e.position().add(0.0D, 1.2D, 0.0D).add(forward(e).scale(0.75D));
        return mid.distanceTo(chest) < 0.8D ? mid.lerp(chest, 0.5D) : chest;
    }

    // ------------------------------------------------------------------ тик

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            CASTS.clear();
            TRAILS.clear();
            FX.motes.clear();
            FX.puffs.clear();
            FX.rings.clear();
            FX.bursts.clear();
            FX.scars.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        clientTicks++;
        Iterator<Cast> it = CASTS.iterator();
        while (it.hasNext()) {
            Cast c = it.next();
            Entity e = mc.level.getEntity(c.entityId);
            if (e != null && c.layer > 0) {
                body(c, e, c.t(), mc);
            }
            if (c.t() > TangRules.windup(c.form) + 80) {
                it.remove();
            }
        }
        daggers(mc);
        tickFx();
    }

    /** Подготовка: пыль, ветер у рукавов, выступы у плеч, надпись на выпуске. */
    private static void body(Cast c, Entity e, int t, Minecraft mc) {
        int w = TangRules.windup(c.form);
        Vec3 feet = e.position();
        Vec3 f = forward(e);
        if (t == w && c.form != TangRules.FIVE) {
            dust(feet, c.n(10) + 2, 0.18D);
        }
        if (t == w) {
            for (boolean right : c.form == TangRules.FIVE ? new boolean[0] : new boolean[] {true, false}) {
                Vec3 h = hand(e, right);
                for (int i = 0; i < Math.max(1, c.n(3)); i++) {
                    wind(h, f.scale(0.2D).add(RANDOM.nextGaussian() * 0.1D, 0.05D, RANDOM.nextGaussian() * 0.1D), 10, 0.05D);
                }
            }
            if (c.own() && c.layer >= 3) {
                TechniqueCaption.showScaled(Component.translatable("technique.murim.tang.school"),
                        Component.translatable("technique.murim.tang." + switch (c.form) {
                            case TangRules.FIVE -> "five";
                            case TangRules.TWELVE -> "twelve";
                            case TangRules.STARS -> "stars";
                            case TangRules.THREE -> "three";
                            case TangRules.FLASH -> "flash";
                            case TangRules.RETURN -> "return";
                            case TangRules.COINS -> "coins";
                            default -> "burst";
                        }), 40, 0.5F);
            }
        }
        // Рукава вздуваются: «Тррр» ножей в рукаве, лёгкий ветер.
        if (t == 2) {
            sound(feet, SoundEvents.CHAIN_HIT, 0.35F, 1.8F);
        }
        if (t < w && t % 4 == 1 && c.form != TangRules.FIVE) {
            Vec3 h = hand(e, t % 8 == 1);
            wind(h, new Vec3(RANDOM.nextGaussian() * 0.04D, 0.06D, RANDOM.nextGaussian() * 0.04D), 10, 0.035D);
        }
        if (c.form == TangRules.STARS && c.layer >= 2 && t > 2 && t < w && t % 2 == 0) {
            // Голубые острые выступы у плеч (d4-01): короткие частицы наружу.
            for (boolean right : new boolean[] {true, false}) {
                Vec3 s = shoulder(e, right);
                Vec3 out = s.subtract(feet.add(0.0D, 1.3D, 0.0D)).normalize().add(0.0D, 0.4D, 0.0D).normalize();
                Mote m = new Mote(s, out.scale(0.22D).add(f.scale(0.08D)), 4, Mote.SPARK, 0.06D, 4);
                m.drag = 0.75D;
                m.colour = AURA_BLUE;
                FX.motes.add(m);
            }
        }
        if (c.form == TangRules.TWELVE && c.layer >= 3 && t >= TangRules.ROSETTE_FROM && t < w && t % 3 == 0) {
            // Белые завитки обтекают розетку (s02).
            Vec3 ctr = rosetteCentre(e);
            double a = t * 0.7D;
            Vec3 side = new Vec3(-f.z, 0.0D, f.x);
            Vec3 at = ctr.add(side.scale(Math.cos(a) * 0.55D)).add(0.0D, Math.sin(a) * 0.55D, 0.0D);
            wind(at, side.scale(-Math.sin(a) * 0.14D).add(0.0D, Math.cos(a) * 0.14D, 0.0D), 9, 0.04D);
        }
        if (c.form == TangRules.BURST && t == 10) {
            sound(feet, SoundEvents.TRIDENT_RIPTIDE_1.value(), 0.4F, 0.5F);
        }
    }

    /** Кинжалы в мире: история положений для следов, вихрь пыли под «карпом», искры за 12-м. */
    private static void daggers(Minecraft mc) {
        java.util.Set<Integer> alive = new java.util.HashSet<>();
        for (Entity en : mc.level.entitiesForRendering()) {
            if (!(en instanceof TangDagger d)) {
                continue;
            }
            alive.add(d.getId());
            Deque<Vec3> h = TRAILS.computeIfAbsent(d.getId(), k -> new ArrayDeque<>());
            int mode = d.mode();
            if (d.invisible() && !REVEALED.contains(d.getId()) && !hidden(d)) {
                // Похищение Жизни: лезвие «появилось прямо перед» — вспышка в точке появления.
                REVEALED.add(d.getId());
                if (d.layer() >= 1) {
                    FX.bursts.add(new Burst(d.position(), clientTicks, 6, 0.6D, 4, RANDOM.nextLong(), false, false));
                    FX.rings.add(new Ring(d.position(), camAxis(d.position()), clientTicks, 0.1D, 0.5D, 3, 0.03D, WHITE, 0.0D));
                }
                sound(d.position(), SoundEvents.TRIDENT_RIPTIDE_1.value(), 0.4F, 2.0F);
            }
            if (mode == TangDagger.STAR || mode == TangDagger.HANG || mode == TangDagger.STUCK || hidden(d)) {
                h.clear();
            } else {
                h.addFirst(d.position());
                while (h.size() > trailLength(d)) {
                    h.removeLast();
                }
            }
            int layer = d.layer();
            if (mode == TangDagger.CARP && layer >= 3) {
                // Вихрь пыли у земли под медленным кинжалом (гл. 196: «пыль поднималась»).
                BlockPos below = BlockPos.containing(d.position().add(0.0D, -2.5D, 0.0D));
                BlockPos ground = null;
                for (int y = 0; y < 3; y++) {
                    BlockPos b = BlockPos.containing(d.position().add(0.0D, -0.5D - y, 0.0D));
                    if (!mc.level.getBlockState(b).isAir()) {
                        ground = b;
                        break;
                    }
                }
                if (ground != null) {
                    // Приземный вихрь: отстаёт от кинжала на полблока, к рывку гуще.
                    Vec3 back = d.getDeltaMovement().lengthSqr() > 1.0E-6D ? d.getDeltaMovement().normalize().scale(-0.5D) : Vec3.ZERO;
                    Vec3 c = new Vec3(d.getX() + back.x, ground.getY() + 1.05D, d.getZ() + back.z);
                    int n = d.tickCount > TangRules.CARP_TICKS - 6 ? 3 : 2;
                    for (int i = 0; i < n; i++) {
                        double a = RANDOM.nextDouble() * Math.PI * 2.0D;
                        Mote m = new Mote(c.add(Math.cos(a) * 0.5D, 0.0D, Math.sin(a) * 0.5D), Vec3.ZERO, 10, Mote.DUST_SWIRL, 0.14D, 1);
                        m.centre = c;
                        FX.motes.add(m);
                    }
                }
            }
            if (mode == TangDagger.BURST && layer >= 2 && d.tickCount % 1 == 0) {
                // Спиральные ленты вокруг прямого потока (d2-06).
                Vec3 v = d.getDeltaMovement();
                if (v.lengthSqr() > 1.0E-4D) {
                    Vec3 dir = v.normalize();
                    Vec3 side = TangDaggerRenderer.perpendicular(dir, d.tickCount * 1.3D);
                    wind(d.position().add(side.scale(0.35D)), side.scale(0.06D).subtract(dir.scale(0.05D)), 7, 0.04D);
                }
            }
        }
        TRAILS.keySet().removeIf(id -> !alive.contains(id));
        REVEALED.removeIf(id -> !alive.contains(id));
    }

    private static int trailLength(TangDagger d) {
        return switch (d.form()) {
            case TangRules.FIVE -> 8;
            case TangRules.TWELVE -> 3;
            case TangRules.STARS -> 5;
            // Боковые дуги длиннее: изгиб читается по следу (codex 03.10).
            case TangRules.THREE -> d.index() != 0 ? 12 : 7;
            // След монеты длиннее (7 точек): поворот у стены после рикошета остаётся виден (codex 04.10).
            case TangRules.COINS -> 7;
            case TangRules.FLASH -> 4;
            default -> d.mode() == TangDagger.CARP ? 9 : 6;
        };
    }

    private static void tickFx() {
        Iterator<Mote> mi = FX.motes.iterator();
        while (mi.hasNext()) {
            Mote m = mi.next();
            m.age++;
            if (m.age > m.life) {
                mi.remove();
                continue;
            }
            for (int i = m.trail.length - 1; i > 0; i--) {
                m.trail[i] = m.trail[i - 1];
            }
            m.trail[0] = m.pos;
            m.count = Math.min(m.trail.length, m.count + 1);
            m.prev = m.pos;
            if (m.kind == Mote.DUST_SWIRL && m.centre != null) {
                // Кружит вокруг центра и поднимается невысоко.
                Vec3 r = m.pos.subtract(m.centre);
                double a = Math.atan2(r.z, r.x) + 0.35D;
                double rad = Math.sqrt(r.x * r.x + r.z * r.z) * 0.96D;
                m.pos = new Vec3(m.centre.x + Math.cos(a) * rad, m.pos.y + 0.035D, m.centre.z + Math.sin(a) * rad);
                continue;
            }
            Vec3 v = m.vel;
            if (m.turbulence > 0.0D) {
                double ph = m.age * 0.7D + m.pos.x * 0.9D + m.pos.z * 0.6D;
                v = v.add(Math.sin(ph) * m.turbulence, Math.sin(ph * 1.3D + 1.1D) * m.turbulence * 0.4D, Math.cos(ph * 0.9D) * m.turbulence);
            }
            v = v.scale(m.drag).add(0.0D, -m.gravity, 0.0D);
            m.vel = v;
            m.pos = m.pos.add(v);
        }
        Iterator<Puff> pi = FX.puffs.iterator();
        while (pi.hasNext()) {
            Puff p = pi.next();
            if (p.delay > 0) {
                p.delay--;
                continue;
            }
            p.age++;
            if (p.age > p.life) {
                pi.remove();
                continue;
            }
            p.prev = p.pos;
            p.vel = p.vel.scale(p.smoke ? 0.9D : 0.86D).add(0.0D, p.smoke ? 0.004D : 0.002D, 0.0D);
            p.pos = p.pos.add(p.vel);
        }
        FX.rings.removeIf(r -> clientTicks - r.born() > r.life());
        FX.bursts.removeIf(b -> clientTicks - b.born() > b.life());
        FX.scars.removeIf(s -> clientTicks - s.born() > 70);
        FX.flare.values().removeIf(v -> v < clientTicks);
    }

    // ------------------------------------------------------------------ рендер

    @SubscribeEvent
    static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (CASTS.isEmpty() && TRAILS.isEmpty() && FX.motes.isEmpty() && FX.puffs.isEmpty() && FX.rings.isEmpty()
                && FX.bursts.isEmpty() && FX.scars.isEmpty()) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        ps.pushPose();
        try {
            ps.translate(-camera.x, -camera.y, -camera.z);
            PoseStack.Pose pose = ps.last();
            // Кинжалы в руках: розетка Двенадцати и кинжал на ладони Тёмного Взрыва.
            VertexConsumer solid = buffers.getBuffer(TangDaggerMesh.renderType());
            for (Cast c : CASTS) {
                Entity e = mc.level.getEntity(c.entityId);
                if (e != null) {
                    held(c, e, ps, solid, c.t() + partial);
                }
            }
            buffers.endBatch(TangDaggerMesh.renderType());
            VertexConsumer air = buffers.getBuffer(MurimRenderTypes.airBand());
            for (Cast c : CASTS) {
                Entity e = mc.level.getEntity(c.entityId);
                if (e != null && c.layer >= 3) {
                    rosetteRings(c, e, pose, camera, air, c.t() + partial);
                }
            }
            trails(mc, pose, camera, air, partial);
            stars(mc, pose, camera, air, partial);
            carp(mc, pose, camera, air, partial);
            threads(mc, pose, camera, air, partial);
            returnLines(mc, pose, camera, air, partial);
            rings(pose, camera, air, partial);
            bursts(pose, camera, air, partial);
            motes(pose, camera, air, partial);
            scars(pose, air, partial);
            buffers.endBatch(MurimRenderTypes.airBand());
            puffs(pose, camera, buffers, partial);
            glows(mc, pose, camera, buffers, partial);
        } finally {
            ps.popPose();
        }
    }

    /** Розетка из кинжалов между ладонями (s01–s02, d1-01) и кинжал над ладонью (d1-02, d2-01). */
    private static void held(Cast c, Entity e, PoseStack ps, VertexConsumer v, float t) {
        int w = TangRules.windup(c.form);
        if (t >= w) {
            return;
        }
        Vec3 f = forward(e);
        Vec3 side = new Vec3(-f.z, 0.0D, f.x);
        if (c.form == TangRules.TWELVE && t >= TangRules.ROSETTE_FROM) {
            int n = TangRules.twelveCount(c.layer);
            double fill = Mth.clamp((t - TangRules.ROSETTE_FROM) / (double) (TangRules.ROSETTE_FULL - TangRules.ROSETTE_FROM), 0.0D, 1.0D);
            int shown = (int) Math.ceil(n * fill);
            Vec3 ctr = rosetteCentre(e);
            // Сжатие при натяжении, разгон вращения после показа.
            double squeeze = t > TangRules.ROSETTE_HOLD ? 1.0D - 0.45D * (t - TangRules.ROSETTE_HOLD) / (w - TangRules.ROSETTE_HOLD) : 1.0D;
            double spin = t * (t > TangRules.ROSETTE_FULL ? 0.32D : 0.18D);
            Vec3 normal = rosetteNormal(f);
            Vec3 w2 = normal.cross(side).normalize();
            for (int k = 0; k < shown; k++) {
                double a = spin + k * Math.PI * 2.0D / n;
                // Лезвия радиально, остриём наружу; диск между ладонями (верхняя над нижней, s01–s02)
                // наклонён к небу — читается и сбоку, и сверху.
                Vec3 radial = side.scale(Math.cos(a)).add(w2.scale(Math.sin(a)));
                double born = Mth.clamp((t - TangRules.ROSETTE_FROM - k * (16.0D / n)) / 3.0D, 0.0D, 1.0D);
                Vec3 at = ctr.add(radial.scale(0.2D * squeeze)).add(f.scale(0.05D * Math.sin(a * 2.0D)));
                Vec3 tipDir = radial.add(normal.scale(0.15D)).normalize();
                TangDaggerRenderer.drawModel(ps, v, at, tipDir, a, (float) (0.65D * squeeze * born), 1.0F);
            }
        }
        if (c.form == TangRules.BURST) {
            // Кинжал лежит на ладони 0–10, поднимается и висит без хвата 10–20, начинает извиваться.
            Vec3 palm = hand(e, true);
            double lift = Mth.clamp((t - 10.0D) / 6.0D, 0.0D, 1.0D);
            double writhe = t > 12.0D ? Math.sin(t * 0.9D) * 0.35D * Mth.clamp((t - 12.0D) / 8.0D, 0.0D, 1.0D) : 0.0D;
            Vec3 tip = f.add(side.scale(writhe)).add(0.0D, 0.15D, 0.0D).normalize();
            Vec3 at = palm.add(0.0D, 0.08D + 0.22D * lift, 0.0D).subtract(tip.scale(0.15D));
            TangDaggerRenderer.drawModel(ps, v, at, tip, t * 0.2D, 1.0F, 1.0F);
        }
    }

    /** Разорванные концентрические кольца вокруг розетки (s02) и кольца у кинжала на ладони (d1-01). */
    private static void rosetteRings(Cast c, Entity e, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t) {
        int w = TangRules.windup(c.form);
        if (t >= w + 1) {
            return;
        }
        Vec3 f = forward(e);
        if (c.form == TangRules.TWELVE && t >= TangRules.ROSETTE_FROM) {
            Vec3 ctr = rosetteCentre(e);
            float grow = (float) Mth.clamp((t - TangRules.ROSETTE_FROM) / 8.0D, 0.0D, 1.0D);
            for (int i = 0; i < 3; i++) {
                double r = (0.45D + 0.08D * i) * (t > TangRules.ROSETTE_HOLD ? 1.0D - 0.3D * (t - TangRules.ROSETTE_HOLD) / (w - TangRules.ROSETTE_HOLD) : 1.0D);
                ring(v, pose, camera, ctr, rosetteNormal(f), r, 0.025D - 0.004D * i, (i % 2 == 0 ? 1 : -1) * t * (0.12D + 0.04D * i) + i, 0.25D,
                        0.85F * grow, i == 0 ? WHITE : PALE);
            }
        }
        if (c.form == TangRules.BURST && t >= 12) {
            Vec3 palm = hand(e, true).add(0.0D, 0.3D, 0.0D);
            float grow = (float) Mth.clamp((t - 12.0D) / 6.0D, 0.0D, 1.0D);
            ring(v, pose, camera, palm, f, 0.22D, 0.02D, t * 0.4D, 0.35D, 0.7F * grow, WHITE);
        }
    }

    /** Следы кинжалов: белое ядро, циановая кромка, синий край — по истории положений. */
    private static void trails(Minecraft mc, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Map.Entry<Integer, Deque<Vec3>> en : TRAILS.entrySet()) {
            if (!(mc.level.getEntity(en.getKey()) instanceof TangDagger d) || d.layer() < 2) {
                continue;
            }
            Deque<Vec3> h = en.getValue();
            if (h.size() < 2) {
                continue;
            }
            Vec3 head = new Vec3(Mth.lerp(partial, d.xo, d.getX()), Mth.lerp(partial, d.yo, d.getY()), Mth.lerp(partial, d.zo, d.getZ()));
            // След начинается за рукоятью: силуэт клинка не тонет в свечении.
            head = head.subtract(TangDaggerRenderer.forward(d, partial).scale(0.2D));
            Vec3[] p = new Vec3[h.size() + 1];
            p[0] = head;
            int i = 1;
            for (Vec3 q : h) {
                p[i++] = q;
            }
            // Хвост тянется за остриём: точки истории идут от головы назад.
            int n = p.length;
            double[] w = new double[n];
            float[] a = new float[n];
            boolean dark = d.form() == TangRules.BURST;
            double width = d.sky() ? 0.1D : 0.075D;
            for (int k = 0; k < n; k++) {
                double u = k / (double) (n - 1);
                w[k] = width * (1.0D - u) * (k == 0 ? 0.6D : 1.0D);
                a[k] = (float) (1.0D - u) * near(p[k], camera);
            }
            // Слои (codex 03.10): белое ядро 0,035, циан 0,1, синий край 0,19 блока.
            // Три Лезвия: боковые — «две широкие белые дуги» (spec А), прямое — обычная циановая нить (codex 03.10).
            boolean arc = d.form() == TangRules.THREE && d.index() != 0;
            if (d.form() == TangRules.COINS) {
                // Монета: короткий бронзовый росчерк без циана.
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 1.2D), PlumVfx.scaled(a, 0.45F), BRONZE_DARK);
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.45D), PlumVfx.scaled(a, 0.9F), BRONZE_HI);
                continue;
            }
            if (d.layer() >= 3) {
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 2.5D), PlumVfx.scaled(a, 0.22F), dark ? GREY : arc ? STEEL : EDGE_BLUE);
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 1.35D), PlumVfx.scaled(a, 0.55F), dark ? STEEL : arc ? WHITE : CYAN);
            }
            PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.47D), PlumVfx.scaled(a, 0.95F), CORE);
        }
    }

    /** Висящие звёзды: игольчатый венец вокруг кинжала (s05, d4-03), вспышка перед схождением. */
    private static void stars(Minecraft mc, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Entity en : mc.level.entitiesForRendering()) {
            if (!(en instanceof TangDagger d) || d.form() != TangRules.STARS || d.layer() < 3
                    || d.mode() != TangDagger.STAR && d.mode() != TangDagger.TO_STAR) {
                continue;
            }
            Vec3 at = new Vec3(Mth.lerp(partial, d.xo, d.getX()), Mth.lerp(partial, d.yo, d.getY()), Mth.lerp(partial, d.zo, d.getZ()));
            float age = d.tickCount + partial;
            Integer flareUntil = d.getOwner() != null ? FX.flare.get(d.getOwner().getId()) : null;
            boolean flare = flareUntil != null;
            // Венец 0,35–0,55 блока, во вспышке +20 % (codex 03.10): кинжал в центре читается.
            double size = (d.mode() == TangDagger.STAR ? 0.26D : 0.12D) * (d.layer() >= 8 ? 1.2D : 1.0D) * (flare ? 1.2D : 1.0D);
            Vec3 axis = camera.subtract(at).normalize();
            Vec3 e1 = axis.cross(new Vec3(0.0D, 1.0D, 0.0D));
            e1 = e1.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : e1.normalize();
            Vec3 e2 = axis.cross(e1).normalize();
            int needles = 8;
            for (int k = 0; k < needles; k++) {
                double ang = k * Math.PI * 2.0D / needles + age * 0.02D + 0.15D * Math.sin(k * 2.3D);
                double len = size * (k % 2 == 0 ? 0.95D : 0.55D) * (0.85D + 0.15D * Math.sin(age * 0.4D + k));
                Vec3 dir = e1.scale(Math.cos(ang)).add(e2.scale(Math.sin(ang)));
                Vec3[] q = {at.add(dir.scale(0.06D)), at.add(dir.scale(0.06D + len * 0.5D)), at.add(dir.scale(0.06D + len))};
                double[] w = {0.11D * size, 0.06D * size, 0.0D};
                float al = flare ? 1.0F : 0.85F;
                PlumVfx.strip(v, pose, camera, q, PlumVfx.scale(w, 2.2D), 0.25F * al, CROWN_RIM);
                PlumVfx.strip(v, pose, camera, q, w, al, k % 2 == 0 ? WHITE : CROWN);
            }
        }
    }

    /** Конус вращающихся колец позади острия «карпа» (s09): шире к рывку — телеграф. */
    private static void carp(Minecraft mc, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Entity en : mc.level.entitiesForRendering()) {
            if (!(en instanceof TangDagger d) || d.mode() != TangDagger.CARP || d.layer() < 3) {
                continue;
            }
            Vec3 at = new Vec3(Mth.lerp(partial, d.xo, d.getX()), Mth.lerp(partial, d.yo, d.getY()), Mth.lerp(partial, d.zo, d.getZ()));
            Vec3 f = TangDaggerRenderer.forward(d, partial);
            float age = d.tickCount + partial;
            double grow = Mth.clamp(age / (double) TangRules.CARP_TICKS, 0.15D, 1.0D);
            // Конус s09 (codex 03.10): 4–5 колец на 1,6 блока, радиус от 0,12 у острия до 0,6 сзади.
            int rings = d.layer() >= 7 ? 5 : 4;
            for (int i = 0; i < rings; i++) {
                Vec3 c = at.subtract(f.scale(0.1D + 0.38D * i));
                // Кольца не крупнее 1–1,5 длины клинка и тусклее: оружие главнее спиралей (codex 03.10).
                double r = (0.08D + 0.09D * i) * (0.7D + 0.5D * grow);
                ring(v, pose, camera, c, f, r, 0.016D + 0.004D * i, age * (0.28D - 0.03D * i) + i * 1.7D, 0.42D, 0.5F - 0.08F * i,
                        i == 0 ? WHITE : PALE);
            }
        }
    }

    /** Подготовка «Возврата»: тонкие линии от каждого воткнутого лезвия к рукаву — телеграф линий (spec В). */
    private static void returnLines(Minecraft mc, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Cast c : CASTS) {
            if (c.form != TangRules.RETURN || c.t() > TangRules.windup(TangRules.RETURN) + 2 || c.layer < 1
                    || !(mc.level.getEntity(c.entityId) instanceof Entity owner)) {
                continue;
            }
            float grow = (float) Mth.clamp((c.t() + partial) / TangRules.windup(TangRules.RETURN), 0.0D, 1.0D);
            Vec3 hand = hand(owner, true);
            for (Entity en : mc.level.entitiesForRendering()) {
                if (!(en instanceof TangDagger d) || d.mode() != TangDagger.STUCK || d.getOwner() != owner
                        || d.distanceTo(owner) > TangRules.RETURN_RANGE) {
                    continue;
                }
                Vec3[] p = {d.position(), d.position().lerp(hand, 0.5D), hand};
                float a = 0.45F * grow * near(p[0], camera);
                PlumVfx.strip(v, pose, camera, p, new double[] {0.02D, 0.012D, 0.004D}, a, PALE);
            }
        }
    }

    /** Нити ци от рукава мастера к звёздам и к зависшему кинжалу (слой 5+). */
    private static void threads(Minecraft mc, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Entity en : mc.level.entitiesForRendering()) {
            if (!(en instanceof TangDagger d) || d.layer() < 5 || !(d.getOwner() instanceof Entity owner)) {
                continue;
            }
            boolean star = d.form() == TangRules.STARS && (d.mode() == TangDagger.STAR || d.mode() == TangDagger.TO_STAR);
            boolean hang = d.form() == TangRules.BURST && (d.mode() == TangDagger.HANG || d.mode() == TangDagger.RECALL);
            if (!star && !hang) {
                continue;
            }
            Vec3 at = new Vec3(Mth.lerp(partial, d.xo, d.getX()), Mth.lerp(partial, d.yo, d.getY()), Mth.lerp(partial, d.zo, d.getZ()));
            Vec3 from = hand(owner, d.index() % 2 == 0);
            int n = 12;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            float[] a = new float[n + 1];
            double sag = 0.25D * at.distanceTo(from) / 8.0D;
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                p[i] = from.lerp(at, u).add(0.0D, -sag * Math.sin(Math.PI * u) + 0.03D * Math.sin(u * 20.0D + d.tickCount * 0.5D), 0.0D);
                w[i] = hang ? 0.025D : 0.012D;
                a[i] = (hang ? 0.45F : 0.3F) * near(p[i], camera);
            }
            PlumVfx.stripVar(v, pose, camera, p, w, a, hang ? STEEL : PALE);
        }
    }

    private static void rings(PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Ring r : FX.rings) {
            float age = clientTicks - r.born() + partial;
            if (age < 0.0F) {
                continue;
            }
            float k = Mth.clamp(age / r.life(), 0.0F, 1.0F);
            double rad = r.r0() + (r.r1() - r.r0()) * (1.0D - Math.pow(1.0D - k, 3.0D));
            float a = (1.0F - k) * (1.0F - k);
            ring(v, pose, camera, r.centre(), r.axis(), rad, r.width() * (1.0D - 0.5D * k), r.born() * 0.7D, r.gap(), a, r.col());
        }
    }

    /** Кольцо с разрывами: {@code gap} — доля окружности в разрывах (рефы: «вложенные, местами разорванные»). */
    private static void ring(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Vec3 centre, Vec3 axis, double r, double width,
                             double phase, double gap, float alpha, VfxColour col) {
        if (alpha <= 0.01F) {
            return;
        }
        Vec3 ax = axis.normalize();
        Vec3 e1 = ax.cross(Math.abs(ax.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        Vec3 e2 = ax.cross(e1).normalize();
        int arcs = gap > 0.0D ? 3 : 1;
        for (int s = 0; s < arcs; s++) {
            double from = phase + s * Math.PI * 2.0D / arcs;
            double span = Math.PI * 2.0D / arcs * (1.0D - gap) * (0.75D + 0.25D * Math.sin(s * 1.7D + phase));
            int n = 14;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            float[] a = new float[n + 1];
            for (int i = 0; i <= n; i++) {
                double ang = from + span * i / n;
                p[i] = centre.add(e1.scale(Math.cos(ang) * r)).add(e2.scale(Math.sin(ang) * r));
                // Заострённые концы дуги.
                w[i] = width * Math.sin(Math.PI * Math.min(1.0D, (i + 0.5D) / (n + 1.0D)));
                a[i] = alpha * near(p[i], camera);
            }
            PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 2.4D), PlumVfx.scaled(a, 0.25F), col == WHITE ? PALE : CROWN_RIM);
            PlumVfx.stripVar(v, pose, camera, p, w, a, col);
        }
    }

    /** Звёзды лучей: короткие иглы удара, изогнутые лучи 12-го с точкой на конце, рваные клинья взрыва. */
    private static void bursts(PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Burst b : FX.bursts) {
            float age = clientTicks - b.born() + partial;
            if (age < 0.0F) {
                continue;
            }
            float k = Mth.clamp(age / b.life(), 0.0F, 1.0F);
            float a = (float) PlumVfx.curve(k, 0.0, 1.0, 0.25, 1.0, 1.0, 0.0);
            Random r = new Random(b.seed());
            double grow = 1.0D - Math.pow(1.0D - Mth.clamp(age / 3.0D, 0.0D, 1.0D), 3.0D);
            for (int i = 0; i < b.rays(); i++) {
                Vec3 dir = new Vec3(r.nextGaussian(), r.nextGaussian() * 0.8D, r.nextGaussian()).normalize();
                double len = b.len() * (0.45D + 0.75D * r.nextDouble()) * grow;
                if (b.curved()) {
                    // Изогнутый луч (рефы 12th dagger, s11; codex 03.10): ровно через 30° в плоскости экрана,
                    // каждый загибается в одну сторону на ~25°, на конце и посередине — светлые точки.
                    Vec3[] ray = curvedRay(b, i, grow, camera);
                    Vec3[] p = ray;
                    int n = p.length - 1;
                    double[] w = new double[n + 1];
                    for (int j = 0; j <= n; j++) {
                        double u = j / (double) n;
                        w[j] = 0.06D * (1.0D - u) + 0.015D;
                    }
                    float[] al = near(p, PlumVfx.filled(n + 1, a), camera);
                    PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 2.4D), PlumVfx.scaled(al, 0.3F), CROWN_RIM);
                    PlumVfx.stripVar(v, pose, camera, p, w, al, i % 3 == 0 ? CROWN : WHITE);
                    continue;
                }
                Vec3 base = b.centre().add(dir.scale(0.08D));
                Vec3[] p = {base, base.add(dir.scale(len * 0.45D)), base.add(dir.scale(len))};
                double w0 = b.dark() ? 0.12D : 0.05D;
                double[] w = {w0, w0 * 0.55D, 0.0D};
                float[] al = near(p, PlumVfx.filled(3, a), camera);
                if (b.dark()) {
                    PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 1.8D), PlumVfx.scaled(al, 0.7F), INK);
                }
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 2.2D), PlumVfx.scaled(al, 0.22F), b.dark() ? GREY : PALE);
                PlumVfx.stripVar(v, pose, camera, p, w, al, WHITE);
            }
        }
    }

    /** Точки изогнутого луча {@code i} звезды 12-го: веер через 360/лучей в плоскости экрана, загиб ~25°. */
    private static Vec3[] curvedRay(Burst b, int i, double grow, Vec3 camera) {
        Vec3 axis = camera.subtract(b.centre()).normalize();
        Vec3 e1 = axis.cross(new Vec3(0.0D, 1.0D, 0.0D));
        e1 = e1.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : e1.normalize();
        Vec3 e2 = axis.cross(e1).normalize();
        Random r = new Random(b.seed() + i * 31L);
        double a0 = i * Math.PI * 2.0D / b.rays() + (r.nextDouble() - 0.5D) * 0.15D;
        double len = b.len() * (0.75D + 0.35D * r.nextDouble()) * grow;
        double bend = Math.toRadians(20.0D + 15.0D * r.nextDouble());
        int n = 10;
        Vec3[] p = new Vec3[n + 1];
        for (int j = 0; j <= n; j++) {
            double u = j / (double) n;
            double a = a0 + bend * u * u;
            p[j] = b.centre().add(e1.scale(Math.cos(a) * len * u)).add(e2.scale(Math.sin(a) * len * u))
                    .add(axis.scale(-0.4D * Math.sin(Math.PI * u) * (r.nextDouble() - 0.5D)));
        }
        return p;
    }

    private static void motes(PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Mote m : FX.motes) {
            if (m.kind == Mote.DUST_SWIRL) {
                continue;
            }
            int n = Math.max(1, m.count);
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            p[0] = m.prev.lerp(m.pos, partial);
            for (int i = 1; i <= n; i++) {
                p[i] = m.trail[i - 1] != null ? m.trail[i - 1] : p[0];
            }
            for (int i = 0; i <= n; i++) {
                w[i] = m.size * Math.sin(Math.PI * Math.min(1.0D, 0.1D + i / (double) n * 0.9D));
            }
            float life = (m.age + partial) / m.life;
            float a = (float) PlumVfx.curve(life, 0.0, 0.3, 0.12, 1.0, 0.6, 0.8, 1.0, 0.0);
            float[] al = near(p, PlumVfx.filled(n + 1, a), camera);
            switch (m.kind) {
                case Mote.WIND -> {
                    PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 2.0D), PlumVfx.scaled(al, 0.1F), PALE);
                    PlumVfx.stripVar(v, pose, camera, p, w, PlumVfx.scaled(al, 0.4F), STEEL);
                    PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.25D), PlumVfx.scaled(al, 0.85F), WHITE);
                }
                case Mote.POISON -> {
                    PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 2.2D), PlumVfx.scaled(al, 0.35F), POISON);
                    PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.6D), PlumVfx.scaled(al, 0.8F), POISON_PALE);
                }
                default -> {
                    PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 2.0D), PlumVfx.scaled(al, 0.25F), m.colour == STEEL ? PALE : m.colour);
                    PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.5D), al, m.colour == AURA_BLUE ? PALE : WHITE);
                }
            }
        }
    }

    /** Отпечаток взрыва: тёмные штрихи-звезда на земле, тают за 3,5 с. */
    private static void scars(PoseStack.Pose pose, VertexConsumer v, float partial) {
        for (Scar s : FX.scars) {
            float age = clientTicks - s.born() + partial;
            float a = (float) PlumVfx.curve(age, 0.0, 0.0, 2.0, 0.9, 40.0, 0.6, 70.0, 0.0);
            Random r = new Random(s.seed());
            for (int i = 0; i < 9; i++) {
                double ang = i * Math.PI * 2.0D / 9.0D + r.nextDouble() * 0.4D;
                double len = 0.8D + 1.6D * r.nextDouble();
                Vec3 d = new Vec3(Math.cos(ang), 0.0D, Math.sin(ang));
                Vec3[] p = {s.centre().add(d.scale(0.2D)), s.centre().add(d.scale(len * 0.5D)), s.centre().add(d.scale(len))};
                double[] w = {0.12D, 0.07D, 0.0D};
                PlumVfx.flatStrip(v, pose, p, w, a, age < 3.0F ? WHITE : INK);
            }
        }
    }

    private static void puffs(PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial) {
        if (FX.puffs.isEmpty() && FX.motes.stream().noneMatch(m -> m.kind == Mote.DUST_SWIRL)) {
            return;
        }
        RenderType dust = MurimRenderTypes.dustPuffs();
        VertexConsumer d = buffers.getBuffer(dust);
        for (Puff p : FX.puffs) {
            if (p.smoke || p.delay > 0) {
                continue;
            }
            float pt = (p.age + partial) / p.life;
            float alpha = pt < 0.5F ? 1.0F : Mth.clamp(1.0F - (pt - 0.5F) / 0.5F, 0.0F, 1.0F);
            PlumVfx.puff(d, pose, camera, p.prev.lerp(p.pos, partial), p.size * (0.7D + 0.8D * pt), p.cell, alpha, p.gray);
        }
        for (Mote m : FX.motes) {
            if (m.kind == Mote.DUST_SWIRL) {
                float pt = (m.age + partial) / m.life;
                PlumVfx.puff(d, pose, camera, m.prev.lerp(m.pos, partial), m.size * (0.7D + 0.6D * pt), m.age % 16,
                        Mth.clamp(1.0F - pt, 0.0F, 1.0F), 0.6F);
            }
        }
        buffers.endBatch(dust);
        RenderType smokeType = MurimRenderTypes.smokeCel();
        VertexConsumer sm = buffers.getBuffer(smokeType);
        for (Puff p : FX.puffs) {
            if (!p.smoke || p.delay > 0) {
                continue;
            }
            float pt = (p.age + partial) / p.life;
            float alpha = Mth.clamp(pt / 0.06F, 0.0F, 1.0F) * (pt < 0.75F ? 1.0F : Mth.clamp(1.0F - (pt - 0.75F) / 0.25F, 0.0F, 1.0F));
            double grow = 0.55D + 0.8D * Math.sqrt(pt) + (pt > 0.75F ? 1.2D * (pt - 0.75D) : 0.0D);
            Vec3 at = p.prev.lerp(p.pos, partial);
            float cam = (float) Mth.clamp((at.distanceTo(camera) - p.size * grow - 0.6D) / 1.5D, 0.0D, 1.0D);
            PlumVfx.smokePuff(sm, pose, camera, at, p.size * grow, p.cell, alpha * cam, p.gray, p.spin);
        }
        buffers.endBatch(smokeType);
    }

    /** Свечение: циановая оболочка кинжала (слой 3+), сердца звёзд, вспышки ударов, «солнце» за 12-м. */
    private static void glows(Minecraft mc, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial) {
        RenderType gt = MurimRenderTypes.mote();
        VertexConsumer g = buffers.getBuffer(gt);
        for (Entity en : mc.level.entitiesForRendering()) {
            if (!(en instanceof TangDagger d) || d.layer() < 3) {
                continue;
            }
            Vec3 at = new Vec3(Mth.lerp(partial, d.xo, d.getX()), Mth.lerp(partial, d.yo, d.getY()), Mth.lerp(partial, d.zo, d.getZ()));
            float n = near(at, camera);
            boolean dark = d.form() == TangRules.BURST;
            int mode = d.mode();
            if (mode == TangDagger.FALL || hidden(d)) {
                continue;
            }
            if (mode == TangDagger.STUCK) {
                // Воткнутое лезвие поблёскивает: враг видит, где пройдут линии «Возврата».
                float pulse = (float) Math.max(0.0D, Math.sin((d.tickCount + partial) * 0.25D + d.getId()));
                PlumVfx.glow(g, pose, camera, at, 0.07D + 0.05D * pulse, (0.25F + 0.5F * pulse) * n, WHITE);
                continue;
            }
            if (mode == TangDagger.STAR || mode == TangDagger.TO_STAR) {
                // Сердце звезды: белое ядро и циановый венец-ореол (s05).
                Integer fl = d.getOwner() != null ? FX.flare.get(d.getOwner().getId()) : null;
                double core = fl != null ? 0.12D : 0.08D;
                PlumVfx.glow(g, pose, camera, at, core, 0.95F * n, WHITE);
                PlumVfx.glow(g, pose, camera, at, 0.25D, 0.35F * n, CROWN);
                PlumVfx.glow(g, pose, camera, at, 0.5D, 0.1F * n, CROWN_RIM);
                continue;
            }
            // Оболочка вдоль клинка: белое у клинка, циан снаружи (d1-02, d2-04); у Тёмного — белое и серое.
            Vec3 f = TangDaggerRenderer.forward(d, partial);
            float age = d.tickCount + partial;
            for (int i = 0; i < 3; i++) {
                // Оболочка вдоль клинка, без острия: последние 0,2 блока у носа — чистый металл.
                Vec3 p = at.add(f.scale(-0.12D + 0.1D * i));
                double wob = 0.02D * Math.sin(age * 1.3D + i);
                PlumVfx.glow(g, pose, camera, p, 0.08D + wob, 0.35F * n, CORE);
                PlumVfx.glow(g, pose, camera, p, 0.18D + wob, 0.2F * n, dark ? GREY : CYAN);
            }
        }
        for (Burst b : FX.bursts) {
            float age = clientTicks - b.born() + partial;
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 2.0, 0.9, b.life(), 0.0);
            if (a <= 0.0F) {
                continue;
            }
            double s = b.curved() ? 0.4D : b.dark() ? 0.9D : b.rays() > 6 ? 0.45D : 0.22D;
            PlumVfx.glow(g, pose, camera, b.centre(), s, a * near(b.centre(), camera), WHITE);
            PlumVfx.glow(g, pose, camera, b.centre(), s * 2.4D, 0.35F * a * near(b.centre(), camera), b.dark() ? GREY : CROWN);
            if (b.curved()) {
                // Светлые точки на концах и вдоль изогнутых лучей (2–3 на луч).
                double grow = 1.0D - Math.pow(1.0D - Mth.clamp(age / 3.0D, 0.0D, 1.0D), 3.0D);
                for (int i = 0; i < b.rays(); i++) {
                    Vec3[] ray = curvedRay(b, i, grow, camera);
                    for (int j : new int[] {4, 7, 10}) {
                        PlumVfx.glow(g, pose, camera, ray[j], j == 10 ? 0.16D : 0.09D, a * near(ray[j], camera), j == 10 ? WHITE : CROWN);
                    }
                }
            }
        }
        buffers.endBatch(gt);
    }

    /**
     * Похищение Жизни: невидимое лезвие не рисуется, пока до горла цели дальше {@link TangRules#FLASH_REVEAL}
     * (без цели — первые 4 тика).
     */
    static boolean hidden(TangDagger d) {
        if (!d.invisible() || d.mode() != TangDagger.STRAIGHT) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (d.targetId() >= 0 && mc.level != null && mc.level.getEntity(d.targetId()) instanceof LivingEntity t) {
            Vec3 throat = t.position().add(0.0D, t.getBbHeight() * 0.72D, 0.0D);
            return d.position().distanceTo(throat) > TangRules.FLASH_REVEAL;
        }
        return d.tickCount < 4;
    }

    /** Вблизи камеры тает: от первого лица ничего не закрывает экран. */
    private static float near(Vec3 at, Vec3 camera) {
        return (float) Mth.clamp((at.distanceTo(camera) - 0.9D) / 1.2D, 0.0D, 1.0D);
    }

    private static float[] near(Vec3[] p, float[] a, Vec3 camera) {
        float[] r = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i] * near(p[i], camera);
        }
        return r;
    }

    private TangVfx() {
    }
}
