import { Injectable, inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

/** Avisos curtos na parte de baixo da tela, anunciados por leitores de tela (o snack bar usa uma região live). */
@Injectable({ providedIn: 'root' })
export class Notifier {
  private readonly snackBar = inject(MatSnackBar);

  info(message: string): void {
    this.snackBar.open(message, 'Fechar', { duration: 5000, politeness: 'polite' });
  }

  error(message: string): void {
    this.snackBar.open(message, 'Fechar', { duration: 8000, politeness: 'assertive' });
  }
}
