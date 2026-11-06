package br.com.encurtador.auth.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/** Troca de senha (ADR-0001 RF03 e D9; ADR-0003, D9). */
@Service
public class PasswordChangeService {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeService.class);

    private final CredentialRepository credentials;
    private final PasswordEncoder passwordEncoder;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PasswordChangeService(CredentialRepository credentials, PasswordEncoder passwordEncoder, JdbcClient jdbc,
                                 TransactionTemplate transaction, Clock clock) {
        this.credentials = credentials;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * Exige a senha atual e encerra todas as autorizações do usuário: os refresh tokens deixam de valer, e o painel
     * leva o usuário ao login na renovação seguinte. O access token em uso vale até expirar (no máximo 15 minutos).
     *
     * @return {@code false} se a senha atual não confere
     */
    public boolean change(String email, String currentPassword, String newPassword) {
        var credential = credentials.findByEmail(email).orElse(null);
        if (credential == null || !PasswordPolicy.fitsEncoder(currentPassword)
                || !passwordEncoder.matches(currentPassword, credential.passwordHash())) {
            return false;
        }
        // O bcrypt é lento de propósito: roda fora da transação para não reter uma conexão do pool.
        String newHash = passwordEncoder.encode(newPassword);
        transaction.executeWithoutResult(status -> {
            credentials.updatePasswordHash(email, newHash, clock.instant());
            jdbc.sql("delete from oauth2_authorization where principal_name = :email").param("email", email).update();
        });
        log.info("Senha alterada e autorizações encerradas (subject={})", credential.subject());
        return true;
    }
}
