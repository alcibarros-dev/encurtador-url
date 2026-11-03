package br.com.encurtador.identity.application;

import java.time.Duration;

/** Access token (JWT) e refresh token (opaco) entregues no login e no refresh. */
public record TokenPair(String accessToken, Duration accessTokenExpiresIn, String refreshToken) {

    @Override
    public String toString() {
        return "TokenPair[accessTokenExpiresIn=" + accessTokenExpiresIn + "]";
    }
}
