// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import crypto from 'crypto';
import { SetupServer } from 'msw/node';
import {
  setupIntersectionMocking,
  resetIntersectionMocking,
} from 'react-intersection-observer/test-utils';
import { vi, afterAll, afterEach, beforeAll } from 'vitest';

import { buildServer } from '../mocks/server';
import { config } from './config';
import { DetachedWindowAPI } from 'happy-dom';

// Provide an implementation for webcrypto when generating insecure jwts in the app
vi.stubGlobal('crypto', crypto.webcrypto);

// Provide a global variable for the app config in the test environment
window.splice_config = config;
declare global {
  interface Window {
    splice_config: typeof config; // (make typescript happy)
    happyDOM: DetachedWindowAPI;
  }
}

export const svUrl: string = window.splice_config.services.sv.url;
export const server: SetupServer = buildServer(svUrl);

// Start server before all tests
beforeAll(() => {
  setupIntersectionMocking(vi.fn);
  server.listen({ onUnhandledRequest: 'error' });
});

//  Close server after all tests
afterAll(() => {
  resetIntersectionMocking();
  server.close();
});

// Reset handlers & react renderers after each test `important for test isolation`
afterEach(() => {
  server.resetHandlers();
  cleanup();
  vi.useRealTimers();
});
