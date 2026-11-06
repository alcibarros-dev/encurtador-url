import { Injectable } from '@angular/core';

/**
 * Navegação de página inteira: o login, o cadastro, a troca de senha e o logout saem da SPA (ADR-0004, D4). Fica
 * num serviço para que os testes a substituam.
 */
@Injectable({ providedIn: 'root' })
export class BrowserNavigation {
  assign(url: string): void {
    window.location.assign(url);
  }
}
