import { vitest_common_conf } from 'common-test-utils';
import { defineConfig, mergeConfig } from 'vitest/config';

export default defineConfig(
  mergeConfig(vitest_common_conf, {
    test: {
      setupFiles: ['./src/test-setup/setup.ts'],
    },
  })
);
