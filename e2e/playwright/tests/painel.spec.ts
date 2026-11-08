import { expect, test } from '@playwright/test';
import { collectCspViolations, expectAccessible } from './accessibility';

/** O serviço de redirecionamento (ADR-0006), dono dos links curtos. REDIRECT_URL muda o endereço. */
const redirectUrl = process.env['REDIRECT_URL'] ?? 'http://localhost:8060';
/** A API, que cria os links mas não os redireciona. API_URL muda o endereço. */
const apiUrl = process.env['API_URL'] ?? 'http://localhost:8080';

/**
 * O caminho completo do painel (ADR-0004, D11): cadastro → login → criar link → abrir o link curto no serviço de
 * redirecionamento (ADR-0006) → estatísticas → remover → trocar senha → sair → entrar com a senha nova, com o axe em
 * cada página, inclusive as do auth-server, e a criação de link só pelo teclado. Cada execução cria uma conta nova,
 * que só serve para o teste.
 */
test('cadastro, links, estatísticas, troca de senha e saída', async ({ page, request }) => {
  const csp = collectCspViolations(page);
  const email = `painel-${Date.now()}-${Math.floor(Math.random() * 1e6)}@exemplo.com`;
  const password = `senha-${Math.random().toString(36).slice(2, 12)}`;
  const newPassword = `nova-${Math.random().toString(36).slice(2, 12)}`;
  const target = 'https://exemplo.com/artigo-do-painel';

  await test.step('página inicial', async () => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Links curtos');
    await expect(page).toHaveTitle('Início · Encurtador');
    await expectAccessible(page);
  });

  await test.step('cadastro no auth-server, com login automático e volta para os links', async () => {
    await page.getByRole('link', { name: 'Criar conta' }).click();
    await expect(page.getByRole('heading', { name: 'Criar conta' })).toBeVisible();
    await expectAccessible(page);
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill(password);
    await page.getByRole('button', { name: 'Criar conta' }).click();
    await expect(page).toHaveURL(/\/links$/);
    await expect(page.getByRole('heading', { name: 'Seus links' })).toBeVisible();
    await expect(page.getByText('Você ainda não tem links')).toBeVisible();
    await expectAccessible(page);
  });

  let code = '';
  let shortUrl = '';
  await test.step('cria um link só pelo teclado', async () => {
    await page.getByRole('button', { name: 'Criar link' }).focus();
    await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog', { name: 'Novo link' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel('Endereço de destino')).toBeFocused();
    await expectAccessible(page);
    await page.keyboard.type(target);
    await page.keyboard.press('Enter');
    await expect(dialog).toBeHidden();
    await expect(page.getByText(/Link criado:/)).toBeVisible();
    const item = page.getByRole('listitem').first();
    await expect(item).toContainText(target);
    code = (await item.locator('.codigo').textContent())?.trim() ?? '';
    expect(code).toMatch(/^[A-Za-z0-9]{7}$/);
    shortUrl = (await item.locator('.curta').textContent())?.trim() ?? '';
    // O link curto é do serviço de redirecionamento, e não da API (ADR-0006, D1).
    expect(shortUrl).toBe(`${redirectUrl}/${code}`);
  });

  await test.step('o serviço de redirecionamento leva ao destino (GET /{code}), e a API não', async () => {
    // Pelo cliente HTTP do Playwright, e não pelo navegador: o navegador seguiria o 302 até o destino, um site de
    // verdade, e o page.route só intercepta a primeira URL de um redirecionamento. Contexto próprio, sem os cookies
    // do painel, como um visitante qualquer.
    const response = await request.get(shortUrl, { maxRedirects: 0 });
    expect(response.status()).toBe(302);
    expect(response.headers()['location']).toBe(target);
    expect(response.headers()['cache-control']).toBe('private, no-store');

    // Na API, o mesmo caminho é uma rota protegida como qualquer outra: o redirecionamento saiu dela.
    const onTheApi = await request.get(`${apiUrl}/${code}`, { maxRedirects: 0 });
    expect(onTheApi.status()).toBe(401);
  });

  await test.step('estatísticas do link e do usuário', async () => {
    await page.getByRole('link', { name: `Estatísticas de ${code}` }).click();
    await expect(page.getByRole('heading', { name: `Link ${code}` })).toBeVisible();
    await expect(page.getByText('Cliques no período')).toBeVisible();
    await expect(page.getByRole('heading', { name: `Link ${code}` })).toBeFocused();
    await expectAccessible(page);

    // O redirect publica o clique no Kafka sem que o 302 espere, e a API o consome: recarrega até ele aparecer.
    const totalClicks = page.locator('.cartao', { hasText: 'Cliques desde a criação' }).locator('strong');
    await expect(async () => {
      await page.reload();
      await expect(totalClicks).toHaveText('1', { timeout: 2_000 });
    }).toPass({ timeout: 30_000 });

    await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Estatísticas' }).click();
    await expect(page.getByRole('heading', { name: 'Estatísticas', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Links mais acessados' })).toBeVisible();
    await expectAccessible(page);
  });

  await test.step('remove o link depois de confirmar', async () => {
    await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Links' }).click();
    await page.getByRole('button', { name: `Remover ${code}` }).click();
    const confirm = page.getByRole('dialog', { name: `Remover ${code}?` });
    await expectAccessible(page);
    await confirm.getByRole('button', { name: 'Remover' }).click();
    await expect(page.getByText(`Link ${code} removido.`)).toBeVisible();
    await expect(page.getByText('Você ainda não tem links')).toBeVisible();

    // A API grava o tombstone no Redis depois do commit, e o serviço de redirecionamento o lê: o link curto, que
    // estava no cache, já responde 410 no acesso seguinte (ADR-0006, D4).
    const gone = await request.get(shortUrl, { maxRedirects: 0 });
    expect(gone.status()).toBe(410);
    expect((await gone.json()).code).toBe('LINK_DELETED');
  });

  await test.step('troca a senha no auth-server e volta para a conta', async () => {
    await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Conta' }).click();
    await expect(page.getByText(email)).toBeVisible();
    await expectAccessible(page);
    await page.getByRole('link', { name: 'Trocar senha' }).click();
    await expect(page.getByRole('heading', { name: 'Trocar senha' })).toBeVisible();
    await expectAccessible(page);
    await page.getByLabel('Senha atual').fill(password);
    await page.getByLabel('Nova senha').fill(newPassword);
    await page.getByRole('button', { name: 'Trocar senha' }).click();
    await expect(page.getByRole('heading', { name: 'Senha alterada' })).toBeVisible();
    await expectAccessible(page);
    await page.getByRole('link', { name: 'Voltar ao painel' }).click();
    await expect(page).toHaveURL(/\/conta$/);
  });

  await test.step('sai do painel e do auth-server', async () => {
    await page.getByRole('button', { name: 'Sair' }).click();
    await expect(page).toHaveURL(/localhost:8070\/$|\/$/);
    await expect(page.getByRole('link', { name: 'Entrar' })).toBeVisible();
  });

  await test.step('entra de novo, já com a senha nova', async () => {
    await page.getByRole('link', { name: 'Entrar' }).click();
    await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible();
    await expectAccessible(page);
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill(newPassword);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await expect(page.getByRole('heading', { name: 'Seus links' })).toBeVisible();
  });

  expect(csp).toEqual([]);
});

test('rota logada sem sessão volta ao início e, depois do login, à rota pedida', async ({ page }) => {
  await page.goto('/estatisticas');
  await expect(page).toHaveURL(/\/\?returnTo=%2Festatisticas$/);
  await expect(page.getByRole('link', { name: 'Entrar' })).toHaveAttribute(
    'href',
    '/oauth2/authorization/encurtador?returnTo=%2Festatisticas',
  );
});

test('endereço desconhecido mostra a página de não encontrado', async ({ page }) => {
  const response = await page.goto('/nao/existe');
  expect(response?.status()).toBe(404);
  await expect(page.getByRole('heading', { name: 'Página não encontrada' })).toBeVisible();
  await expectAccessible(page);
});
