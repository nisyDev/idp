package com.empresa.pdi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdiApplicationTests {

    @Test
    void applicationDeclaresPublicStaticMain() throws NoSuchMethodException {
        var main = PdiApplication.class.getDeclaredMethod(
                "main", String[].class);

        assertTrue(Modifier.isPublic(main.getModifiers()));
        assertTrue(Modifier.isStatic(main.getModifiers()));
        assertEquals(void.class, main.getReturnType());
    }
}
