import { defineConfig } from 'eslint/config';
import tseslint from 'typescript-eslint';

export default defineConfig(
  { ignores: ['out/**', '.vscode-test/**', '.superpowers/**'] },
  tseslint.configs.recommended,
  {
    rules: {
      curly: 'warn',
      eqeqeq: 'warn',
      'no-throw-literal': 'warn',
    },
  },
);
