package br.com.encurtador.redirect.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rota do redirect aceita exatamente os códigos que a API cria (ADR-0006, D2). O redirect não depende do módulo
 * {@code link}, nem nos testes, onde ele traria o JPA para o contexto: o padrão é lido do código-fonte do
 * {@code ShortCode}, como o Flyway dos testes lê as migrações da API da pasta dela.
 */
class CodePatternTest {

    private static final Path SHORT_CODE =
            Path.of("../../modules/link/src/main/java/br/com/encurtador/link/domain/ShortCode.java");

    @Test
    void routePatternIsTheShortCodePattern() throws IOException {
        Matcher declaration = Pattern.compile("String PATTERN = \"(.*)\";").matcher(Files.readString(SHORT_CODE));

        assertThat(declaration.find()).as("declaração de ShortCode.PATTERN").isTrue();
        assertThat(RedirectController.CODE_PATTERN).isEqualTo(declaration.group(1));
    }
}
