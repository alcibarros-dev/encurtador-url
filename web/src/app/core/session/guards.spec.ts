import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { NotifierStub, notifierStub, stubProviders } from '../../../testing/stubs';
import { authGuard, guestGuard } from './guards';
import { Session, SessionService } from './session.service';

describe('guards', () => {
  let notifier: NotifierStub;
  let load: ReturnType<typeof vi.fn<() => Promise<Session | null>>>;

  beforeEach(() => {
    notifier = notifierStub();
    load = vi.fn<() => Promise<Session | null>>();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), ...stubProviders(notifier), { provide: SessionService, useValue: { load } }],
    });
  });

  function run(guard: typeof authGuard, url = '/links/meu-artigo') {
    return TestBed.runInInjectionContext(() =>
      guard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    ) as Promise<boolean | UrlTree>;
  }

  function serialize(result: boolean | UrlTree): string | boolean {
    return typeof result === 'boolean' ? result : TestBed.inject(Router).serializeUrl(result);
  }

  it('deixa entrar quem tem sessão', async () => {
    load.mockResolvedValue({ email: 'fulano@exemplo.com' });

    expect(await run(authGuard)).toBe(true);
    expect(serialize(await run(guestGuard))).toBe('/links');
  });

  it('sem sessão, leva ao início guardando a rota pedida', async () => {
    load.mockResolvedValue(null);

    expect(serialize(await run(authGuard))).toBe('/?returnTo=%2Flinks%2Fmeu-artigo');
    expect(await run(guestGuard)).toBe(true);
  });

  it('com o BFF fora, avisa e leva ao início', async () => {
    load.mockRejectedValue(new Error('fora'));

    expect(serialize(await run(authGuard))).toBe('/');
    expect(notifier.error).toHaveBeenCalled();
    expect(await run(guestGuard)).toBe(true);
  });
});
