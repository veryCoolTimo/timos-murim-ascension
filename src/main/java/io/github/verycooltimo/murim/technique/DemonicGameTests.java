package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniqueService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real TechniqueService timelines, not executor calls pretending to test damage timing. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class DemonicGameTests {
    private static ServerPlayer player(GameTestHelper h, String art, int executionTier, double qi) {
        ServerPlayer p = JunkArtTests.fakePlayer(h);
        p.moveTo(h.absoluteVec(new Vec3(8.5,3,6.5)));
        p.setYRot(0); p.setXRot(0);
        p.setData(ModAttachments.PROFILE,new DantianProfile(100,1,1,"demonic","test",100,qi,1,4,0));
        int cap = io.github.verycooltimo.murim.mastery.Arts.cap(DemonicRules.id(art));
        int stars = io.github.verycooltimo.murim.mastery.Arts.unlockStar(DemonicRules.id(art), executionTier);
        p.setData(ModAttachments.MASTERY,MasteryState.EMPTY.with(DemonicRules.id(art),TechniqueProgress.learned(stars,cap)));
        if (DemonicRules.sword(art)) p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.IRON_SWORD));
        return p;
    }
    private static Zombie target(GameTestHelper h) {
        Zombie z = h.spawn(EntityType.ZOMBIE,new Vec3(8.5,3,8.3));
        z.setNoAi(true);z.setInvulnerable(false);
        z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);z.setHealth(1000);
        z.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,ItemStack.EMPTY);
        z.getAttribute(Attributes.ARMOR).setBaseValue(0);
        return z;
    }
    @GameTest(template="camp_floor",timeoutTicks=100)
    public static void damageOccursOnlyOnAnimationContactsAndSumsToOneBudget(GameTestHelper h) {
        for (String art : java.util.List.of("mountain_thunder_fist","rift_palm","clear_sky_sword","four_horizons")) {
            for (int executionTier : new int[]{0,1,2,4,8}) {
                ServerPlayer p=player(h,art,executionTier,40); Zombie z=target(h);
                var id=DemonicRules.id(art); var d=TechniqueLoader.get(id);
                double budget=TechniqueDamage.base(p,id)*DemonicRules.coefficient(art,executionTier)
                        *Casters.powerFactor(p,id);
                h.assertTrue(TechniqueService.tryStart(p,id),"start refused: "+art);
                double spent=DemonicRules.cost(art)*io.github.verycooltimo.murim.mastery.MasteryRules.costFactor(
                        io.github.verycooltimo.murim.mastery.MasteryService.stars(p,id),
                        io.github.verycooltimo.murim.mastery.Arts.cap(id));
                h.assertTrue(Math.abs(p.getData(ModAttachments.PROFILE).circulating()-(40-spent))<1e-6,"wrong launch qi "+art);
                double expected=0;
                for (int tick=0;tick<=d.totalTicks();tick++) {
                    int since=tick-d.startTickOf(io.github.verycooltimo.murim.combat.TechniquePhase.IMPACT);
                    for (int i=0;i<DemonicRules.contacts(art,executionTier);i++)
                        if (since==DemonicRules.contactTick(art,executionTier,i)) expected+=budget*DemonicRules.share(art,executionTier,i);
                    float before=z.getHealth();TechniqueService.tick(p);
                    h.assertTrue(Math.abs((1000-z.getHealth())-expected)<.02,"damage outside contact / lost hurt-frame "+art+" L"+executionTier+" tick "+tick);
                    if (since<0) h.assertTrue(z.getHealth()==before,"damage during preparation");
                }
                h.assertFalse(TechniqueService.tryStart(p,id),"own cooldown missing "+art);
                DemonicExecutor.clear(p);z.discard();p.discard();
            }
        }
        h.succeed();
    }
    @GameTest(template="camp_floor",timeoutTicks=30)
    public static void meleeCannotConnectFromBeyondArmsLength(GameTestHelper h) {
        for (String art : java.util.List.of("mountain_thunder_fist", "clear_sky_sword")) {
            for (int executionTier : new int[]{0, 8}) {
                ServerPlayer p=player(h,art,executionTier,40);Zombie z=target(h);
                z.moveTo(h.absoluteVec(new Vec3(8.5,3,9.4)));
                var id=DemonicRules.id(art);
                h.assertTrue(TechniqueService.tryStart(p,id),"melee start refused");
                for (int tick=0;tick<=TechniqueLoader.get(id).totalTicks();tick++) TechniqueService.tick(p);
                h.assertTrue(z.getHealth()==1000,"melee reached 2.9 blocks "+art+" L"+executionTier);
                DemonicExecutor.clear(p);z.discard();p.discard();
            }
        }
        h.succeed();
    }
    @GameTest(template="camp_floor",timeoutTicks=50)
    public static void fistsIgnoreWeaponAndWallsStopEveryArt(GameTestHelper h) {
        for (String art : java.util.List.of("mountain_thunder_fist","rift_palm","clear_sky_sword","four_horizons")) {
            ServerPlayer p=player(h,art,8,40);Zombie z=target(h);var id=DemonicRules.id(art);
            if (!DemonicRules.sword(art)) {
                double bare=TechniqueDamage.base(p,id);
                p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.DIAMOND_SWORD));
                // Use the equipped attribute itself too: held item attribute updates need an entity tick.
                p.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(100);
                h.assertTrue(Math.abs(TechniqueDamage.base(p,id)-bare)<1e-6,"weapon amplified bare hand "+art);
            }
            for(int x=6;x<=10;x++)for(int y=2;y<=4;y++)h.setBlock(new BlockPos(x,y,7),Blocks.STONE.defaultBlockState());
            h.assertTrue(TechniqueService.tryStart(p,id),"cast refused");
            for(int tick=0;tick<=TechniqueLoader.get(id).totalTicks();tick++)TechniqueService.tick(p);
            h.assertTrue(z.getHealth()==1000,"hit through wall "+art);
            for(int x=6;x<=10;x++)for(int y=2;y<=4;y++)h.setBlock(new BlockPos(x,y,7),Blocks.AIR.defaultBlockState());
            DemonicExecutor.clear(p);z.discard();p.discard();
        }
        h.succeed();
    }
    @GameTest(template="camp_floor",timeoutTicks=20)
    public static void rejectsInsufficientQiAndManualsStayIndependent(GameTestHelper h) {
        for(String art:DemonicRules.ARTS) {
            var id=DemonicRules.id(art);ServerPlayer p=player(h,art,0,0);
            h.assertFalse(TechniqueService.tryStart(p,id),"zero qi cast "+art);
            h.assertTrue(p.getData(ModAttachments.PROFILE).circulating()==0,"failed cast charged qi");
            var stack=new ItemStack(ModItems.DEMONIC_MANUALS.get(art).get());
            h.assertTrue(id.equals(stack.get(ModDataComponents.TECHNIQUE.get())),"manual lost default art");
            h.assertTrue(io.github.verycooltimo.murim.mastery.PageService.teaches(id).equals(java.util.List.of(id)),"book teaches other demonic arts");
            p.discard();
        }
        h.succeed();
    }
    @GameTest(template="camp_floor",timeoutTicks=170)
    public static void maintainedArtsPayEachTickAndCooldownBeginsOnEnding(GameTestHelper h) {
        ServerPlayer run=player(h,"high_cloud_steps",8,40), guard=player(h,"quiet_heart_guard",8,40);
        var rid=DemonicRules.id("high_cloud_steps");var gid=DemonicRules.id("quiet_heart_guard");
        h.assertTrue(TechniqueService.tryStart(run,rid),"run start");h.assertTrue(TechniqueService.tryStart(guard,gid),"guard start");
        // Fake players aren't world-ticked. Complete their activation manually; server upkeep is real.
        for(int t=0;t<20;t++){TechniqueService.tick(run);TechniqueService.tick(guard);}
        h.assertTrue(DemonicExecutor.active(run,rid)&&DemonicExecutor.active(guard,gid),"activation missing");
        double runQi=run.getData(ModAttachments.PROFILE).circulating(),guardQi=guard.getData(ModAttachments.PROFILE).circulating();
        h.runAfterDelay(10,()->{
            h.assertTrue(Math.abs(runQi-run.getData(ModAttachments.PROFILE).circulating()-10*DemonicRules.upkeep("high_cloud_steps",8))<.015,"run upkeep multiplied or absent");
            h.assertTrue(Math.abs(guardQi-guard.getData(ModAttachments.PROFILE).circulating()-10*DemonicRules.upkeep("quiet_heart_guard",8))<.015,"guard upkeep multiplied or absent");
            h.assertTrue(Math.abs(DemonicExecutor.pressureFactor(guard)-.6)<1e-6,"pressure reduction missing");
        });
        h.runAfterDelay(145,()->{
            h.assertFalse(DemonicExecutor.active(run,rid)||DemonicExecutor.active(guard,gid),"maintained art never ended");
            h.assertTrue(Math.abs(DemonicExecutor.pressureFactor(guard)-1)<1e-6,"guard leaked");
            h.assertFalse(TechniqueService.tryStart(run,rid),"run CD measured from start");
            h.assertFalse(TechniqueService.tryStart(guard,gid),"guard CD measured from start");
            h.assertTrue(run.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(DemonicRules.id("high_cloud_speed"))==null,"speed leaked");
            DemonicExecutor.clear(run);DemonicExecutor.clear(guard);run.discard();guard.discard();h.succeed();
        });
    }
    @GameTest(template="camp_floor",timeoutTicks=30)
    public static void guardActuallyReducesDamageAndQiExhaustionRemovesIt(GameTestHelper h) {
        ServerPlayer p=player(h,"quiet_heart_guard",8,40);var id=DemonicRules.id("quiet_heart_guard");
        Zombie source=target(h);
        h.assertTrue(TechniqueService.tryStart(p,id),"guard activation refused");
        for(int tick=0;tick<20;tick++)TechniqueService.tick(p);
        float before=p.getHealth();p.invulnerableTime=0;
        h.assertTrue(p.hurt(source.damageSources().mobAttack(source),10),"guarded damage rejected");
        h.assertTrue(Math.abs(before-p.getHealth()-8)<.01,"guard did not reduce actual damage by 20%");
        p.setData(ModAttachments.PROFILE,p.getData(ModAttachments.PROFILE).withCirculating(0));
        h.runAfterDelay(2,()->{
            h.assertFalse(DemonicExecutor.active(p,id),"zero qi left guard active");
            h.assertTrue(Math.abs(DemonicExecutor.pressureFactor(p)-1)<1e-6,"exhausted pressure protection leaked");
            DemonicExecutor.clear(p);source.discard();p.discard();h.succeed();
        });
    }
    private DemonicGameTests() {}
}
