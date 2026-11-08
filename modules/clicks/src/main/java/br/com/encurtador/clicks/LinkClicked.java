package br.com.encurtador.clicks;

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Evento de clique publicado no tópico {@code link-clicks} (ADR-0001, D8). Privacidade (RNF08): sem IP, sem
 * user-agent e, do Referer, só o host, que as estatísticas expõem em {@code topReferrers}.
 *
 * @param v          versão do formato
 * @param eventId    UUIDv7 gerado no redirecionamento; o consumidor usa para descartar duplicatas
 * @param occurredAt instante do redirecionamento, nunca o do consumo (corrige P4)
 */
public record LinkClicked(int v, UUID eventId, long linkId, Instant occurredAt, @Nullable String referrerHost) {

    public static final int VERSION = 1;
    public static final int MAX_REFERRER_HOST_LENGTH = 255;

    /** @param refererHeader cabeçalho {@code Referer} como veio do navegador; só o host é guardado */
    public static LinkClicked fromRedirect(UUID eventId, long linkId, Instant occurredAt,
                                           @Nullable String refererHeader) {
        return new LinkClicked(VERSION, eventId, linkId, occurredAt, referrerHostOf(refererHeader));
    }

    /** Host em minúsculas, ou vazio se o cabeçalho faltar, for inválido ou não tiver host. */
    static @Nullable String referrerHostOf(@Nullable String refererHeader) {
        if (refererHeader == null || refererHeader.isBlank()) {
            return null;
        }
        try {
            String host = new URI(refererHeader.strip()).getHost();
            if (host == null || host.length() > MAX_REFERRER_HOST_LENGTH) {
                return null;
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException ex) {
            return null;
        }
    }
}
