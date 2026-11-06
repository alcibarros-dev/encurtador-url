import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { problem, someUserStats } from '../../../testing/fixtures';
import { StatsPage } from './stats.page';

describe('StatsPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('carrega as estatísticas do usuário com os links mais acessados', async () => {
    const fixture = TestBed.createComponent(StatsPage);
    await fixture.whenStable();
    const request = http.expectOne((req) => req.url === '/api/v1/stats');
    expect(request.request.params.get('tz')).toBeTruthy();
    request.flush(someUserStats());
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Links mais acessados');
  });

  it('erro com nova tentativa manual', async () => {
    const fixture = TestBed.createComponent(StatsPage);
    await fixture.whenStable();
    http.expectOne((req) => req.url === '/api/v1/stats').flush(problem(503, 'UPSTREAM_UNAVAILABLE'), {
      status: 503,
      statusText: 'Unavailable',
    });
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[role=alert]')?.textContent).toContain('indisponível');

    (element.querySelector('[role=alert] button') as HTMLButtonElement).click();
    http.expectOne((req) => req.url === '/api/v1/stats').flush(someUserStats());
    await fixture.whenStable();
    expect(element.querySelector('[role=alert]')).toBeNull();
  });
});
