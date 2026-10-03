package io.github.verycooltimo.murim.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.SyncMeditationPayload;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
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

import java.util.UUID;

/**
 * «Осмысление» в медитации после семени: полупрозрачный двойник игрока повторяет приём
 * из недавнего боя (docs/design/19 §3, решение автора 29.09 — «на фоне полупрозрачные
 * образы, как будто осмысление»).
 *
 * <p>Двойник — клиентская копия игрока ({@link RemotePlayer}), которой нет в мире: её
 * тикаем и рисуем сами. Анимацию техники ей даёт та же библиотека, что и игроку, — слой
 * создаётся для любого клиентского игрока, и приём повторяется кадр в кадр.
 *
 * <p>Рисуется ванильным рендерером игрока в «невидимом, но видимом нам» режиме — он уже
 * полупрозрачный; поверх цвет переводится в холодный ци-тон обёрткой буфера.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class MeditationEcho {

    /** Сколько последних приёмов помнит тело. */
    private static final int MEMORY = 4;

    /** Пауза между повторами приёма, тики: образ проявляется, бьёт, растворяется. */
    private static final int PAUSE_TICKS = 40;

    /**
     * Двойник стоит впереди и сбоку, лицом к игроку. Прямо впереди его закрывало
     * собственное тело игрока: камера за спиной смотрит по той же линии (кадры 30.09).
     */
    private static final double DISTANCE = 2.6D;
    private static final double SIDE = 1.2D;

    /** Приём озарения, который двойник покажет немедленно. */
    private static ResourceLocation forced;
    private static int insightTicks;

    private static Echo echo;
    private static ResourceLocation playing;
    private static int cycleTick;
    private static int memoryIndex;
    private static boolean broken;

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
        return echo != null;
    }

    /** Сила проявления образа 0..1: проявляется, держится на приёме, растворяется. */
    public static float presence(float partial) {
        if (echo == null || playing == null) {
            return 0.0F;
        }
        TechniqueDefinition definition = TechniqueLoader.get(playing);
        int length = definition == null ? 60 : definition.totalTicks();
        float t = cycleTick + partial;
        float in = Mth.clamp(t / 12.0F, 0.0F, 1.0F);
        float out = Mth.clamp((length + PAUSE_TICKS - t) / 16.0F, 0.0F, 1.0F);
        return in * out;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.isPaused()) {
            return;
        }
        SyncMeditationPayload state = ClientMeditationState.state();
        // Двойник показывает то, что сервер реально осмысливает (§3г), и приём озарения.
        java.util.List<ResourceLocation> pending = ClientMasteryState.pending();
        if (insightTicks > 0) {
            insightTicks--;
        }
        boolean wanted = !broken && state.active() && state.beats() >= 3
                && (!pending.isEmpty() || insightTicks > 0 || playing != null && cycleTick > 0)
                && ClientMeditationState.sessionTicks() > 30;
        if (!wanted) {
            echo = null;
            playing = null;
            return;
        }
        if (echo == null || echo.level() != minecraft.level) {
            echo = new Echo(minecraft.level, player);
            cycleTick = 0;
            playing = null;
        }
        place(player);
        try {
            // Библиотека анимаций двигает кадры в тике клиентского игрока.
            echo.tick();
        } catch (RuntimeException exception) {
            MurimMod.LOGGER.error("Двойник медитации сломался и отключён до перезахода", exception);
            broken = true;
            echo = null;
            return;
        }
        place(player);

        TechniqueDefinition definition = playing == null ? null : TechniqueLoader.get(playing);
        int length = definition == null ? 0 : definition.totalTicks();
        if (playing == null || ++cycleTick >= length + PAUSE_TICKS) {
            // Приёмы идут по кругу: тело перебирает пережитое; озарение — вне очереди.
            if (forced != null) {
                playing = forced;
                forced = null;
            } else if (!pending.isEmpty()) {
                playing = pending.get(memoryIndex++ % pending.size());
            } else {
                echo = null;
                playing = null;
                return;
            }
            cycleTick = 0;
            TechniqueDefinition next = TechniqueLoader.get(playing);
            if (next != null) {
                MurimPlayerAnimations.play(echo, next.animation());
            }
        }
    }

    /** Двойник стоит перед игроком лицом к нему и не двигается сам. */
    private static void place(LocalPlayer player) {
        float yaw = player.getYRot();
        Vec3 forward = Vec3.directionFromRotation(0.0F, yaw);
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        Vec3 spot = player.position().add(forward.scale(DISTANCE)).add(right.scale(SIDE));
        Vec3 toPlayer = player.position().subtract(spot);
        float facing = (float) (Mth.atan2(toPlayer.z, toPlayer.x) * (180.0D / Math.PI)) - 90.0F;
        echo.setPos(spot.x, player.getY(), spot.z);
        echo.xo = echo.xOld = spot.x;
        echo.yo = echo.yOld = player.getY();
        echo.zo = echo.zOld = spot.z;
        echo.setYRot(facing);
        echo.yRotO = facing;
        echo.yBodyRot = echo.yBodyRotO = facing;
        echo.yHeadRot = echo.yHeadRotO = facing;
        echo.setXRot(0.0F);
        echo.xRotO = 0.0F;
        echo.setDeltaMovement(Vec3.ZERO);
    }

    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || echo == null) {
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
        // Двойник рисуется АДДИТИВНО, как светящиеся глаза паука: тёмное в скине исчезает,
        // светлое светится, фон просвечивает. Полупрозрачный обычный режим давал
        // «голубого зомби» — второго человека, а не образ (кадры 30.09).
        // API: reference/minecraft-src/net/minecraft/client/renderer/RenderType.java#eyes
        net.minecraft.client.renderer.RenderType ghost =
                net.minecraft.client.renderer.RenderType.eyes(echo.getSkin().texture());
        // В озарении образ вспыхивает ярче: приём выполнен чисто.
        float strength = presence * (insightTicks > 0 ? 1.4F : 0.85F);
        MultiBufferSource tinted = type -> new Tint(source.getBuffer(ghost), strength);
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        try {
            // API: EntityRenderDispatcher#render(E, double, double, double, float, float,
            // PoseStack, MultiBufferSource, int) — javap по build/moddev/artifacts/neoforge-21.1.*.jar
            minecraft.getEntityRenderDispatcher().render(echo,
                    echo.getX() - camera.x, echo.getY() - camera.y, echo.getZ() - camera.z,
                    echo.getYRot(), partial, poseStack, tinted, LightTexture.FULL_BRIGHT);
            source.endBatch();
            hands(minecraft, poseStack, camera, presence);
        } finally {
            poseStack.popPose();
        }
    }

    /** Ци у кистей двойника: в приёме видно, куда идёт сила, — это и есть «осмысление». */
    private static void hands(Minecraft minecraft, PoseStack poseStack, Vec3 camera, float presence) {
        Vec3 right = io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.position(echo,
                io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.RIGHT_HAND);
        Vec3 left = io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.position(echo,
                io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.LEFT_HAND);
        Vec3 core = io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.position(echo,
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

    /** Над двойником не нужна табличка с ником. */
    @SubscribeEvent
    static void onNameTag(RenderNameTagEvent event) {
        if (echo != null && event.getEntity() == echo) {
            event.setCanRender(TriState.FALSE);
        }
    }

    public static void reset() {
        echo = null;
        playing = null;
        forced = null;
        insightTicks = 0;
        broken = false;
        memoryIndex = 0;
    }

    /** Клиентская копия игрока: его скин, в мир не добавляется, «невидима, но видна нам». */
    private static final class Echo extends RemotePlayer {

        private final Player original;

        Echo(ClientLevel level, Player original) {
            super(level, new GameProfile(UUID.randomUUID(), original.getGameProfile().getName()));
            this.original = original;
        }

        /** Скин игрока без плаща и элитр: их слои рисовались бы текстурой тела. */
        @Override
        public PlayerSkin getSkin() {
            PlayerSkin skin = original instanceof net.minecraft.client.player.AbstractClientPlayer client
                    ? client.getSkin() : super.getSkin();
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

    /** Перекрашивает вершины двойника в холодный ци-тон с заданной прозрачностью. */
    private record Tint(VertexConsumer inner, float strength) implements VertexConsumer {

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            inner.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int ignored) {
            // При аддитивном смешении яркость — это и есть прозрачность: цвет вершины
            // задаёт холодный ци-тон и силу проявления образа.
            // Каналы ограничены: при усилении в озарении синий переполнялся и образ зеленел.
            inner.setColor(Math.min(255, (int) (70 * strength)), Math.min(255, (int) (170 * strength)),
                    Math.min(255, (int) (255 * strength)), 255);
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
