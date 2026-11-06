/** Rotas da SPA para onde o login pode voltar (ADR-0004, D4 e D6); o BFF aplica a mesma regra. */
const SPA_ROUTES = [/^\/links(\/[A-Za-z0-9_-]+)?$/, /^\/estatisticas$/, /^\/conta$/];

export const DEFAULT_RETURN_TO = '/links';

/** Só caminhos da própria SPA: nada de `//host`, `/\host` nem URL absoluta. */
export function safeReturnTo(candidate: string | null | undefined): string {
  if (!candidate || !candidate.startsWith('/') || candidate.startsWith('//') || candidate.startsWith('/\\')) {
    return DEFAULT_RETURN_TO;
  }
  const path = candidate.split(/[?#]/, 1)[0];
  return SPA_ROUTES.some((route) => route.test(path)) ? candidate : DEFAULT_RETURN_TO;
}
