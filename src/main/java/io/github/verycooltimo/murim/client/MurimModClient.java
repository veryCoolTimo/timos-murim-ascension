package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Клиентская точка входа. Класс не загружается на dedicated server, поэтому обращаться
 * к {@code net.minecraft.client.*} отсюда безопасно.
 *
 * <p>Обратное неверно: из общего кода клиентские классы недоступны. Всё, что рисует,
 * слушает ввод или обращается к {@code Minecraft.getInstance()}, живёт в этом пакете.
 */
@Mod(value = MurimMod.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public class MurimModClient {
    public MurimModClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        // Качество эффектов и доступность принадлежат клиенту: на сервер они не уезжают
        // и в сейве не хранятся.
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ClientConfig.SPEC);
    }

    /**
     * Слой привязки к костям вешается на оба варианта модели игрока: тонкие и обычные руки.
     * Пропустить один значит потерять привязку у половины скинов.
     */
    @SubscribeEvent
    static void onAddLayers(net.neoforged.neoforge.client.event.EntityRenderersEvent.AddLayers event) {
        for (net.minecraft.client.resources.PlayerSkin.Model skin : event.getSkins()) {
            net.minecraft.client.renderer.entity.LivingEntityRenderer<
                    net.minecraft.client.player.AbstractClientPlayer,
                    net.minecraft.client.model.PlayerModel<net.minecraft.client.player.AbstractClientPlayer>>
                    renderer = event.getSkin(skin);
            if (renderer != null) {
                renderer.addLayer(new io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer(renderer));
            }
        }
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        MurimMod.LOGGER.info("Murim: client setup");
        // Слой анимаций регистрируется один раз; PAL сам создаст контроллер каждому игроку.
        MurimPlayerAnimations.register();
    }
}
