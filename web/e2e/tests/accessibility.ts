import AxeBuilder from '@axe-core/playwright';
import { Page, expect } from '@playwright/test';

/** WCAG 2.2 nível AA (ADR-0004, D10), no painel e nas páginas do auth-server. */
export async function expectAccessible(page: Page): Promise<void> {
  // Diálogos e avisos do Material entram com animação de opacidade: o contraste só vale depois dela.
  await page.evaluate(() => Promise.all(document.getAnimations().map((animation) => animation.finished)));
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze();
  const violations = results.violations.map(
    (violation) => `${violation.id}: ${violation.help} (${violation.nodes.map((node) => node.target).join(', ')})`,
  );
  expect(violations, `violações de acessibilidade em ${page.url()}`).toEqual([]);
}

/** Nenhuma violação de CSP durante a página (ADR-0004, D9). */
export function collectCspViolations(page: Page): string[] {
  const violations: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && /Content Security Policy/i.test(message.text())) {
      violations.push(message.text());
    }
  });
  return violations;
}
