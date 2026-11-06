import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { problem } from '../../../testing/fixtures';
import { NotifierStub, notifierStub, stubProviders } from '../../../testing/stubs';
import { GlobalErrorHandler } from './global-error-handler';

describe('GlobalErrorHandler', () => {
  let handler: GlobalErrorHandler;
  let notifier: NotifierStub;
  let console: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    notifier = notifierStub();
    TestBed.configureTestingModule({ providers: [GlobalErrorHandler, ...stubProviders(notifier)] });
    handler = TestBed.inject(GlobalErrorHandler);
    console = vi.spyOn(globalThis.console, 'error').mockImplementation(() => undefined);
  });

  afterEach(() => console.mockRestore());

  it('registra só o code e o instance de um erro da API e mostra a mensagem genérica', () => {
    handler.handleError(
      new HttpErrorResponse({ status: 500, error: problem(500, 'INTERNAL', { instance: '/api/v1/links' }) }),
    );

    expect(console).toHaveBeenCalledWith('Erro não tratado', { code: 'INTERNAL', instance: '/api/v1/links' });
    expect(notifier.error).toHaveBeenCalledWith('Algo deu errado. Tente de novo.');
  });

  it('de outro erro, registra só o tipo, também dentro de uma promise rejeitada', () => {
    handler.handleError({ rejection: new TypeError('dado do usuário') });
    handler.handleError('texto');

    expect(console).toHaveBeenNthCalledWith(1, 'Erro não tratado', 'TypeError');
    expect(console).toHaveBeenNthCalledWith(2, 'Erro não tratado', 'string');
  });
});
