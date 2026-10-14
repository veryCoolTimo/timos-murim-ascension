package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.mastery.Arts;
import io.github.verycooltimo.murim.mastery.StarDisplay;
import io.github.verycooltimo.murim.network.SyncMasteryPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

public final class MasteryText {
    public static MutableComponent summary(ResourceLocation form, SyncMasteryPayload.Entry entry) {
        return summary(form, entry == null ? 0 : entry.stars());
    }

    public static MutableComponent summary(ResourceLocation form, int stars) {
        int cap = Arts.cap(form);
        return Component.translatable("murim.mastery.stars", StarDisplay.gauge(stars, cap), stars, cap);
    }

    private MasteryText() {}
}
