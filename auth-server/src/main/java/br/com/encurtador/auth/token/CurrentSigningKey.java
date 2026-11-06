package br.com.encurtador.auth.token;

import com.nimbusds.jose.jwk.RSAKey;

/** Chave RSA atual, já carregada: a que assina os tokens e cujo {@code kid} vai no cabeçalho (ADR-0003, D5). */
record CurrentSigningKey(RSAKey key) {

    String keyId() {
        return key.getKeyID();
    }
}
