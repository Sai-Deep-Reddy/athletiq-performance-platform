import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { viteStaticCopy } from 'vite-plugin-static-copy';
import { VitePWA } from 'vite-plugin-pwa';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const sharedModelsDir = path.resolve(__dirname, '../shared/models');
const mediapipeWasmDir = path.resolve(__dirname, 'node_modules/@mediapipe/tasks-vision/wasm');

const staticTargets = [];
if (fs.existsSync(sharedModelsDir)) {
  staticTargets.push({
    src: `${sharedModelsDir.replace(/\\/g, '/')}/*`,
    dest: 'models',
  });
}
if (fs.existsSync(mediapipeWasmDir)) {
  staticTargets.push({
    src: `${mediapipeWasmDir.replace(/\\/g, '/')}/*`,
    dest: 'mediapipe/wasm',
  });
}

const apiProxyTarget = process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:8000';
const apiProxy = {
  '/api': {
    target: apiProxyTarget,
    changeOrigin: true,
  },
  '/health': {
    target: apiProxyTarget,
    changeOrigin: true,
  },
};

const basePath = process.env.VITE_BASE_PATH || '/';

export default defineConfig({
  base: basePath,
  server: {
    port: 3000,
    strictPort: false,
    proxy: apiProxy,
  },
  preview: {
    port: 3000,
    strictPort: false,
    proxy: apiProxy,
  },
  optimizeDeps: {
    exclude: ['@mediapipe/tasks-vision'],
  },
  worker: {
    format: 'es',
  },
  plugins: [
    react(),
    tailwindcss(),
    ...(staticTargets.length > 0 ? [viteStaticCopy({ targets: staticTargets })] : []),
    VitePWA({
      registerType: 'autoUpdate',
      strategies: 'generateSW',
      manifest: {
        name: 'AthletiQ Core Assessment',
        short_name: 'AthletiQ',
        description: 'Contactless Sports Performance Assessment & Biomechanics Platform',
        theme_color: '#004ac6',
        background_color: '#f8f9ff',
        display: 'standalone',
        start_url: basePath,
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,ico,png,svg,woff,woff2,wasm,task,json}'],
        maximumFileSizeToCacheInBytes: 15 * 1024 * 1024,
        navigateFallbackDenylist: [/^\/api\//, /^\/health$/],
        runtimeCaching: [
          {
            urlPattern: ({ url }) => url.pathname.startsWith('/api/') || url.pathname === '/health',
            handler: 'NetworkOnly',
          },
          {
            urlPattern: ({ url }) =>
              url.pathname.startsWith('/models/') || url.pathname.startsWith('/mediapipe/wasm/'),
            handler: 'CacheFirst',
            options: {
              cacheName: 'athletiq-vision-assets-v1',
              expiration: {
                maxEntries: 20,
                maxAgeSeconds: 60 * 60 * 24 * 30,
              },
              cacheableResponse: {
                statuses: [0, 200],
              },
            },
          },
        ],
      },
    }),
  ],
});
