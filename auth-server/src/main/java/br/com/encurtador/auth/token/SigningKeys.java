package br.com.encurtador.auth.token;

import br.com.encurtador.auth.config.AuthServerProperties.SigningKey;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Lê a chave RSA de assinatura de um PEM PKCS#8 (ADR-0003, D5). A chave pública é derivada da privada.
 * <p>
 * Sem arquivo, gera uma chave na subida: serve para experimentar a stack, mas todo token deixa de valer quando o
 * processo reinicia. O {@code kid} dela ganha um sufixo aleatório: com o mesmo {@code kid} da chave anterior, o app,
 * que guarda as chaves em cache, recusaria os tokens novos sem buscar o JWKS de novo (ADR-0003, D6).
 */
final class SigningKeys {

    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private SigningKeys() {
    }

    static RSAKey load(SigningKey key) {
        try {
            RSAPrivateCrtKey privateKey;
            RSAPublicKey publicKey;
            String keyId = key.id();
            if (key.location() == null) {
                log.warn("Sem app.auth.signing-key.location: usando uma chave gerada agora, que some no reinício");
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                privateKey = (RSAPrivateCrtKey) pair.getPrivate();
                publicKey = (RSAPublicKey) pair.getPublic();
                byte[] suffix = new byte[4];
                RANDOM.nextBytes(suffix);
                keyId = key.id() + "-" + HexFormat.of().formatHex(suffix);
            } else {
                privateKey = readPrivateKey(key);
                publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            }
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(keyId)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Chave de assinatura inválida (kid=%s)".formatted(key.id()), ex);
        }
    }

    private static RSAPrivateCrtKey readPrivateKey(SigningKey key) throws GeneralSecurityException {
        String pem;
        try (InputStream in = key.location().getInputStream()) {
            pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException ex) {
            throw new IllegalStateException("Não foi possível ler a chave de assinatura em " + key.location(), ex);
        }
        if (!pem.contains("-----BEGIN PRIVATE KEY-----")) {
            throw new IllegalStateException("A chave de assinatura precisa estar em PEM PKCS#8 (BEGIN PRIVATE KEY)");
        }
        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }
}
