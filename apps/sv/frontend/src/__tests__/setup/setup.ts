import { cleanup } from '@testing-library/react';
import crypto from 'crypto';
import { SetupServer } from 'msw/node';
import { vi, afterAll, afterEach, beforeAll } from 'vitest';

import { buildServer } from '../mocks/server';
import { config } from './config';

// Provide an implementation for webcrypto when generating insecure jwts in the app
vi.stubGlobal('crypto', crypto.webcrypto);

// Provide a global variable for the app config in the test environment
window.canton_network_config = config;
declare global {
  interface Window {
    canton_network_config: typeof config; // (make typescript happy)
  }
}

export const server: SetupServer = buildServer(window.canton_network_config.services.sv.url);

// Start server before all tests
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));

//  Close server after all tests
afterAll(() => server.close());

// Reset handlers & react renderers after each test `important for test isolation`
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
