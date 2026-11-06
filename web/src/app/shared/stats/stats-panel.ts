import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LinkClicks, Stats } from '../../core/api/models';
import { DailyChart } from '../chart/daily-chart';
import { DayPipe, LinkStatusPipe, dayLabel } from '../format';

/**
 * Números de um período: totais, cliques por dia (gráfico e tabela) e principais origens; nas estatísticas do
 * usuário, também os links mais acessados (ADR-0004, D6 e D7). Fica em `shared/` porque `links/` e `stats/` o usam.
 */
@Component({
  selector: 'app-stats-panel',
  imports: [DailyChart, DayPipe, DecimalPipe, LinkStatusPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="resumo">
      <div class="cartao destaque">
        <span class="texto-suave">Cliques no período</span>
        <strong>{{ stats().periodClicks | number }}</strong>
      </div>
      <div class="cartao destaque">
        <span class="texto-suave">Cliques desde a criação</span>
        <strong>{{ stats().totalClicks | number }}</strong>
      </div>
    </div>

    <h2>Cliques por dia</h2>
    <app-daily-chart [daily]="stats().daily" [label]="chartLabel()" />
    <details>
      <summary>Ver os números de cada dia</summary>
      <table class="dados">
        <caption class="cdk-visually-hidden">{{ chartLabel() }}</caption>
        <thead>
          <tr>
            <th scope="col">Dia</th>
            <th scope="col" class="numero">Cliques</th>
          </tr>
        </thead>
        <tbody>
          @for (day of stats().daily; track day.date) {
            <tr>
              <td>{{ day.date | day }}</td>
              <td class="numero">{{ day.clicks | number }}</td>
            </tr>
          }
        </tbody>
      </table>
    </details>

    @if (topLinks(); as links) {
      <h2>Links mais acessados</h2>
      @if (links.length) {
        <table class="dados">
          <thead>
            <tr>
              <th scope="col">Link</th>
              <th scope="col">Status</th>
              <th scope="col" class="numero">Cliques</th>
            </tr>
          </thead>
          <tbody>
            @for (link of links; track link.code) {
              <tr>
                <td><a [routerLink]="['/links', link.code]">{{ link.code }}</a></td>
                <td>{{ link.status | linkStatus }}</td>
                <td class="numero">{{ link.clicks | number }}</td>
              </tr>
            }
          </tbody>
        </table>
      } @else {
        <p class="texto-suave">Nenhum clique no período.</p>
      }
    }

    <h2>Principais origens</h2>
    @if (stats().topReferrers.length) {
      <table class="dados">
        <thead>
          <tr>
            <th scope="col">Origem</th>
            <th scope="col" class="numero">Cliques</th>
          </tr>
        </thead>
        <tbody>
          @for (referrer of stats().topReferrers; track referrer.host) {
            <tr>
              <td>{{ referrer.host ?? 'Acesso direto' }}</td>
              <td class="numero">{{ referrer.clicks | number }}</td>
            </tr>
          }
        </tbody>
      </table>
    } @else {
      <p class="texto-suave">Nenhum clique no período.</p>
    }
  `,
  styles: `
    .resumo {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 16px;
    }
    .destaque {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .destaque strong {
      font-size: 2rem;
      font-variant-numeric: tabular-nums;
    }
    details {
      margin-top: 8px;
    }
    summary {
      cursor: pointer;
      padding: 8px 0;
    }
  `,
})
export class StatsPanel {
  readonly stats = input.required<Stats>();
  /** Só nas estatísticas do usuário. */
  readonly topLinks = input<LinkClicks[]>();

  protected readonly chartLabel = computed(() => {
    const stats = this.stats();
    return `Cliques por dia, de ${dayLabel(stats.from)} a ${dayLabel(stats.to)}, no fuso ${stats.timezone}`;
  });
}
