package io.github.verycooltimo.murim.world;

import net.minecraft.core.BlockPos;

/**
 * Найденное место силы (docs/design/19b §3): камень жилы или природное место — пик, вода,
 * старое дерево (автор 03.10: «также должно работать около деревьев, гор и т. д.»).
 *
 * @param anchor точка места: от неё идут аура, поток к медитирующему и цикл волн
 * @param kind   вид — от него цвет и волна
 * @param stone  камень жилы (×2) или природное место (×1,5)
 */
public record Place(BlockPos anchor, PlaceKind kind, boolean stone) {

    public double gain() {
        return stone ? PlaceRules.GAIN : PlaceRules.NATURAL_GAIN;
    }
}
