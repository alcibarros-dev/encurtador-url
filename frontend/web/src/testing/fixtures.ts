import { Link, LinkStats, UserStats } from '../app/core/api/models';

/** Dados de exemplo dos testes, no formato do app. */
export function aLink(overrides: Partial<Link> = {}): Link {
  return {
    code: 'meu-artigo',
    shortUrl: 'http://localhost:8060/meu-artigo',
    targetUrl: 'https://exemplo.com/artigo',
    status: 'ACTIVE',
    createdAt: '2026-10-01T12:00:00Z',
    expiresAt: '2026-10-31T12:00:00Z',
    ...overrides,
  };
}

export function someLinkStats(overrides: Partial<LinkStats> = {}): LinkStats {
  return {
    code: 'meu-artigo',
    totalClicks: 12,
    periodClicks: 5,
    from: '2026-10-01',
    to: '2026-10-03',
    timezone: 'America/Sao_Paulo',
    daily: [
      { date: '2026-10-01', clicks: 2 },
      { date: '2026-10-02', clicks: 0 },
      { date: '2026-10-03', clicks: 3 },
    ],
    topReferrers: [
      { host: 't.co', clicks: 4 },
      { host: null, clicks: 1 },
    ],
    ...overrides,
  };
}

export function someUserStats(overrides: Partial<UserStats> = {}): UserStats {
  const stats: Partial<LinkStats> = { ...someLinkStats() };
  delete stats.code;
  return {
    ...(stats as Omit<LinkStats, 'code'>),
    topLinks: [
      { code: 'meu-artigo', clicks: 4, status: 'ACTIVE' },
      { code: 'antigo', clicks: 1, status: 'EXPIRED' },
    ],
    ...overrides,
  };
}

/** Corpo problem+json do app (RFC 9457). */
export function problem(status: number, code: string, extra: Record<string, unknown> = {}) {
  return { type: `urn:encurtador:problem:${code.toLowerCase()}`, title: 'Erro', status, code, ...extra };
}
