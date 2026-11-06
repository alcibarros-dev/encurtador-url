import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { LinkStats, StatsQuery, UserStats } from './models';

/** Estatísticas de um link e as do usuário (ADR-0004, D7). */
@Injectable({ providedIn: 'root' })
export class StatsApi {
  private readonly http = inject(HttpClient);

  link(code: string, query: StatsQuery): Observable<LinkStats> {
    return this.http.get<LinkStats>(`/api/v1/links/${encodeURIComponent(code)}/stats`, { params: { ...query } });
  }

  user(query: StatsQuery): Observable<UserStats> {
    return this.http.get<UserStats>('/api/v1/stats', { params: { ...query } });
  }
}
