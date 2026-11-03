package br.com.encurtador.security.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Devolve ao Spring Security as exceções de segurança lançadas dentro do MVC, para que o entry point e o access denied
 * handler escrevam o 401 e o 403 (ADR-0002, D4). Vem antes do handler global do {@code shared}, que trataria qualquer
 * exceção como 500 e não conhece o Spring Security.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class SecurityExceptionAdvice {

    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    void rethrow(RuntimeException ex) {
        throw ex;
    }
}
