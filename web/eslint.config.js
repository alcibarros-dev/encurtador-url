// @ts-check
const eslint = require('@eslint/js');
const { defineConfig } = require('eslint/config');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');

/** Pastas de src/app/features. Uma feature só importa de core/ e shared/, nunca de outra (ADR-0004, D2). */
const FEATURES = ['home', 'links', 'stats', 'account', 'not-found'];
const FEATURE_RULE = 'Uma feature só importa de core/ e shared/, nunca de outra feature (ADR-0004, D2).';

/** Importações de outra feature: relativas (../stats/...) ou pelo caminho (features/stats/...). */
function otherFeatures(feature) {
  const others = FEATURES.filter((name) => name !== feature).join('|');
  return [
    { regex: `^(\\.\\./)+(${others})(/|$)`, message: FEATURE_RULE },
    { regex: `(^|/)features/(${others})(/|$)`, message: FEATURE_RULE },
  ];
}

module.exports = defineConfig([
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      tseslint.configs.recommended,
      tseslint.configs.stylistic,
      angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      '@angular-eslint/directive-selector': ['error', { type: 'attribute', prefix: 'app', style: 'camelCase' }],
      '@angular-eslint/component-selector': ['error', { type: 'element', prefix: 'app', style: 'kebab-case' }],
    },
  },
  ...FEATURES.map((feature) => ({
    files: [`src/app/features/${feature}/**/*.ts`],
    rules: { 'no-restricted-imports': ['error', { patterns: otherFeatures(feature) }] },
  })),
  {
    // core/ e shared/ não conhecem as features.
    files: ['src/app/core/**/*.ts', 'src/app/shared/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        { patterns: [{ regex: '(^|/)features(/|$)', message: 'core/ e shared/ não importam de features/ (ADR-0004, D2).' }] },
      ],
    },
  },
  {
    files: ['**/*.html'],
    extends: [angular.configs.templateRecommended, angular.configs.templateAccessibility],
    rules: {},
  },
]);
