import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { problem } from '../../../testing/fixtures';
import { NavigationStub, navigationStub, notifierStub, stubProviders } from '../../../testing/stubs';
import { SessionService } from './session.service';

describe('SessionService', () => {
  let sessions: SessionService;
  let http: HttpTestingController;
  let navigation: NavigationStub;

  beforeEach(() => {
    navigation = navigationStub();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ...stubProviders(notifierStub(), navigation)],
    });
    sessions = TestBed.inject(SessionService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('consulta a sessão uma vez só', async () => {
    const first = sessions.load();
    const second = sessions.load();
    http.expectOne('/bff/session').flush({ email: 'fulano@exemplo.com' });

    expect(await first).toEqual({ email: 'fulano@exemplo.com' });
    expect(await second).toEqual({ email: 'fulano@exemplo.com' });
    expect(sessions.loggedIn()).toBe(true);
  });

  it('401 quer dizer ninguém logado', async () => {
    const loaded = sessions.load();
    http.expectOne('/bff/session').flush(problem(401, 'UNAUTHENTICATED'), { status: 401, statusText: 'Unauthorized' });

    expect(await loaded).toBeNull();
    expect(sessions.session()).toBeNull();
    expect(sessions.loggedIn()).toBe(false);
  });

  it('com o BFF fora, falha e tenta de novo na próxima consulta', async () => {
    const failed = sessions.load();
    http.expectOne('/bff/session').flush(null, { status: 503, statusText: 'Unavailable' });
    await expect(failed).rejects.toBeTruthy();

    const retried = sessions.load();
    http.expectOne('/bff/session').flush({ email: 'fulano@exemplo.com' });
    expect(await retried).toEqual({ email: 'fulano@exemplo.com' });
  });

  it('login, cadastro e troca de senha navegam a página inteira', () => {
    sessions.login('/estatisticas');
    sessions.register();
    sessions.changePassword();

    expect(navigation.assign.mock.calls.map((call) => call[0])).toEqual([
      '/oauth2/authorization/encurtador?returnTo=%2Festatisticas',
      '/bff/register',
      '/bff/password',
    ]);
  });

  it('logout encerra a sessão do BFF e navega para o logout do provedor', async () => {
    const loaded = sessions.load();
    http.expectOne('/bff/session').flush({ email: 'fulano@exemplo.com' });
    await loaded;

    const done = sessions.logout();
    const request = http.expectOne('/bff/logout');
    expect(request.request.method).toBe('POST');
    request.flush({ logoutUrl: 'http://localhost:8090/connect/logout?id_token_hint=x' });
    await done;

    expect(sessions.session()).toBeNull();
    expect(navigation.assign).toHaveBeenCalledWith('http://localhost:8090/connect/logout?id_token_hint=x');
  });

  it('sessão expirada durante o uso liga o aviso; sem sessão, não', async () => {
    sessions.markExpired();
    expect(sessions.expired()).toBe(false);

    const loaded = sessions.load();
    http.expectOne('/bff/session').flush({ email: 'fulano@exemplo.com' });
    await loaded;
    sessions.markExpired();

    expect(sessions.expired()).toBe(true);
    expect(sessions.loggedIn()).toBe(false);
    expect(await sessions.load()).toBeNull();
  });
});
