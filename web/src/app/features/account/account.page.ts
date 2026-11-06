import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ApiError, errorMessage } from '../../core/errors/api-error';
import { Notifier } from '../../core/errors/notifier';
import { SessionService } from '../../core/session/session.service';

/**
 * E-mail, troca de senha e saída (ADR-0004, D6). A troca de senha é uma página do auth-server; o painel só leva o
 * usuário até ela, pelo BFF.
 */
@Component({
  selector: 'app-account-page',
  imports: [MatButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pagina">
      <h1 tabindex="-1">Sua conta</h1>
      <div class="cartao conta">
        <p><span class="texto-suave">E-mail</span><br /><strong>{{ sessions.session()?.email }}</strong></p>
        <div class="acoes">
          <a mat-stroked-button href="/bff/password">Trocar senha</a>
          <button mat-flat-button type="button" (click)="logout()" [disabled]="leaving()">Sair</button>
        </div>
      </div>
      <p class="texto-suave">Depois de trocar a senha, você entra de novo em todos os dispositivos.</p>
    </div>
  `,
  styles: `
    .conta {
      max-width: 480px;
    }
  `,
})
export class AccountPage {
  protected readonly sessions = inject(SessionService);
  private readonly notifier = inject(Notifier);
  protected readonly leaving = signal(false);

  protected async logout(): Promise<void> {
    this.leaving.set(true);
    try {
      await this.sessions.logout();
    } catch (error) {
      this.leaving.set(false);
      this.notifier.error(errorMessage(ApiError.from(error)));
    }
  }
}
