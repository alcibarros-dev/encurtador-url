import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { SessionService } from '../session/session.service';
import { sessionExpiryInterceptor } from './session-expiry.interceptor';

describe('sessionExpiryInterceptor', () => {
  let http: HttpClient;
  let controller: HttpTestingController;
  const markExpired = vi.fn();

  beforeEach(() => {
    markExpired.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([sessionExpiryInterceptor])),
        provideHttpClientTesting(),
        { provide: SessionService, useValue: { markExpired } },
      ],
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
  });

  it('401 numa chamada ao BFF marca a sessão como expirada', async () => {
    const request = firstValueFrom(http.get('/api/v1/links'));
    controller.expectOne('/api/v1/links').flush(null, { status: 401, statusText: 'Unauthorized' });

    await expect(request).rejects.toBeTruthy();
    expect(markExpired).toHaveBeenCalledTimes(1);
  });

  it('401 da consulta inicial da sessão e outros erros não', async () => {
    const session = firstValueFrom(http.get('/bff/session'));
    controller.expectOne('/bff/session').flush(null, { status: 401, statusText: 'Unauthorized' });
    const other = firstValueFrom(http.get('/api/v1/links'));
    controller.expectOne('/api/v1/links').flush(null, { status: 500, statusText: 'Error' });

    await expect(session).rejects.toBeTruthy();
    await expect(other).rejects.toBeTruthy();
    expect(markExpired).not.toHaveBeenCalled();
  });
});
