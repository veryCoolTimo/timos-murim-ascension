package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.Styles;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual vanilla damage, equipment/cooldown and event hooks. No direct hurt or cooldown resets.
 * Input means server receipt: GameTest cannot measure a physical mouse/network round trip.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SwordBasicGameTests {
    private static final net.minecraft.resources.ResourceLocation BASIC = Styles.SEVEN_PLUM.basic().orElseThrow();

    private static ServerPlayer player(GameTestHelper helper, boolean style) {
        return player(helper, style, null);
    }

    private static ServerPlayer player(GameTestHelper helper, boolean style,
                                     java.util.List<io.github.verycooltimo.murim.network.FoundationPayloads.Hit> outbound) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "sword-basic-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        if (outbound == null) {
            new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        } else {
            // Exercise the actual ChannelSafe -> PacketDistributor -> connection.send path.
            new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie) {
                @Override public boolean hasChannel(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
                    return payload instanceof io.github.verycooltimo.murim.network.FoundationPayloads.Hit;
                }
                @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
                    if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                            && custom.payload() instanceof io.github.verycooltimo.murim.network.FoundationPayloads.Hit hit) {
                        outbound.add(hit);
                    }
                }
            };
        }
        Vec3 at = helper.absoluteVec(new Vec3(2.5D, 2.0D, 2.5D));
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        p.setOnGround(true);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        if (style) {
            p.setData(ModAttachments.PROFILE, io.github.verycooltimo.murim.profile.DantianProfile.INITIAL.withTags("pure", "debug"));
            // Layer zero must already alternate, rather than repeating one crooked beginner cut.
            p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(BASIC, TechniqueProgress.learned(0, 10)));
            p.setData(ModAttachments.LOADOUT, p.getData(ModAttachments.LOADOUT).withFoundation(java.util.Optional.of(BASIC)));
        }
        return p;
    }

    private static Cow target(GameTestHelper helper, double x) {
        Cow cow = helper.spawn(EntityType.COW, new Vec3(x, 2.0D, 4.5D));
        cow.setNoAi(true);
        cow.setNoGravity(true);
        cow.noPhysics = true; // No wall suffocation: fixtures sit near template edges and would add stray damage.
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        cow.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0D);
        cow.setHealth(200.0F);
        return cow;
    }

    private static void tick(ServerPlayer p) {
        // Fake players aren't in PlayerList. doTick invokes Player.tick (cooldown/equipment),
        // whereas ServerPlayer.tick alone does not advance the attack-strength ticker.
        p.doTick();
        SwordBasicAttack.tick(p); // Idempotent if PlayerTickEvent.Post already ran it.
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0.0F;
        p.setOnGround(true);
    }

    private static void input(ServerPlayer p, Cow target, int step) {
        SwordBasicAttack.input(p, SwordBasicCombo.at(step).ordinal(), target.getId());
    }

    @GameTest(template = "small_floor", batch = "sword_basic", timeoutTicks = 50)
    public static void inputDealsDamageWithinThreeTicks(GameTestHelper helper) {
        ServerPlayer p = player(helper, true);
        Cow target = target(helper, 2.5D);
        int[] ticks = {0};
        long[] inputAt = {-1};
        helper.onEachTick(() -> {
            tick(p);
            int now = ticks[0]++;
            if (now == 25) {
                inputAt[0] = now;
                input(p, target, 0);
                input(p, target, 1); // Duplicate intent before contact must not replace/double the hit.
                helper.assertTrue(p.getData(ModAttachments.BASIC_COMBO).nextStep() == 1, "duplicate intent advanced combo");
                helper.assertTrue(target.getHealth() == 200.0F, "damage preceded the contact frame");
            }
            if (inputAt[0] >= 0) {
                if (target.getHealth() < 200.0F) {
                    helper.assertTrue(now - inputAt[0] == SwordBasicCombo.CONTACT_TICK, "damage/contact mismatch");
                    helper.assertTrue(now - inputAt[0] <= 3, "damage arrived late");
                    helper.assertTrue(!p.getData(ModAttachments.TECHNIQUE_STATE).isActive(), "LMB started a cast");
                    helper.assertTrue(p.getData(ModAttachments.FOUNDATION_SWING)[1] == -1,
                            "confirmed damage did not consume style feedback once");
                    helper.assertTrue(p.getData(ModAttachments.COOLDOWNS).isEmpty(), "LMB added technique cooldowns");
                    helper.succeed();
                } else if (now - inputAt[0] >= 3) {
                    helper.fail("No damage within three ticks of server input");
                }
            }
        });
    }

    @GameTest(template = "small_floor", batch = "sword_basic", timeoutTicks = 80)
    public static void threeHitsWithinThirtyTicks(GameTestHelper helper) {
        ServerPlayer p = player(helper, true);
        Cow target = target(helper, 2.5D);
        int[] ticks = {0};
        float[] health = {200.0F};
        int[] hits = {0};
        helper.onEachTick(() -> {
            tick(p);
            int elapsed = ticks[0]++ - 25;
            if (elapsed >= 0 && elapsed <= 26 && elapsed % 13 == 0) {
                int step = elapsed / 13;
                input(p, target, step);
                var combo = p.getData(ModAttachments.BASIC_COMBO);
                helper.assertTrue(combo.nextStep() == (step + 1) % 3, "wrong server combo order");
                helper.assertTrue(p.getData(ModAttachments.FOUNDATION_SWING)[0] == SwordBasicCombo.at(step).ordinal(),
                        "wrong alternating cut");
            }
            if (elapsed >= 2 && elapsed <= 28 && (elapsed - 2) % 13 == 0) {
                helper.assertTrue(target.getHealth() < health[0], "missing combo hit " + hits[0]);
                health[0] = target.getHealth();
                if (++hits[0] == 3) {
                    helper.assertTrue(elapsed <= 30, "three hits took too long");
                    helper.succeed();
                }
            }
        });
    }

    @GameTest(template = "small_floor", batch = "sword_basic", timeoutTicks = 230)
    public static void damagePerSecondAtLeastVanillaIronSword(GameTestHelper helper) {
        compareDamage(helper, 4.0D, 13, 156);
    }

    @GameTest(template = "small_floor", batch = "sword_basic", timeoutTicks = 230)
    public static void fasterAttackAttributeDoesNotAddComboCooldown(GameTestHelper helper) {
        compareDamage(helper, 5.6D, 7, 182); // Iron sword at 3.2 attack speed, cooldown 6.25 ticks.
    }

    private static void compareDamage(GameTestHelper helper, double baseSpeed, int interval, int lastInput) {
        ServerPlayer styled = player(helper, true);
        ServerPlayer vanilla = player(helper, false);
        styled.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(baseSpeed);
        vanilla.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(baseSpeed);
        Cow styledTarget = target(helper, 2.5D);
        Cow vanillaTarget = target(helper, 5.5D);
        Vec3 at = helper.absoluteVec(new Vec3(5.5D, 2.0D, 2.5D));
        vanilla.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        int[] ticks = {0};
        helper.onEachTick(() -> {
            tick(styled);
            tick(vanilla);
            int elapsed = ticks[0]++ - 25;
            if (elapsed >= 0 && elapsed <= lastInput && elapsed % interval == 0) {
                helper.assertTrue(Math.abs(styled.getAttributeValue(Attributes.ATTACK_SPEED)
                        - vanilla.getAttributeValue(Attributes.ATTACK_SPEED)) < 1.0E-6D, "different attack speeds");
                input(styled, styledTarget, elapsed / interval);
                vanilla.attack(vanillaTarget);
            }
            if (elapsed == lastInput + SwordBasicCombo.CONTACT_TICK) {
                float styleDamage = 200.0F - styledTarget.getHealth();
                float vanillaDamage = 200.0F - vanillaTarget.getHealth();
                helper.assertTrue(vanillaDamage >= 70.0F, "control sword didn't deal full repeated damage");
                helper.assertTrue(styleDamage + 0.001F >= vanillaDamage,
                        "style DPS below vanilla over identical interval: " + styleDamage + " vs " + vanillaDamage);
                helper.succeed();
            }
        });
    }

    @GameTest(template = "small_floor", batch = "sword_basic", timeoutTicks = 80)
    public static void missesAdvanceAndIdleResetsTheCombo(GameTestHelper helper) {
        ServerPlayer p = player(helper, true);
        int[] ticks = {0};
        helper.onEachTick(() -> {
            tick(p);
            int elapsed = ticks[0]++ - 25;
            if (elapsed == 0 || elapsed == 8 || elapsed == 28) {
                FoundationService.onSwing(p, 0); // Even a forged index cannot select a different cut.
                var combo = p.getData(ModAttachments.BASIC_COMBO);
                helper.assertTrue(combo.nextStep() == (elapsed == 8 ? 2 : 1), "miss/reset order");
                if (elapsed == 28) helper.succeed();
            }
        });
    }

    @GameTest(template = "small_floor", batch = "sword_basic", timeoutTicks = 50)
    public static void emptyHandAtSettledPeakUsesVanillaPunch(GameTestHelper helper) {
        ServerPlayer p = player(helper, true);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.setData(ModAttachments.PROFILE, io.github.verycooltimo.murim.profile.DantianProfile.INITIAL
                .withTags("pure", "debug").withRank(QiSword.MIN_RANK).withStage(QiSword.MIN_STAGE));
        Cow target = target(helper, 2.5D);
        int[] ticks = {0};
        helper.onEachTick(() -> {
            tick(p);
            int now = ticks[0]++;
            if (now == 25) {
                helper.assertTrue(FoundationService.active(p).isEmpty(), "empty hand activated sword foundation");
                input(p, target, 0); // A forged foundation packet must not queue a sword hit.
                helper.assertTrue(p.getData(ModAttachments.BASIC_ATTACK).basic() == null, "bare-handed sword hit queued");
                helper.assertTrue(p.getData(ModAttachments.BASIC_COMBO).equals(SwordBasicCombo.State.IDLE), "fist advanced sword combo");
                // An earlier cast may leave qi attributes until timeout; punch must still use vanilla ones.
                QiSword.draw(p);
                p.attack(target);
                helper.assertTrue(p.getData(ModAttachments.COMBO).equals(SchoolStyle.ComboState.IDLE), "fist inherited legacy sword combo");
            }
            if (now == 27) {
                helper.assertTrue(Math.abs(p.getAttributeValue(Attributes.ATTACK_SPEED) - 4.0D) < 1.0E-6D,
                        "vanilla punch retained sword speed");
                helper.assertTrue(200.0F - target.getHealth() > 0.0F && 200.0F - target.getHealth() <= 1.01F,
                        "vanilla punch retained sword damage");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "small_floor", batch = "sword_basic", timeoutTicks = 50)
    public static void retreatDuringWindupMakesTheCutMiss(GameTestHelper helper) {
        ServerPlayer p = player(helper, true);
        Cow target = target(helper, 2.5D);
        int[] ticks = {0};
        helper.onEachTick(() -> {
            tick(p);
            int now = ticks[0]++;
            if (now == 25) input(p, target, 0);
            if (now == 26) {
                Vec3 at = helper.absoluteVec(new Vec3(7.5D, 2.0D, 7.5D));
                target.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
            }
            if (now == 27) {
                helper.assertTrue(target.getHealth() == 200.0F, "cut hit a target beyond vanilla reach");
                helper.assertTrue(p.getData(ModAttachments.BASIC_ATTACK).basic() == null, "miss stayed queued");
                helper.succeed();
            }
        });
    }

    /** Count calls too: immunity frames must not conceal a second hurt against the primary target. */
    private static final class CountedCow extends Cow {
        private int calls;
        private int hits;
        CountedCow(net.minecraft.world.level.Level level) { super(EntityType.COW, level); }
        @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
            boolean playerAttack = source.getEntity() instanceof ServerPlayer && source.getDirectEntity() == source.getEntity();
            if (playerAttack) calls++;
            boolean accepted = super.hurt(source, amount);
            if (playerAttack && accepted) hits++;
            return accepted;
        }
    }
    private static CountedCow counted(GameTestHelper h, double x) {
        CountedCow cow = new CountedCow(h.getLevel());
        Vec3 at = h.absoluteVec(new Vec3(x, 3.0D, 4.5D));
        cow.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        cow.setNoAi(true); cow.setNoGravity(true); cow.noPhysics = true;
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        cow.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        cow.setHealth(200);
        h.getLevel().addFreshEntity(cow);
        return cow;
    }

    @GameTest(template = "small_floor", batch = "sword_basic", timeoutTicks = 60)
    public static void oneBasicHitSweepsAnotherEntityButNeverHitsPrimaryTwice(GameTestHelper h) {
        ServerPlayer p = player(h, true);
        // Stand on top of the template's grass (y=3), not inside it.
        Vec3 stand = h.absoluteVec(new Vec3(2.5D, 3.0D, 2.5D));
        p.moveTo(stand.x, stand.y, stand.z, 0.0F, 0.0F);
        // The fake player has no movement packets and doTick's physics clears onGround; vanilla sweep needs a grounded
        // attacker, so ground it just before the mod's PlayerTickEvent.Post handler fires the queued cut.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,
                (net.neoforged.neoforge.event.tick.PlayerTickEvent.Post e) -> { if (e.getEntity() == p) p.setOnGround(true); });
        CountedCow primary = counted(h, 2.5D);
        CountedCow neighbour = counted(h, 3.25D);
        int[] ticks = {0};
        h.onEachTick(() -> {
            tick(p);
            int now = ticks[0]++;
            if (now == 25) {
                input(p, primary, 0);
                // Mirrors capture's dispatch rule: Six Harmonies/compatibility use vanilla, plum owns the intent.
                if (!SwordBasicCombo.ownsAttack(BASIC, QiSword.holdsSword(p.getMainHandItem()), false)) p.attack(primary);
                h.assertTrue(!SwordBasicCombo.ownsAttack(io.github.verycooltimo.murim.sect.SectService.SIX, true, false)
                        && !SwordBasicCombo.ownsAttack(BASIC, true, true), "vanilla/Better Combat fallback lost");
                input(p, primary, 0); // Duplicate packet is ignored during the queued windup.
            }
            if (now == 35) {
                h.assertTrue(primary.calls == 1 && primary.hits == 1, "primary hurt more than once");
                h.assertTrue(Math.abs(primary.getHealth() - 194.0F) < 0.01F, "primary damage differs from one iron sword hit");
                h.assertTrue(neighbour.calls == 1 && neighbour.hits == 1, "vanilla sweep did not hit the other entity exactly once");
                h.assertTrue(Math.abs(neighbour.getHealth() - 199.0F) < 0.01F, "secondary damage differs from vanilla sweep");
                h.assertTrue(p.getData(ModAttachments.FOUNDATION_SWING)[1] == -1, "sweep repeated foundation feedback");
                h.succeed();
            }
        });
    }

    // The four lanes reach x=18.5: in the 5-wide small_floor they ran into neighbouring tests' arenas, whose mobs could
    // be hit (flaky «outside reach / invulnerable target emitted a finisher impact», agent-log 13.10).
    @GameTest(template = "sect_yard", batch = "sword_basic", timeoutTicks = 85)
    public static void finisherImpactIsSentOnlyOnActualHit(GameTestHelper h) {
        var hitPackets = new java.util.ArrayList<io.github.verycooltimo.murim.network.FoundationPayloads.Hit>();
        var missPackets = new java.util.ArrayList<io.github.verycooltimo.murim.network.FoundationPayloads.Hit>();
        var immunePackets = new java.util.ArrayList<io.github.verycooltimo.murim.network.FoundationPayloads.Hit>();
        var retreatPackets = new java.util.ArrayList<io.github.verycooltimo.murim.network.FoundationPayloads.Hit>();
        ServerPlayer hit = player(h, true, hitPackets), miss = player(h, true, missPackets);
        ServerPlayer immune = player(h, true, immunePackets), retreat = player(h, true, retreatPackets);
        Cow hitTarget = target(h, 2.5D), immuneTarget = target(h, 6.5D), retreatTarget = target(h, 10.5D);
        // Separate lanes: another player's vanilla sweep must not damage our controls.
        for (ServerPlayer p : new ServerPlayer[] {hit, miss, immune, retreat}) p.setNoGravity(true);
        Vec3 immuneAt = h.absoluteVec(new Vec3(6.5D, 2.0D, 2.5D));
        immune.moveTo(immuneAt.x, immuneAt.y, immuneAt.z, 0, 0);
        Vec3 retreatAt = h.absoluteVec(new Vec3(10.5D, 2.0D, 2.5D));
        retreat.moveTo(retreatAt.x, retreatAt.y, retreatAt.z, 0, 0);
        int[] ticks = {0};
        h.onEachTick(() -> {
            tick(hit); tick(miss); tick(immune); tick(retreat);
            int elapsed = ticks[0]++ - 25;
            // Leave reach before the finisher's input: its contact can resolve on the input tick itself, so a later
            // move raced the contact (flaky, agent-log 13.10).
            if (elapsed == 25) {
                Vec3 far = h.absoluteVec(new Vec3(18.5D, 2.0D, 7.5D));
                retreatTarget.moveTo(far.x, far.y, far.z, 0, 0);
                immuneTarget.setInvulnerable(true);
            }
            if (elapsed == 0 || elapsed == 13 || elapsed == 26) {
                int step = elapsed / 13;
                input(hit, hitTarget, step);
                SwordBasicAttack.input(miss, SwordBasicCombo.at(step).ordinal(), -1);
                input(immune, immuneTarget, step);
                input(retreat, retreatTarget, step);
                h.assertTrue(hitPackets.size() == step, "impact was sent before contact");
            }
            if (elapsed == 30) {
                h.assertTrue(hitPackets.size() == 3, "confirmed combo did not send exactly three hit packets");
                h.assertTrue(!hitPackets.get(0).finisher() && !hitPackets.get(1).finisher()
                        && hitPackets.get(2).finisher(), "finisher accent leaked into earlier cuts");
                h.assertTrue(hitPackets.stream().allMatch(p -> !p.kind().allowsImpactFrames() && !p.kind().allowsHitStop()),
                        "basic combo enabled screen impact or hitstop");
                h.assertTrue(StrikeKind.TECHNIQUE_FINALE.allowsImpactFrames() && StrikeKind.TECHNIQUE_FINALE.allowsHitStop(),
                        "big technique finales lost screen punctuation");
                var packet = hitPackets.get(2);
                h.assertTrue(packet.targetId() == hitTarget.getId() && packet.entityId() == hit.getId(), "wrong impact target/source");
                h.assertTrue(hitTarget.getBoundingBox().inflate(0.001D).contains(packet.contact()), "contact detached from damaged target");
                h.assertTrue(missPackets.isEmpty(), "air swing emitted an impact");
                h.assertTrue(immunePackets.size() == 2 && immunePackets.stream().noneMatch(p -> p.finisher()),
                        "invulnerable target emitted a finisher impact");
                h.assertTrue(retreatPackets.size() == 2 && retreatPackets.stream().noneMatch(p -> p.finisher()),
                        "target outside reach emitted a finisher impact");
                h.assertTrue(hitTarget.getHealth() < 188.0F && immuneTarget.getHealth() == 188.0F,
                        "packet assertions were not backed by actual vanilla damage");
                h.succeed();
            }
        });
    }

    private SwordBasicGameTests() {}
}
