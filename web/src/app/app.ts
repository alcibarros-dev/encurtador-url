import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { FocusOnNavigation } from './core/a11y/focus-on-navigation';
import { loginUrl } from './core/navigation/login-url';
import { SessionService } from './core/session/session.service';

/** Layout: pular para o conteúdo, navegação, aviso de sessão expirada e a página da rota. */
@Component({
  selector: 'app-root',
  imports: [MatButtonModule, RouterLink, RouterLinkActive, RouterOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly sessions = inject(SessionService);
  private readonly router = inject(Router);

  constructor() {
    inject(FocusOnNavigation).start();
  }

  /** Volta para a rota atual depois do login. */
  protected loginHref(): string {
    return loginUrl(this.router.url);
  }

  protected skipToContent(event: Event): void {
    event.preventDefault();
    document.getElementById('conteudo')?.focus();
  }
}
