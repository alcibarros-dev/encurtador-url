import { Clipboard } from '@angular/cdk/clipboard';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { Link, Page } from '../../core/api/models';
import { aLink, problem } from '../../../testing/fixtures';
import { NotifierStub, notifierStub, stubProviders } from '../../../testing/stubs';
import { LinksPage } from './links.page';

function page(content: Link[], page = 0, totalElements = content.length): Page<Link> {
  return { content, page, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20) };
}

describe('LinksPage', () => {
  let http: HttpTestingController;
  let notifier: NotifierStub;
  let dialogResult: unknown;
  const open = vi.fn(() => ({ afterClosed: () => of(dialogResult) }));
  const copy = vi.fn<(text: string) => boolean>();

  beforeEach(() => {
    notifier = notifierStub();
    open.mockClear();
    copy.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        ...stubProviders(notifier),
        { provide: MatDialog, useValue: { open } },
        { provide: Clipboard, useValue: { copy } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(result: Page<Link>) {
    const fixture = TestBed.createComponent(LinksPage);
    http.expectOne('/api/v1/links?page=0&size=20').flush(result);
    await fixture.whenStable();
    return fixture;
  }

  function button(element: HTMLElement, text: string): HTMLButtonElement {
    return [...element.querySelectorAll('button')].find((b) => b.textContent?.trim() === text) as HTMLButtonElement;
  }

  it('lista os links com status, destino em nova aba e paginação', async () => {
    const fixture = await render(page([aLink(), aLink({ code: 'antigo', status: 'EXPIRED' })], 0, 45));
    const element = fixture.nativeElement as HTMLElement;

    expect([...element.querySelectorAll('.status')].map((s) => s.textContent)).toEqual(['Ativo', 'Expirado']);
    const target = element.querySelector('a[target=_blank]');
    expect(target?.getAttribute('rel')).toBe('noopener noreferrer');
    expect(element.querySelector('mat-paginator')).not.toBeNull();
  });

  it('sem links, convida a criar o primeiro', async () => {
    const fixture = await render(page([]));

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Você ainda não tem links');
  });

  it('erro com nova tentativa manual', async () => {
    const fixture = TestBed.createComponent(LinksPage);
    http.expectOne('/api/v1/links?page=0&size=20').flush(problem(503, 'UPSTREAM_UNAVAILABLE'), {
      status: 503,
      statusText: 'Unavailable',
    });
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('indisponível');

    button(element, 'Tentar de novo').click();
    http.expectOne('/api/v1/links?page=0&size=20').flush(page([aLink()]));
    await fixture.whenStable();
    expect(element.querySelectorAll('li')).toHaveLength(1);
  });

  it('criar abre o diálogo e, com um link criado, volta para a primeira página', async () => {
    const fixture = await render(page([aLink()]));
    dialogResult = aLink({ code: 'novo', shortUrl: 'http://localhost:8060/novo' });

    button(fixture.nativeElement, 'Criar link').click();

    expect(notifier.info).toHaveBeenCalledWith('Link criado: http://localhost:8060/novo');
    http.expectOne('/api/v1/links?page=0&size=20').flush(page([aLink()]));
  });

  it('copia a URL curta', async () => {
    const fixture = await render(page([aLink()]));
    copy.mockReturnValueOnce(true).mockReturnValueOnce(false);

    button(fixture.nativeElement, 'Copiar URL curta').click();
    button(fixture.nativeElement, 'Copiar URL curta').click();

    expect(copy).toHaveBeenCalledWith('http://localhost:8060/meu-artigo');
    expect(notifier.info).toHaveBeenCalledWith('URL curta copiada.');
    expect(notifier.error).toHaveBeenCalled();
  });

  it('remove depois de confirmar e, com a página vazia, volta uma página', async () => {
    const fixture = TestBed.createComponent(LinksPage);
    http.expectOne('/api/v1/links?page=0&size=20').flush(page([aLink()], 0, 21));
    await fixture.whenStable();
    fixture.componentInstance['changePage']({ pageIndex: 1, pageSize: 20, length: 21 });
    http.expectOne('/api/v1/links?page=1&size=20').flush(page([aLink({ code: 'ultimo' })], 1, 21));
    await fixture.whenStable();
    dialogResult = true;

    button(fixture.nativeElement, 'Remover').click();
    const removal = http.expectOne('/api/v1/links/ultimo');
    expect(removal.request.method).toBe('DELETE');
    removal.flush(null);
    http.expectOne('/api/v1/links?page=1&size=20').flush(page([], 1, 20));
    http.expectOne('/api/v1/links?page=0&size=20').flush(page([aLink()], 0, 20));

    expect(notifier.info).toHaveBeenCalledWith('Link ultimo removido.');
  });

  it('não remove sem confirmação; erro na remoção é avisado', async () => {
    const fixture = await render(page([aLink()]));
    dialogResult = false;
    button(fixture.nativeElement, 'Remover').click();

    dialogResult = true;
    button(fixture.nativeElement, 'Remover').click();
    http.expectOne('/api/v1/links/meu-artigo').flush(problem(404, 'LINK_NOT_FOUND'), {
      status: 404,
      statusText: 'Not Found',
    });

    expect(notifier.error).toHaveBeenCalledWith(expect.stringContaining('Link não encontrado'));
  });
});
