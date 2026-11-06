import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { NotifierStub, notifierStub, stubProviders } from '../../../testing/stubs';
import { SessionService } from '../../core/session/session.service';
import { AccountPage } from './account.page';

describe('AccountPage', () => {
  let notifier: NotifierStub;
  const logout = vi.fn<() => Promise<void>>();

  beforeEach(() => {
    notifier = notifierStub();
    logout.mockReset();
    TestBed.configureTestingModule({
      providers: [
        ...stubProviders(notifier),
        { provide: SessionService, useValue: { session: signal({ email: 'fulano@exemplo.com' }), logout } },
      ],
    });
  });

  it('mostra o e-mail, leva à troca de senha e sai', async () => {
    logout.mockResolvedValue();
    const fixture = TestBed.createComponent(AccountPage);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.textContent).toContain('fulano@exemplo.com');
    expect(element.querySelector('a')?.getAttribute('href')).toBe('/bff/password');
    element.querySelector('button')?.click();
    await fixture.whenStable();
    expect(logout).toHaveBeenCalled();
  });

  it('falha ao sair: avisa e libera o botão', async () => {
    logout.mockRejectedValue(new HttpErrorResponse({ status: 0 }));
    const fixture = TestBed.createComponent(AccountPage);
    await fixture.whenStable();
    const button = (fixture.nativeElement as HTMLElement).querySelector('button') as HTMLButtonElement;

    button.click();
    await fixture.whenStable();

    expect(notifier.error).toHaveBeenCalledWith(expect.stringContaining('Sem conexão'));
    expect(button.disabled).toBe(false);
  });
});
