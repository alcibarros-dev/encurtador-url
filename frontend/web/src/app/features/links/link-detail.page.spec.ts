import { Clipboard } from '@angular/cdk/clipboard';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { aLink, problem, someLinkStats } from '../../../testing/fixtures';
import { NotifierStub, notifierStub, stubProviders } from '../../../testing/stubs';
import { DEFAULT_ZONE } from '../../shared/stats/period';
import { LinkDetailPage } from './link-detail.page';

describe('LinkDetailPage', () => {
  let http: HttpTestingController;
  let notifier: NotifierStub;
  const copy = vi.fn<(text: string) => boolean>();

  beforeEach(() => {
    notifier = notifierStub();
    copy.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        ...stubProviders(notifier),
        { provide: Clipboard, useValue: { copy } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function open(code = 'meu-artigo') {
    const fixture = TestBed.createComponent(LinkDetailPage);
    fixture.componentRef.setInput('code', code);
    await fixture.whenStable();
    return fixture;
  }

  it('mostra o link e as estatísticas dele, com o destino em nova aba', async () => {
    const fixture = await open();
    http.expectOne('/api/v1/links/meu-artigo').flush(aLink());
    await fixture.whenStable();
    http.expectOne((req) => req.url === '/api/v1/links/meu-artigo/stats').flush(someLinkStats());
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;

    const target = element.querySelector('a[target=_blank]');
    expect(target?.getAttribute('href')).toBe('https://exemplo.com/artigo');
    expect(target?.getAttribute('rel')).toBe('noopener noreferrer');
    expect(element.textContent).toContain('Cliques por dia');

    copy.mockReturnValue(true);
    (element.querySelector('.acoes button') as HTMLButtonElement).click();
    expect(copy).toHaveBeenCalledWith('http://localhost:8060/meu-artigo');
    expect(notifier.info).toHaveBeenCalledWith('URL curta copiada.');
    copy.mockReturnValue(false);
    (element.querySelector('.acoes button') as HTMLButtonElement).click();
    expect(notifier.error).toHaveBeenCalled();
  });

  it('fuso recusado: refaz no fuso padrão e avisa', async () => {
    const fixture = await open();
    fixture.componentInstance['zone'].set('Europe/Kyiv');
    http.expectOne('/api/v1/links/meu-artigo').flush(aLink());
    await fixture.whenStable();
    const first = http.expectOne((req) => req.url === '/api/v1/links/meu-artigo/stats');
    first.flush(problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'tz', message: 'fuso inválido' }] }), {
      status: 400,
      statusText: 'Bad Request',
    });
    const fallback = http.expectOne((req) => req.url === '/api/v1/links/meu-artigo/stats');
    expect(fallback.request.params.get('tz')).toBe(DEFAULT_ZONE);
    fallback.flush(someLinkStats());
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('O fuso do navegador não é aceito');
  });

  it('link removido ou de outro usuário', async () => {
    const fixture = await open('outro');
    http.expectOne('/api/v1/links/outro').flush(problem(404, 'LINK_NOT_FOUND'), {
      status: 404,
      statusText: 'Not Found',
    });
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).querySelector('[role=alert]')?.textContent).toContain(
      'Link não encontrado',
    );
  });

  it('erro nas estatísticas com nova tentativa', async () => {
    const fixture = await open();
    http.expectOne('/api/v1/links/meu-artigo').flush(aLink());
    await fixture.whenStable();
    http.expectOne((req) => req.url === '/api/v1/links/meu-artigo/stats').flush(null, {
      status: 503,
      statusText: 'Unavailable',
    });
    await fixture.whenStable();
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('[role=alert] button')?.click();

    http.expectOne((req) => req.url === '/api/v1/links/meu-artigo/stats').flush(someLinkStats());
  });
});
