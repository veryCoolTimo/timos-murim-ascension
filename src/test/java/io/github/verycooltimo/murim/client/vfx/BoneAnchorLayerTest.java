package io.github.verycooltimo.murim.client.vfx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class BoneAnchorLayerTest {
    @Test
    void forgetsEntitiesThatAreNoLongerDrawn() {
        BoneAnchorLayer.clear();
        UUID keep = UUID.randomUUID();
        for (int i = 0; i < 1000; i++) {
            BoneAnchorLayer.put(UUID.randomUUID(), BoneAnchorLayer.Bone.HEAD, Vec3.ZERO);
        }
        BoneAnchorLayer.put(keep, BoneAnchorLayer.Bone.HEAD, Vec3.ZERO);
        System.out.println("BONEANCHOR entries before: " + BoneAnchorLayer.size());
        for (int t = 0; t < 100; t++) {
            BoneAnchorLayer.advanceForTest(1);
            BoneAnchorLayer.put(keep, BoneAnchorLayer.Bone.HEAD, Vec3.ZERO);
        }
        System.out.println("BONEANCHOR entries after 100 ticks: " + BoneAnchorLayer.size());
        assertEquals(1, BoneAnchorLayer.size());
        BoneAnchorLayer.clear();
    }
}
