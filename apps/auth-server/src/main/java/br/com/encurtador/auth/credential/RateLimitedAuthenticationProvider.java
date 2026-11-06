package br.com.encurtador.auth.credential;

import br.com.encurtador.ratelimit.ClientAddress;
import br.com.encurtador.ratelimit.RateLimitProperties.Limit;
import br.com.encurtador.ratelimit.RateLimiter;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * Login por formulário com os limites do ADR-0003 D9: por IP e por e-mail, consultados antes do bcrypt, que é lento de
 * propósito. O formulário do Spring Security não passa por um serviço nosso, por isso os limites ficam aqui.
 * <p>
 * E-mail e senha são normalizados como no cadastro; senha acima de 72 bytes recebe a mesma resposta de senha errada,
 * sem chegar ao encoder. E-mail inexistente e senha errada são indistinguíveis (o provider do Spring Security também
 * compara um hash fictício quando a conta não existe).
 */
public final class RateLimitedAuthenticationProvider implements AuthenticationProvider {

    private final DaoAuthenticationProvider delegate;
    private final RateLimiter limiter;
    private final Limit byIp;
    private final Limit byEmail;

    public RateLimitedAuthenticationProvider(UserDetailsService users, PasswordEncoder passwordEncoder,
                                             RateLimiter limiter) {
        this.delegate = new DaoAuthenticationProvider(users);
        this.delegate.setPasswordEncoder(passwordEncoder);
        this.limiter = limiter;
        this.byIp = limiter.limit("login");
        this.byEmail = limiter.limit("login-email");
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String email = EmailAddress.normalize(authentication.getName());
        String password = PasswordPolicy.normalize((String) authentication.getCredentials());
        if (authentication.getDetails() instanceof WebAuthenticationDetails details
                && details.getRemoteAddress() != null) {
            acquire("login", byIp, ClientAddress.subjectOf(details.getRemoteAddress()));
        }
        acquire("login-email", byEmail, email);
        if (password == null || !PasswordPolicy.fitsEncoder(password)) {
            throw new BadCredentialsException("Credenciais inválidas");
        }
        var normalized = UsernamePasswordAuthenticationToken.unauthenticated(email, password);
        normalized.setDetails(authentication.getDetails());
        return delegate.authenticate(normalized);
    }

    private void acquire(String rule, Limit limit, String subject) {
        if (limiter.tryAcquire(rule, limit, subject) instanceof RateLimiter.Decision.Rejected(var retryAfter)) {
            throw new LoginBlockedException(retryAfter);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
