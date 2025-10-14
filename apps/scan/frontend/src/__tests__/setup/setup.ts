// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { cleanup } from '@testing-library/react';
import crypto from 'crypto';
import { SetupServer } from 'msw/node';
import {
  setupIntersectionMocking,
  resetIntersectionMocking,
} from 'react-intersection-observer/test-utils';
import { beforeAll, afterAll, afterEach, vi } from 'vitest';
import { DetachedWindowAPI } from 'happy-dom';

import { buildServer } from '../mocks/server';
import { config } from './config';

// Provide an implementation for webcrypto when generating insecure jwts in the app
vi.stubGlobal('crypto', crypto.webcrypto);

type Config = typeof config;
export type Services = Config['services'];

// Provide a global variable for the app config in the test environment
window.splice_config = config;
export const server: SetupServer = buildServer(window.splice_config.services);

window.happyDOM.settings.enableJavaScriptEvaluation = true;

// Start server before all tests
beforeAll(() => {
  setupIntersectionMocking(vi.fn);
  server.listen({ onUnhandledRequest: 'error' });
  console.log('===========');
  console.log(window.happyDOM.settings.enableJavaScriptEvaluation);
});

//  Close server after all tests
afterAll(() => {
  resetIntersectionMocking();
  server.close();
});

// Reset handlers after each test `important for test isolation`
afterEach(() => {
  cleanup();
  server.resetHandlers();
});

declare global {
  interface Window {
    splice_config: typeof config; // (make typescript happy)
    happyDOM: DetachedWindowAPI;
  }
}
