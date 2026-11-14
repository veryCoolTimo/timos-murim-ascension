package io.github.verycooltimo.murim.technique;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniqueState;
import io.github.verycooltimo.murim.mastery.Loadout;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.PlumFormMigration;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Read-only API verification: /tmp/mr-ref-1121/minecraft-src/net/minecraft/{gametest/framework,
 * world/entity,world/damagesource}; NeoForge DamageContainer and LivingIncomingDamageEvent.
 * These fixtures use the production executors and damage events, never force a spar result.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class PlumBloomGameTests {
    private static ServerPlayer player(GameTestHelper h) {
        ServerPlayer p = io.github.verycooltimo.murim.sect.SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(8.5, 3, 4.5));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setData(ModAttachments.TECHNIQUE_STATE, TechniqueState.started(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "twenty_four_plum_sea"), h.getLevel().getGameTime()));
        return p;
    }
    private static Zombie foe(GameTestHelper h, double z) {
        Zombie e = h.spawn(EntityType.ZOMBIE, new Vec3(8.5, 3, z));
        e.setNoAi(true);
        return e;
    }

    /** Retired Dome and Full Bloom ids leave a save entirely: progress, known list, comprehended, slots. */
    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void retiredPlumFormsLeaveSaves(GameTestHelper h) {
        String progress = "{\"version\":5,\"stars\":7,\"progress\":12,\"unprocessed\":3,\"cap\":12,\"help\":2}";
        for (String saved : java.util.List.of(
                "{\"techniques\":{\"murim:twenty_four_plum_dome\":" + progress + ",\"murim:twenty_four_plum_full_bloom\":" + progress + "}}",
                "{\"version\":5,\"arts\":{\"murim:twenty_four_plum\":" + progress + "},\"known\":[\"murim:twenty_four_plum_dome\",\"murim:twenty_four_plum_full_bloom\"]}")) {
            MasteryState m = MasteryState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(saved)).getOrThrow();
            h.assertTrue(m.techniques().isEmpty(), "a retired plum form stayed in the save");
        }
        Loadout l = Loadout.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"slots\":[{\"technique\":\"murim:twenty_four_plum_dome\"},{\"technique\":\"murim:twenty_four_plum_full_bloom\"}],\"active\":1}")).getOrThrow();
        h.assertTrue(l.slots().stream().noneMatch(java.util.Optional::isPresent), "a retired plum form stayed in a slot");
        var state = TechniqueState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"technique\":\"murim:twenty_four_plum_full_bloom\",\"tick\":3,\"impact_done\":false,\"last_start\":9}")).getOrThrow();
        h.assertTrue(!state.isActive(), "a retired cast stayed active");
        h.assertTrue(TechniqueLoader.get(PlumFormMigration.DOME) == null && TechniqueLoader.get(PlumFormMigration.BLOOM) == null,
                "a retired plum form is still in the datapack");
        h.succeed();
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void seaHalvesFrontAndSideMeleeOnlyWhileHolding(GameTestHelper h) {
        ServerPlayer p = player(h);
        ResourceLocation sea = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "twenty_four_plum_sea");
        p.setData(ModAttachments.TECHNIQUE_STATE, new TechniqueState(sea, SeaRules.TIDE_END, true, 0));
        SeaExecutor.begin(p, sea, 0);
        Zombie e = foe(h, 6.5);
        Vec3 base = p.position();
        for (Vec3 offset : java.util.List.of(new Vec3(0, 0, 2), new Vec3(2, 0, 0), new Vec3(0, 0, -2))) {
            e.moveTo(base.add(offset));
            var event = new LivingIncomingDamageEvent(p, new DamageContainer(p.damageSources().mobAttack(e), 8));
            SeaExecutor.softenMelee(event);
            h.assertTrue(event.getAmount() == (offset.z < 0 ? 8 : 4), "incorrect melee coverage for " + offset);
        }
        e.moveTo(base.add(0, 0, 2));
        var arrow = new Arrow(h.getLevel(), e, new ItemStack(Items.ARROW), null);
        var ranged = new LivingIncomingDamageEvent(p, new DamageContainer(p.damageSources().arrow(arrow, e), 8));
        SeaExecutor.softenMelee(ranged);
        h.assertTrue(ranged.getAmount() == 8, "melee protection also reduced projectile damage");
        p.getData(ModAttachments.SEA)[5] = SeaRules.TIDE_END;
        e.moveTo(base.add(0, 0, 2));
        var ended = new LivingIncomingDamageEvent(p, new DamageContainer(p.damageSources().mobAttack(e), 8));
        SeaExecutor.softenMelee(ended);
        h.assertTrue(ended.getAmount() == 8, "melting Sea still softens melee");
        h.succeed();
    }

    private PlumBloomGameTests() {}
}
