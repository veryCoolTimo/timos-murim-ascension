package io.github.verycooltimo.murim.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Основа меча: формы по слоям и темп под оружие. */
class FoundationFormsTest {

    @Test
    @DisplayName("Слой открывает формы: 0 — только первая, 4 — все шесть")
    void unlocking() {
        assertEquals(1, FoundationForms.unlocked(0));
        assertEquals(6, FoundationForms.unlocked(4));
        assertEquals(FoundationForms.Form.DOWN_SLASH, FoundationForms.at(5, 0));
        assertEquals(FoundationForms.Form.THRUST, FoundationForms.at(1, 1));
        assertEquals(FoundationForms.Form.DOWN_SLASH, FoundationForms.at(2, 1));
    }

    @Test
    @DisplayName("Форма не медленнее ванили: скорость анимации — номинал ÷ перезарядка")
    void tempo() {
        assertEquals(1.0F, FoundationForms.speed(FoundationForms.NOMINAL_TICKS), 1.0E-6F);
        assertTrue(FoundationForms.speed(10.0F) > 1.0F);
        assertTrue(FoundationForms.chainWindow(12.5F) > 12.5F);
    }
}
