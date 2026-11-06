import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { SessionService } from '../session/session.service';

/**
 * Um 401 do BFF em `/api/**` ou `/bff/**` é sessão expirada (ADR-0004, D4): a SPA mostra o aviso com o botão
 * "Entrar". A consulta inicial da sessão fica de fora: nela, 401 só quer dizer "ninguém logado".
 */
export const sessionExpiryInterceptor: HttpInterceptorFn = (request, next) => {
  const sessions = inject(SessionService);
  return next(request).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && !request.url.startsWith('/bff/session')) {
        sessions.markExpired();
      }
      return throwError(() => error);
    }),
  );
};
