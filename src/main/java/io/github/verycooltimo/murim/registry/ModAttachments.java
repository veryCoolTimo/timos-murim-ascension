package io.github.verycooltimo.murim.registry;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniqueState;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Data Attachments мода. В 1.21.1 это штатная замена capabilities.
 */
public final class ModAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MurimMod.MODID);

    /**
     * Состояние применяемой техники. Без сериализации и без синхронизации: техника короче двух
     * секунд, клиент узнаёт о ней отдельным пакетом, а состояние, пережившее перезаход, вернуло бы
     * игрока в мир посреди замаха.
     */
    public static final Supplier<AttachmentType<TechniqueState>> TECHNIQUE_STATE =
            ATTACHMENT_TYPES.register("technique_state",
                    () -> AttachmentType.<TechniqueState>builder(() -> TechniqueState.IDLE).build());

    private ModAttachments() {
    }
}
