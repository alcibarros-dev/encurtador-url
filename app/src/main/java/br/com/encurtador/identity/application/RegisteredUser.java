package br.com.encurtador.identity.application;

import java.time.Instant;

public record RegisteredUser(long id, String email, Instant createdAt) {
}
