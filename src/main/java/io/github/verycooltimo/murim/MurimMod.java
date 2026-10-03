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

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Murim: common setup");
    }
}
