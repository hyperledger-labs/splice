import crypto from 'crypto';
import { vi } from 'vitest';

import { config } from './config';

// Provide an implementation for webcrypto when generating insecure jwts in the app
vi.stubGlobal('crypto', crypto.webcrypto);

// Provide a global variable for the app config in the test environment
window.splice_config = config;
declare global {
  interface Window {
    splice_config: typeof config; // (make typescript happy)
  }
}
