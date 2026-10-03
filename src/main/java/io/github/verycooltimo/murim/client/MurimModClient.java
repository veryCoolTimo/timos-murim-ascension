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
                // Ци-меч в пустой руке (03.10).
                renderer.addLayer(new QiSwordClient.Layer(renderer));
            }
        }
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        MurimMod.LOGGER.info("Murim: client setup");
        // Слой анимаций регистрируется один раз; PAL сам создаст контроллер каждому игроку.
        MurimPlayerAnimations.register();
        // Книга-предмет по уровню техники: 0 — обычная, 0.5 — форма стиля (слива), 1 — секретная.
        // API: build/moddev/artifacts/neoforge-21.1.248.jar#ItemProperties.register (public в NeoForge).
        event.enqueueWork(() -> net.minecraft.client.renderer.item.ItemProperties.register(
                io.github.verycooltimo.murim.registry.ModItems.TECHNIQUE_MANUAL.get(),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "book_tier"),
                (stack, level, entity, seed) -> {
                    net.minecraft.resources.ResourceLocation id = stack.get(io.github.verycooltimo.murim.registry.ModDataComponents.TECHNIQUE.get());
                    io.github.verycooltimo.murim.technique.TechniqueDefinition d = id == null ? null
                            : io.github.verycooltimo.murim.technique.TechniqueLoader.get(id);
                    if (d == null) {
                        return 0.0F;
                    }
                    if (d.tier() == io.github.verycooltimo.murim.mastery.TechniqueTier.SECRET || id.getPath().startsWith("twenty_four_plum")) {
                        return 1.0F;
                    }
                    return io.github.verycooltimo.murim.technique.Styles.of(id).isPresent() ? 0.5F : 0.0F;
                }));
    }
}
