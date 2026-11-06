import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { BrowserNavigation } from '../navigation/browser-navigation';
import { loginUrl } from '../navigation/login-url';
import { DEFAULT_RETURN_TO } from '../navigation/return-to';

export interface Session {
  email: string;
}

interface LogoutResponse {
  logoutUrl: string;
}

/**
 * Quem está logado, pela sessão do BFF (ADR-0004, D4). O navegador nunca vê token: só o cookie de sessão, que o
 * JavaScript não lê.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);
  private readonly navigation = inject(BrowserNavigation);
  private readonly current = signal<Session | null | undefined>(undefined);
  private loading?: Promise<Session | null>;

  /** `undefined` enquanto a sessão não foi consultada; `null` sem login. */
  readonly session = this.current.asReadonly();
  readonly loggedIn = computed(() => !!this.current());
  /** Sessão que expirou durante o uso: a SPA mostra o aviso e o botão "Entrar". */
  readonly expired = signal(false);

  /** Consulta a sessão uma vez; as chamadas seguintes reaproveitam a resposta. */
  load(): Promise<Session | null> {
    this.loading ??= firstValueFrom(this.http.get<Session>('/bff/session')).then(
      (session) => {
        this.current.set(session);
        return session;
      },
      (error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 401) {
          this.current.set(null);
          return null;
        }
        // Falha de rede ou BFF fora: a próxima navegação tenta de novo.
        this.loading = undefined;
        throw error;
      },
    );
    return this.loading;
  }

  login(returnTo: string = DEFAULT_RETURN_TO): void {
    this.navigation.assign(loginUrl(returnTo));
  }

  register(): void {
    this.navigation.assign('/bff/register');
  }

  changePassword(): void {
    this.navigation.assign('/bff/password');
  }

  /**
   * O BFF revoga o refresh token e encerra a própria sessão; a navegação para a URL de logout do auth-server encerra
   * a dele, para que o próximo "Entrar" peça a senha de novo.
   */
  async logout(): Promise<void> {
    const response = await firstValueFrom(this.http.post<LogoutResponse>('/bff/logout', null));
    this.current.set(null);
    this.navigation.assign(response.logoutUrl);
  }

  /** Um 401 do BFF durante o uso: a sessão acabou (expirou, ou a senha foi trocada em outro lugar). */
  markExpired(): void {
    if (this.current()) {
      this.current.set(null);
      this.loading = Promise.resolve(null);
      this.expired.set(true);
    }
  }
}
