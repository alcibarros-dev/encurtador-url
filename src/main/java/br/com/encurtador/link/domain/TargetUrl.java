package br.com.encurtador.link.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Destino de um link, tratado como entrada hostil (ADR-0001, D10). Só existe validado e normalizado em ASCII, pronto
 * para o cabeçalho {@code Location} sem risco de injeção.
 *
 * @param value URL normalizada, como é gravada e devolvida no redirecionamento
 * @param host  host normalizado; os logs registram só ele, nunca a URL inteira (D13)
 */
public record TargetUrl(String value, String host) {

    public static final int MAX_LENGTH = 2048;

    /**
     * @throws InvalidValueException com a primeira regra violada
     */
    public static TargetUrl parse(String raw, HostClassifier hosts) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidValueException("é obrigatória");
        }
        String input = raw.strip();
        if (input.length() > MAX_LENGTH) {
            throw new InvalidValueException("deve ter no máximo " + MAX_LENGTH + " caracteres");
        }
        if (input.codePoints().anyMatch(TargetUrl::isSpaceOrControl)) {
            throw new InvalidValueException("não pode conter espaços nem caracteres de controle; codifique-os (ex.: %20)");
        }
        URI uri;
        try {
            uri = new URI(input);
        } catch (URISyntaxException ex) {
            throw new InvalidValueException("não é uma URL válida; caracteres especiais precisam vir codificados "
                    + "(percent-encoding)");
        }
        if (!uri.isAbsolute()) {
            throw notAbsolute();
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new InvalidValueException("deve usar http ou https");
        }
        if (uri.isOpaque()) {
            throw notAbsolute(); // http:exemplo.com
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidValueException("não pode ter usuário ou senha antes do host");
        }
        if (uri.getHost() == null) {
            throw invalidHost(uri.getRawAuthority());
        }
        if (uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new InvalidValueException("tem uma porta fora do intervalo de 1 a 65535");
        }
        String host = HostClassifier.normalize(uri.getHost());
        hosts.check(host);

        String normalized = toAscii(scheme, host, uri);
        if (normalized.length() > MAX_LENGTH) {
            throw new InvalidValueException("passa de " + MAX_LENGTH + " caracteres depois de codificada");
        }
        return new TargetUrl(normalized, host);
    }

    private static InvalidValueException notAbsolute() {
        return new InvalidValueException("deve ser uma URL absoluta, como https://exemplo.com/pagina");
    }

    private static boolean isSpaceOrControl(int codePoint) {
        return Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint);
    }

    /**
     * O {@code java.net.URI} não aceita host fora do ASCII, e converter com {@code IDN.toASCII} (IDNA2003) poderia
     * gravar um destino diferente do que os navegadores abririam (UTS-46).
     */
    private static InvalidValueException invalidHost(String authority) {
        if (authority != null && authority.chars().anyMatch(c -> c > 0x7f)) {
            return new InvalidValueException("tem um domínio internacional: informe-o em Punycode (xn--…)");
        }
        return new InvalidValueException("não tem um host válido");
    }

    /**
     * Remonta a URL com esquema e host normalizados a partir dos componentes <em>crus</em>: os decodificados
     * mudariam o significado de {@code %2F} no caminho. O {@code toASCIIString} codifica o que estiver fora do ASCII.
     */
    private static String toAscii(String scheme, String host, URI uri) {
        StringBuilder url = new StringBuilder(scheme).append("://").append(host);
        if (uri.getPort() != -1) {
            url.append(':').append(uri.getPort());
        }
        url.append(uri.getRawPath());
        if (uri.getRawQuery() != null) {
            url.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            url.append('#').append(uri.getRawFragment());
        }
        return URI.create(url.toString()).toASCIIString();
    }
}
