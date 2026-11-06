import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/session/guards';

/**
 * Rotas da SPA (ADR-0004, D6), cada feature carregada sob demanda. O BFF entrega o `index.html` para elas e só
 * aceita voltar do login para as rotas logadas.
 */
export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Início',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/home/home.page').then((m) => m.HomePage),
  },
  {
    path: 'links',
    title: 'Seus links',
    canActivate: [authGuard],
    loadComponent: () => import('./features/links/links.page').then((m) => m.LinksPage),
  },
  {
    path: 'links/:code',
    title: 'Link',
    canActivate: [authGuard],
    loadComponent: () => import('./features/links/link-detail.page').then((m) => m.LinkDetailPage),
  },
  {
    path: 'estatisticas',
    title: 'Estatísticas',
    canActivate: [authGuard],
    loadComponent: () => import('./features/stats/stats.page').then((m) => m.StatsPage),
  },
  {
    path: 'conta',
    title: 'Sua conta',
    canActivate: [authGuard],
    loadComponent: () => import('./features/account/account.page').then((m) => m.AccountPage),
  },
  {
    path: '**',
    title: 'Página não encontrada',
    loadComponent: () => import('./features/not-found/not-found.page').then((m) => m.NotFoundPage),
  },
];
