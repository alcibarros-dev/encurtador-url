package br.com.encurtador.link.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regras do destino (ADR-0001, D10): o que é aceito, como é normalizado e por que o resto é recusado. */
class TargetUrlTest {

    private final HostClassifier hosts = new HostClassifier("encurta.dor");

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            https://exemplo.com/a                 | https://exemplo.com/a                          | exemplo.com
            HTTPS://Exemplo.COM/Caminho?Q=1#Frag  | https://exemplo.com/Caminho?Q=1#Frag           | exemplo.com
            '  https://exemplo.com  '             | https://exemplo.com                            | exemplo.com
            https://exemplo.com./a                | https://exemplo.com/a                          | exemplo.com
            https://exemplo.com:8443/a            | https://exemplo.com:8443/a                     | exemplo.com
            https://exemplo.com/ação?q=café       | https://exemplo.com/a%C3%A7%C3%A3o?q=caf%C3%A9 | exemplo.com
            https://exemplo.com/a%2Fb%20c         | https://exemplo.com/a%2Fb%20c                  | exemplo.com
            http://203.0.113.10/x                 | http://203.0.113.10/x                          | 203.0.113.10
            http://[2001:DB8::1]:8080/x           | http://[2001:db8::1]:8080/x                    | [2001:db8::1]
            https://xn--caf-dma.com.br/           | https://xn--caf-dma.com.br/                    | xn--caf-dma.com.br
            https://meuencurta.dor/               | https://meuencurta.dor/                        | meuencurta.dor
            """)
    void acceptsAndNormalizes(String raw, String expectedValue, String expectedHost) {
        TargetUrl target = TargetUrl.parse(raw, hosts);

        assertThat(target.value()).isEqualTo(expectedValue);
        assertThat(target.host()).isEqualTo(expectedHost);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            javascript:alert(1)                    | deve usar http ou https
            data:text/html;base64,PHNjcmlwdD4=     | deve usar http ou https
            file:///etc/passwd                     | deve usar http ou https
            ftp://exemplo.com/                     | deve usar http ou https
            //exemplo.com/a                        | deve ser uma URL absoluta
            exemplo.com/a                          | deve ser uma URL absoluta
            http:exemplo.com                       | deve ser uma URL absoluta
            https://banco.com@golpe.com/           | não pode ter usuário ou senha
            https://usuario:senha@exemplo.com/     | não pode ter usuário ou senha
            https://exemplo.com/a b                | não pode conter espaços
            https://exemplo.com/a<b>               | não é uma URL válida
            https://exemplo.com:0/                 | porta fora do intervalo
            https://exemplo.com:65536/             | porta fora do intervalo
            https://açaí.com.br/                   | Punycode
            https://meu_host.com/                  | não tem um host válido
            https:///caminho                       | não tem um host válido
            http://LocalHost:8080/                 | localhost
            http://painel.localhost/               | localhost
            https://encurta.dor/abc                | próprio encurtador
            https://www.ENCURTA.DOR./abc           | próprio encurtador
            http://127.0.0.1/                      | IP privado, local ou reservado
            http://169.254.169.254/latest          | IP privado, local ou reservado
            http://2130706433/                     | forma canônica
            http://0177.0.0.1/                     | forma canônica
            http://0x7F000001/                     | forma canônica
            http://[::1]/                          | IP privado, local ou reservado
            http://[::ffff:192.168.0.1]/           | IP privado, local ou reservado
            http://[fe80::1%25eth0]/               | zona IPv6
            """)
    void rejectsWithAClearReason(String raw, String reason) {
        assertThatThrownBy(() -> TargetUrl.parse(raw, hosts))
                .isInstanceOf(InvalidValueException.class)
                .hasMessageContaining(reason);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void isRequired(String raw) {
        assertThatThrownBy(() -> TargetUrl.parse(raw, hosts)).hasMessage("é obrigatória");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://exemplo.com/a\r\nSet-Cookie: x=1", "https://exemplo.com/\u0000",
            "https://exemplo.com/a\u00A0b", "https://exemplo.com/a\u2028b"})
    void controlAndSpaceCharactersCannotReachTheLocationHeader(String raw) {
        assertThatThrownBy(() -> TargetUrl.parse(raw, hosts)).hasMessageContaining("caracteres de controle");
    }

    @Test
    void inputLongerThanTheColumnIsRejected() {
        String raw = "https://exemplo.com/" + "a".repeat(TargetUrl.MAX_LENGTH);

        assertThatThrownBy(() -> TargetUrl.parse(raw, hosts)).hasMessage("deve ter no máximo 2048 caracteres");
    }

    @Test
    void lengthIsCheckedAgainAfterPercentEncoding() {
        // 1.020 caracteres na entrada; cada "ç" vira "%C3%A7" e a URL final passa de 6 mil.
        String raw = "https://exemplo.com/" + "ç".repeat(1000);

        assertThatThrownBy(() -> TargetUrl.parse(raw, hosts))
                .hasMessage("passa de 2048 caracteres depois de codificada");
    }
}
