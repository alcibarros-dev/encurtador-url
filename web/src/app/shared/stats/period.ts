import { StatsQuery } from '../../core/api/models';

/** O fuso padrão do app, usado quando ele recusa o do navegador (ADR-0004, D6). */
export const DEFAULT_ZONE = 'America/Sao_Paulo';
export const PERIOD_OPTIONS = [7, 30, 90] as const;
export const DEFAULT_DAYS = 30;

export function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || DEFAULT_ZONE;
  } catch {
    return DEFAULT_ZONE;
  }
}

/** Fusos que o navegador conhece, para o seletor; sempre com o padrão e o UTC. */
export function zoneOptions(current: string): string[] {
  const known = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : [];
  return [...new Set([current, DEFAULT_ZONE, 'UTC', ...known])].sort();
}

/** Dia de hoje (AAAA-MM-DD) no fuso pedido; um fuso que o navegador não conhece cai no padrão. */
export function todayIn(zone: string, now: Date = new Date()): string {
  const format = (timeZone: string) =>
    new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
  try {
    return format(zone);
  } catch {
    return format(DEFAULT_ZONE);
  }
}

/** Os últimos `days` dias, hoje incluído, no fuso pedido. */
export function periodQuery(days: number, zone: string, now: Date = new Date()): StatsQuery {
  const to = todayIn(zone, now);
  const [year, month, day] = to.split('-').map(Number);
  const from = new Date(Date.UTC(year, month - 1, day - (days - 1))).toISOString().slice(0, 10);
  return { from, to, tz: zone };
}
