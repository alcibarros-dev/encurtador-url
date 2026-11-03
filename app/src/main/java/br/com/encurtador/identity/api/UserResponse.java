package br.com.encurtador.identity.api;

import br.com.encurtador.identity.application.RegisteredUser;

import java.time.Instant;

record UserResponse(long id, String email, Instant createdAt) {

    static UserResponse from(RegisteredUser user) {
        return new UserResponse(user.id(), user.email(), user.createdAt());
    }
}
