import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found-page',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pagina">
      <h1 tabindex="-1">Página não encontrada</h1>
      <p>O endereço não existe no painel. <a routerLink="/">Voltar ao início</a>.</p>
    </div>
  `,
})
export class NotFoundPage {}
