package br.com.encurtador.identity.application;

import br.com.encurtador.identity.domain.User;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/** Visão de {@link User} para o {@code DaoAuthenticationProvider}. Sem papéis na v1. */
final class AccountDetails implements UserDetails, CredentialsContainer {

    private final long userId;
    private final String email;
    private String passwordHash;

    AccountDetails(User user) {
        this.userId = user.id();
        this.email = user.email();
        this.passwordHash = user.passwordHash();
    }

    long userId() {
        return userId;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }
}
