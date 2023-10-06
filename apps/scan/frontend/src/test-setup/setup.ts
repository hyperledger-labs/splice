import { cleanup } from '@testing-library/react';
import crypto from 'crypto';
import { setupServer } from 'msw/node';
import { beforeAll, afterAll, afterEach, vi } from 'vitest';

import { buildDirectoryMock, buildScanMock } from '../__tests__/mocks';
import { config } from './config';

// Provide an implementation for webcrypto when generating insecure jwts in the app
vi.stubGlobal('crypto', { subtle: crypto.webcrypto.subtle });

// Provide a global variable for the app config in the test environment
window.canton_network_config = config;
const server = setupServer(
  ...buildScanMock(window.canton_network_config.services.scan.url),
  ...buildDirectoryMock(window.canton_network_config.services.directory.url)
);

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
