package br.com.encurtador.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Corpo do refresh e do logout. */
record RefreshTokenRequest(@NotBlank @Size(max = 128) String refreshToken) {

    @Override
    public String toString() {
        return "RefreshTokenRequest[***]";
    }
}
