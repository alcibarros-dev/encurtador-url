import { safeReturnTo } from './return-to';

/** Início do login no BFF, com a volta para uma rota da SPA (ADR-0004, D4). */
export function loginUrl(returnTo: string | null | undefined): string {
  return `/oauth2/authorization/encurtador?returnTo=${encodeURIComponent(safeReturnTo(returnTo))}`;
}
