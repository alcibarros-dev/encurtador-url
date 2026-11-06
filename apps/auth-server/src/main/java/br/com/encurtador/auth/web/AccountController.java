package br.com.encurtador.auth.web;

import br.com.encurtador.auth.config.PanelRedirects;
import br.com.encurtador.auth.credential.PasswordChangeService;
import br.com.encurtador.ratelimit.RateLimitProperties.Limit;
import br.com.encurtador.ratelimit.RateLimiter;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Página inicial da conta, troca de senha (ADR-0003, D9) e confirmação de dispositivo (D4). A troca de senha volta
 * para o painel, se a volta pedida for de uma origem dele.
 */
@Controller
class AccountController {

    private final PasswordChangeService passwords;
    private final PanelRedirects redirects;
    private final RateLimiter limiter;
    private final Limit limit;

    AccountController(PasswordChangeService passwords, PanelRedirects redirects, RateLimiter limiter) {
        this.passwords = passwords;
        this.redirects = redirects;
        this.limiter = limiter;
        this.limit = limiter.limit("password-change");
    }

    @GetMapping("/")
    String home(@AuthenticationPrincipal UserDetails user, Model model) {
        model.addAttribute("email", user.getUsername());
        model.addAttribute("panelUrl", redirects.target(null));
        return "home";
    }

    @GetMapping("/account/password")
    String passwordForm(@RequestParam(name = "continue", required = false) @Nullable String continueTo, Model model) {
        model.addAttribute("form", new PasswordChangeForm());
        model.addAttribute("continueTo", redirects.isAllowed(continueTo) ? continueTo : null);
        return "password";
    }

    @PostMapping("/account/password")
    String changePassword(@AuthenticationPrincipal UserDetails user,
                          @Valid @ModelAttribute("form") PasswordChangeForm form, BindingResult errors,
                          @RequestParam(name = "continue", required = false) @Nullable String continueTo,
                          Model model) {
        model.addAttribute("continueTo", redirects.isAllowed(continueTo) ? continueTo : null);
        // Por usuário: força bruta da senha atual com uma sessão roubada (ADR-0001, D11).
        if (limiter.tryAcquire("password-change", limit, user.getUsername())
                instanceof RateLimiter.Decision.Rejected(var retryAfter)) {
            model.addAttribute("retryAfter", Math.max(1, retryAfter.toSeconds()));
            return "password";
        }
        if (errors.hasErrors()) {
            return "password";
        }
        if (!passwords.change(user.getUsername(), form.getCurrentPassword(), form.getNewPassword())) {
            errors.rejectValue("currentPassword", "wrong", "A senha atual não confere.");
            return "password";
        }
        model.addAttribute("panelUrl", redirects.target(continueTo));
        return "password-changed";
    }

    /** O usuário digita o código mostrado na CLI e confirma o acesso na página de consentimento (D9). */
    @GetMapping("/activate")
    String activate(@RequestParam(name = "user_code", required = false) @Nullable String userCode, Model model) {
        model.addAttribute("userCode", userCode == null ? "" : userCode);
        return "activate";
    }
}
