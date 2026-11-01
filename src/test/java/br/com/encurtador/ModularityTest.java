package br.com.encurtador;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Fronteiras entre módulos: sem ciclos e sem acesso a pacotes internos de outro módulo (ADR-0001, D2).
 */
class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(EncurtadorApplication.class);

    @Test
    void modulesRespectTheirBoundaries() {
        modules.verify();
    }
}
