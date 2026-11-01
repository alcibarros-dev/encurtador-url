package br.com.encurtador.identity.application;

import br.com.encurtador.identity.infra.UserRepository;
import br.com.encurtador.shared.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Cadastro de contas (RF01; ADR-0001, D9). */
@Service
public class RegistrationService {

    public static final String EMAIL_TAKEN = "EMAIL_TAKEN";

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public RegistrationService(UserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * Recebe e-mail e senha já normalizados e validados pela API. O 409 para e-mail em uso permite enumerar contas;
     * é uma escolha consciente, contida pelo rate limit (ADR-0001, R7).
     */
    public RegisteredUser register(String email, String password) {
        String passwordHash = passwordEncoder.encode(password);
        // Precisão do timestamptz: a resposta mostra exatamente o instante gravado.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long id = users.insertIfEmailAvailable(email, passwordHash, now)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, EMAIL_TAKEN,
                        "Já existe uma conta com este e-mail."));
        log.info("Conta criada (userId={})", id);
        return new RegisteredUser(id, email, now);
    }
}
