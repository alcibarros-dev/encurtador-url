package br.com.encurtador.auth.token;

import br.com.encurtador.auth.config.AuthServerProperties.SigningKey;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningKeysTest {

    @Test
    void loadsAPkcs8PemAndDerivesThePublicKey() throws Exception {
        RSAKey key = SigningKeys.load(new SigningKey("k1", new ClassPathResource("keys/test-signing-key.pem")));

        assertThat(key.getKeyID()).isEqualTo("k1");
        assertThat(key.isPrivate()).isTrue();
        assertThat(key.toRSAPublicKey().getModulus()).isEqualTo(key.toRSAPrivateKey().getModulus());
    }

    /** O kid muda a cada chave gerada: o app, com a chave anterior em cache, busca o JWKS de novo. */
    @Test
    void withoutAFileGeneratesAKeyForThisProcessWithAUniqueKid() {
        RSAKey key = SigningKeys.load(new SigningKey("efemera", null));
        RSAKey next = SigningKeys.load(new SigningKey("efemera", null));

        assertThat(key.getKeyID()).matches("efemera-[0-9a-f]{8}");
        assertThat(next.getKeyID()).isNotEqualTo(key.getKeyID());
        assertThat(key.size()).isEqualTo(2048);
    }

    @Test
    void rejectsAnythingButPkcs8() {
        var pem = new ByteArrayResource("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----"
                .getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> SigningKeys.load(new SigningKey("k", pem))).hasMessageContaining("PKCS#8");
    }
}
