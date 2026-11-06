import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { SessionService } from './core/session/session.service';

describe('App', () => {
  const loggedIn = signal(false);
  const expired = signal(false);

  beforeEach(() => {
    loggedIn.set(false);
    expired.set(false);
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: SessionService, useValue: { loggedIn, expired } }],
    });
  });

  it('só mostra a navegação para quem entrou', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('nav')).toBeNull();

    loggedIn.set(true);
    await fixture.whenStable();
    expect([...element.querySelectorAll('nav a')].map((link) => link.getAttribute('href'))).toEqual([
      '/links',
      '/estatisticas',
      '/conta',
    ]);
  });

  it('sessão expirada: aviso com "Entrar" de volta para a rota atual', async () => {
    expired.set(true);
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const alert = (fixture.nativeElement as HTMLElement).querySelector('[role=alert]');

    expect(alert?.textContent).toContain('Sua sessão expirou');
    expect(alert?.querySelector('a')?.getAttribute('href')).toBe('/oauth2/authorization/encurtador?returnTo=%2Flinks');
  });

  it('"Pular para o conteúdo" leva o foco ao conteúdo', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    document.body.appendChild(element);

    element.querySelector<HTMLAnchorElement>('.pular')?.click();

    expect(document.activeElement?.id).toBe('conteudo');
    element.remove();
  });
});
