package br.com.encurtador.shared.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * Fábrica de respostas RFC 9457 no formato da API: {@code type}, título em português e {@code code} estável
 * (ADR-0001, D12). Usada pelo handler global e pelos filtros que escrevem erros fora do MVC.
 */
public final class ProblemDetails {

    public static final String CODE = "code";
    public static final String ERRORS = "errors";

    private static final String TYPE_PREFIX = "urn:encurtador:problem:";

    private static final Map<Integer, String> TITLES = Map.ofEntries(
            Map.entry(400, "Requisição inválida"),
            Map.entry(401, "Não autenticado"),
            Map.entry(403, "Acesso negado"),
            Map.entry(404, "Recurso não encontrado"),
            Map.entry(405, "Método não permitido"),
            Map.entry(406, "Formato de resposta não aceito"),
            Map.entry(409, "Conflito"),
            Map.entry(410, "Recurso indisponível"),
            Map.entry(413, "Requisição grande demais"),
            Map.entry(415, "Tipo de mídia não suportado"),
            Map.entry(422, "Requisição não processável"),
            Map.entry(429, "Muitas requisições"),
            Map.entry(500, "Erro interno"),
            Map.entry(503, "Serviço indisponível"));

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
        return complete(ProblemDetail.forStatusAndDetail(status, detail), code);
    }

    /**
     * Completa um ProblemDetail (inclusive os criados pelo Spring) com {@code code}, {@code type} e título.
     * Não sobrescreve um {@code code} já definido.
     */
    public static ProblemDetail complete(ProblemDetail problem, String code) {
        String effectiveCode = existingCode(problem);
        if (effectiveCode == null) {
            effectiveCode = code;
            problem.setProperty(CODE, code);
        }
        problem.setType(URI.create(TYPE_PREFIX + effectiveCode.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(titleFor(problem.getStatus()));
        return problem;
    }

    /** Código padrão derivado do status, ex.: 404 → {@code NOT_FOUND}. */
    public static String defaultCode(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known != null ? known.name() : "HTTP_" + status.value();
    }

    static String titleFor(int status) {
        String title = TITLES.get(status);
        if (title != null) {
            return title;
        }
        HttpStatus known = HttpStatus.resolve(status);
        return known != null ? known.getReasonPhrase() : "Erro";
    }

    private static String existingCode(ProblemDetail problem) {
        Map<String, Object> properties = problem.getProperties();
        return properties != null && properties.get(CODE) instanceof String code ? code : null;
    }
}
