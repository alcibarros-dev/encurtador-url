import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { problem } from '../../../testing/fixtures';
import { ApiError, errorMessage } from './api-error';

function httpError(status: number, body: unknown, headers: Record<string, string> = {}): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: body, headers: new HttpHeaders(headers) });
}

describe('ApiError', () => {
  it('lê o problem+json do app', () => {
    const error = ApiError.from(
      httpError(400, problem(400, 'VALIDATION_FAILED', {
        detail: 'Campos inválidos.',
        instance: '/api/v1/links',
        errors: [{ field: 'url', message: 'deve usar http ou https' }, { bogus: true }],
      })),
    );

    expect(error.status).toBe(400);
    expect(error.code).toBe('VALIDATION_FAILED');
    expect(error.detail).toBe('Campos inválidos.');
    expect(error.instance).toBe('/api/v1/links');
    expect(error.errors).toEqual([{ field: 'url', message: 'deve usar http ou https' }]);
    expect(error.fieldError('url')).toBe('deve usar http ou https');
    expect(error.fieldError('alias')).toBeUndefined();
  });

  it('guarda o Retry-After do 429', () => {
    const error = ApiError.from(httpError(429, problem(429, 'RATE_LIMITED'), { 'Retry-After': '13' }));

    expect(error.retryAfter).toBe(13);
    expect(errorMessage(error)).toBe('Muitas tentativas seguidas. Tente de novo em 13 segundos.');
  });

  it('distingue falha de rede, corpo que não é problem+json e erro que não é HTTP', () => {
    expect(ApiError.from(httpError(0, null)).code).toBe('NETWORK');
    expect(ApiError.from(httpError(502, '<html>')).code).toBe('HTTP_502');
    expect(ApiError.from(new Error('x')).code).toBe('UNEXPECTED');
    const error = ApiError.from(httpError(404, null));
    expect(ApiError.from(error)).toBe(error);
  });
});

describe('errorMessage', () => {
  it('traduz os códigos conhecidos', () => {
    expect(errorMessage(new ApiError(409, 'ALIAS_TAKEN', null))).toContain('alias já está em uso');
    expect(errorMessage(new ApiError(409, 'LINK_QUOTA_EXCEEDED', null))).toContain('100 links ativos');
    expect(errorMessage(new ApiError(503, 'UPSTREAM_UNAVAILABLE', null))).toContain('indisponível');
    expect(errorMessage(new ApiError(429, 'RATE_LIMITED', null))).toContain('Aguarde um pouco');
    expect(errorMessage(new ApiError(401, 'SESSION_EXPIRED', null))).toContain('sessão expirou');
  });

  it('usa o detail para um código desconhecido e uma mensagem genérica sem ele', () => {
    expect(errorMessage(new ApiError(422, 'NOVO_CODIGO', 'Detalhe do backend.'))).toBe('Detalhe do backend.');
    expect(errorMessage(new ApiError(500, 'HTTP_500', null))).toBe('Algo deu errado. Tente de novo.');
    expect(errorMessage(new ApiError(503, 'HTTP_503', null))).toContain('indisponível');
  });
});
