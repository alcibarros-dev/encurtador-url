import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { Router, TitleStrategy, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { FocusOnNavigation } from './focus-on-navigation';
import { PageTitleStrategy } from './page-title.strategy';
import { paginatorIntl } from './paginator-intl';

@Component({ selector: 'app-first-page', template: '<main><h1 tabindex="-1">Primeira</h1></main>' })
class FirstPage {}

@Component({ selector: 'app-second-page', template: '<main><h1 tabindex="-1">Segunda</h1></main>' })
class SecondPage {}

describe('acessibilidade da navegação', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'primeira', title: 'Primeira', component: FirstPage },
          { path: 'segunda', component: SecondPage },
        ]),
        { provide: TitleStrategy, useClass: PageTitleStrategy },
      ],
    });
  });

  it('o título do documento muda a cada rota, e o foco vai ao título da página a partir da segunda', async () => {
    TestBed.inject(FocusOnNavigation).start();
    // A primeira navegação, a da abertura do painel, não mexe no foco.
    const harness = await RouterTestingHarness.create();
    expect(document.activeElement).toBe(document.body);

    await harness.navigateByUrl('/primeira');
    harness.detectChanges();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Title).getTitle()).toBe('Primeira · Encurtador');
    expect(document.activeElement?.textContent).toBe('Primeira');

    await harness.navigateByUrl('/segunda');
    harness.detectChanges();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Title).getTitle()).toBe('Encurtador');
    expect(document.activeElement?.textContent).toBe('Segunda');
    expect(TestBed.inject(Router).url).toBe('/segunda');
  });

  it('o paginador fala português', () => {
    const intl = paginatorIntl();

    expect(intl.getRangeLabel(0, 20, 0)).toBe('Nenhum item');
    expect(intl.getRangeLabel(1, 20, 45)).toBe('21 a 40 de 45');
    expect(intl.nextPageLabel).toBe('Próxima página');
  });
});
