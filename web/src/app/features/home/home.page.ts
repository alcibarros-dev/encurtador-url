import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { loginUrl } from '../../core/navigation/login-url';

/** Página pública: "Entrar" e "Criar conta" saem da SPA para o BFF, que leva ao auth-server (ADR-0004, D4). */
@Component({
  selector: 'app-home-page',
  imports: [MatButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pagina inicio">
      <h1 tabindex="-1">Links curtos, com os cliques de cada um</h1>
      @if (erro() === 'login') {
        <p class="erro" role="alert">Não foi possível entrar. Tente de novo.</p>
      }
      <p class="texto-suave">
        Crie links curtos para os seus endereços, acompanhe quantas pessoas clicaram em cada um e de onde elas vieram.
      </p>
      <div class="acoes">
        <a mat-flat-button [href]="loginHref()">Entrar</a>
        <a mat-stroked-button href="/bff/register">Criar conta</a>
      </div>
    </div>
  `,
  styles: `
    .inicio {
      max-width: 640px;
      padding-top: 64px;
    }
    p {
      font-size: 1.125rem;
      margin-bottom: 24px;
    }
  `,
})
export class HomePage {
  /** Rota pedida antes do login, vinda do guard (`?returnTo=`). */
  readonly returnTo = input<string>();
  /** O BFF volta para cá com `?erro=login` quando o login falha. */
  readonly erro = input<string>();

  protected readonly loginHref = computed(() => loginUrl(this.returnTo()));
}
