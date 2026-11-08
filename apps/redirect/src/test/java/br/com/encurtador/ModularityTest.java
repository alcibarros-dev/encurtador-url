package br.com.encurtador;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static org.assertj.core.api.Assertions.assertThat;

/** Fronteiras entre os módulos do serviço: o redirect só usa os contratos e a plataforma (ADR-0006, D3). */
class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(RedirectApplication.class);

    @Test
    void modulesRespectTheirBoundaries() {
        modules.verify();
    }

    @Test
    void onlyTheRedirectAndItsContractsAreInTheService() {
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
                .contains("redirect", "linkcache", "clicks")
                .doesNotContain("link", "analytics", "account", "security");
    }
}
