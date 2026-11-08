package io.github.verycooltimo.murim.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniquePhase;
import io.github.verycooltimo.murim.mastery.ArtSlots;
import io.github.verycooltimo.murim.mastery.Arts;
import io.github.verycooltimo.murim.mastery.EchoKata;
import io.github.verycooltimo.murim.mastery.EchoWeapon;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.network.SyncMasteryPayload;
import io.github.verycooltimo.murim.network.SyncMeditationPayload;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.util.TriState;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * «Эхо» — занятие медитации после семени (hua-main-1810 §1): перед игроком его двойник повторяет приёмы ПОСЛЕДНЕГО боя
 * выбранного искусства против тени врага (docs/design/19 §3, решение автора 29.09 — «полупрозрачные образы, как будто
 * осмысление»; 18.10 — двойник и тень). Что осмысливается, решает сервер: искусство в руке при посадке и список приёмов
 * приходят в {@link SyncMeditationPayload}; здесь только картинка.
 *
 * <p>Двойник и тень — клиентские копии игрока ({@link RemotePlayer}), которых нет в мире: их тикаем и рисуем сами.
 * Анимацию техники двойнику даёт та же библиотека, что и игроку, — слой создаётся для любого клиентского игрока, и приём
 * повторяется кадр в кадр. Тень врага стоит напротив, лицом к двойнику, без урона: в момент удара её отбрасывает и она
 * вспыхивает.
 *
 * <p>Чёткость двойника — мастерство искусства (мутный на первых звёздах, резкий на последних); числа игроку не показываются.
 * Рисуется аддитивно, как светящиеся глаза паука: тёмное в скине исчезает, светлое светится.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class MeditationEcho {

    /** Pause after the last move of a kata, ticks: the image dissolves, then a new kata appears. */
    private static final int PAUSE_TICKS = 40;

    /**
     * Дуэль стоит впереди и справа от игрока по диагонали: двойник ближе, тень дальше и правее. Прямо перед игроком его
     * закрывало бы собственное тело и камера за спиной (кадры 30.09 и 19.10), а пара поперёк взгляда сливалась с мишенями стенда;
     * по диагонали оба видны и от первого лица (двойник справа-впереди, тень за ним), и из-за плеча.
     */
    private static final double FIGHTER_AHEAD = 2.5D;
    private static final double FIGHTER_SIDE = 1.5D;
    private static final double FOE_AHEAD = 3.9D;
    private static final double FOE_SIDE = 3.0D;

    /** Тень отлетает от удара на столько блоков и возвращается за столько тиков. */
    private static final double RECOIL = 0.55D;
    private static final int RECOIL_TICKS = 16;

    /** Приём озарения, который двойник покажет немедленно. */
    private static ResourceLocation forced;
    private static int insightTicks;

    private static Figure fighter;
    private static Figure foe;
    private static ResourceLocation playing;
    private static int cycleTick;
    private static int memoryIndex;
    private static int sinceImpact = 1000;
    private static boolean broken;

    /** The place of the duel is fixed in the world when the double appears: the camera may walk around it (author 22.10). */
    private static Vec3 anchor;
    private static float anchorYaw;
    /** The kata being shown, the position of the current move in it, and the seed that orders it. */
    private static List<ResourceLocation> kata = List.of();
    private static int kataPos;
    private static long kataSeed;
    private static int gapTicks = PAUSE_TICKS;
    private static EchoWeapon.Kind weapon = EchoWeapon.Kind.EMPTY;

    /** What the double holds, for tests and capture. */
    public static EchoWeapon.Kind weapon() {
        return weapon;
    }

    /**
     * Озарение в медитации: двойник сразу и ярко выполняет этот приём.
     */
    public static void insight(ResourceLocation technique) {
        forced = technique;
        insightTicks = InsightEffects.TICKS;
        cycleTick = 0;
        playing = null;
    }

    /** Двойник есть и виден — для съёмки и отладки. */
    public static boolean visible() {
        return fighter != null;
    }

    /** Тень врага есть и видна — для съёмки и тестов. */
    public static boolean foeVisible() {
        return foe != null;
    }

    /** Приём, который двойник показывает сейчас, или {@code null}. */
    public static ResourceLocation current() {
        return playing;
    }

    /** Сила проявления образа 0..1: проявляется, держится на приёме, растворяется. */
    public static float presence(float partial) {
        if (fighter == null || playing == null) {
            return 0.0F;
        }
        TechniqueDefinition definition = TechniqueLoader.get(playing);
        int length = definition == null ? 60 : definition.totalTicks();
        float t = cycleTick + partial;
        // The image appears at the start of a kata and dissolves at its end; between the moves it stays.
        float in = kataPos <= 1 ? Mth.clamp(t / 12.0F, 0.0F, 1.0F) : 1.0F;
        boolean last = kataPos >= kata.size();
        float out = last ? Mth.clamp((length + gapTicks - t) / 16.0F, 0.0F, 1.0F) : 1.0F;
        return in * out;
    }

    /**
     * Чёткость двойника 0..1 — мастерство искусства: от 0,55 («размыт») до 1 («резок»). Без чисел для игрока.
     */
    public static float clarity(ResourceLocation art) {
        if (art == null) {
            return 0.6F;
        }
        ResourceLocation target = MasteryState.masteryId(art);
        for (SyncMasteryPayload.Entry entry : ClientMasteryState.entries()) {
            if (MasteryState.masteryId(entry.technique()).equals(target) && entry.cap() > 0) {
                int cap = io.github.verycooltimo.murim.mastery.Arts.cap(entry.technique());
                return Mth.lerp(Mth.clamp(entry.stars() / (float) Math.max(1, cap), 0.0F, 1.0F), 0.55F, 1.0F);
            }
        }
        return 0.6F;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.isPaused()) {
            return;
        }
        SyncMeditationPayload state = ClientMeditationState.state();
        if (insightTicks > 0) {
            insightTicks--;
        }
        List<ResourceLocation> moves = state.moves();
        // Двойник идёт только в Эхо: искусство выбрано при посадке, а не очередью «что накопилось».
        boolean wanted = !broken && state.active() && state.beats() >= 3
                && (state.echo().isPresent() && !moves.isEmpty() || insightTicks > 0 || playing != null && cycleTick > 0 && state.echo().isPresent())
                && ClientMeditationState.sessionTicks() > 30;
        if (!wanted) {
            fighter = null;
            foe = null;
            playing = null;
            anchor = null;
            kata = List.of();
            kataPos = 0;
            return;
        }
        if (fighter == null || fighter.level() != minecraft.level) {
            fighter = new Figure(minecraft.level, player.getGameProfile().getName(), player);
            foe = new Figure(minecraft.level, "shadow", null);
            cycleTick = 0;
            playing = null;
            sinceImpact = 1000;
            anchor = player.position();
            anchorYaw = player.getYRot();
            kata = List.of();
            kataPos = 0;
            kataSeed = player.getUUID().getLeastSignificantBits() ^ minecraft.level.getGameTime();
        }
        place();
        try {
            // Библиотека анимаций двигает кадры в тике клиентского игрока.
            fighter.tick();
            foe.tick();
        } catch (RuntimeException exception) {
            MurimMod.LOGGER.error("Двойник медитации сломался и отключён до перезахода", exception);
            broken = true;
            fighter = null;
            foe = null;
            return;
        }
        sinceImpact++;
        place();

        TechniqueDefinition definition = playing == null ? null : TechniqueLoader.get(playing);
        int length = definition == null ? 0 : definition.totalTicks();
        if (playing == null || ++cycleTick >= length + gapTicks) {
            // A kata of the art: the last fight, the other known forms woven in, the stance first; insight goes out of turn.
            if (forced != null) {
                playing = forced;
                forced = null;
            } else if (!moves.isEmpty() || !kata.isEmpty()) {
                if (kataPos >= kata.size()) {
                    kataSeed++;
                    kata = buildKata(state.echo().orElse(null), moves, kataSeed);
                    kataPos = 0;
                    weapon = EchoWeapon.of(kata);
                    equip(player);
                }
                playing = kata.get(kataPos++);
                gapTicks = kataPos >= kata.size() ? PAUSE_TICKS : EchoKata.gap(kataPos - 1, kataSeed);
            } else {
                fighter = null;
                foe = null;
                playing = null;
                return;
            }
            cycleTick = 0;
            TechniqueDefinition next = TechniqueLoader.get(playing);
            if (next != null) {
                MurimPlayerAnimations.play(fighter, next.animation());
            }
            definition = next;
        }
        // Удар двойника: с начала активных кадров тень отлетает и вспыхивает; промах техники тоже виден — тень не двигается.
        if (definition != null && cycleTick == definition.startTickOf(TechniquePhase.IMPACT)) {
            sinceImpact = 0;
        }
    }

    /**
     * The pair stands in WORLD space, where the player looked when the double appeared; turning the camera does not move
     * it, so the player can look at it from any side. The shadow is thrown back by the blow along the line of the duel.
     */
    private static void place() {
        Vec3 forward = Vec3.directionFromRotation(0.0F, anchorYaw);
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        Vec3 fighterSpot = anchor.add(forward.scale(FIGHTER_AHEAD)).add(right.scale(FIGHTER_SIDE));
        Vec3 foeBase = anchor.add(forward.scale(FOE_AHEAD)).add(right.scale(FOE_SIDE));
        Vec3 foeSpot = foeBase.add(foeBase.subtract(fighterSpot).normalize().scale(recoil()));
        face(fighter, fighterSpot, foeSpot, anchor.y);
        face(foe, foeSpot, fighterSpot, anchor.y);
    }

    /** The world position of the double, or {@code null}: for tests and capture. */
    public static Vec3 fighterPosition() {
        return fighter == null ? null : fighter.position();
    }

    static List<ResourceLocation> buildKata(ResourceLocation art, List<ResourceLocation> recorded, long seed) {
        List<ResourceLocation> known = new java.util.ArrayList<>();
        ResourceLocation opening = null;
        if (art != null) {
            ResourceLocation target = MasteryState.masteryId(art);
            for (SyncMasteryPayload.Entry entry : ClientMasteryState.entries()) {
                ResourceLocation form = entry.technique();
                TechniqueDefinition definition = TechniqueLoader.get(form);
                if (definition == null || Arts.isMethod(form) || !MasteryState.masteryId(form).equals(target)) {
                    continue;
                }
                ArtSlots.Kind kind = ArtSlots.kind(form);
                if (kind == ArtSlots.Kind.STEPS || kind == ArtSlots.Kind.BODY_PROTECTION || kind == ArtSlots.Kind.BREATHING) {
                    continue;
                }
                if (definition.foundation()) {
                    opening = form;
                } else {
                    known.add(form);
                }
            }
        }
        return EchoKata.build(recorded, known, opening, seed);
    }

    /** Puts the weapon of the art in the hands of the double: the player's own sword, daggers, or nothing. */
    private static void equip(LocalPlayer player) {
        net.minecraft.world.item.ItemStack main = net.minecraft.world.item.ItemStack.EMPTY;
        net.minecraft.world.item.ItemStack off = net.minecraft.world.item.ItemStack.EMPTY;
        switch (weapon) {
            case SWORD -> {
                for (net.minecraft.world.item.ItemStack stack : player.getInventory().items) {
                    if (io.github.verycooltimo.murim.combat.QiSword.holdsSword(stack)) {
                        main = stack.copyWithCount(1);
                        break;
                    }
                }
                if (main.isEmpty()) {
                    main = new net.minecraft.world.item.ItemStack(io.github.verycooltimo.murim.registry.ModItems.HUASHAN_SWORD.get());
                }
            }
            case DAGGERS -> {
                main = new net.minecraft.world.item.ItemStack(io.github.verycooltimo.murim.registry.ModItems.TANG_DAGGER.get());
                off = main.copy();
            }
            default -> {
            }
        }
        fighter.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, main);
        fighter.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, off);
    }

    /** How strongly the blow is in the air, 0..1: from a little before the active frames to a little after them. */
    private static float swing(float partial) {
        TechniqueDefinition definition = playing == null ? null : TechniqueLoader.get(playing);
        if (definition == null) {
            return 0.0F;
        }
        float t = cycleTick + partial - definition.startTickOf(TechniquePhase.IMPACT);
        if (t < -6.0F || t > 10.0F) {
            return 0.0F;
        }
        return t < 0.0F ? 1.0F + t / 6.0F : 1.0F - t / 10.0F;
    }

    /** Отброс тени: резкий откат в момент удара и плавный возврат. */
    static double recoil() {
        if (sinceImpact >= RECOIL_TICKS) {
            return 0.0D;
        }
        float k = sinceImpact / (float) RECOIL_TICKS;
        return RECOIL * (1.0F - k) * (1.0F - k) * Math.min(1.0F, 4.0F * k + 0.0F);
    }

    private static void face(Figure figure, Vec3 spot, Vec3 target, double y) {
        Vec3 to = target.subtract(spot);
        float facing = (float) (Mth.atan2(to.z, to.x) * (180.0D / Math.PI)) - 90.0F;
        figure.setPos(spot.x, y, spot.z);
        figure.xo = figure.xOld = spot.x;
        figure.yo = figure.yOld = y;
        figure.zo = figure.zOld = spot.z;
        figure.setYRot(facing);
        figure.yRotO = facing;
        figure.yBodyRot = figure.yBodyRotO = facing;
        figure.yHeadRot = figure.yHeadRotO = facing;
        figure.setXRot(0.0F);
        figure.xRotO = 0.0F;
        figure.setDeltaMovement(Vec3.ZERO);
    }

    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || fighter == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float presence = presence(partial);
        if (presence <= 0.01F) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource source = minecraft.renderBuffers().bufferSource();
        ResourceLocation art = ClientMeditationState.state().echo().orElse(null);
        // Чёткость — мастерство: муть на первых звёздах, резкость на последних. В озарении образ вспыхивает ярче.
        float strength = presence * clarity(art) * (insightTicks > 0 ? 1.4F : 0.95F);
        PoseStack poseStack = event.getPoseStack();
        // Тень врага: холодный красный, слабее двойника, вспыхивает от удара.
        float flash = sinceImpact < 6 ? 1.0F - sinceImpact / 6.0F : 0.0F;
        render(minecraft, source, poseStack, camera, partial, foe, new int[] {170, 18, 46},
                presence * (0.40F + 0.55F * flash), Vec3.ZERO);
        // Afterimages of the strike (the mock-up's shadow step): two fainter copies of the double, left behind along the
        // line of the duel while the blow lands. The same pose, displaced: no history of poses is kept.
        float swing = swing(partial);
        if (swing > 0.02F) {
            Vec3 back = fighter.position().subtract(foe.position()).multiply(1.0D, 0.0D, 1.0D).normalize();
            render(minecraft, source, poseStack, camera, partial, fighter, new int[] {120, 205, 255}, strength * 0.22F * swing,
                    back.scale(0.85D));
            render(minecraft, source, poseStack, camera, partial, fighter, new int[] {120, 205, 255}, strength * 0.40F * swing,
                    back.scale(0.40D));
        }
        render(minecraft, source, poseStack, camera, partial, fighter, new int[] {120, 205, 255}, strength, Vec3.ZERO);
        poseStack.pushPose();
        try {
            hands(minecraft, poseStack, camera, presence * clarity(art));
        } finally {
            poseStack.popPose();
        }
    }

    private static void render(Minecraft minecraft, MultiBufferSource.BufferSource source, PoseStack poseStack, Vec3 camera,
                               float partial, Figure figure, int[] tint, float strength, Vec3 shift) {
        // Двойник рисуется АДДИТИВНО, как светящиеся глаза паука: тёмное в скине исчезает,
        // светлое светится, фон просвечивает. Полупрозрачный обычный режим давал
        // «голубого зомби» — второго человека, а не образ (кадры 30.09).
        // API: reference/minecraft-src/net/minecraft/client/renderer/RenderType.java#eyes
        net.minecraft.client.renderer.RenderType ghost = net.minecraft.client.renderer.RenderType.eyes(figure.getSkin().texture());
        // The held weapon is drawn through the same buffer source: its quads need the block atlas, not the skin of the body.
        net.minecraft.client.renderer.RenderType ghostItem =
                net.minecraft.client.renderer.RenderType.eyes(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS);
        String skin = figure.getSkin().texture().toString();
        MultiBufferSource tinted = type -> new Tint(source.getBuffer(type.toString().contains(skin) ? ghost : ghostItem), tint, strength);
        poseStack.pushPose();
        try {
            // API: EntityRenderDispatcher#render(E, double, double, double, float, float,
            // PoseStack, MultiBufferSource, int) — javap по build/moddev/artifacts/neoforge-21.1.*.jar
            minecraft.getEntityRenderDispatcher().render(figure,
                    figure.getX() + shift.x - camera.x, figure.getY() + shift.y - camera.y, figure.getZ() + shift.z - camera.z,
                    figure.getYRot(), partial, poseStack, tinted, LightTexture.FULL_BRIGHT);
            source.endBatch();
        } finally {
            poseStack.popPose();
        }
    }

    /** Ци у кистей двойника: в приёме видно, куда идёт сила, — это и есть «осмысление». */
    private static void hands(Minecraft minecraft, PoseStack poseStack, Vec3 camera, float presence) {
        Vec3 right = io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.position(fighter,
                io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.RIGHT_HAND);
        Vec3 left = io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.position(fighter,
                io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.LEFT_HAND);
        Vec3 core = io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.position(fighter,
                io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.DANTIAN);
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer glow = minecraft.renderBuffers().bufferSource()
                .getBuffer(io.github.verycooltimo.murim.client.vfx.MurimRenderTypes.impactCore());
        float age = cycleTick;
        for (Vec3 hand : new Vec3[] {right, left}) {
            if (hand != null) {
                io.github.verycooltimo.murim.client.vfx.CoreGlow.draw(glow, pose, hand, camera, age, 0.07D,
                        0.55F * presence, HALO, CORE);
            }
        }
        if (core != null) {
            io.github.verycooltimo.murim.client.vfx.CoreGlow.draw(glow, pose, core, camera, age, 0.06D,
                    0.45F * presence, HALO, CORE);
        }
        minecraft.renderBuffers().bufferSource()
                .endBatch(io.github.verycooltimo.murim.client.vfx.MurimRenderTypes.impactCore());
        poseStack.popPose();
    }

    private static final io.github.verycooltimo.murim.client.vfx.VfxColour HALO =
            new io.github.verycooltimo.murim.client.vfx.VfxColour(0.16F, 0.42F, 0.95F);
    private static final io.github.verycooltimo.murim.client.vfx.VfxColour CORE =
            new io.github.verycooltimo.murim.client.vfx.VfxColour(0.45F, 0.88F, 1.0F);

    /** Над двойником и тенью не нужна табличка с ником. */
    @SubscribeEvent
    static void onNameTag(RenderNameTagEvent event) {
        if (fighter != null && (event.getEntity() == fighter || event.getEntity() == foe)) {
            event.setCanRender(TriState.FALSE);
        }
    }

    public static void reset() {
        fighter = null;
        foe = null;
        playing = null;
        forced = null;
        insightTicks = 0;
        broken = false;
        memoryIndex = 0;
        sinceImpact = 1000;
        anchor = null;
        kata = List.of();
        kataPos = 0;
        weapon = EchoWeapon.Kind.EMPTY;
    }

    /**
     * Клиентская копия игрока или тени: в мир не добавляется, «невидима, но видна нам». Тень носит стандартный скин —
     * силуэт другой, чем у двойника.
     */
    private static final class Figure extends RemotePlayer {

        /** API: reference/minecraft-src — the vanilla white texture used by the mod's other effects. */
        private static final ResourceLocation SILHOUETTE = ResourceLocation.withDefaultNamespace("textures/misc/white.png");

        private final Player original;

        Figure(ClientLevel level, String name, Player original) {
            super(level, new GameProfile(UUID.randomUUID(), name));
            this.original = original;
        }

        /** Скин игрока без плаща и элитр: их слои рисовались бы текстурой тела. */
        @Override
        public PlayerSkin getSkin() {
            if (original == null) {
                // The shadow is a flat silhouette: a white texture, so that the whole body glows in the one colour of the tint.
                return new PlayerSkin(SILHOUETTE, null, null, null, DefaultPlayerSkin.get(getUUID()).model(), true);
            }
            PlayerSkin skin = original instanceof net.minecraft.client.player.AbstractClientPlayer client
                    ? client.getSkin() : DefaultPlayerSkin.get(getUUID());
            return new PlayerSkin(skin.texture(), skin.textureUrl(), null, null, skin.model(), skin.secure());
        }

        /** Невидимость включает у рендерера полупрозрачный режим тела. */
        @Override
        public boolean isInvisible() {
            return true;
        }

        @Override
        public boolean isInvisibleTo(Player viewer) {
            return false;
        }
    }

    /** Перекрашивает вершины в заданный тон с заданной прозрачностью. */
    private record Tint(VertexConsumer inner, int[] colour, float strength) implements VertexConsumer {

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            inner.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int ignored) {
            // При аддитивном смешении яркость — это и есть прозрачность: цвет вершины
            // задаёт тон и силу проявления образа.
            // Каналы ограничены: при усилении в озарении синий переполнялся и образ зеленел.
            inner.setColor(Math.min(255, (int) (colour[0] * strength)), Math.min(255, (int) (colour[1] * strength)),
                    Math.min(255, (int) (colour[2] * strength)), 255);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            inner.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            inner.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            inner.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            inner.setNormal(x, y, z);
            return this;
        }
    }

    private MeditationEcho() {
    }
}
