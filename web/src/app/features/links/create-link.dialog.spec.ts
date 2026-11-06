import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { aLink, problem } from '../../../testing/fixtures';
import { CreateLinkDialog } from './create-link.dialog';

describe('CreateLinkDialog', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<CreateLinkDialog>;
  const close = vi.fn();

  beforeEach(async () => {
    close.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CreateLinkDialog);
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  function element(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function type(index: number, value: string): void {
    const input = element().querySelectorAll('input')[index];
    input.value = value;
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
  }

  /** A resposta do app chega por promises: espera a fila de microtarefas esvaziar antes de olhar a tela. */
  async function settle(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();
  }

  async function submit(): Promise<void> {
    element().querySelector('form')?.dispatchEvent(new Event('submit'));
    await settle();
  }

  function errors(): string[] {
    return [...element().querySelectorAll('mat-error')].map((error) => error.textContent?.trim() ?? '');
  }

  it('confere os campos antes de enviar', async () => {
    await submit();
    expect(errors()).toEqual(['Informe o endereço de destino.']);

    type(0, 'ftp://exemplo.com');
    type(1, 'ab');
    type(2, '2000-01-01T10:00');
    await submit();
    expect(errors()).toEqual([
      'Use um endereço que comece com http:// ou https://.',
      'Use de 4 a 32 caracteres: letras, números, _ e -.',
      'Escolha uma data no futuro.',
    ]);
  });

  it('cria o link e fecha o diálogo com ele', async () => {
    type(0, ' https://exemplo.com/artigo ');
    type(1, 'meu-artigo');
    type(2, '2099-12-31T23:59');
    await submit();

    const request = http.expectOne('/api/v1/links');
    expect(request.request.body).toEqual({
      url: 'https://exemplo.com/artigo',
      alias: 'meu-artigo',
      expiresAt: new Date('2099-12-31T23:59').toISOString(),
    });
    request.flush(aLink());
    await settle();
    expect(close).toHaveBeenCalledWith(aLink());
  });

  it('sem alias nem validade, manda só a URL', async () => {
    type(0, 'https://exemplo.com/');
    await submit();

    const request = http.expectOne('/api/v1/links');
    expect(request.request.body).toEqual({ url: 'https://exemplo.com/', alias: undefined, expiresAt: undefined });
    request.flush(aLink());
  });

  it('alias em uso aparece no campo do alias', async () => {
    type(0, 'https://exemplo.com/');
    type(1, 'em-uso');
    await submit();
    http.expectOne('/api/v1/links').flush(problem(409, 'ALIAS_TAKEN'), { status: 409, statusText: 'Conflict' });
    await settle();

    expect(errors()).toEqual(['Este alias já está em uso. Escolha outro.']);
    expect(close).not.toHaveBeenCalled();
  });

  it('cada erro de validação do app vai para o campo dele', async () => {
    type(0, 'https://192.168.0.1/');
    await submit();
    http.expectOne('/api/v1/links').flush(
      problem(400, 'VALIDATION_FAILED', {
        errors: [{ field: 'url', message: 'não pode apontar para um endereço IP privado, local ou reservado' }],
      }),
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();

    expect(errors()).toEqual(['não pode apontar para um endereço IP privado, local ou reservado']);
  });

  it('cota esgotada aparece no topo do formulário', async () => {
    type(0, 'https://exemplo.com/');
    await submit();
    http.expectOne('/api/v1/links').flush(problem(409, 'LINK_QUOTA_EXCEEDED'), { status: 409, statusText: 'Conflict' });
    await settle();

    expect(element().querySelector('[role=alert]')?.textContent).toContain('100 links ativos');
  });
});
