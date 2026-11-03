package br.com.encurtador.security;

import java.util.Map;

/**
 * Traduz o assunto de um token válido no id do usuário no app (ADR-0002, D3). Quem implementa é o provedor de
 * identidade, o único que conhece o formato do {@code sub}: o {@code security} só valida o token.
 */
public interface UserIdResolver {

    /**
     * @param subject o {@code sub} do token, já validado
     * @param claims  todos os claims do token
     * @return o id do usuário
     * @throws UnknownSubjectException se o assunto não corresponde a nenhum usuário
     */
    long resolve(String subject, Map<String, Object> claims);
}
