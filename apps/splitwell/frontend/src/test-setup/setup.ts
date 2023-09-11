import {
  buildDirectoryMock,
  buildJsonApiMock,
  buildScanMock,
  buildSplitwellMock,
} from 'common-test-utils';
import crypto from 'crypto';
import { setupServer } from 'msw/node';
import { vi, afterAll, afterEach, beforeAll } from 'vitest';

import { config } from './config';

// Provide an implementation for webcrypto when generating insecure jwts in the app
vi.stubGlobal('crypto', { subtle: crypto.webcrypto.subtle });

// Provide a global variable for the app config in the test environment
window.canton_network_config = config;
declare global {
  interface Window {
    canton_network_config: typeof config; // (make typescript happy)
  }
}

const server = setupServer(
  ...buildSplitwellMock(window.canton_network_config.services.splitwell.url),
  ...buildScanMock(window.canton_network_config.services.scan.url),
  ...buildDirectoryMock(window.canton_network_config.services.directory.url),
  ...buildJsonApiMock(window.canton_network_config.services.jsonApi.url)
);

// Start server before all tests
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));

//  Close server after all tests
afterAll(() => server.close());

// Reset handlers after each test `important for test isolation`
afterEach(() => server.resetHandlers());
