package br.com.encurtador.identity.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenTest {

    @Test
    void generates256RandomBitsAsBase64Url() {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            values.add(RefreshToken.generate().value());
        }

        assertThat(values).hasSize(1_000).allSatisfy(value -> assertThat(value).matches("[A-Za-z0-9_-]{43}"));
    }

    @Test
    void hashIsLowercaseHexSha256() {
        // Vetor de teste do FIPS 180-2.
        assertThat(RefreshToken.hashOf("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        RefreshToken token = RefreshToken.generate();
        assertThat(token.hash()).isEqualTo(RefreshToken.hashOf(token.value())).hasSize(64);
    }

    @Test
    void toStringDoesNotLeakTheToken() {
        RefreshToken token = RefreshToken.generate();

        assertThat(token.toString()).doesNotContain(token.value());
    }
}
