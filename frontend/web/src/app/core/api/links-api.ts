import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Link, NewLink, Page } from './models';

/** Links do usuário, pelo repasse `/api/**` do BFF (ADR-0004, D5). */
@Injectable({ providedIn: 'root' })
export class LinksApi {
  static readonly PAGE_SIZE = 20;

  private readonly http = inject(HttpClient);

  list(page: number): Observable<Page<Link>> {
    return this.http.get<Page<Link>>('/api/v1/links', {
      params: { page, size: LinksApi.PAGE_SIZE },
    });
  }

  get(code: string): Observable<Link> {
    return this.http.get<Link>(`/api/v1/links/${encodeURIComponent(code)}`);
  }

  create(link: NewLink): Observable<Link> {
    return this.http.post<Link>('/api/v1/links', link);
  }

  delete(code: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/links/${encodeURIComponent(code)}`);
  }
}
