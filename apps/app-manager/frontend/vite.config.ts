import react from '@vitejs/plugin-react';
import { vitest_common_conf } from 'common-test-vite-utils';
import { defineConfig, loadEnv, mergeConfig } from 'vite';
import viteTsconfigPaths from 'vite-tsconfig-paths';

// https://vitejs.dev/config/
/** @type {import('vite').UserConfig} */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return mergeConfig(vitest_common_conf, {
    plugins: [react(), viteTsconfigPaths()],
    server: {
      port: parseInt(env.PORT),
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
    test: {
      setupFiles: ['./src/__tests__/setup/setup.ts'],
      reporters: [
        'default',
        ['junit', { outputFile: './../target/test-reports/TEST-app-manager.xml' }], // JUnit XML report
      ],
    },
  });
});
