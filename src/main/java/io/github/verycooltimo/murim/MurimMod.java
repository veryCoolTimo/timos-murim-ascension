package io.github.verycooltimo.murim;

import com.mojang.logging.LogUtils;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

/**
 * Точка входа мода. Значение аннотации обязано совпадать с {@code mod_id} из gradle.properties
 * и с идентификатором в neoforge.mods.toml.
 *
 * <p>Здесь только подписка на шины и регистрация DeferredRegister-холдеров. Игровая логика
 * живёт в подпакетах; клиентский код — исключительно в {@code client}.
 */
@Mod(MurimMod.MODID)
public class MurimMod {
    public static final String MODID = "murim";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * FML сам подставляет параметры известных типов — шину модовых событий и контейнер мода.
     */
    public MurimMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);
        io.github.verycooltimo.murim.registry.ModEntities.register(modEventBus);
        io.github.verycooltimo.murim.registry.ModDataComponents.register(modEventBus);
        io.github.verycooltimo.murim.registry.ModItems.register(modEventBus);
        io.github.verycooltimo.murim.world.ModWorld.register(modEventBus);
        io.github.verycooltimo.murim.registry.ModCreativeTabs.register(modEventBus);
        io.github.verycooltimo.murim.registry.ModSounds.register(modEventBus);
        io.github.verycooltimo.murim.registry.ModBlocks.register(modEventBus);
        io.github.verycooltimo.murim.registry.ModFeatures.register(modEventBus);
        io.github.verycooltimo.murim.library.ModLibrary.register(modEventBus);
        io.github.verycooltimo.murim.world.camp.BanditCamp.register(modEventBus);
        io.github.verycooltimo.murim.trade.ModTrade.register(modEventBus);
        io.github.verycooltimo.murim.npc.ModNpcs.register(modEventBus);
        io.github.verycooltimo.murim.entity.boss.BossRegistry.register(modEventBus);
        io.github.verycooltimo.murim.world.fortress.Fortress.register(modEventBus);
        io.github.verycooltimo.murim.training.TrainingRegistry.register(modEventBus);
        io.github.verycooltimo.murim.sect.seal.SealRegistry.register(modEventBus);
        io.github.verycooltimo.murim.decor.SectPlaques.register(modEventBus);
        io.github.verycooltimo.murim.decor.AncPlaques.register(modEventBus);
        io.github.verycooltimo.murim.decor.EldPlaques.register(modEventBus);
        io.github.verycooltimo.murim.decor.GateDoors.register(modEventBus);
        io.github.verycooltimo.murim.decor.HallGrandDoors.register(modEventBus);
        io.github.verycooltimo.murim.decor.WeaponRacks.register(modEventBus);
        io.github.verycooltimo.murim.decor.HallDecor.register(modEventBus);
        io.github.verycooltimo.murim.decor.SectMissionBoard.register(modEventBus);
        io.github.verycooltimo.murim.decor.RoofTiles.register(modEventBus);
        io.github.verycooltimo.murim.decor.Pailou.register(modEventBus);
        io.github.verycooltimo.murim.decor.HallLights.register(modEventBus);
        io.github.verycooltimo.murim.decor.ShrineDecor.register(modEventBus);
        io.github.verycooltimo.murim.decor.SectOrnaments.register(modEventBus);
        io.github.verycooltimo.murim.decor.CampDecor.register(modEventBus);
        io.github.verycooltimo.murim.decor.CampPlaques.register(modEventBus);
        io.github.verycooltimo.murim.decor.HallWindows.register(modEventBus);
        io.github.verycooltimo.murim.decor.HallJoinery.register(modEventBus);
        io.github.verycooltimo.murim.decor.HallFurniture.register(modEventBus);
        io.github.verycooltimo.murim.decor.IntSeating.register(modEventBus);
        io.github.verycooltimo.murim.decor.IntStore.register(modEventBus);
        io.github.verycooltimo.murim.world.location.ModLocations.register(modEventBus);
        io.github.verycooltimo.murim.world.hua.HuaBiomes.register(modEventBus);
        io.github.verycooltimo.murim.world.hua.HuaGround.register(modEventBus);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Murim: common setup");
    }
}
