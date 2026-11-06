import { HttpErrorResponse } from '@angular/common/http';
import { ErrorHandler, Injectable, Injector, inject } from '@angular/core';
import { ApiError } from './api-error';
import { Notifier } from './notifier';

/**
 * Nenhum erro some em silêncio (ADR-0004, D8): o que nenhuma página tratou mostra uma mensagem genérica e registra no
 * console só o `code` e o `instance`, nunca dados do usuário.
 */
@Injectable()
export class GlobalErrorHandler implements ErrorHandler {
  // O Notifier depende do Material, que pode não existir ainda quando o ErrorHandler é criado.
  private readonly injector = inject(Injector);

  handleError(error: unknown): void {
    const cause = unwrap(error);
    if (cause instanceof HttpErrorResponse || cause instanceof ApiError) {
      const api = ApiError.from(cause);
      console.error('Erro não tratado', { code: api.code, instance: api.instance });
    } else {
      console.error('Erro não tratado', cause instanceof Error ? cause.name : typeof cause);
    }
    this.injector.get(Notifier).error('Algo deu errado. Tente de novo.');
  }
}

function unwrap(error: unknown): unknown {
  if (typeof error === 'object' && error !== null && 'rejection' in error) {
    return (error as { rejection: unknown }).rejection;
  }
  return error;
}
