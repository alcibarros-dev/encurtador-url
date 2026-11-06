import { defineConfig, devices } from '@playwright/test';

/** Pausa entre as ações, em milissegundos, para acompanhar o navegador (`SLOW_MO=500 npm run test:headed`). */
const slowMo = Number(process.env['SLOW_MO'] ?? 0);

/**
 * Ponta a ponta do painel contra a stack do compose (ADR-0004, D11): suba antes com
 * `docker compose --profile app up -d --build`. PANEL_URL muda o endereço do painel.
 */
export default defineConfig({
  testDir: './tests',
  // Com a câmera lenta, cada teste leva bem mais tempo.
  timeout: slowMo > 0 ? 300_000 : 60_000,
  retries: process.env['CI'] ? 1 : 0,
  // O relatório HTML sai sempre, para o `npm run report` depois de uma falha.
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env['PANEL_URL'] ?? 'http://localhost:8070',
    locale: 'pt-BR',
    timezoneId: 'America/Sao_Paulo',
    trace: 'retain-on-failure',
    launchOptions: { slowMo },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
