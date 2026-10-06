package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Optional;

/**
 * Стенд разговора (только {@code -Dmurim.capture=true} и техника {@code dialogue}): через пару секунд после
 * входа ставит NPC секты перед игроком — собеседник {@code MURIM_CAPTURE_NPC} в трёх с половиной блоках,
 * остальные позади него; флаги секты — {@code MURIM_CAPTURE_SECT="member,lesson.six"}.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectCapture {

    private static int pending;
    /** Ещё столько тиков стенд дочищает сцену: свою цель он может поставить позже нас. */
    private static int sweep;

    private SectCapture() {
    }

    private static boolean enabled() {
        return Boolean.getBoolean("murim.capture") && "dialogue".equals(System.getProperty("murim.capture.technique"));
    }

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (enabled()) {
            pending = 50;
        }
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (sweep > 0 && --sweep % 5 == 0 && !event.getServer().getPlayerList().getPlayers().isEmpty()) {
            clean(event.getServer().getPlayerList().getPlayers().get(0));
        }
        // Ночной стенд: враждебные мобы не мешают разговору (ученики секты — тоже Monster, их не трогаем).
        if (enabled() && event.getServer().getTickCount() % 10 == 0 && !event.getServer().getPlayerList().getPlayers().isEmpty()) {
            ServerPlayer p = event.getServer().getPlayerList().getPlayers().get(0);
            event.getServer().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, event.getServer());
            for (net.minecraft.world.entity.monster.Monster m : p.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
                    p.getBoundingBox().inflate(64.0D), m -> !(m instanceof SectDisciple))) {
                m.discard();
            }
        }
        if (pending <= 0 || --pending > 0 || event.getServer().getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        sweep = 40;
        ServerPlayer p = event.getServer().getPlayerList().getPlayers().get(0);
        ServerLevel level = p.serverLevel();
        for (SectDisciple old : level.getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(32.0D))) {
            old.discard();
        }
        clean(p);
        SectRole talk = SectRole.of(System.getenv().getOrDefault("MURIM_CAPTURE_NPC", "mentor"));
        Vec3 fwd = Vec3.directionFromRotation(0.0F, p.getYRot());
        Vec3 right = Vec3.directionFromRotation(0.0F, p.getYRot() + 90.0F);
        double dist = Double.parseDouble(System.getenv().getOrDefault("MURIM_CAPTURE_NPC_DIST", "3.5"));
        // MURIM_CAPTURE_PEOPLE="seo_rang,mok_hayeon,yul_ak" — вместо собеседника по роли люди горы по ключу, в ряд
        // перед игроком (личные диалоги, 05.10); стенд говорит с ними по очереди (DialogueCapture: use@ключ).
        String people = System.getenv().getOrDefault("MURIM_CAPTURE_PEOPLE", "");
        if (!people.isBlank()) {
            String[] keys = people.split(",");
            for (int k = 0; k < keys.length; k++) {
                double side = (k - (keys.length - 1) / 2.0D) * 1.8D;
                SectRoster m = SectRoster.of(keys[k].trim()).orElse(null);
                if (m != null) {
                    SectDisciple npc = SectService.spawn(level, m.role(), p.position().add(fwd.scale(Math.min(dist, 2.5D))).add(right.scale(side)),
                            p.position());
                    npc.setMember(m);
                    // На горе этого мира люди ушли бы по распорядку к своим площадкам: на стенде они стоят.
                    npc.setNoAi(true);
                }
            }
        } else {
            SectService.spawn(level, talk, p.position().add(fwd.scale(dist)), p.position());
        }
        double[][] spots = {{8.0D, -4.0D}, {9.0D, 3.5D}, {12.0D, -1.0D}, {11.0D, 6.5D}};
        int i = 0;
        for (SectRole r : SectRole.values()) {
            if (r != talk && i < spots.length && people.isBlank()) {
                SectService.spawn(level, r, p.position().add(fwd.scale(spots[i][0])).add(right.scale(spots[i][1])), p.position());
                i++;
            }
        }
        SectState s = SectState.NONE;
        for (String f : System.getenv().getOrDefault("MURIM_CAPTURE_SECT", "").split(",")) {
            if ("member".equals(f.trim())) {
                s = s.joined();
            } else if (!f.isBlank()) {
                s = s.with(f.trim());
            }
        }
        p.setData(ModAttachments.SECT, s);
        // Шесть Равновесий в ячейке основы и меч в руке — как у ученика после вступления.
        p.setData(ModAttachments.LOADOUT, p.getData(ModAttachments.LOADOUT).withFoundation(Optional.of(SectService.SIX)));
        io.github.verycooltimo.murim.mastery.LoadoutService.sync(p);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        MurimMod.LOGGER.info("Стенд разговора: NPC {} перед игроком, флаги {}", talk.id(), s.flags());
    }

    private static void clean(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        // Сцена чистая: манекены и зомби стенда убраны .
        for (net.minecraft.world.entity.Entity e : level.getEntities((net.minecraft.world.entity.Entity) null, p.getBoundingBox().inflate(32.0D),
                e -> e instanceof io.github.verycooltimo.murim.entity.TrainingDummy || e instanceof net.minecraft.world.entity.monster.Zombie
                        || e instanceof net.minecraft.world.entity.decoration.ArmorStand && e.getCustomName() == null)) {
            e.discard();
        }
    }
}
