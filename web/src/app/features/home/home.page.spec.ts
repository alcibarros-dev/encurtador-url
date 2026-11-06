import { TestBed } from '@angular/core/testing';
import { HomePage } from './home.page';

describe('HomePage', () => {
  it('"Entrar" volta para a rota pedida e "Criar conta" vai ao cadastro do BFF', async () => {
    const fixture = TestBed.createComponent(HomePage);
    fixture.componentRef.setInput('returnTo', '/estatisticas');
    await fixture.whenStable();
    const links = [...(fixture.nativeElement as HTMLElement).querySelectorAll('a')];

    expect(links.map((link) => [link.textContent?.trim(), link.getAttribute('href')])).toEqual([
      ['Entrar', '/oauth2/authorization/encurtador?returnTo=%2Festatisticas'],
      ['Criar conta', '/bff/register'],
    ]);
  });

  it('avisa quando o login falhou', async () => {
    const fixture = TestBed.createComponent(HomePage);
    fixture.componentRef.setInput('erro', 'login');
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).querySelector('[role=alert]')?.textContent).toContain(
      'Não foi possível entrar',
    );
  });
});
