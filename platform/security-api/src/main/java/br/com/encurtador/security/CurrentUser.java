package br.com.encurtador.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Id do usuário autenticado num parâmetro de controller: {@code @CurrentUser long userId} (ADR-0002, D3).
 * <p>
 * O controller não vê o token: quem traduz o assunto do token no id do usuário é o {@link UserIdResolver} do provedor
 * de identidade. Um assunto que não corresponde a nenhum usuário responde 401, como um token inválido.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
