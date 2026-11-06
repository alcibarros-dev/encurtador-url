import { DEFAULT_ZONE, browserTimeZone, periodQuery, todayIn, zoneOptions } from './period';

describe('período das estatísticas', () => {
  const now = new Date('2026-11-04T02:30:00Z');

  it('o dia de hoje depende do fuso', () => {
    expect(todayIn('UTC', now)).toBe('2026-11-04');
    expect(todayIn('America/Sao_Paulo', now)).toBe('2026-11-03');
    expect(todayIn('Fuso/Inexistente', now)).toBe('2026-11-03');
  });

  it('os últimos N dias incluem hoje', () => {
    expect(periodQuery(7, 'UTC', now)).toEqual({ from: '2026-10-29', to: '2026-11-04', tz: 'UTC' });
    expect(periodQuery(30, 'America/Sao_Paulo', now)).toEqual({
      from: '2026-10-05',
      to: '2026-11-03',
      tz: 'America/Sao_Paulo',
    });
  });

  it('o seletor sempre oferece o fuso atual, o padrão e o UTC', () => {
    const zones = zoneOptions('Asia/Tokyo');

    expect(zones).toEqual(expect.arrayContaining(['Asia/Tokyo', DEFAULT_ZONE, 'UTC']));
    expect(zones).toEqual([...zones].sort());
    expect(browserTimeZone()).toBeTruthy();
  });
});
