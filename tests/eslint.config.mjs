// ESLint flat config for the Cypress tests.
// eslint-plugin-cypress >= 6 only ships flat configs, so the legacy .eslintrc.json was migrated here.
// The legacy setup linted only TypeScript files (`--ext .ts`): every config below is scoped to *.ts.
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {FlatCompat} from '@eslint/eslintrc';
import cypress from 'eslint-plugin-cypress';
import chaiFriendly from 'eslint-plugin-chai-friendly';

const baseDirectory = path.dirname(fileURLToPath(import.meta.url));
const compat = new FlatCompat({baseDirectory});

const TS_FILES = ['**/*.ts'];
const scopeToTs = configs => configs.map(config => ({...config, files: TS_FILES}));

export default [
    {
        ignores: ['node_modules/', 'results/', 'artifacts/', 'run-artifacts/', '**/*.js', '**/*.cjs', '**/*.mjs']
    },
    ...scopeToTs([
        ...compat.extends('@jahia'),
        cypress.configs.recommended,
        // @typescript-eslint/eslint-plugin 7.x ships no flat configs: load its eslintrc preset (parser included).
        ...compat.extends('plugin:@typescript-eslint/recommended'),
        chaiFriendly.configs.recommendedFlat,
        {
            // FlatCompat lets @jahia's `env: {es6: true}` downgrade ecmaVersion to 6; eslintrc kept eslint-config-xo's 2020.
            languageOptions: {ecmaVersion: 2020},
            rules: {
                'react/boolean-prop-naming': ['error', {rule: '^((is|has)[A-Z]([A-Za-z0-9]?)+|disabled|readOnly|autoFocus)'}],
                '@typescript-eslint/no-shadow': ['error'],
                'no-warning-comments': ['error', {terms: ['fixme']}]
            }
        }
    ])
];
