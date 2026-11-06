package br.com.encurtador.auth.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/** Cadastro de contas (ADR-0001 RF01 e D9; ADR-0003, D8 e D9). */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final CredentialRepository credentials;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public RegistrationService(CredentialRepository credentials, PasswordEncoder passwordEncoder, Clock clock) {
        this.credentials = credentials;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * Recebe e-mail e senha já normalizados e validados. Usuário novo recebe um UUID como {@code sub}, que nunca
     * colide com o {@code sub} numérico dos usuários importados (D8).
     *
     * @return a credencial criada, ou vazio se o e-mail já tem conta (enumeração aceita, ADR-0001 R7)
     */
    public Optional<Credential> register(String email, String password) {
        String passwordHash = passwordEncoder.encode(password);
        Optional<Credential> created = credentials.insertIfEmailAvailable(UUID.randomUUID().toString(), email,
                passwordHash, clock.instant().truncatedTo(ChronoUnit.MICROS));
        created.ifPresent(credential -> log.info("Conta criada (subject={})", credential.subject()));
        return created;
    }
}
