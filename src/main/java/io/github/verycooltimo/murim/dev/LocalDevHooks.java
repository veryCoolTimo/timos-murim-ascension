package io.github.verycooltimo.murim.dev;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Loaded by name only in development; excluded from the release jar. */
public final class LocalDevHooks implements DevHooks {
    private final BladeExamStand bladeExam = new BladeExamStand();
    private boolean bladeRegistered;

    @Override public void bladeExamEvent(ServerPlayer player, io.github.verycooltimo.murim.entity.SectDisciple mentor,
                                         String event, long tick) {
        bladeExam.event(player, mentor, event, tick);
    }

    // API: Minecraft commands/CommandSourceStack.java#hasPermission (moved command attachment).
    @Override public void commands(LiteralArgumentBuilder<CommandSourceStack> dev, CommandBuildContext buildContext) {
        if (!bladeRegistered) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(bladeExam);
            bladeRegistered = true;
        }
        dev.then(bladeExam.node());
        dev.then(FilmCommands.useBlockNode()).then(FilmCommands.useItemNode()).then(FilmCommands.closeNode());
        dev.then(io.github.verycooltimo.murim.world.location.LocationCommand.node());

        // World-building literals keep their old paths, at permission 4.
        for (var child : DevRegionCommand.nodes(buildContext).build().getChildren()) {
            var restricted = child.createBuilder().requires(s -> s.hasPermission(4));
            child.getChildren().forEach(restricted::then);
            dev.then(restricted);
        }
    }
    @Override public void huaCommands(LiteralArgumentBuilder<CommandSourceStack> hua) {
        hua.then(io.github.verycooltimo.murim.world.hua.HuaRegradeCommand.node().requires(s -> s.hasPermission(4)));
    }
    @Override public void payloads(PayloadRegistrar registrar) {
        // API: network/registration/PayloadRegistrar.java#optional, playToClient (NeoForge 21.1).
        registrar.optional().playToClient(FilmScreenPayload.TYPE, FilmScreenPayload.STREAM_CODEC,
                (payload, context) -> resetFilmScreen());
    }
    /** Stand-only diagnostics around the real network request; no gameplay bypasses. */
    @Override public void footworkRequest(ServerPlayer player, net.minecraft.resources.ResourceLocation subject, int input) {
        if (!Boolean.getBoolean("murim.capture") || System.getenv().getOrDefault("MURIM_CAPTURE_FOOTWORK", "").isEmpty()) {
            DevHooks.super.footworkRequest(player, subject, input);
            return;
        }
        var attachments = io.github.verycooltimo.murim.registry.ModAttachments.COOLDOWNS;
        var beforeCooldown = player.getData(attachments).get(subject);
        var beforeState = player.getData(io.github.verycooltimo.murim.registry.ModAttachments.TECHNIQUE_STATE);
        boolean beforeActive = io.github.verycooltimo.murim.combat.FootworkService.active(player);
        String reason = footworkBlocker(player, subject, input);
        DevHooks.super.footworkRequest(player, subject, input);
        boolean started = !java.util.Objects.equals(beforeCooldown, player.getData(attachments).get(subject))
                || !beforeState.equals(player.getData(io.github.verycooltimo.murim.registry.ModAttachments.TECHNIQUE_STATE))
                || beforeActive != io.github.verycooltimo.murim.combat.FootworkService.active(player);
        io.github.verycooltimo.murim.MurimMod.LOGGER.info(
                "FOOTWORK request subject={} selected={} started={} reason={}", subject,
                io.github.verycooltimo.murim.combat.FootworkService.selected(player, subject), started,
                started ? "accepted" : reason);
    }

    private static String footworkBlocker(ServerPlayer player, net.minecraft.resources.ResourceLocation subject, int input) {
        // API: /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/attachment/IAttachmentHolder.java#getData(Supplier)
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/level/Level.java#getGameTime
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/Entity.java#onGround
        var def = io.github.verycooltimo.murim.technique.TechniqueLoader.get(subject);
        if (def == null) return "unknown_definition";
        if (io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.pinned(player)) return "pinned";
        if (!io.github.verycooltimo.murim.mastery.MasteryService.knows(player, subject)) return "unknown_form";
        if (!io.github.verycooltimo.murim.combat.FootworkService.selected(player, subject)) return "not_selected";
        if (io.github.verycooltimo.murim.combat.FootworkService.family(def) == null) return "not_footwork";
        long now = player.level().getGameTime();
        var traverse = player.getData(io.github.verycooltimo.murim.registry.ModAttachments.TRAVERSE);
        if (!player.onGround() && now - traverse[7] > 2) return "in_air";
        String mode = def.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.Footwork f ? f.mode() : "evade";
        if ((input & io.github.verycooltimo.murim.combat.FootworkService.PASSIVE) != 0
                && (!"run".equals(mode) || !player.isSprinting())) return "passive_input";
        var state = player.getData(io.github.verycooltimo.murim.registry.ModAttachments.TECHNIQUE_STATE);
        if (state.isActive() || io.github.verycooltimo.murim.combat.FootworkService.active(player)) return "already_active";
        if (io.github.verycooltimo.murim.combat.Stun.isStunned(player)) return "stunned";
        var profile = player.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE);
        if (!profile.isAwakened()) return "not_awakened";
        if (io.github.verycooltimo.murim.combat.AuraService.pressure(player)
                >= io.github.verycooltimo.murim.combat.AuraPressure.TECHNIQUE_LOCK) return "pressure";
        boolean seven = io.github.verycooltimo.murim.technique.SevenStarsRules.isForm(subject);
        if (seven && (input & (io.github.verycooltimo.murim.combat.FootworkService.FORWARD
                | io.github.verycooltimo.murim.combat.FootworkService.BACK
                | io.github.verycooltimo.murim.combat.FootworkService.LEFT
                | io.github.verycooltimo.murim.combat.FootworkService.RIGHT)) == 0) return "direction_required";
        int cooldown = seven ? io.github.verycooltimo.murim.technique.SevenStarsRules.COOLDOWN
                : io.github.verycooltimo.murim.combat.TechniqueService.effectiveCooldown(def,
                        io.github.verycooltimo.murim.mastery.MasteryService.executionTier(player, subject));
        var cds = player.getData(io.github.verycooltimo.murim.registry.ModAttachments.COOLDOWNS);
        Long previous = cds.get(subject);
        if (previous != null && now - previous < cooldown) return "cooldown";
        if (state.lastStartGameTime() != Long.MIN_VALUE && now - state.lastStartGameTime()
                < io.github.verycooltimo.murim.combat.TechniqueService.gapAfter(cds, state.lastStartGameTime())) return "global_cooldown";
        int stars = io.github.verycooltimo.murim.mastery.MasteryService.stars(player, subject);
        double cost = (seven ? io.github.verycooltimo.murim.technique.SevenStarsRules.qi(stars)
                : io.github.verycooltimo.murim.combat.TechniqueService.techniqueCost(def)
                    * io.github.verycooltimo.murim.mastery.MasteryRules.costFactor(stars, def.starCap()))
                * io.github.verycooltimo.murim.cultivation.PillService.costFactor(player);
        if (!io.github.verycooltimo.murim.cultivation.QiRules.has(player, profile, cost)) return "no_qi";
        return "no_state_change";
    }

    @Override public boolean legacySites(ServerPlayer player) {
        // API: neoforge/common/extensions/IEntityExtension.java#getPersistentData; CompoundTag#getBoolean.
        return player.getPersistentData().getBoolean("murim_test_legacy_sites");
    }
    @Override public boolean benchActive() {
        return io.github.verycooltimo.murim.client.DevClientHooks.benchActive();
    }
    @Override public void resetFilmScreen() {
        io.github.verycooltimo.murim.client.DevClientHooks.closeFilmScreen();
    }
    @Override public void initializeClient() { io.github.verycooltimo.murim.client.DevClientHooks.initialize(); }
    @Override public boolean galleryActive() { return io.github.verycooltimo.murim.client.DevClientHooks.galleryActive(); }
    @Override public void resetCapture() {
        io.github.verycooltimo.murim.client.DevClientHooks.resetCapture();
    }
    @Override public void stopBench() { io.github.verycooltimo.murim.client.DevClientHooks.stopBench(); }
    @Override public double benchSeconds() { return io.github.verycooltimo.murim.client.DevClientHooks.benchSeconds(); }
    @Override public String benchElement() { return io.github.verycooltimo.murim.client.DevClientHooks.benchElement(); }
    @Override public int startBench(String element, double from, double to, boolean loop) { return io.github.verycooltimo.murim.client.DevClientHooks.startBench(element, from, to, loop); }

}
