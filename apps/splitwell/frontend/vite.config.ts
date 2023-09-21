import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';
import viteTsconfigPaths from 'vite-tsconfig-paths';

// https://vitejs.dev/config/
/** @type {import('vite').UserConfig} */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return {
    plugins: [react(), viteTsconfigPaths()],
    server: {
      port: parseInt(env.PORT),
      proxy: {
        '^/api/json-api/.*': {
          target: env.JSON_API_URL,
          rewrite: path => path.replace(/^\/api\/json-api/, ''),
        },
      },
    },
    build: {
      outDir: 'build',
      // TODO(#7672): reduce/remove this limit
      chunkSizeWarningLimit: 4800,
      commonjsOptions: {
        transformMixedEsModules: true,
      },
    },
    resolve: {
      preserveSymlinks: true,
    },
  };
});
