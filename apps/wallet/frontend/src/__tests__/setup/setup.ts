// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { cleanup } from '@testing-library/react';
import crypto from 'crypto';
import { beforeAll, afterAll, afterEach, vi } from 'vitest';

import { buildServer } from '../mocks/server';
import { config } from './config';

// Provide an implementation for webcrypto when generating insecure jwts in the app
vi.stubGlobal('crypto', crypto.webcrypto);

type Config = typeof config;
export type Services = Config['services'];

// Provide a global variable for the app config in the test environment
window.splice_config = config;
declare global {
  interface Window {
    splice_config: typeof config; // (make typescript happy)
  }
}

const server = buildServer(window.splice_config.services);

// Start server before all tests
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));

//  Close server after all tests
afterAll(() => server.close());

// Reset handlers after each test `important for test isolation`
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
