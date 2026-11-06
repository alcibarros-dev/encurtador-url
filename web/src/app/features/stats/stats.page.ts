import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { StatsApi } from '../../core/api/stats-api';
import { UserStats } from '../../core/api/models';
import { errorMessage } from '../../core/errors/api-error';
import { DEFAULT_DAYS, DEFAULT_ZONE, browserTimeZone } from '../../shared/stats/period';
import { PeriodPicker } from '../../shared/stats/period-picker';
import { StatsLoader } from '../../shared/stats/stats-loader';
import { StatsPanel } from '../../shared/stats/stats-panel';

/** Estatísticas de todos os links do usuário (ADR-0004, D6 e D7). */
@Component({
  selector: 'app-stats-page',
  imports: [MatButtonModule, MatProgressBarModule, PeriodPicker, StatsPanel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pagina">
      <h1 tabindex="-1">Estatísticas</h1>
      <p class="texto-suave">Todos os seus links, ativos e expirados. Links removidos não entram.</p>
      <app-period-picker [(days)]="days" [(zone)]="zone" />
      @if (loader.zoneFallback()) {
        <p class="aviso" role="status">O fuso do navegador não é aceito; os números estão em {{ defaultZone }}.</p>
      }
      @if (loader.loading()) {
        <mat-progress-bar mode="indeterminate" aria-label="Carregando as estatísticas" />
      }
      @if (loader.error(); as error) {
        <div class="cartao" role="alert">
          <p class="erro">{{ message(error) }}</p>
          <button mat-stroked-button type="button" (click)="reload()">Tentar de novo</button>
        </div>
      } @else if (loader.stats(); as stats) {
        <app-stats-panel [stats]="stats" [topLinks]="stats.topLinks" />
      }
    </div>
  `,
})
export class StatsPage {
  private readonly api = inject(StatsApi);

  protected readonly days = signal(DEFAULT_DAYS);
  protected readonly zone = signal(browserTimeZone());
  protected readonly defaultZone = DEFAULT_ZONE;
  protected readonly message = errorMessage;
  protected readonly loader = new StatsLoader<UserStats>((query) => this.api.user(query), inject(DestroyRef));

  constructor() {
    effect(() => this.loader.load(this.days(), this.zone()));
  }

  protected reload(): void {
    this.loader.load(this.days(), this.zone());
  }
}
