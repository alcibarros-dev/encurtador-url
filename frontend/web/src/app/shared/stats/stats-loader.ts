import { DestroyRef, signal } from '@angular/core';
import { Observable, Subscription, catchError, throwError } from 'rxjs';
import { Stats, StatsQuery } from '../../core/api/models';
import { ApiError } from '../../core/errors/api-error';
import { DEFAULT_ZONE, periodQuery } from './period';

/**
 * Carrega estatísticas para um período, com o fallback do fuso (ADR-0004, D6): se o app recusar o fuso do navegador
 * (400 no campo `tz`), a consulta é refeita no fuso padrão, e a página avisa.
 */
export class StatsLoader<T extends Stats> {
  readonly stats = signal<T | null>(null);
  readonly error = signal<ApiError | null>(null);
  readonly loading = signal(false);
  /** O fuso pedido foi recusado, e os números estão no fuso padrão. */
  readonly zoneFallback = signal(false);

  private subscription?: Subscription;

  constructor(
    private readonly fetch: (query: StatsQuery) => Observable<T>,
    destroyRef: DestroyRef,
  ) {
    destroyRef.onDestroy(() => this.subscription?.unsubscribe());
  }

  load(days: number, zone: string): void {
    this.subscription?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);
    this.zoneFallback.set(false);
    this.subscription = this.fetch(periodQuery(days, zone))
      .pipe(
        catchError((failure: unknown) => {
          const error = ApiError.from(failure);
          if (zone !== DEFAULT_ZONE && error.status === 400 && error.fieldError('tz')) {
            this.zoneFallback.set(true);
            return this.fetch(periodQuery(days, DEFAULT_ZONE));
          }
          return throwError(() => error);
        }),
      )
      .subscribe({
        next: (stats) => {
          this.stats.set(stats);
          this.loading.set(false);
        },
        error: (failure: unknown) => {
          this.error.set(ApiError.from(failure));
          this.loading.set(false);
        },
      });
  }
}
