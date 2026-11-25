import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/testSetup.ts'],
    globals: false,
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
