package br.com.encurtador.identity.application;

import br.com.encurtador.identity.domain.PasswordPolicy;
import br.com.encurtador.identity.domain.User;
import br.com.encurtador.identity.infra.UserRepository;
import br.com.encurtador.shared.error.ApiException;
import br.com.encurtador.shared.error.UnknownAccountException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/** Troca de senha (RF03; ADR-0001, D9; corrige S3). */
@Service
public class PasswordService {

    public static final String WRONG_CURRENT_PASSWORD = "WRONG_CURRENT_PASSWORD";

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PasswordService(UserRepository users, PasswordEncoder passwordEncoder, RefreshTokenService refreshTokens,
                           TransactionTemplate transaction, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * Exige a senha atual e revoga todas as sessões. O access token em uso continua válido até expirar
     * (no máximo 15 minutos, aceito no ADR).
     */
    public void change(long userId, String currentPassword, String newPassword) {
        User user = users.findById(userId).orElseThrow(UnknownAccountException::new);
        if (!PasswordPolicy.fitsEncoder(currentPassword)
                || !passwordEncoder.matches(currentPassword, user.passwordHash())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, WRONG_CURRENT_PASSWORD,
                    "A senha atual não confere.");
        }
        // O bcrypt é lento de propósito: roda fora da transação para não reter uma conexão do pool.
        String newHash = passwordEncoder.encode(newPassword);
        transaction.executeWithoutResult(status -> {
            users.updatePasswordHash(userId, newHash, clock.instant());
            refreshTokens.endAllSessions(userId);
        });
        log.info("Senha alterada e sessões revogadas (userId={})", userId);
    }
}
