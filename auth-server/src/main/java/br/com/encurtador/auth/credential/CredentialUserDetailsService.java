package br.com.encurtador.auth.credential;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Carrega a credencial pelo e-mail para o login por formulário. O principal é o {@link User} do Spring Security, que
 * o serviço de autorização JDBC sabe serializar; o {@code sub} dos tokens vem da credencial (ADR-0003, D5).
 */
@Service
class CredentialUserDetailsService implements UserDetailsService {

    private final CredentialRepository credentials;

    CredentialUserDetailsService(CredentialRepository credentials) {
        this.credentials = credentials;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return credentials.findByEmail(email)
                .map(credential -> User.withUsername(credential.email()).password(credential.passwordHash()).build())
                .orElseThrow(() -> new UsernameNotFoundException("Conta inexistente"));
    }
}
