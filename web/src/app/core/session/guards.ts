import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Notifier } from '../errors/notifier';
import { SessionService } from './session.service';

/** Páginas que exigem login: sem sessão, a página inicial, guardando a rota pedida para depois do login. */
export const authGuard: CanActivateFn = async (_route, state) => {
  const sessions = inject(SessionService);
  const router = inject(Router);
  const notifier = inject(Notifier);
  try {
    const session = await sessions.load();
    return session ? true : router.createUrlTree(['/'], { queryParams: { returnTo: state.url } });
  } catch {
    notifier.error('O painel está indisponível agora. Tente de novo em instantes.');
    return router.createUrlTree(['/']);
  }
};

/** A página inicial é para quem não entrou: logado, vai direto para os links. */
export const guestGuard: CanActivateFn = async () => {
  const sessions = inject(SessionService);
  const router = inject(Router);
  try {
    return (await sessions.load()) ? router.createUrlTree(['/links']) : true;
  } catch {
    return true;
  }
};
