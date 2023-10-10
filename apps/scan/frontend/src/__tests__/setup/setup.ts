import { cleanup } from '@testing-library/react';
import crypto from 'crypto';
import { beforeAll, afterAll, afterEach, vi } from 'vitest';

import { buildServer } from '../mocks/server';
import { config } from './config';

// Provide an implementation for webcrypto when generating insecure jwts in the app
vi.stubGlobal('crypto', { subtle: crypto.webcrypto.subtle });

type Config = typeof config;
export type Services = Config['services'];

// Provide a global variable for the app config in the test environment
window.canton_network_config = config;
const server = buildServer(window.canton_network_config.services);

// Start server before all tests
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));

//  Close server after all tests
afterAll(() => server.close());

// Reset handlers after each test `important for test isolation`
afterEach(() => {
  cleanup();
  server.resetHandlers();
});

declare global {
  interface Window {
    canton_network_config: typeof config; // (make typescript happy)
  }
}
