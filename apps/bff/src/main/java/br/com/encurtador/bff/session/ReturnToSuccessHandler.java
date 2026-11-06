package br.com.encurtador.bff.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;

/** Depois do login, volta para o destino guardado no início dele, ou para {@code /links} (ADR-0004, D4). */
public final class ReturnToSuccessHandler implements AuthenticationSuccessHandler {

    private final RedirectStrategy redirects = new DefaultRedirectStrategy();

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        HttpSession session = request.getSession(false);
        String target = ReturnTo.DEFAULT;
        if (session != null && session.getAttribute(ReturnTo.SESSION_ATTRIBUTE) instanceof String saved) {
            session.removeAttribute(ReturnTo.SESSION_ATTRIBUTE);
            target = ReturnTo.sanitize(saved);
        }
        redirects.sendRedirect(request, response, target);
    }
}
