// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import vitest_common_conf from '@canton-network/splice-common-test-vite-utils';
import react from '@vitejs/plugin-react';
import path from 'path';
import { defineConfig, loadEnv, mergeConfig } from 'vite';

// https://vitejs.dev/config/
/** @type {import('vite').UserConfig} */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return mergeConfig(vitest_common_conf, {
    plugins: [react()],
    server: {
      port: parseInt(env.PORT),
    },
    build: {
      outDir: 'build',
      // TODO(#854): reduce/remove this limit
      chunkSizeWarningLimit: 4800,
      commonjsOptions: {
        transformMixedEsModules: true,
      },
    },
    resolve: {
      preserveSymlinks: true,
      // Vite 8 resolves tsconfig `paths` natively; this replaces the
      // legacy `vite-tsconfig-paths` plugin (which causes the PLUGIN_TIMINGS warning).
      tsconfigPaths: true,
    },
    test: {
      globalSetup: path.resolve(import.meta.dirname, 'vitest.global-setup.ts'),
      setupFiles: ['./src/__tests__/setup/setup.ts'],
      reporters: [
        'default',
        ['junit', { outputFile: './../target/test-reports/TEST-sv.xml' }], // Logs to a file
      ],
    },
  });
});
