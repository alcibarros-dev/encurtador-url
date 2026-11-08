package br.com.encurtador.redirect.application;

import java.util.Optional;

/** De onde vêm os links quando o cache não os tem: o PostgreSQL, só leitura (ADR-0006, D2). */
public interface LinkSource {

    /** Inclui links removidos e expirados: o redirecionamento distingue 410 de 404. */
    Optional<StoredLink> findByCode(String code);
}
