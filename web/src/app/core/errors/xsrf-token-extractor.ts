import { DOCUMENT } from '@angular/common';
import { HttpXsrfTokenExtractor } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';

/**
 * Token de CSRF do cookie do BFF (`csrf.spa()`, ADR-0004 D4): `__Host-XSRF-TOKEN` fora do ambiente local e
 * `XSRF-TOKEN` nele, porque os cookies de `localhost` não aceitam o prefixo `__Host-` sem HTTPS. O `HttpClient` o
 * devolve no cabeçalho `X-XSRF-TOKEN` em toda requisição para a mesma origem que não seja GET nem HEAD.
 */
@Injectable()
export class BffXsrfTokenExtractor implements HttpXsrfTokenExtractor {
  static readonly COOKIES = ['__Host-XSRF-TOKEN', 'XSRF-TOKEN'];

  private readonly document = inject(DOCUMENT);

  getToken(): string | null {
    const cookies = new Map(
      this.document.cookie
        .split(';')
        .map((cookie) => cookie.trim())
        .filter((cookie) => cookie.includes('='))
        .map((cookie) => {
          const separator = cookie.indexOf('=');
          return [cookie.slice(0, separator), decodeURIComponent(cookie.slice(separator + 1))] as const;
        }),
    );
    for (const name of BffXsrfTokenExtractor.COOKIES) {
      const value = cookies.get(name);
      if (value) {
        return value;
      }
    }
    return null;
  }
}
