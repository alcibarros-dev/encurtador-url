package br.com.encurtador.auth.web;

import br.com.encurtador.auth.config.PanelRedirects;
import br.com.encurtador.auth.credential.RegistrationService;
import br.com.encurtador.ratelimit.ClientAddress;
import br.com.encurtador.ratelimit.RateLimitProperties.Limit;
import br.com.encurtador.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;

/**
 * Cadastro (ADR-0003, D9; ADR-0004, D4): ao fim, o usuário fica logado e volta ao painel, se a volta pedida for de
 * uma origem do painel. O login seguinte do painel é silencioso.
 */
@Controller
class RegistrationController {

    private final RegistrationService registrations;
    private final UserDetailsService users;
    private final PanelRedirects redirects;
    private final RateLimiter limiter;
    private final Limit limit;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    private final RequestCache savedRequests = new HttpSessionRequestCache();

    RegistrationController(RegistrationService registrations, UserDetailsService users, PanelRedirects redirects,
                           RateLimiter limiter) {
        this.registrations = registrations;
        this.users = users;
        this.redirects = redirects;
        this.limiter = limiter;
        this.limit = limiter.limit("register");
    }

    @GetMapping("/register")
    String form(@RequestParam(name = "continue", required = false) @Nullable String continueTo, Model model) {
        model.addAttribute("form", new RegistrationForm());
        model.addAttribute("continueTo", redirects.isAllowed(continueTo) ? continueTo : null);
        return "register";
    }

    @PostMapping("/register")
    String register(@Valid @ModelAttribute("form") RegistrationForm form, BindingResult errors,
                    @RequestParam(name = "continue", required = false) @Nullable String continueTo, Model model,
                    HttpServletRequest request, HttpServletResponse response) {
        model.addAttribute("continueTo", redirects.isAllowed(continueTo) ? continueTo : null);
        if (limiter.tryAcquire("register", limit, ClientAddress.subjectOf(request.getRemoteAddr()))
                instanceof RateLimiter.Decision.Rejected(var retryAfter)) {
            model.addAttribute("retryAfter", Math.max(1, retryAfter.toSeconds()));
            return "register";
        }
        if (errors.hasErrors()) {
            return "register";
        }
        if (registrations.register(form.getEmail(), form.getPassword()).isEmpty()) {
            errors.rejectValue("email", "taken", "Já existe uma conta com este e-mail.");
            return "register";
        }
        logIn(form.getEmail(), request, response);
        return "redirect:" + destination(continueTo, request, response);
    }

    /**
     * A volta pedida pelo painel, se for de uma origem dele; senão, o pedido de autorização que levou à página de
     * login (o usuário clicou em "Criar conta" no meio de um login OAuth); senão, o painel.
     */
    private String destination(@Nullable String continueTo, HttpServletRequest request, HttpServletResponse response) {
        if (redirects.isAllowed(continueTo)) {
            return continueTo;
        }
        SavedRequest saved = savedRequests.getRequest(request, response);
        return saved != null ? saved.getRedirectUrl() : redirects.target(null);
    }

    /**
     * Como o formulário de login faria: contexto novo, id de sessão trocado (fixação de sessão) e contexto salvo. O
     * fator de senha, com o instante do login, vira o {@code auth_time} do {@code id_token}: sem ele, o SAS recusa
     * emitir o token.
     */
    private void logIn(String email, HttpServletRequest request, HttpServletResponse response) {
        UserDetails user = users.loadUserByUsername(email);
        List<GrantedAuthority> authorities = new ArrayList<>(user.getAuthorities());
        authorities.add(FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
        authentication.setDetails(new WebAuthenticationDetails(request));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        request.getSession(true);
        request.changeSessionId();
        contexts.saveContext(context, request, response);
    }
}
