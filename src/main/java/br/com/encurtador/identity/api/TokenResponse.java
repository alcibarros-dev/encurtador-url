package br.com.encurtador.identity.api;

import br.com.encurtador.identity.application.TokenPair;

/** {@code expiresIn} em segundos, como no OAuth 2.0 (RFC 6749, §5.1). */
record TokenResponse(String tokenType, String accessToken, long expiresIn, String refreshToken) {

    static TokenResponse from(TokenPair pair) {
        return new TokenResponse("Bearer", pair.accessToken(), pair.accessTokenExpiresIn().toSeconds(),
                pair.refreshToken());
    }

    @Override
    public String toString() {
        return "TokenResponse[expiresIn=" + expiresIn + "]";
    }
}
