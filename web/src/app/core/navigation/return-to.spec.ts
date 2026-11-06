import { loginUrl } from './login-url';
import { DEFAULT_RETURN_TO, safeReturnTo } from './return-to';

describe('safeReturnTo', () => {
  it.each(['/links', '/links/meu-artigo', '/estatisticas', '/conta', '/links?page=2'])('aceita a rota %s', (path) => {
    expect(safeReturnTo(path)).toBe(path);
  });

  it.each([null, undefined, '', 'links', '//mal.com', '/\\mal.com', 'https://mal.com/links', '/', '/admin', '/links/a/b'])(
    'recusa %s e volta para os links',
    (path) => {
      expect(safeReturnTo(path)).toBe(DEFAULT_RETURN_TO);
    },
  );

  it('monta o início do login com a volta codificada', () => {
    expect(loginUrl('/links/meu-artigo')).toBe('/oauth2/authorization/encurtador?returnTo=%2Flinks%2Fmeu-artigo');
    expect(loginUrl('//mal.com')).toBe('/oauth2/authorization/encurtador?returnTo=%2Flinks');
  });
});
