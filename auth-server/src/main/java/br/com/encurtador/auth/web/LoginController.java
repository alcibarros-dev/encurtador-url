package br.com.encurtador.auth.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Página de login; o POST é do formulário do Spring Security (ADR-0003, D4). */
@Controller
class LoginController {

    @GetMapping("/login")
    String login() {
        return "login";
    }
}
