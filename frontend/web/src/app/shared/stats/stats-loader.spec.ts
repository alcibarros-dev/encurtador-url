import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { LinkStats, StatsQuery } from '../../core/api/models';
import { problem, someLinkStats } from '../../../testing/fixtures';
import { DEFAULT_ZONE } from './period';
import { StatsLoader } from './stats-loader';

describe('StatsLoader', () => {
  const rejectedZone = new HttpErrorResponse({
    status: 400,
    error: problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'tz', message: 'deve ser um fuso IANA' }] }),
  });

  function loader(fetch: (query: StatsQuery) => Observable<LinkStats>) {
    return new StatsLoader<LinkStats>(fetch, TestBed.inject(DestroyRef));
  }

  it('carrega o período pedido', () => {
    const fetch = vi.fn(() => of(someLinkStats()));
    const stats = loader(fetch);

    stats.load(7, 'UTC');

    expect(fetch).toHaveBeenCalledWith(expect.objectContaining({ tz: 'UTC' }));
    expect(stats.stats()?.periodClicks).toBe(5);
    expect(stats.loading()).toBe(false);
    expect(stats.zoneFallback()).toBe(false);
  });

  it('fuso recusado pelo app: refaz no fuso padrão e avisa', () => {
    const fetch = vi.fn((query: StatsQuery) =>
      query.tz === DEFAULT_ZONE ? of(someLinkStats()) : throwError(() => rejectedZone),
    );
    const stats = loader(fetch);

    stats.load(30, 'Europe/Kyiv');

    expect(fetch).toHaveBeenCalledTimes(2);
    expect(stats.zoneFallback()).toBe(true);
    expect(stats.stats()).not.toBeNull();
  });

  it('outros erros aparecem na página', () => {
    const stats = loader(() => throwError(() => new HttpErrorResponse({ status: 503, error: problem(503, 'UPSTREAM_UNAVAILABLE') })));

    stats.load(30, DEFAULT_ZONE);

    expect(stats.error()?.code).toBe('UPSTREAM_UNAVAILABLE');
    expect(stats.loading()).toBe(false);
  });
});
