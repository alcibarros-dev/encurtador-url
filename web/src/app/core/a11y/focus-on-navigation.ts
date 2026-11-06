import { DestroyRef, Injectable, Injector, afterNextRender, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

/**
 * A cada troca de rota, o foco vai para o título principal da página (ADR-0004, D10): o leitor de tela anuncia a
 * página nova, e quem navega pelo teclado recomeça do conteúdo. A primeira navegação fica de fora, para não roubar o
 * foco de quem acabou de abrir o painel.
 */
@Injectable({ providedIn: 'root' })
export class FocusOnNavigation {
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);

  start(): void {
    let first = true;
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        if (first) {
          first = false;
          return;
        }
        afterNextRender(() => document.querySelector<HTMLElement>('main h1')?.focus(), { injector: this.injector });
      });
  }
}
