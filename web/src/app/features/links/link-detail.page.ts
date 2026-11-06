import { Clipboard } from '@angular/cdk/clipboard';
import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { LinksApi } from '../../core/api/links-api';
import { Link, LinkStats } from '../../core/api/models';
import { StatsApi } from '../../core/api/stats-api';
import { ApiError, errorMessage } from '../../core/errors/api-error';
import { Notifier } from '../../core/errors/notifier';
import { LinkStatusPipe } from '../../shared/format';
import { DEFAULT_DAYS, DEFAULT_ZONE, browserTimeZone } from '../../shared/stats/period';
import { PeriodPicker } from '../../shared/stats/period-picker';
import { StatsLoader } from '../../shared/stats/stats-loader';
import { StatsPanel } from '../../shared/stats/stats-panel';

/** Um link e as estatísticas dele (ADR-0004, D6). */
@Component({
  selector: 'app-link-detail-page',
  imports: [DatePipe, LinkStatusPipe, MatButtonModule, MatProgressBarModule, PeriodPicker, RouterLink, StatsPanel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pagina">
      <p><a routerLink="/links">← Seus links</a></p>
      <h1 tabindex="-1">Link {{ code() }}</h1>
      @if (linkError(); as error) {
        <div class="cartao" role="alert">
          <p class="erro">{{ message(error) }}</p>
        </div>
      } @else if (link(); as link) {
        <div class="cartao dados-link">
          <p><span class="texto-suave">URL curta:</span> {{ link.shortUrl }}</p>
          <p class="destino"><span class="texto-suave">Destino:</span> {{ link.targetUrl }}</p>
          <p>
            <span class="texto-suave">Status:</span> {{ link.status | linkStatus }} ·
            <span class="texto-suave">criado em</span> {{ link.createdAt | date: 'short' }} ·
            <span class="texto-suave">{{ link.status === 'EXPIRED' ? 'expirou' : 'expira' }} em</span>
            {{ link.expiresAt | date: 'short' }}
          </p>
          <div class="acoes">
            <button mat-stroked-button type="button" (click)="copy(link)">Copiar URL curta</button>
            <a mat-stroked-button [href]="link.targetUrl" target="_blank" rel="noopener noreferrer">
              Abrir destino em nova aba
            </a>
          </div>
        </div>

        <h2>Estatísticas</h2>
        <app-period-picker [(days)]="days" [(zone)]="zone" />
        @if (loader.zoneFallback()) {
          <p role="status">O fuso do navegador não é aceito; os números estão em {{ defaultZone }}.</p>
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
          <app-stats-panel [stats]="stats" />
        }
      }
    </div>
  `,
  styles: `
    .dados-link p {
      margin: 4px 0;
    }
    .destino {
      overflow-wrap: anywhere;
    }
    .acoes {
      margin-top: 12px;
    }
  `,
})
export class LinkDetailPage {
  private readonly links = inject(LinksApi);
  private readonly statsApi = inject(StatsApi);
  private readonly clipboard = inject(Clipboard);
  private readonly notifier = inject(Notifier);

  /** Parâmetro `:code` da rota. */
  readonly code = input.required<string>();

  protected readonly link = signal<Link | null>(null);
  protected readonly linkError = signal<ApiError | null>(null);
  protected readonly days = signal(DEFAULT_DAYS);
  protected readonly zone = signal(browserTimeZone());
  protected readonly defaultZone = DEFAULT_ZONE;
  protected readonly message = errorMessage;
  protected readonly loader = new StatsLoader<LinkStats>(
    (query) => this.statsApi.link(this.code(), query),
    inject(DestroyRef),
  );

  constructor() {
    effect((onCleanup) => {
      const subscription = this.links.get(this.code()).subscribe({
        next: (link) => this.link.set(link),
        error: (failure: unknown) => this.linkError.set(ApiError.from(failure)),
      });
      onCleanup(() => subscription.unsubscribe());
    });
    effect(() => {
      if (this.link()) {
        this.loader.load(this.days(), this.zone());
      }
    });
  }

  protected reload(): void {
    this.loader.load(this.days(), this.zone());
  }

  protected copy(link: Link): void {
    if (this.clipboard.copy(link.shortUrl)) {
      this.notifier.info('URL curta copiada.');
    } else {
      this.notifier.error('Não foi possível copiar. Selecione a URL e copie manualmente.');
    }
  }
}
