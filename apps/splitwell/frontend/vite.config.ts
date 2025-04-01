// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { vitest_common_conf } from '@lfdecentralizedtrust/splice-common-test-vite-utils';
import react from '@vitejs/plugin-react';
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
    test: {
      setupFiles: ['./src/__tests__/setup/setup.ts'],
      reporters: [
        'default',
        ['junit', { outputFile: './../target/test-reports/TEST-splitwell.xml' }], // JUnit XML report
      ],
    },
  });
});
