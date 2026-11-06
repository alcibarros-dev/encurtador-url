import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Stats } from '../../core/api/models';
import { someLinkStats, someUserStats } from '../../../testing/fixtures';
import { DayPipe, LinkStatusPipe, dayLabel, statusLabel } from '../format';
import { StatsPanel } from './stats-panel';

describe('StatsPanel', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([])] }));

  async function render(stats: Stats = someLinkStats(), topLinks?: ReturnType<typeof someUserStats>['topLinks']) {
    const fixture = TestBed.createComponent(StatsPanel);
    fixture.componentRef.setInput('stats', stats);
    if (topLinks) {
      fixture.componentRef.setInput('topLinks', topLinks);
    }
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('mostra os totais, o gráfico com rótulo e a tabela com os mesmos números', async () => {
    const element = await render();

    expect(element.textContent).toContain('Cliques no período');
    expect(element.querySelector('canvas')?.getAttribute('aria-label')).toBe(
      'Cliques por dia, de 01/10/2026 a 03/10/2026, no fuso America/Sao_Paulo',
    );
    const days = [...element.querySelectorAll('details tbody tr')].map((row) =>
      [...row.querySelectorAll('td')].map((cell) => cell.textContent?.trim()).join(' '),
    );
    expect(days).toEqual(['01/10/2026 2', '02/10/2026 0', '03/10/2026 3']);
  });

  it('acesso direto aparece com nome, e os links mais acessados só nas estatísticas do usuário', async () => {
    const element = await render();
    expect(element.textContent).toContain('Acesso direto');
    expect(element.textContent).not.toContain('Links mais acessados');

    const withLinks = await render(someUserStats(), someUserStats().topLinks);
    const links = [...withLinks.querySelectorAll('a')].map((link) => link.getAttribute('href'));
    expect(links).toEqual(['/links/meu-artigo', '/links/antigo']);
    expect(withLinks.textContent).toContain('Expirado');
  });

  it('período sem cliques', async () => {
    const element = await render(someLinkStats({ topReferrers: [] }), []);

    expect(element.textContent?.match(/Nenhum clique no período/g)).toHaveLength(2);
  });

  it('formata dias e status', () => {
    expect(dayLabel('2026-01-09')).toBe('09/01/2026');
    expect(new DayPipe().transform('2026-12-31')).toBe('31/12/2026');
    expect(statusLabel('ACTIVE')).toBe('Ativo');
    expect(new LinkStatusPipe().transform('EXPIRED')).toBe('Expirado');
  });
});
