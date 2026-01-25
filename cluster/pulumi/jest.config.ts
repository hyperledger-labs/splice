// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import type { Config } from '@jest/types';
import { createDefaultPreset } from 'ts-jest';
import pkg from './package.json' with { type: 'json' };

const config: Config.InitialOptions = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  testMatch: ['<rootDir>/src/**/*.test.ts'],
  projects: pkg.workspaces
    .filter(workspace => !['observability'].includes(workspace))
    .map(projectDir => ({
      displayName: projectDir,
      rootDir: projectDir,
      transform: createDefaultPreset().transform,
    })),
};
export default config;
