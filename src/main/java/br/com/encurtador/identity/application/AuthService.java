package br.com.encurtador.identity.application;

import br.com.encurtador.identity.domain.PasswordPolicy;
import br.com.encurtador.identity.domain.RefreshToken;
import br.com.encurtador.identity.infra.UserRepository;
import br.com.encurtador.shared.error.ApiException;
import br.com.encurtador.shared.ratelimit.RateLimitExceededException;
import br.com.encurtador.shared.ratelimit.RateLimitProperties;
import br.com.encurtador.shared.ratelimit.RateLimiter;
import br.com.encurtador.shared.security.AccessTokenIssuer;
import br.com.encurtador.shared.security.AccessTokenIssuer.AccessToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Login, refresh e logout (RF02; ADR-0001, D9). */
@Service
public class AuthService {

    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN";

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthenticationManager authenticationManager;
    private final RefreshTokenService refreshTokens;
    private final AccessTokenIssuer accessTokens;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties.Limit emailLimit;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, RefreshTokenService refreshTokens,
                       AccessTokenIssuer accessTokens, RateLimiter rateLimiter, RateLimitProperties rateLimits) {
        // Não é bean: publicar um AuthenticationManager o tornaria o pai do gerenciador da cadeia JWT.
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(email -> users.findByEmail(email)
                .map(AccountDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("Conta inexistente")));
        provider.setPasswordEncoder(passwordEncoder);
        this.authenticationManager = new ProviderManager(provider);
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.rateLimiter = rateLimiter;
        this.emailLimit = rateLimits.loginEmail();
    }

    /**
     * E-mail inexistente e senha errada devolvem o mesmo 401 (corrige S2); o provider também compara a senha
     * contra um hash fictício quando a conta não existe, para que o tempo de resposta não denuncie.
     */
    public TokenPair login(String email, String password) {
        // O limite por IP fica no filtro; o por conta, aqui, onde o e-mail já chega normalizado (D11). Toda tentativa
        // conta, antes do bcrypt.
        if (rateLimiter.tryAcquire("login-email", emailLimit, email)
                instanceof RateLimiter.Decision.Rejected(var retryAfter)) {
            throw new RateLimitExceededException(retryAfter);
        }
        // Acima de 72 bytes o encoder lança exceção: a resposta é a mesma de senha errada, sem chamá-lo.
        if (!PasswordPolicy.fitsEncoder(password)) {
            throw invalidCredentials();
        }
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        } catch (InternalAuthenticationServiceException ex) {
            // Falha de infraestrutura (ex.: banco fora) não é credencial inválida: vira 500, não 401.
            throw new IllegalStateException("Falha ao verificar as credenciais", ex);
        } catch (AuthenticationException ex) {
            throw invalidCredentials();
        }
        long userId = ((AccountDetails) authentication.getPrincipal()).userId();
        log.info("Login (userId={})", userId);
        return tokensFor(userId, refreshTokens.startSession(userId));
    }

    /** A rotação já terminou (e foi commitada) quando o 401 é lançado: a revogação por reuso persiste. */
    public TokenPair refresh(String presentedToken) {
        return switch (refreshTokens.rotate(presentedToken)) {
            case RefreshOutcome.Issued(long userId, RefreshToken refreshToken) -> tokensFor(userId, refreshToken);
            case RefreshOutcome.Invalid _, RefreshOutcome.Reused _ -> throw new ApiException(HttpStatus.UNAUTHORIZED,
                    INVALID_REFRESH_TOKEN, "O refresh token é inválido, expirou ou já foi usado. Faça login de novo.");
        };
    }

    public void logout(String presentedToken) {
        refreshTokens.endSession(presentedToken);
    }

    private TokenPair tokensFor(long userId, RefreshToken refreshToken) {
        AccessToken accessToken = accessTokens.issue(userId);
        return new TokenPair(accessToken.value(), accessToken.expiresIn(), refreshToken.value());
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS, "E-mail ou senha inválidos.");
    }
}
