/** Formatos do app (ADR-0001, D12; ADR-0004, D7), repassados pelo BFF sem tradução. */
export type LinkStatus = 'ACTIVE' | 'EXPIRED';

export interface Link {
  code: string;
  shortUrl: string;
  targetUrl: string;
  status: LinkStatus;
  createdAt: string;
  expiresAt: string;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface NewLink {
  url: string;
  alias?: string;
  /** ISO-8601 com fuso; sem ele, o padrão do app (30 dias). */
  expiresAt?: string;
}

export interface DailyClicks {
  date: string;
  clicks: number;
}

export interface ReferrerClicks {
  /** `null` é acesso direto, sem Referer. */
  host: string | null;
  clicks: number;
}

export interface LinkClicks {
  code: string;
  clicks: number;
  status: LinkStatus;
}

/** O que as estatísticas de um link e as do usuário têm em comum. */
export interface Stats {
  totalClicks: number;
  periodClicks: number;
  from: string;
  to: string;
  timezone: string;
  daily: DailyClicks[];
  topReferrers: ReferrerClicks[];
}

export interface LinkStats extends Stats {
  code: string;
}

export interface UserStats extends Stats {
  topLinks: LinkClicks[];
}

export interface StatsQuery {
  from: string;
  to: string;
  tz: string;
}
