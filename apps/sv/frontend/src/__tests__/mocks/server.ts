import { setupServer, SetupServer } from 'msw/node';

import { buildSvMock } from './handlers/sv-api';

export const buildServer = (svApiUrl: string): SetupServer => setupServer(...buildSvMock(svApiUrl));
