import { HttpErrorResponse } from '@angular/common/http';

export interface FieldError {
  field: string;
  message: string;
}

/** Erro de uma chamada ao BFF ou ao app, a partir do problem+json (RFC 9457; ADR-0001, D12). */
export class ApiError {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string | null,
    readonly errors: readonly FieldError[] = [],
    readonly retryAfter: number | null = null,
    readonly instance: string | null = null,
  ) {}

  static from(error: unknown): ApiError {
    if (error instanceof ApiError) {
      return error;
    }
    if (!(error instanceof HttpErrorResponse)) {
      return new ApiError(0, 'UNEXPECTED', null);
    }
    if (error.status === 0) {
      return new ApiError(0, 'NETWORK', null);
    }
    const body = isRecord(error.error) ? error.error : {};
    const retryAfter = Number(error.headers.get('Retry-After'));
    return new ApiError(
      error.status,
      typeof body['code'] === 'string' ? body['code'] : `HTTP_${error.status}`,
      typeof body['detail'] === 'string' ? body['detail'] : null,
      Array.isArray(body['errors']) ? body['errors'].filter(isFieldError) : [],
      Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : null,
      typeof body['instance'] === 'string' ? body['instance'] : null,
    );
  }

  fieldError(field: string): string | undefined {
    return this.errors.find((error) => error.field === field)?.message;
  }
}

/** Mensagens em português por `code` (ADR-0004, D8); um código desconhecido mostra o `detail` do backend. */
const MESSAGES: Readonly<Record<string, string>> = {
  ALIAS_TAKEN: 'Este alias já está em uso. Escolha outro.',
  LINK_QUOTA_EXCEEDED: 'Você chegou ao limite de 100 links ativos. Remova algum para criar outro.',
  LINK_NOT_FOUND: 'Link não encontrado. Ele pode ter sido removido.',
  CODE_GENERATION_EXHAUSTED: 'Não foi possível gerar um código agora. Tente de novo.',
  VALIDATION_FAILED: 'Confira os campos destacados.',
  UNAUTHENTICATED: 'Sua sessão expirou. Entre de novo para continuar.',
  INVALID_TOKEN: 'Sua sessão expirou. Entre de novo para continuar.',
  SESSION_EXPIRED: 'Sua sessão expirou. Entre de novo para continuar.',
  UPSTREAM_UNAVAILABLE: 'O serviço está indisponível agora. Tente de novo em instantes.',
  AUTH_UNAVAILABLE: 'O serviço está indisponível agora. Tente de novo em instantes.',
  NETWORK: 'Sem conexão com o painel. Confira a sua rede e tente de novo.',
  NOT_FOUND: 'Não encontrado.',
};

const GENERIC = 'Algo deu errado. Tente de novo.';

export function errorMessage(error: ApiError): string {
  if (error.code === 'RATE_LIMITED') {
    return error.retryAfter
      ? `Muitas tentativas seguidas. Tente de novo em ${error.retryAfter} segundos.`
      : 'Muitas tentativas seguidas. Aguarde um pouco e tente de novo.';
  }
  if (error.status === 503 && !MESSAGES[error.code]) {
    return MESSAGES['UPSTREAM_UNAVAILABLE'];
  }
  return MESSAGES[error.code] ?? error.detail ?? GENERIC;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isFieldError(value: unknown): value is FieldError {
  return isRecord(value) && typeof value['field'] === 'string' && typeof value['message'] === 'string';
}
